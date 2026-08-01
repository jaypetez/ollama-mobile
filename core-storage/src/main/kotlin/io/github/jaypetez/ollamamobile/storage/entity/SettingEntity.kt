package io.github.jaypetez.ollamamobile.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single app preference, as text.
 *
 * DataStore is used for the small hot set the UI reads on every frame; this
 * table exists for the long tail (per-model overrides, dismissed one-off
 * notices, last-used values) where a typed Preferences key per item would be
 * unmanageable and where the value wants to take part in the same transaction
 * as the row it describes.
 */
@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey
    val key: String,
    val value: String,
    val updatedAt: Long,
)
