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
    // TTL из ping (-1 = не получен)
    val pingTtl: Int = -1,
    // Active probing результаты
    val httpStatusCode: Int = -1,      // HTTP status code port 80
    val httpsStatusCode: Int = -1,     // HTTP status code port 443
    val httpFingerprint: String = "",   // HTTP fingerprint detail
    val certFingerprint: String = "",   // Certificate fingerprint detail
    val activeWarning: String = "",     // "blocked", "cert_mismatch", ""
    val port80Blocked: Boolean = false,
    val port443Blocked: Boolean = false,
    // Расширенные результаты active probing
    val redirectUrl: String = "",              // Location header при 3xx
    val responseSize: Int = -1,               // Байт в ответе (-1 = не проверялось)
    val comparativeTimingRatio: Float = -1f,  // port_ms / ping_ms (-1 = не проверялось)
    val redirectChain: String = "",           // Цепочка "url1 → url2 → final"
    // Нормализованный ключ для сопоставления (address:port в lowercase)
    val normalizedKey: String = ""
) {
    // Хост жив если есть хоть один положительный ответ по любой проверке
    fun isAlive(): Boolean = pingMs >= 0 || yggRttMs >= 0 || portDefaultMs >= 0 || port80Ms >= 0 || port443Ms >= 0

    // Форматирование результата проверки
    fun formatCheckResult(value: Long): String = when {
        value >= 0 -> "${value}ms"
        value == -2L -> "X"
        else -> "off"
    }

    fun getErrorReason(): String = when {
        checkError.contains("timeout") -> "Timeout"
        checkError.contains("connection refused") -> "Refused"
        checkError.contains("no such host") -> "No host"
        checkError.contains("network unreachable") -> "Unreachable"
        checkError.isNotEmpty() -> "Failed"
        else -> "Unknown"
    }

    // Сериализация в JSON для persist
    fun toSaveJson(): JSONObject {
        return JSONObject().apply {
            put("address", address)
            put("protocol", protocol)
            put("region", region)
            put("geoIp", geoIp)
            put("source", source)
            put("sourceShort", sourceShort)
            put("rtt", rtt)
            put("available", available)
            put("responseMs", responseMs)
            put("lastSeen", lastSeen)
            put("checkError", checkError)
            put("ping", ping)
            put("pingMs", pingMs)
            put("yggRttMs", yggRttMs)
            put("portDefaultMs", portDefaultMs)
            put("port80Ms", port80Ms)
            put("port443Ms", port443Ms)
            put("hops", hops)
            put("pingTtl", pingTtl)
            put("httpStatusCode", httpStatusCode)
            put("httpsStatusCode", httpsStatusCode)
            put("httpFingerprint", httpFingerprint)
            put("certFingerprint", certFingerprint)
            put("activeWarning", activeWarning)
            put("port80Blocked", port80Blocked)
            put("port443Blocked", port443Blocked)
            put("redirectUrl", redirectUrl)
            put("responseSize", responseSize)
            put("comparativeTimingRatio", comparativeTimingRatio.toDouble())
            put("redirectChain", redirectChain)
            put("normalizedKey", normalizedKey)
        }
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

        // Загрузка из persist JSON
        fun fromSaveJson(json: JSONObject): DiscoveredPeer {
            return DiscoveredPeer(
                address = json.optString("address", ""),
                protocol = json.optString("protocol", ""),
                region = json.optString("region", ""),
                geoIp = json.optString("geoIp", ""),
                source = json.optString("source", ""),
                sourceShort = json.optString("sourceShort", ""),
                rtt = json.optLong("rtt", 0),
                available = json.optBoolean("available", false),
                responseMs = json.optInt("responseMs", 0),
                lastSeen = json.optLong("lastSeen", 0),
                checkError = json.optString("checkError", ""),
                ping = json.optLong("ping", -1),
                pingMs = json.optLong("pingMs", -1),
                yggRttMs = json.optLong("yggRttMs", -1),
                portDefaultMs = json.optLong("portDefaultMs", -1),
                port80Ms = json.optLong("port80Ms", -1),
                port443Ms = json.optLong("port443Ms", -1),
                hops = json.optInt("hops", -1),
                pingTtl = json.optInt("pingTtl", -1),
                httpStatusCode = json.optInt("httpStatusCode", -1),
                httpsStatusCode = json.optInt("httpsStatusCode", -1),
                httpFingerprint = json.optString("httpFingerprint", ""),
                certFingerprint = json.optString("certFingerprint", ""),
                activeWarning = json.optString("activeWarning", ""),
                port80Blocked = json.optBoolean("port80Blocked", false),
                port443Blocked = json.optBoolean("port443Blocked", false),
                redirectUrl = json.optString("redirectUrl", ""),
                responseSize = json.optInt("responseSize", -1),
                comparativeTimingRatio = json.optDouble("comparativeTimingRatio", -1.0).toFloat(),
                redirectChain = json.optString("redirectChain", ""),
                normalizedKey = json.optString("normalizedKey", "")
            )
        }
    }
}
