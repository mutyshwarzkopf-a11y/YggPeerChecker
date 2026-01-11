package com.example.yggpeerchecker.utils

import java.io.BufferedReader
import java.io.InputStreamReader

object PingUtil {
    /**
     * Ping a host and return RTT in milliseconds, or -1 if failed
     */
    fun ping(host: String, timeoutMs: Int = 3000): Long {
        return try {
            val timeoutSec = (timeoutMs / 1000).coerceAtLeast(1)
            val process = Runtime.getRuntime().exec(arrayOf("ping", "-c", "1", "-W", timeoutSec.toString(), host))

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            var rtt: Long = -1

            while (reader.readLine().also { line = it } != null) {
                // Parse: time=45.2 ms
                if (line!!.contains("time=")) {
                    val parts = line!!.split("time=")
                    if (parts.size > 1) {
                        val timeStr = parts[1].split(" ")[0]
                        rtt = timeStr.toDoubleOrNull()?.toLong() ?: -1
                        break
                    }
                }
            }

            reader.close()
            process.waitFor()
            process.destroy()

            rtt
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Extract hostname from address like "tcp://example.com:8080"
     */
    fun extractHost(address: String): String? {
        return try {
            val parts = address.split("://")
            if (parts.size < 2) return null

            val hostPort = parts[1]
            // Remove port and query parameters
            val host = hostPort.split(":")[0].split("?")[0]
            host.takeIf { it.isNotEmpty() && !it.contains("/") }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if address looks like it can be pinged (has hostname or IP)
     */
    fun canPing(address: String): Boolean {
        val host = extractHost(address) ?: return false
        // Allow pinging both hostnames and IP addresses
        return true
    }
}
