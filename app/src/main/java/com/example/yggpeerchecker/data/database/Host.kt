package com.example.yggpeerchecker.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity для хранения хостов и пиров
 * Единая таблица для всех типов: ygg peers, sni hosts, http(s) hosts
 */
@Entity(tableName = "hosts")
data class Host(
    @PrimaryKey val id: String,              // UUID или hash от строки хоста
    val source: String,                      // URL источника или "file"/"clipboard"/"manual"
    val dateAdded: Long,                     // timestamp добавления
    val hostType: String,                    // "tcp", "tls", "quic", "ws", "wss", "sni", "http", "https"
    val hostString: String,                  // полная строка (включая протокол, порт, параметры)
    val address: String,                     // парсированный адрес (доменное имя или IP)
    val port: Int?,                          // парсированный порт (если есть)
    val region: String? = null,              // регион (germany, russia, etc.) для Ygg пиров
    val dnsIp1: String? = null,              // закэшированный первый IP из DNS
    val dnsIp2: String? = null,              // второй IP из DNS результата
    val dnsIp3: String? = null,              // третий IP из DNS результата
    val dnsIp4: String? = null,              // четвёртый DNS IP
    val dnsIp5: String? = null,              // пятый DNS IP
    val dnsSource1: String? = null,          // DNS сервер для ip1 ("yandex", "cloudflare", "google", "system", custom IP)
    val dnsSource2: String? = null,          // DNS сервер для ip2
    val dnsSource3: String? = null,          // DNS сервер для ip3
    val dnsSource4: String? = null,          // DNS сервер для ip4
    val dnsSource5: String? = null,          // DNS сервер для ip5
    val dnsTimestamp: Long? = null,          // когда был выполнен DNS резолв
    val geoIp: String? = null                // GeoIP info: "CC:City" (например "US:Washington")
) {
    companion object {
        // Типы хостов для Ygg пиров
        val YGG_TYPES = setOf("tcp", "tls", "quic", "ws", "wss")

        // Типы хостов для SNI/общих проверок (включая прокси)
        val SNI_TYPES = setOf("sni", "http", "https", "vless", "vmess")

        // Типы прокси (vless/vmess) - подмножество SNI
        val PROXY_TYPES = setOf("vless", "vmess")

        fun isYggType(type: String): Boolean = type.lowercase() in YGG_TYPES
        fun isSniType(type: String): Boolean = type.lowercase() in SNI_TYPES
        fun isProxyType(type: String): Boolean = type.lowercase() in PROXY_TYPES
    }
}
