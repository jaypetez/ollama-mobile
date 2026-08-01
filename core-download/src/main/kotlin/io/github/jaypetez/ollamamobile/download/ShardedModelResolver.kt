package io.github.jaypetez.ollamamobile.download

/**
 * One part of a split GGUF.
 *
 * @property base everything before the shard suffix — `Qwen3-30B-A3B-Q4_K_M`
 *   for `Qwen3-30B-A3B-Q4_K_M-00002-of-00005.gguf`.
 * @property index 1-based, as it appears in the name.
 * @property total how many parts the name claims exist.
 */
public data class ShardRef(
    public val base: String,
    public val index: Int,
    public val total: Int,
) {
    /** The canonical filename for this shard. */
    public val fileName: String get() = ShardedModelResolver.nameOf(base, index, total)
}

/** A complete or incomplete split-GGUF set. */
public data class ShardSet(
    public val base: String,
    public val total: Int,
    /** In shard order, 1..[total]. */
    public val expected: List<String>,
    /** The subset that the source actually offers, or that is already on disk. */
    public val present: List<String>,
) {
    public val missing: List<String> get() = expected - present.toSet()

    public val isComplete: Boolean get() = missing.isEmpty()

    /** The shard the loader is handed. Never a concatenation — see the class KDoc. */
    public val primary: String get() = expected.first()
}

/**
 * Recognises and enumerates multi-part GGUF files.
 *
 * llama.cpp locates a model's siblings purely by the `-%05d-of-%05d` suffix, so
 * the names are not cosmetic: the files have to keep them, sit in the same
 * directory, and be numbered from one. Renaming the first shard to something
 * friendlier breaks loading with an error that does not mention naming, which
 * is a long afternoon for whoever hits it.
 *
 * The three rules this type exists to enforce:
 *
 *  1. **All shards or none.** A set with a part missing is not a partially
 *     usable model; it is an unusable one. [ShardSet.isComplete] gates whether
 *     the directory is published at all.
 *  2. **One unit of work.** The whole set downloads under a single WorkManager
 *     request with a single progress figure, because a bar that fills up five
 *     times reads as a bug.
 *  3. **Point the loader at shard one**, and never concatenate. The parts are
 *     separate GGUF containers with their own headers, not fragments of one
 *     file.
 *
 * At the sizes that fit on a phone sharding is uncommon — most 1–8B builds are a
 * single file — but the failure mode when it is unhandled is a download that
 * appears to succeed and a model that will not load, so it is handled anyway.
 */
public object ShardedModelResolver {
    /**
     * Upstream's `llama_split_path` format is `%s-%05d-of-%05d.gguf`.
     *
     * Five digits exactly, not `\d+`: a looser pattern turns an ordinary
     * filename that happens to contain `-1-of-2` into a phantom shard set, and
     * the resulting "missing 1 of 2 parts" error is baffling.
     */
    private val SHARD_PATTERN = Regex("""^(?<base>.+)-(?<index>\d{5})-of-(?<total>\d{5})\.gguf$""")

    private const val SHARD_DIGITS = 5

    /** Null when [fileName] is an ordinary single-file GGUF. */
    public fun parse(fileName: String): ShardRef? {
        val match = SHARD_PATTERN.matchEntire(fileName) ?: return null
        val index = match.groups["index"]?.value?.toIntOrNull() ?: return null
        val total = match.groups["total"]?.value?.toIntOrNull() ?: return null
        // Numbered from one, and never more parts claimed than exist. Both are
        // guaranteed by the writer, so a violation means the name is a
        // coincidence rather than a real shard suffix.
        if (index < 1 || total < 1 || index > total) return null
        return ShardRef(base = match.groups["base"]!!.value, index = index, total = total)
    }

    public fun isShard(fileName: String): Boolean = parse(fileName) != null

    /** The canonical name of shard [index] of [total]. */
    public fun nameOf(base: String, index: Int, total: Int): String =
        "$base-${pad(index)}-of-${pad(total)}.gguf"

    private fun pad(value: Int): String = value.toString().padStart(SHARD_DIGITS, '0')

    /**
     * Every part of the set [fileName] belongs to, in order.
     *
     * Derived from the suffix rather than from a directory listing, so it works
     * before anything has been downloaded: the name of any one shard states how
     * many there are.
     */
    public fun expectedSiblings(fileName: String): List<String> {
        val shard = parse(fileName) ?: return listOf(fileName)
        return (1..shard.total).map { nameOf(shard.base, it, shard.total) }
    }

    /**
     * Resolves [fileName] against the names a repository (or a directory)
     * actually offers.
     *
     * Returns null when [fileName] is not a shard, which is the caller's signal
     * to treat it as an ordinary single file.
     */
    public fun resolve(fileName: String, available: Collection<String>): ShardSet? {
        val shard = parse(fileName) ?: return null
        val expected = expectedSiblings(fileName)
        val offered = available.toSet()
        return ShardSet(
            base = shard.base,
            total = shard.total,
            expected = expected,
            present = expected.filter { it in offered },
        )
    }

    /**
     * The file the loader should be given: shard one, or null when [fileNames]
     * is not a shard set.
     */
    public fun primaryOf(fileNames: Collection<String>): String? = fileNames
        .mapNotNull { name -> parse(name)?.let { it to name } }
        .minByOrNull { (shard, _) -> shard.index }
        ?.takeIf { (shard, _) -> shard.index == 1 }
        ?.second

    /**
     * Turns one requested file into the whole unit of work.
     *
     * [available] is the repository listing. A shard set whose parts are not all
     * offered fails here rather than after gigabytes of transfer.
     */
    public fun expand(requested: RemoteFile, available: List<RemoteFile>): List<RemoteFile> {
        val set = resolve(requested.fileName, available.map { it.fileName }) ?: return listOf(requested)
        if (!set.isComplete) throw DownloadError.IncompleteShardSet(missing = set.missing).asException()
        val byName = available.associateBy { it.fileName }
        return set.expected.map { name -> byName.getValue(name) }
    }
}
