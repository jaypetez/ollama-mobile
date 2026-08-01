package io.github.jaypetez.ollamamobile.storage.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.jaypetez.ollamamobile.storage.entity.BenchmarkResultEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BenchmarkDao {
    @Query("SELECT * FROM benchmark_results ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BenchmarkResultEntity>>

    @Query("SELECT * FROM benchmark_results WHERE modelId = :modelId ORDER BY createdAt DESC")
    fun observeForModel(modelId: String): Flow<List<BenchmarkResultEntity>>

    /**
     * The best run for a model *under one configuration*. Grouping by anything
     * less than the full set of knobs compares numbers that are not comparable.
     */
    @Query(
        "SELECT * FROM benchmark_results WHERE modelId = :modelId AND backend = :backend " +
            "AND threads = :threads AND contextLength = :contextLength " +
            "ORDER BY tokensPerSecond DESC LIMIT 1",
    )
    suspend fun findBest(
        modelId: String,
        backend: String,
        threads: Int,
        contextLength: Int,
    ): BenchmarkResultEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(result: BenchmarkResultEntity)

    @Delete
    suspend fun delete(result: BenchmarkResultEntity)

    @Query("DELETE FROM benchmark_results WHERE modelId = :modelId")
    suspend fun deleteForModel(modelId: String)

    @Query("DELETE FROM benchmark_results")
    suspend fun deleteAll()
}
