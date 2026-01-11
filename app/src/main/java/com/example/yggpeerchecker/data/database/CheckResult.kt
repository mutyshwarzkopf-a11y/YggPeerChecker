package com.example.yggpeerchecker.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity для хранения результатов проверок
 */
@Entity(
    tableName = "check_results",
    foreignKeys = [
        ForeignKey(
            entity = Host::class,
            parentColumns = ["id"],
            childColumns = ["hostId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("hostId")]
)
data class CheckResult(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hostId: String,                      // FK к Host.id
    val checkType: String,                   // "ygg_peer", "ping", "port_check", "dns"
    val targetIp: String,                    // IP адрес проверяемого хоста
    val status: String,                      // "online", "offline", "timeout", "dns_failed", "error"
    val responseTime: Long,                  // время ответа в ms (RTT)
    val pingTime: Long? = null,              // результат ICMP ping в ms
    val timestamp: Long,                     // timestamp проверки
    val details: String? = null              // дополнительная информация
) {
    companion object {
        // Статусы проверки
        const val STATUS_ONLINE = "online"
        const val STATUS_OFFLINE = "offline"
        const val STATUS_TIMEOUT = "timeout"
        const val STATUS_DNS_FAILED = "dns_failed"
        const val STATUS_ERROR = "error"

        // Типы проверок
        const val CHECK_YGG_PEER = "ygg_peer"
        const val CHECK_PING = "ping"
        const val CHECK_PORT = "port_check"
        const val CHECK_DNS = "dns"
    }
}
