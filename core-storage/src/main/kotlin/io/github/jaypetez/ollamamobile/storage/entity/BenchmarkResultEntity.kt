package io.github.jaypetez.ollamamobile.storage.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One benchmark run.
 *
 * Every knob that changes the number is a column, because a throughput figure
 * without the thread count, context length and backend it was measured with is
 * not comparable to anything and is therefore worse than no figure at all.
 */
@Entity(
    tableName = "benchmark_results",
    indices = [Index(value = ["modelId", "createdAt"])],
)
data class BenchmarkResultEntity(
    @PrimaryKey
    val id: String,
    val modelId: String,
    /** Free-form device identifier, e.g. `Pixel 8 / arm64-v8a / Android 15`. */
    val device: String,
    /** `cpu`, `gpu-opencl`, `npu`, ... */
    val backend: String,
    val threads: Int,
    val contextLength: Int,
    val batchSize: Int,
    val quantization: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val promptTokensPerSecond: Double? = null,
    val tokensPerSecond: Double? = null,
    val loadNanos: Long? = null,
    val peakMemoryBytes: Long? = null,
    /** Battery temperature in tenths of a degree Celsius, as reported by the platform. */
    val batteryTemperatureDeciCelsius: Int? = null,
    val createdAt: Long,
)
