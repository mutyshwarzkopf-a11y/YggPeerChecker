package com.example.yggpeerchecker.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DnsCacheDao {
    @Query("SELECT * FROM dns_cache WHERE hostname = :hostname")
    suspend fun getByHostname(hostname: String): DnsCache?

    @Query("SELECT * FROM dns_cache")
    suspend fun getAll(): List<DnsCache>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(dnsCache: DnsCache)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(dnsCaches: List<DnsCache>)

    @Query("DELETE FROM dns_cache WHERE hostname = :hostname")
    suspend fun delete(hostname: String)

    @Query("DELETE FROM dns_cache")
    suspend fun deleteAll()

    @Query("DELETE FROM dns_cache WHERE cachedAt + (ttl * 1000) < :currentTime")
    suspend fun deleteExpired(currentTime: Long)
}
