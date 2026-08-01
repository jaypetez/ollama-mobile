package io.github.jaypetez.ollamamobile.data.rag

import android.content.ContentResolver
import android.net.Uri
import io.github.jaypetez.ollamamobile.common.dispatcher.AppDispatchers
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.AppErrorException
import io.github.jaypetez.ollamamobile.model.asException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import kotlinx.coroutines.withContext

/**
 * Turns an imported file into plain text.
 *
 * ## Scope for v1: `.txt` and `.md`, and nothing else
 *
 * **PDF is deliberately not supported yet, pending a licence review.** Every
 * viable Android PDF text extractor is either AGPL/commercial-dual (iText,
 * PDFBox-Android's lineage) or drags in a large native dependency whose own
 * transitive licences need auditing before they can ship in a release APK. That
 * review has not happened, so rather than pick one and sort it out later — which
 * in practice means shipping it — the format is refused with a message that says
 * so. [SupportedFormat] is the single place to widen this once the review lands.
 *
 * Refusing loudly matters more than it sounds. Handing a PDF's raw bytes to a
 * UTF-8 decoder does not fail; it yields a few kilobytes of mojibake with
 * recognisable words in it, which chunks, embeds and indexes perfectly happily
 * and pollutes every subsequent retrieval.
 */
public class TextExtractor
    @Inject
    constructor(
        private val contentResolver: ContentResolver,
        private val dispatchers: AppDispatchers,
    ) {
        /**
         * Reads [uri] as text.
         *
         * @throws AppErrorException [AppError.Storage.Io] when the format is
         *   unsupported or the stream cannot be opened.
         */
        public suspend fun extract(uri: Uri, mimeType: String?, fileName: String?): String =
            withContext(dispatchers.io) {
                val format = SupportedFormat.of(mimeType, fileName)
                    ?: throw AppError.Storage
                        .Io(unsupportedMessage(mimeType, fileName))
                        .asException()

                val stream = runCatching { contentResolver.openInputStream(uri) }.getOrNull()
                    ?: throw AppError.Storage.Io("Could not open $uri").asException()

                stream.use { input -> normalise(readText(input), format) }
            }

        /**
         * Decoded strictly as UTF-8, with malformed input replaced rather than
         * throwing.
         *
         * A single bad byte in an otherwise fine 200 KB note should not fail the
         * import; U+FFFD in one word costs one chunk a little quality, whereas
         * refusing the file costs the user the whole document.
         */
        private fun readText(input: InputStream): String =
            input.readBytes().toString(StandardCharsets.UTF_8)

        private fun normalise(raw: String, format: SupportedFormat): String {
            val text = raw
                // A BOM survives decoding and would become the first character of
                // the first chunk — and of the first heading the chunker detects.
                .removePrefix("")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
            return when (format) {
                SupportedFormat.PLAIN_TEXT, SupportedFormat.MARKDOWN -> text
            }
        }

        private fun unsupportedMessage(mimeType: String?, fileName: String?): String {
            val what = fileName ?: mimeType ?: "this file"
            return if (isProbablyPdf(mimeType, fileName)) {
                "PDF import is not available yet. Only .txt and .md files can be indexed."
            } else {
                "$what is not a supported document. Only .txt and .md files can be indexed."
            }
        }

        private fun isProbablyPdf(mimeType: String?, fileName: String?): Boolean =
            mimeType == "application/pdf" || fileName?.endsWith(".pdf", ignoreCase = true) == true

        /** The formats v1 can read. Widening this is the entire change to support a new one. */
        public enum class SupportedFormat {
            PLAIN_TEXT,
            MARKDOWN,
            ;

            public companion object {
                /**
                 * Extension first, MIME second.
                 *
                 * SAF providers are unreliable about MIME: cloud providers routinely
                 * report `application/octet-stream` for a `.md` file, and some report
                 * `text/plain` for anything textual. The name the user picked is the
                 * better signal, and the MIME type is the fallback for a provider
                 * that supplies no name at all.
                 */
                public fun of(mimeType: String?, fileName: String?): SupportedFormat? {
                    fileName?.substringAfterLast('.', "")?.lowercase()?.let { extension ->
                        when (extension) {
                            "md", "markdown", "mdown" -> return MARKDOWN

                            "txt", "text", "log" -> return PLAIN_TEXT

                            // A known-but-unsupported extension must not fall through
                            // to the MIME check, or a provider reporting text/plain
                            // for a .pdf would get it indexed as mojibake.
                            "pdf", "doc", "docx", "epub", "rtf", "odt" -> return null

                            else -> Unit
                        }
                    }
                    return when (mimeType) {
                        "text/markdown", "text/x-markdown" -> MARKDOWN
                        "text/plain" -> PLAIN_TEXT
                        else -> null
                    }
                }
            }
        }
    }
