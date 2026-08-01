package io.github.jaypetez.ollamamobile.storage

import androidx.room.RoomDatabase
import androidx.room.RoomRawQuery
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Every line of FTS5 SQL in the app, in one file so it can be reviewed as a
 * whole.
 *
 * ## Why this is hand-written SQL and not `@Fts4`
 *
 * Room's full-text support stops at FTS4: `@Fts3` and `@Fts4` are the only
 * annotations it has, there is no `@Fts5`, and FTS4 has no `bm25()`. Ranking is
 * the entire point of the lexical half of hybrid retrieval, so FTS4 is not a
 * degraded option — it is the wrong tool. The virtual tables and their sync
 * triggers are therefore created by raw SQL from a [RoomDatabase.Callback], and
 * the search queries are `@RawQuery` because Room's compile-time query verifier
 * builds its schema from `@Entity` classes and would reject any `@Query`
 * mentioning a table it has never heard of.
 *
 * ## External content
 *
 * Both indexes are `content=` external-content tables: FTS5 stores only the
 * inverted index and reads column values back from the ordinary Room table.
 * That halves the storage cost of the message body — which for a chat app is
 * most of the database — but it means SQLite does **not** keep the index in
 * sync for you. The `AFTER INSERT/UPDATE/DELETE` triggers below are what does,
 * and a missing one produces an index that silently returns stale or phantom
 * rows rather than an error.
 *
 * The `'delete'` command takes the **old** column values, not the new ones:
 * FTS5 removes index entries by re-deriving the token list from the values it
 * was given, so passing `new.content` on an update leaves the old tokens
 * pointing at the row forever. That is why the update trigger deletes with
 * `old` and re-inserts with `new`.
 *
 * ## bm25 is negated
 *
 * SQLite returns bm25 scores **negative**, best-first when sorted ascending.
 * Every `ORDER BY bm25(...)` here is ascending on purpose; writing `DESC`
 * because "higher score is better" returns the worst matches and returns them
 * without an error.
 */
object FtsSchema {
    const val MESSAGE_FTS_TABLE: String = "message_fts"
    const val RAG_CHUNK_FTS_TABLE: String = "rag_chunk_fts"

    /** SQLite bind parameters are 1-indexed, so the third `?` is 3, not 2. */
    private const val THIRD_BIND_INDEX = 3

    /**
     * `remove_diacritics 2` is the Unicode-correct variant and requires SQLite
     * 3.27+; the bundled SQLite is far newer, and this is one more reason the
     * driver must not fall back to whatever the OEM shipped.
     */
    private const val TOKENIZER = """tokenize = "unicode61 remove_diacritics 2""""

    private val MESSAGE_INDEX = FtsIndex(
        ftsTable = MESSAGE_FTS_TABLE,
        createTable = """
            CREATE VIRTUAL TABLE IF NOT EXISTS $MESSAGE_FTS_TABLE USING fts5(
                content,
                content = 'messages',
                content_rowid = 'rowId',
                $TOKENIZER
            )
        """.trimIndent(),
        createTriggers = listOf(
            """
            CREATE TRIGGER IF NOT EXISTS messages_fts_ai AFTER INSERT ON messages BEGIN
                INSERT INTO $MESSAGE_FTS_TABLE(rowid, content) VALUES (new.rowId, new.content);
            END
            """.trimIndent(),
            """
            CREATE TRIGGER IF NOT EXISTS messages_fts_ad AFTER DELETE ON messages BEGIN
                INSERT INTO $MESSAGE_FTS_TABLE($MESSAGE_FTS_TABLE, rowid, content)
                VALUES ('delete', old.rowId, old.content);
            END
            """.trimIndent(),
            """
            CREATE TRIGGER IF NOT EXISTS messages_fts_au AFTER UPDATE ON messages BEGIN
                INSERT INTO $MESSAGE_FTS_TABLE($MESSAGE_FTS_TABLE, rowid, content)
                VALUES ('delete', old.rowId, old.content);
                INSERT INTO $MESSAGE_FTS_TABLE(rowid, content) VALUES (new.rowId, new.content);
            END
            """.trimIndent(),
        ),
    )

