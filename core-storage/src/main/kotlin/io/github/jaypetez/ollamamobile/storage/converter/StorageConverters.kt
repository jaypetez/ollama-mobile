package io.github.jaypetez.ollamamobile.storage.converter

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The only converters in the module.
 *
 * Everything else is stored in a column of its own. Two collection columns
 * genuinely have no relational shape worth a join table — stop sequences and
 * model capabilities are both short, always read whole, and never queried
 * element-wise — so they are JSON. Anything that grows past that gets a table.
 */
class StorageConverters {
    @TypeConverter
    fun stringListToJson(value: List<String>?): String? =
        value?.let { json.encodeToString(stringListSerializer, it) }

    @TypeConverter
    fun jsonToStringList(value: String?): List<String> =
        value?.let { json.decodeFromString(stringListSerializer, it) } ?: emptyList()

    @TypeConverter
    fun stringSetToJson(value: Set<String>?): String? =
        value?.let { json.encodeToString(stringListSerializer, it.toList()) }

    @TypeConverter
    fun jsonToStringSet(value: String?): Set<String> =
        value?.let { json.decodeFromString(stringListSerializer, it).toSet() } ?: emptySet()

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val stringListSerializer = ListSerializer(String.serializer())
    }
}
