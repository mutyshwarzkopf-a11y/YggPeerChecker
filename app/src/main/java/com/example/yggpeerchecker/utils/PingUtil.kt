package com.example.yggpeerchecker.utils

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Результат пинга: RTT + TTL
 */
data class PingResult(
    val rttMs: Long = -1,   // RTT в миллисекундах (-1 = не проверялось, -2 = failed)
    val ttl: Int = -1       // TTL из ответа (-1 = не получен)
)

object PingUtil {
    /**
     * Ping a host and return PingResult with RTT and TTL.
     * @param count количество пингов (1-5). При count>1 возвращает avg RTT
     */
    fun ping(host: String, timeoutMs: Int = 3000, count: Int = 1): PingResult {
        return try {
            val timeoutSec = (timeoutMs / 1000).coerceAtLeast(1)
            val pingCount = count.coerceIn(1, 5)
            val process = Runtime.getRuntime().exec(
                arrayOf("ping", "-c", pingCount.toString(), "-W", timeoutSec.toString(), host)
            )

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            var rtt: Long = -1
            var ttl: Int = -1

            while (reader.readLine().also { line = it } != null) {
                // Парсим TTL из строк вида "64 bytes from ...: icmp_seq=1 ttl=64 time=45.2 ms"
                if (ttl < 0 && line!!.contains("ttl=")) {
                    val ttlStr = line!!.substringAfter("ttl=").substringBefore(" ")
                    ttl = ttlStr.toIntOrNull() ?: -1
                }

                if (pingCount > 1) {
                    // При count>1 парсим avg из строки "rtt min/avg/max/mdev = ..."
                    if (line!!.contains("min/avg/max")) {
                        val statsStr = line!!.substringAfter("= ").substringBefore(" ms")
                        val parts = statsStr.split("/")
                        if (parts.size >= 2) {
                            rtt = parts[1].trim().toDoubleOrNull()?.toLong() ?: -1
                        }
                    }
                } else {
                    // При count=1 парсим "time=45.2 ms"
                    if (line!!.contains("time=")) {
                        val parts = line!!.split("time=")
                        if (parts.size > 1) {
                            val timeStr = parts[1].split(" ")[0]
                            rtt = timeStr.toDoubleOrNull()?.toLong() ?: -1
                            break
                        }
                    }
                }
            }

            reader.close()
            process.waitFor()
            process.destroy()

            PingResult(rtt, ttl)
        } catch (e: Exception) {
            PingResult(-1, -1)
        }
    }

    /**
     * Extract hostname from address like "tcp://example.com:8080"
     * Делегируем UrlParser
     */
    fun extractHost(address: String): String? = UrlParser.extractHostname(address)

    /**
     * Check if address looks like it can be pinged (has hostname or IP)
     */
    fun canPing(address: String): Boolean {
        val host = extractHost(address) ?: return false
        return true
    }
}
