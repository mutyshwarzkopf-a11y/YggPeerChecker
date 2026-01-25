package com.example.yggpeerchecker.data

import org.json.JSONObject

/**
 * Represents a discovered Yggdrasil peer
 */
data class DiscoveredPeer(
    val address: String,      // "tls://host:port"
    val protocol: String,     // "tcp", "tls", "quic", etc.
    val region: String,       // "germany", "france", etc.
    val geoIp: String = "",   // GeoIP info "CC:City" (например "US:Washington")
    val source: String = "",  // Источник (URL откуда загружен)
    val sourceShort: String = "",  // Короткое имя источника (ygg:neil, miniblack и т.д.)
    val rtt: Long,            // Best RTT for sorting (deprecated, use specific fields)
    val available: Boolean,   // Deprecated, use isAlive()
    val responseMs: Int,      // Response time from publicnodes.json
    val lastSeen: Long,       // Unix timestamp
    val checkError: String = "",  // Error reason if not available
    val ping: Long = -1,      // ICMP ping in milliseconds (-1 if not checked)
    // Индивидуальные результаты проверок (-1 = не проверялось, -2 = failed)
    val pingMs: Long = -1,
    val yggRttMs: Long = -1,
    val portDefaultMs: Long = -1,
    val port80Ms: Long = -1,
    val port443Ms: Long = -1,
    // Tracert hops (-1 = не проверялось)
    val hops: Int = -1,
    // Нормализованный ключ для сопоставления (address:port в lowercase)
    val normalizedKey: String = ""
) {
    // Хост жив если есть хоть один положительный ответ по любой проверке
    fun isAlive(): Boolean = pingMs >= 0 || yggRttMs >= 0 || portDefaultMs >= 0 || port80Ms >= 0 || port443Ms >= 0

    fun getRttFormatted(): String = "${rtt}ms"
    fun getPingFormatted(): String = if (ping >= 0) "${ping}ms" else "---"

    // Форматирование результата проверки
    fun formatCheckResult(value: Long): String = when {
        value >= 0 -> "${value}ms"
        value == -2L -> "X"
        else -> "off"
    }
    
    // Форматирование hops
    fun formatHops(): String = if (hops > 0) "${hops}h" else "---"

    fun getErrorReason(): String = when {
        checkError.contains("timeout") -> "Timeout"
        checkError.contains("connection refused") -> "Refused"
        checkError.contains("no such host") -> "No host"
        checkError.contains("network unreachable") -> "Unreachable"
        checkError.isNotEmpty() -> "Failed"
        else -> "Unknown"
    }

    companion object {
        fun fromJson(json: JSONObject): DiscoveredPeer {
            // RTT is stored as nanoseconds (time.Duration), convert to milliseconds
            val rttNs = json.optLong("RTT", 0)
            val rttMs = if (rttNs > 0) rttNs / 1_000_000 else 0

            return DiscoveredPeer(
                address = json.getString("Address"),
                protocol = json.optString("Protocol", ""),
                region = json.optString("Region", ""),
                rtt = rttMs,
                available = json.optBoolean("Available", false),
                responseMs = json.optInt("response_ms", 0),
                lastSeen = json.optLong("last_seen", 0),
                checkError = json.optString("CheckError", "")
            )
        }
    }
}