    private val RAG_CHUNK_INDEX = FtsIndex(
        ftsTable = RAG_CHUNK_FTS_TABLE,
        // `heading` is a separate column so bm25 can be given per-column
        // weights: a query term matching a section heading is a much stronger
        // signal than the same term buried in body text.
        createTable = """
            CREATE VIRTUAL TABLE IF NOT EXISTS $RAG_CHUNK_FTS_TABLE USING fts5(
                text,
                heading,
                content = 'rag_chunks',
                content_rowid = 'rowId',
                $TOKENIZER
            )
        """.trimIndent(),
        createTriggers = listOf(
            """
            CREATE TRIGGER IF NOT EXISTS rag_chunks_fts_ai AFTER INSERT ON rag_chunks BEGIN
                INSERT INTO $RAG_CHUNK_FTS_TABLE(rowid, text, heading)
                VALUES (new.rowId, new.text, new.heading);
            END
            """.trimIndent(),
            """
            CREATE TRIGGER IF NOT EXISTS rag_chunks_fts_ad AFTER DELETE ON rag_chunks BEGIN
                INSERT INTO $RAG_CHUNK_FTS_TABLE($RAG_CHUNK_FTS_TABLE, rowid, text, heading)
                VALUES ('delete', old.rowId, old.text, old.heading);
            END
            """.trimIndent(),
            """
            CREATE TRIGGER IF NOT EXISTS rag_chunks_fts_au AFTER UPDATE ON rag_chunks BEGIN
                INSERT INTO $RAG_CHUNK_FTS_TABLE($RAG_CHUNK_FTS_TABLE, rowid, text, heading)
                VALUES ('delete', old.rowId, old.text, old.heading);
                INSERT INTO $RAG_CHUNK_FTS_TABLE(rowid, text, heading)
                VALUES (new.rowId, new.text, new.heading);
            END
            """.trimIndent(),
        ),
    )

    internal val indexes: List<FtsIndex> = listOf(MESSAGE_INDEX, RAG_CHUNK_INDEX)

    // ---------------------------------------------------------------------
    // Queries
    // ---------------------------------------------------------------------

    /**
     * The FTS table is joined un-aliased: FTS5 resolves `MATCH` and `bm25()`
     * against the name on the left, and an alias there is a syntax error in
     * some SQLite builds and silently the wrong table in others.
     */
    private const val MESSAGE_SEARCH_SQL = """
        SELECT messages.* FROM messages
        JOIN $MESSAGE_FTS_TABLE ON $MESSAGE_FTS_TABLE.rowid = messages.rowId
        WHERE $MESSAGE_FTS_TABLE MATCH ?
        ORDER BY bm25($MESSAGE_FTS_TABLE) ASC
        LIMIT ?
    """

    private const val MESSAGE_SEARCH_IN_CONVERSATION_SQL = """
        SELECT messages.* FROM messages
        JOIN $MESSAGE_FTS_TABLE ON $MESSAGE_FTS_TABLE.rowid = messages.rowId
        WHERE $MESSAGE_FTS_TABLE MATCH ? AND messages.conversationId = ?
        ORDER BY bm25($MESSAGE_FTS_TABLE) ASC
        LIMIT ?
    """

    /**
     * `bm25(tbl, 1.0, 4.0)` weights the `heading` column four times the body.
     * The multiplier is a starting point, not a measured optimum; it is here so
     * the weighting is visible rather than implicit.
     */
    private const val RAG_CHUNK_SEARCH_SQL = """
        SELECT rag_chunks.* FROM rag_chunks
        JOIN $RAG_CHUNK_FTS_TABLE ON $RAG_CHUNK_FTS_TABLE.rowid = rag_chunks.rowId
        WHERE $RAG_CHUNK_FTS_TABLE MATCH ?
        ORDER BY bm25($RAG_CHUNK_FTS_TABLE, 1.0, 4.0) ASC
        LIMIT ?
    """

