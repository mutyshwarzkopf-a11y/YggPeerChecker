package com.example.yggpeerchecker.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket

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

        val available = pingResult.rttMs >= 0 || portResult?.available == true
        val responseTime = when {
            portResult?.available == true -> portResult.responseTime
            pingResult.rttMs >= 0 -> pingResult.rttMs
            else -> System.currentTimeMillis() - startTime
        }

        CheckResult(
            available = available,
            responseTime = responseTime,
            pingTime = if (pingResult.rttMs >= 0) pingResult.rttMs else null,
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

    /**
     * Проверка порта с TLS/SNI handshake
     * Используется для проверки HTTPS хостов через IP адрес с указанием оригинального hostname в SNI
     *
     * @param targetHost IP адрес или хост для подключения
     * @param sniHostname Оригинальное доменное имя для SNI (если отличается от targetHost)
     * @param port Порт для подключения (по умолчанию 443)
     * @param timeoutMs Таймаут в миллисекундах
     */
    suspend fun checkPortWithSni(
        targetHost: String,
        sniHostname: String? = null,
        port: Int = 443,
        timeoutMs: Int = 5000
    ): CheckResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val hostname = sniHostname ?: targetHost

        try {
            val result = withTimeoutOrNull(timeoutMs.toLong()) {
                // Создаём обычный TCP сокет
                val socket = Socket()
                try {
                    socket.connect(InetSocketAddress(targetHost, port), timeoutMs)
                    socket.soTimeout = timeoutMs

                    // Создаём SSLContext и оборачиваем сокет
                    val sslContext = SSLContext.getInstance("TLS")
                    sslContext.init(null, null, null)

                    val sslSocketFactory = sslContext.socketFactory
                    val sslSocket = sslSocketFactory.createSocket(
                        socket,
                        hostname,  // Используем hostname для SNI
                        port,
                        true  // autoClose
                    ) as SSLSocket

                    try {
                        // Настраиваем SNI параметры
                        val sslParams = SSLParameters()
                        sslParams.serverNames = listOf(SNIHostName(hostname))
                        sslSocket.sslParameters = sslParams

                        // Выполняем TLS handshake
                        sslSocket.startHandshake()

                        val endTime = System.currentTimeMillis()
                        CheckResult(
                            available = true,
                            responseTime = endTime - startTime,
                            portTime = endTime - startTime
                        )
                    } finally {
                        try {
                            sslSocket.close()
                        } catch (e: Exception) {
                            // Игнорируем ошибки закрытия
                        }
                    }
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
                error = "TLS handshake timeout"
            )
        } catch (e: Exception) {
            CheckResult(
                available = false,
                responseTime = System.currentTimeMillis() - startTime,
                error = e.message ?: "TLS connection failed"
            )
        }
    }

    /**
     * Проверка HTTPS хоста через IP с SNI
     * Подключается к IP адресу, но указывает оригинальный hostname в SNI
     */
    suspend fun checkHttpsWithSni(
        ipAddress: String,
        hostname: String,
        port: Int = 443,
        timeoutMs: Int = 5000
    ): CheckResult {
        return checkPortWithSni(
            targetHost = ipAddress,
            sniHostname = hostname,
            port = port,
            timeoutMs = timeoutMs
        )
    }
}
