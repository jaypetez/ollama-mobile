package io.github.jaypetez.ollamamobile.download

import io.github.jaypetez.ollamamobile.download.hf.HuggingFaceApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks the right [DownloadUrlResolver] for a source.
 *
 * A one-method indirection so that [ModelDownloadWorker] does not have to know
 * that Hugging Face downloads can re-issue an expired signed URL and pasted ones
 * cannot. The worker asks for a resolver; the difference lives here.
 */
@Singleton
public class DownloadResolvers
    @Inject
    constructor(
        private val huggingFace: HuggingFaceApi,
        private val customUrl: CustomUrlSource,
    ) {
        public fun forSource(source: DownloadSource): DownloadUrlResolver = when (source) {
            is DownloadSource.HuggingFace -> huggingFace.resolverFor(source)
            is DownloadSource.CustomUrl -> customUrl.resolverFor(source)
        }
    }
