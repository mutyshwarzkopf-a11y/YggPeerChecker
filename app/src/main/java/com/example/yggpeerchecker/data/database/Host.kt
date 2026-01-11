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
    val dnsTimestamp: Long? = null           // когда был выполнен DNS резолв
) {
    companion object {
        // Типы хостов для Ygg пиров
        val YGG_TYPES = setOf("tcp", "tls", "quic", "ws", "wss")

        // Типы хостов для SNI/общих проверок
        val SNI_TYPES = setOf("sni", "http", "https")

        fun isYggType(type: String): Boolean = type.lowercase() in YGG_TYPES
        fun isSniType(type: String): Boolean = type.lowercase() in SNI_TYPES
    }
}
