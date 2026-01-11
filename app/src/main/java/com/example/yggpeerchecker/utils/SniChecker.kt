package com.example.yggpeerchecker.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Утилита для проверки SNI/Host хостов
 * Проверяет доступность через ping и/или TCP порт
 */
object SniChecker {

    data class CheckResult(
        val available: Boolean,
        val responseTime: Long,      // Время ответа в ms
        val pingTime: Long? = null,  // ICMP ping результат
        val portTime: Long? = null,  // TCP port check результат
        val error: String? = null
    )

    /**
     * Проверка доступности хоста через TCP порт
     */
    suspend fun checkPort(
        host: String,
        port: Int,
        timeoutMs: Int = 3000
    ): CheckResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            val result = withTimeoutOrNull(timeoutMs.toLong()) {
                val socket = Socket()
                try {
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    val endTime = System.currentTimeMillis()
                    CheckResult(
                        available = true,
                        responseTime = endTime - startTime,
                        portTime = endTime - startTime
                    )
                } finally {
                    try {
                        socket.close()
                    } catch (e: Exception) {
                        // Игнорируем ошибки закрытия
                    }
                }
            }

            result ?: CheckResult(
                available = false,
                responseTime = timeoutMs.toLong(),
                error = "Connection timeout"
            )
        } catch (e: Exception) {
            CheckResult(
                available = false,
                responseTime = System.currentTimeMillis() - startTime,
                error = e.message ?: "Connection failed"
            )
        }
    }

    /**
     * Проверка хоста через ICMP ping (если возможно) и TCP порт
     */
    suspend fun checkHost(
        host: String,
        port: Int? = null,
        timeoutMs: Int = 3000
    ): CheckResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        // Сначала пробуем ping
        val pingResult = PingUtil.ping(host, timeoutMs)

        // Если указан порт, проверяем и его
        val portResult = if (port != null) {
            checkPort(host, port, timeoutMs)
        } else {
            null
        }

        val available = pingResult >= 0 || portResult?.available == true
        val responseTime = when {
            portResult?.available == true -> portResult.responseTime
            pingResult >= 0 -> pingResult.toLong()
            else -> System.currentTimeMillis() - startTime
        }

        CheckResult(
            available = available,
            responseTime = responseTime,
            pingTime = if (pingResult >= 0) pingResult.toLong() else null,
            portTime = portResult?.portTime,
            error = if (!available) portResult?.error ?: "Host unreachable" else null
        )
    }

    /**
     * Проверка HTTP(S) хоста через стандартные порты
     */
    suspend fun checkHttpHost(
        host: String,
        isHttps: Boolean = false,
        timeoutMs: Int = 3000
    ): CheckResult {
        val port = if (isHttps) 443 else 80
        return checkPort(host, port, timeoutMs)
    }

    /**
     * Проверка нескольких портов и выбор лучшего результата
     */
    suspend fun checkMultiplePorts(
        host: String,
        ports: List<Int>,
        timeoutMs: Int = 3000
    ): CheckResult = withContext(Dispatchers.IO) {
        var bestResult: CheckResult? = null

        for (port in ports) {
            val result = checkPort(host, port, timeoutMs)
            if (result.available) {
                if (bestResult == null || result.responseTime < bestResult.responseTime) {
                    bestResult = result
                }
            }
        }

        bestResult ?: CheckResult(
            available = false,
            responseTime = timeoutMs.toLong(),
            error = "All ports unreachable"
        )
    }
}
