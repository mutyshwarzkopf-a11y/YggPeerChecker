package com.example.yggpeerchecker.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CheckResultDao {
    @Query("SELECT * FROM check_results ORDER BY timestamp DESC")
    fun getAllResults(): Flow<List<CheckResult>>

    @Query("SELECT * FROM check_results WHERE hostId = :hostId ORDER BY timestamp DESC")
    fun getResultsForHost(hostId: String): Flow<List<CheckResult>>

    @Query("SELECT * FROM check_results WHERE hostId = :hostId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestResultForHost(hostId: String): CheckResult?

    @Query("SELECT * FROM check_results WHERE status = :status ORDER BY timestamp DESC")
    fun getResultsByStatus(status: String): Flow<List<CheckResult>>

    @Query("SELECT * FROM check_results WHERE checkType = :checkType ORDER BY timestamp DESC")
    fun getResultsByCheckType(checkType: String): Flow<List<CheckResult>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(result: CheckResult)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(results: List<CheckResult>)

    @Query("DELETE FROM check_results")
    suspend fun deleteAll()

    @Query("DELETE FROM check_results WHERE hostId = :hostId")
    suspend fun deleteByHostId(hostId: String)

    @Query("DELETE FROM check_results WHERE timestamp < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)
}
