package io.github.jaypetez.ollamamobile.storage.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A remote Ollama-compatible server.
 *
 * No credential value is stored here. [tokenRefAlias] and [passwordRefAlias]
 * hold a `SecretRef` alias; the bytes live in the Keystore-backed
 * `SecretsStore`, which is excluded from backup. A row in this table is safe to
 * export.
 */
@Entity(
    tableName = "servers",
    indices = [Index(value = ["baseUrl"], unique = true)],
)
data class ServerConfigEntity(
    @PrimaryKey
    val id: String,
    val label: String,
    val baseUrl: String,
    /** One of [ServerAuthColumn]. */
    @ColumnInfo(defaultValue = "none")
    val authType: String = ServerAuthColumn.NONE,
    /** Set when [authType] is [ServerAuthColumn.BEARER]. */
    val tokenRefAlias: String? = null,
    /** Set when [authType] is [ServerAuthColumn.BASIC]. */
    val username: String? = null,
    /** Set when [authType] is [ServerAuthColumn.BASIC]. */
    val passwordRefAlias: String? = null,
    /** SPKI SHA-256 the user accepted for this host, base64. */
    val pinnedCertSha256: String? = null,
    @ColumnInfo(defaultValue = "0")
    val allowPinnedSelfSignedTls: Boolean = false,
    @ColumnInfo(defaultValue = "1")
    val enabled: Boolean = true,
    val lastSeenAt: Long? = null,
    @ColumnInfo(defaultValue = "0")
    val sortOrder: Int = 0,
)

/** The persisted spelling of `ServerAuth`. */
object ServerAuthColumn {
    const val NONE: String = "none"
    const val BEARER: String = "bearer"
    const val BASIC: String = "basic"
}
