package io.github.jaypetez.ollamamobile.storage.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A model the app knows about, local or remote.
 *
 * `ModelOrigin` is a sealed hierarchy in the domain but a discriminator plus
 * three nullable columns here, because Room cannot map a sealed type and
 * because the picker filters on origin without deserialising anything.
 */
@Entity(
    tableName = "models",
    indices = [
        Index(value = ["originType"]),
        Index(value = ["serverId"]),
        Index(value = ["lastUsedAt"]),
    ],
)
data class ModelEntity(
    @PrimaryKey
    val id: String,
    val displayName: String,
    /** The wire name, e.g. `qwen3:1.7b`. */
    val name: String,
    /** One of [ModelOriginColumn]. */
    val originType: String,
    /** Set when [originType] is [ModelOriginColumn.LOCAL]. */
    val localPath: String? = null,
    /** Set when [originType] is [ModelOriginColumn.REMOTE]. */
    val serverId: String? = null,
    /** Set when [originType] is [ModelOriginColumn.CATALOG]. */
    val catalogRepo: String? = null,
    /** Set when [originType] is [ModelOriginColumn.CATALOG]. */
    val catalogFile: String? = null,
    val parameterCount: Long? = null,
    /** `Quantization.name`, or null when the file type maps to nothing we model. */
    val quantization: String? = null,
    val sizeBytes: Long? = null,
    val contextLength: Int? = null,
    /** `ModelCapability.name` values. */
    val capabilities: Set<String> = emptySet(),
    val chatTemplate: String? = null,
    /**
     * Embedding dimensionality, for models with the EMBEDDING capability.
     *
     * Part of the index format: vectors produced by two different embedding
     * models are not comparable, so a mismatch at query time is a hard error
     * rather than a silent quality loss.
     */
    val embeddingDimensions: Int? = null,
    val installedAt: Long? = null,
    val lastUsedAt: Long? = null,
    @ColumnInfo(defaultValue = "0")
    val favourite: Boolean = false,
)

/** The persisted spelling of `ModelOrigin`. */
object ModelOriginColumn {
    const val LOCAL: String = "local"
    const val REMOTE: String = "remote"
    const val CATALOG: String = "catalog"
}
