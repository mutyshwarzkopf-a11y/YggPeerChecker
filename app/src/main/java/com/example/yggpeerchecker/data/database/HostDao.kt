package com.example.yggpeerchecker.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface HostDao {
    @Query("SELECT * FROM hosts ORDER BY dateAdded DESC")
    fun getAllHosts(): Flow<List<Host>>

    @Query("SELECT * FROM hosts ORDER BY dateAdded DESC")
    suspend fun getAllHostsList(): List<Host>

    @Query("SELECT * FROM hosts WHERE hostType IN (:types) ORDER BY dateAdded DESC")
    fun getHostsByTypes(types: List<String>): Flow<List<Host>>

    @Query("SELECT * FROM hosts WHERE hostType IN (:types)")
    suspend fun getHostsByTypesList(types: List<String>): List<Host>

    @Query("SELECT * FROM hosts WHERE hostType IN ('tcp', 'tls', 'quic', 'ws', 'wss')")
    suspend fun getYggHostsList(): List<Host>

    @Query("SELECT * FROM hosts WHERE hostType IN ('sni', 'http', 'https')")
    suspend fun getSniHostsList(): List<Host>

    @Query("SELECT * FROM hosts WHERE id = :id")
    suspend fun getHostById(id: String): Host?

    @Query("SELECT * FROM hosts WHERE source = :source")
    suspend fun getHostsBySource(source: String): List<Host>

    @Query("SELECT COUNT(*) FROM hosts")
    fun getHostsCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM hosts WHERE hostType IN ('tcp', 'tls', 'quic', 'ws', 'wss')")
    fun getYggHostsCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM hosts WHERE hostType IN ('sni', 'http', 'https')")
    fun getSniHostsCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM hosts WHERE hostType IN ('vless', 'vmess')")
    fun getVlessHostsCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM hosts WHERE dnsIp1 IS NOT NULL")
    fun getResolvedHostsCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(host: Host)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(hosts: List<Host>)

    @Update
    suspend fun update(host: Host)

    @Delete
    suspend fun delete(host: Host)

    @Query("DELETE FROM hosts")
    suspend fun deleteAll()

    @Query("DELETE FROM hosts WHERE source = :source")
    suspend fun deleteBySource(source: String)

    // Удаление по списку ID (для Clear Visible)
    @Query("DELETE FROM hosts WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("UPDATE hosts SET dnsIp1 = :ip1, dnsIp2 = :ip2, dnsIp3 = :ip3, dnsTimestamp = :timestamp WHERE id = :hostId")
    suspend fun updateDnsIps(hostId: String, ip1: String?, ip2: String?, ip3: String?, timestamp: Long)

    // Очищаем DNS только для хостов где address != dnsIp1 (т.е. не чистые IP)
    @Query("UPDATE hosts SET dnsIp1 = NULL, dnsIp2 = NULL, dnsIp3 = NULL, dnsTimestamp = NULL WHERE address != dnsIp1 OR dnsIp1 IS NULL")
    suspend fun clearAllDns()

    // Очистить DNS только для резолвленных (не чистых IP)
    @Query("UPDATE hosts SET dnsIp1 = NULL, dnsIp2 = NULL, dnsIp3 = NULL, dnsTimestamp = NULL WHERE dnsTimestamp IS NOT NULL")
    suspend fun clearResolvedDns()

    // Очистить DNS по списку ID (для Clear DNS по фильтру)
    @Query("UPDATE hosts SET dnsIp1 = NULL, dnsIp2 = NULL, dnsIp3 = NULL, dnsTimestamp = NULL WHERE id IN (:ids)")
    suspend fun clearDnsByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM hosts")
    suspend fun getHostCount(): Int

    @Query("SELECT COUNT(*) FROM hosts WHERE hostType IN ('tcp', 'tls', 'quic', 'ws', 'wss')")
    suspend fun getYggHostsCountSync(): Int

    @Query("SELECT COUNT(*) FROM hosts WHERE hostType IN ('sni', 'http', 'https')")
    suspend fun getSniHostsCountSync(): Int

    @Query("SELECT COUNT(*) FROM hosts WHERE hostType IN ('vless', 'vmess')")
    suspend fun getVlessHostsCountSync(): Int

    @Query("SELECT * FROM hosts WHERE hostType IN ('tcp', 'tls', 'quic', 'ws', 'wss')")
    suspend fun getYggHosts(): List<Host>

    @Query("SELECT * FROM hosts WHERE hostType IN ('sni', 'http', 'https')")
    suspend fun getSniHosts(): List<Host>

    // GeoIP методы
    @Query("UPDATE hosts SET geoIp = :geoIp WHERE id = :hostId")
    suspend fun updateGeoIp(hostId: String, geoIp: String)

    @Query("UPDATE hosts SET geoIp = NULL WHERE id IN (:ids)")
    suspend fun clearGeoIpByIds(ids: List<String>)

    @Query("SELECT * FROM hosts WHERE geoIp IS NULL")
    suspend fun getHostsWithoutGeoIp(): List<Host>

    // Получение уникальных источников для фильтра
    @Query("SELECT DISTINCT source FROM hosts")
    suspend fun getDistinctSources(): List<String>

    // Подсчёт хостов по источнику
    @Query("SELECT COUNT(*) FROM hosts WHERE source = :source")
    suspend fun getHostCountBySource(source: String): Int

    // Получение хостов по источнику
    @Query("SELECT * FROM hosts WHERE source = :source ORDER BY dateAdded DESC")
    suspend fun getHostsBySourceList(source: String): List<Host>
}
