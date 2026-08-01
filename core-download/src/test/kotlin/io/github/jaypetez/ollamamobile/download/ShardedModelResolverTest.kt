package io.github.jaypetez.ollamamobile.download

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Shard naming, which is load-bearing rather than cosmetic: llama.cpp finds a
 * model's siblings purely from the `-%05d-of-%05d` suffix.
 */
@RunWith(JUnit4::class)
class ShardedModelResolverTest {
    @Test
    fun `a five-digit suffix is a shard and anything looser is not`() {
        val shard = ShardedModelResolver.parse("Qwen3-30B-A3B-Q4_K_M-00002-of-00005.gguf")

        assertThat(shard).isNotNull()
        assertThat(shard?.base).isEqualTo("Qwen3-30B-A3B-Q4_K_M")
        assertThat(shard?.index).isEqualTo(2)
        assertThat(shard?.total).isEqualTo(5)

        // A looser pattern would turn an ordinary filename that happens to
        // contain "-1-of-2" into a phantom shard set, and the resulting
        // "missing 1 of 2 parts" error is baffling.
        assertThat(ShardedModelResolver.parse("model-1-of-2.gguf")).isNull()
        assertThat(ShardedModelResolver.parse("Llama-3.2-1B-Instruct-Q4_K_M.gguf")).isNull()
        assertThat(ShardedModelResolver.parse("weights-00001-of-00002.safetensors")).isNull()
    }

    @Test
    fun `an out-of-range index is not a shard`() {
        assertThat(ShardedModelResolver.parse("m-00000-of-00003.gguf")).isNull()
        assertThat(ShardedModelResolver.parse("m-00004-of-00003.gguf")).isNull()
    }

    @Test
    fun `the whole set is derivable from any one member's name`() {
        val siblings = ShardedModelResolver.expectedSiblings("m-00003-of-00003.gguf")

        assertThat(siblings)
            .containsExactly(
                "m-00001-of-00003.gguf",
                "m-00002-of-00003.gguf",
                "m-00003-of-00003.gguf",
            ).inOrder()
    }

    @Test
    fun `a single file resolves to itself`() {
        assertThat(ShardedModelResolver.expectedSiblings("plain.gguf")).containsExactly("plain.gguf")
        assertThat(ShardedModelResolver.resolve("plain.gguf", listOf("plain.gguf"))).isNull()
    }

    @Test
    fun `a set missing a part is incomplete and names what is missing`() {
        val set = ShardedModelResolver.resolve(
            "m-00001-of-00003.gguf",
            listOf("m-00001-of-00003.gguf", "m-00003-of-00003.gguf", "README.md"),
        )

        assertThat(set).isNotNull()
        assertThat(set!!.isComplete).isFalse()
        assertThat(set.missing).containsExactly("m-00002-of-00003.gguf")
        assertThat(set.primary).isEqualTo("m-00001-of-00003.gguf")
    }

    @Test
    fun `the primary is shard one, and there is none when shard one is absent`() {
        assertThat(
            ShardedModelResolver.primaryOf(listOf("m-00002-of-00002.gguf", "m-00001-of-00002.gguf")),
        ).isEqualTo("m-00001-of-00002.gguf")

        assertThat(ShardedModelResolver.primaryOf(listOf("m-00002-of-00002.gguf"))).isNull()
        assertThat(ShardedModelResolver.primaryOf(listOf("plain.gguf"))).isNull()
    }

    @Test
    fun `expanding one shard yields the set in order and refuses an incomplete one`() {
        val available = listOf(
            RemoteFile("m-00002-of-00002.gguf", sizeBytes = 2),
            RemoteFile("m-00001-of-00002.gguf", sizeBytes = 1),
        )

        val expanded = ShardedModelResolver.expand(available[0], available)

        assertThat(expanded.map { it.fileName })
            .containsExactly("m-00001-of-00002.gguf", "m-00002-of-00002.gguf")
            .inOrder()

        val failure = runCatching {
            ShardedModelResolver.expand(available[1], listOf(available[1]))
        }.exceptionOrNull()
        assertThat((failure as DownloadException).error)
            .isInstanceOf(DownloadError.IncompleteShardSet::class.java)
    }
}
