package io.github.jaypetez.ollamamobile.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class IdsTest {
    @Test
    fun `random ids are unique`() {
        val ids = List(100) { ConversationId.random() }

        assertThat(ids.toSet()).hasSize(100)
    }

    @Test
    fun `chunk ids are derived from the document so re-indexing overwrites`() {
        val document = DocumentId("doc-1")

        assertThat(ChunkId.of(document, 0)).isEqualTo(ChunkId.of(document, 0))
        assertThat(ChunkId.of(document, 0)).isNotEqualTo(ChunkId.of(document, 1))
        assertThat(ChunkId.of(document, 7).value).isEqualTo("doc-1:7")
    }

    @Test
    fun `toString is the bare value so ids read cleanly in logs`() {
        assertThat(ModelId("qwen3:1.7b").toString()).isEqualTo("qwen3:1.7b")
        assertThat(SecretRef("server.abc.bearer").toString()).isEqualTo("server.abc.bearer")
    }

    @Test
    fun `secret aliases follow one convention`() {
        val ref = SecretRef.forServer(ServerId("abc"), purpose = "bearer")

        assertThat(ref.alias).isEqualTo("server.abc.bearer")
    }
}
