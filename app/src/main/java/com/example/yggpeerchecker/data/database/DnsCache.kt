package com.example.yggpeerchecker.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity для DNS кэша
 */
@Entity(tableName = "dns_cache")
data class DnsCache(
    @PrimaryKey val hostname: String,
    val ip1: String?,
    val ip2: String?,
    val ip3: String?,
    val ip4: String? = null,
    val ip5: String? = null,
    val dnsSource1: String? = null,          // DNS сервер для ip1
    val dnsSource2: String? = null,          // DNS сервер для ip2
    val dnsSource3: String? = null,          // DNS сервер для ip3
    val dnsSource4: String? = null,          // DNS сервер для ip4
    val dnsSource5: String? = null,          // DNS сервер для ip5
    val cachedAt: Long,
    val ttl: Long = 3600                     // TTL в секундах (по умолчанию 1 час)
) {
    fun isExpired(): Boolean {
        val now = System.currentTimeMillis()
        val expirationTime = cachedAt + (ttl * 1000)
        return now > expirationTime
    }

    fun getIps(): List<String> {
        return listOfNotNull(ip1, ip2, ip3, ip4, ip5)
    }
}