    fun messageSearchQuery(matchExpression: String, limit: Int): RoomRawQuery =
        RoomRawQuery(MESSAGE_SEARCH_SQL.trimIndent()) { statement ->
            statement.bindText(1, matchExpression)
            statement.bindLong(2, limit.toLong())
        }

    fun messageSearchInConversationQuery(
        matchExpression: String,
        conversationId: String,
        limit: Int,
    ): RoomRawQuery = RoomRawQuery(MESSAGE_SEARCH_IN_CONVERSATION_SQL.trimIndent()) { statement ->
        statement.bindText(1, matchExpression)
        statement.bindText(2, conversationId)
        statement.bindLong(THIRD_BIND_INDEX, limit.toLong())
    }

    fun ragChunkSearchQuery(matchExpression: String, limit: Int): RoomRawQuery =
        RoomRawQuery(RAG_CHUNK_SEARCH_SQL.trimIndent()) { statement ->
            statement.bindText(1, matchExpression)
            statement.bindLong(2, limit.toLong())
        }

    /**
     * Turns free text into an FTS5 `MATCH` expression.
     *
     * `MATCH` takes FTS5 query syntax, not prose. A user question containing a
     * hyphen, a colon, a quote or the bare word `AND` is either reinterpreted
     * as an operator or raises a syntax error — so raw input is never handed to
     * `MATCH`. Each run of word characters becomes one quoted phrase and the
     * phrases are joined by whitespace, which FTS5 reads as implicit AND.
     *
     * [prefixLastToken] appends `*` to the final term, which is what makes
     * search-as-you-type find "quanti" → "quantisation". It is off by default
     * because for a completed query it only adds noise.
     *
     * Returns an empty string when nothing survives tokenisation; callers must
     * treat that as "no query" rather than passing it to `MATCH`, which errors.
     */
    fun sanitizeMatchQuery(raw: String, prefixLastToken: Boolean = false): String {
        val tokens = raw
            .split(TOKEN_SPLITTER)
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return ""
        return tokens
            .mapIndexed { index, token ->
                // Doubling is FTS5's own escape for a quote inside a phrase.
                val quoted = "\"" + token.replace("\"", "\"\"") + "\""
                if (prefixLastToken && index == tokens.lastIndex) "$quoted*" else quoted
            }.joinToString(" ")
    }

    private val TOKEN_SPLITTER = Regex("[^\\p{L}\\p{N}_]+")
}

/** One external-content FTS5 index and the triggers that keep it honest. */
internal data class FtsIndex(
    val ftsTable: String,
    val createTable: String,
    val createTriggers: List<String>,
) {
    /** Reindexes from the content table. Only needed when the virtual table is created late. */
    val rebuild: String get() = "INSERT INTO $ftsTable($ftsTable) VALUES ('rebuild')"
}

/**
 * Creates the FTS5 tables and triggers.
 *
 * `onCreate` covers a fresh database. `onOpen` exists because the virtual
 * tables are not part of Room's schema, so nothing in Room validates them:
 * if a table were ever dropped — by a migration, by a repair, by a database
 * file restored from a build that predates the index — Room would open the
 * database happily and search would return nothing at all, with no error. The
 * check is one `sqlite_master` lookup per open, and when it does find the table
 * missing it rebuilds the index from the content table rather than leaving an
 * empty one behind.
 */
internal class FtsCallback : RoomDatabase.Callback() {
    override fun onCreate(connection: SQLiteConnection) {
        FtsSchema.indexes.forEach { index ->
            connection.execSQL(index.createTable)
            index.createTriggers.forEach(connection::execSQL)
        }
    }

    override fun onOpen(connection: SQLiteConnection) {
        FtsSchema.indexes.forEach { index ->
            val existed = tableExists(connection, index.ftsTable)
            connection.execSQL(index.createTable)
            index.createTriggers.forEach(connection::execSQL)
            if (!existed) {
                connection.execSQL(index.rebuild)
            }
        }
    }

    private fun tableExists(connection: SQLiteConnection, name: String): Boolean =
        connection.prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use { statement ->
            statement.bindText(1, name)
            statement.step()
        }
}
