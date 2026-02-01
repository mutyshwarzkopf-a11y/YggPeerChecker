package com.example.yggpeerchecker.utils

import android.content.Context
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/**
 * Кастомный DNS резолвер с поддержкой выбора DNS сервера
 * Позволяет обходить системный DNS и использовать альтернативные серверы
 */
object DnsResolver {

    // Результат резолва с детекцией подмены
    data class ResolveResult(
        val ips: List<String>,
        val isSpoofed: Boolean = false,  // true если обнаружена подмена (127.0.0.1)
        val error: String? = null
    )

    // IP адреса которые считаются подменой DNS
    private val SPOOFED_IPS = setOf(
        "127.0.0.1",
        "0.0.0.0",
        "127.0.0.0",
        "::1"
    )

    /**
     * Резолв hostname через указанный DNS сервер
     * @param hostname - имя хоста для резолва
     * @param dnsServer - IP адрес DNS сервера (напр. "8.8.8.8")
     * @param timeout - таймаут в мс
     * @return ResolveResult с IP адресами и флагом подмены
     */
    fun resolve(hostname: String, dnsServer: String, timeout: Int = 5000): ResolveResult {
        return try {
            // Приводим к lowercase и конвертируем IDN
            val normalizedHost = try {
                java.net.IDN.toASCII(hostname.lowercase().trim())
            } catch (e: Exception) {
                hostname.lowercase().trim()
            }

            // Если dnsServer пустой или "system" - используем системный резолвер
            if (dnsServer.isEmpty() || dnsServer == "system") {
                return resolveSystem(normalizedHost)
            }

            // Кастомный DNS запрос через UDP
            val ips = try {
                queryDns(normalizedHost, dnsServer, timeout)
            } catch (e: Exception) {
                // Fallback на системный резолвер если UDP не работает
                val systemResult = resolveSystem(normalizedHost)
                return systemResult
            }

            // Проверяем на подмену (localhost адреса) — НЕ фильтруем, только помечаем
            val isSpoofed = ips.any { it in SPOOFED_IPS }

            ResolveResult(
                ips = ips.take(5),  // Возвращаем до 5 IP (ip1-ip5), включая localhost для отображения
                isSpoofed = isSpoofed
            )
        } catch (e: SocketTimeoutException) {
            // Fallback на системный резолвер при таймауте
            try {
                resolveSystem(hostname.lowercase().trim())
            } catch (e2: Exception) {
                ResolveResult(emptyList(), error = "DNS timeout + system fallback failed")
            }
        } catch (e: Exception) {
            // Fallback на системный резолвер при любой ошибке
            try {
                resolveSystem(hostname.lowercase().trim())
            } catch (e2: Exception) {
                ResolveResult(emptyList(), error = e.message)
            }
        }
    }

    /**
     * Системный резолвер (fallback)
     */
    private fun resolveSystem(hostname: String): ResolveResult {
        return try {
            val addresses = InetAddress.getAllByName(hostname)
            val ips = addresses.map { it.hostAddress ?: "" }
                .filter { it.isNotEmpty() }
                .take(5)

            val isSpoofed = ips.any { it in SPOOFED_IPS }
            // Не фильтруем localhost — возвращаем все IP, включая spoofed, для отображения
            ResolveResult(ips, isSpoofed)
        } catch (e: Exception) {
            ResolveResult(emptyList(), error = e.message)
        }
    }

    /**
     * Отправка DNS запроса через UDP
     * Простая реализация DNS протокола для A записей
     */
    private fun queryDns(hostname: String, dnsServer: String, timeout: Int): List<String> {
        val socket = DatagramSocket()
        socket.soTimeout = timeout

        try {
            val dnsAddress = InetAddress.getByName(dnsServer)
            val query = buildDnsQuery(hostname)

            // Отправляем запрос
            val requestPacket = DatagramPacket(query, query.size, dnsAddress, 53)
            socket.send(requestPacket)

            // Получаем ответ
            val responseBuffer = ByteArray(512)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(responsePacket)

            // Парсим ответ
            return parseDnsResponse(responsePacket.data, responsePacket.length)
        } finally {
            socket.close()
        }
    }

    /**
     * Построение DNS запроса (A запись)
     */
    private fun buildDnsQuery(hostname: String): ByteArray {
        val buffer = ByteBuffer.allocate(512)

        // ID (random)
        val id = (System.currentTimeMillis() and 0xFFFF).toShort()
        buffer.putShort(id)

        // Flags: standard query
        buffer.putShort(0x0100.toShort())

        // Questions: 1
        buffer.putShort(1)
        // Answer RRs: 0
        buffer.putShort(0)
        // Authority RRs: 0
        buffer.putShort(0)
        // Additional RRs: 0
        buffer.putShort(0)

        // Question: hostname
        hostname.split(".").forEach { label ->
            buffer.put(label.length.toByte())
            buffer.put(label.toByteArray())
        }
        buffer.put(0) // End of name

        // Type: A (1)
        buffer.putShort(1)
        // Class: IN (1)
        buffer.putShort(1)

        val result = ByteArray(buffer.position())
        buffer.flip()
        buffer.get(result)
        return result
    }

    /**
     * Парсинг DNS ответа
     */
    private fun parseDnsResponse(data: ByteArray, length: Int): List<String> {
        if (length < 12) return emptyList()

        val buffer = ByteBuffer.wrap(data, 0, length)

        // Skip header
        buffer.position(4)
        val questions = buffer.short.toInt() and 0xFFFF
        val answers = buffer.short.toInt() and 0xFFFF
        buffer.position(12)

        // Skip questions
        repeat(questions) {
            while (buffer.hasRemaining()) {
                val labelLen = buffer.get().toInt() and 0xFF
                if (labelLen == 0) break
                if (labelLen >= 0xC0) {
                    buffer.get() // Skip pointer
                    break
                }
                buffer.position(buffer.position() + labelLen)
            }
            if (buffer.remaining() >= 4) {
                buffer.short // Type
                buffer.short // Class
            }
        }

        // Parse answers
        val ips = mutableListOf<String>()
        repeat(answers) {
            if (buffer.remaining() < 12) return@repeat

            // Name (may be pointer)
            val firstByte = buffer.get().toInt() and 0xFF
            if (firstByte >= 0xC0) {
                buffer.get() // Skip pointer second byte
            } else {
                // Skip name labels
                var len = firstByte
                while (len > 0 && buffer.hasRemaining()) {
                    buffer.position(buffer.position() + len)
                    if (buffer.hasRemaining()) {
                        len = buffer.get().toInt() and 0xFF
                    } else {
                        len = 0
                    }
                }
            }

            if (buffer.remaining() < 10) return@repeat

            val type = buffer.short.toInt() and 0xFFFF
            buffer.short // Class
            buffer.int   // TTL
            val rdLength = buffer.short.toInt() and 0xFFFF

            if (type == 1 && rdLength == 4 && buffer.remaining() >= 4) {
                // A record
                val ip = "${buffer.get().toInt() and 0xFF}." +
                        "${buffer.get().toInt() and 0xFF}." +
                        "${buffer.get().toInt() and 0xFF}." +
                        "${buffer.get().toInt() and 0xFF}"
                ips.add(ip)
            } else if (buffer.remaining() >= rdLength) {
                buffer.position(buffer.position() + rdLength)
            }
        }

        return ips
    }

    /**
     * Получение выбранного DNS сервера из настроек
     */
    fun getSelectedDnsServer(context: Context): String {
        val prefs = context.getSharedPreferences("ygg_prefs", Context.MODE_PRIVATE)
        return prefs.getString("dns_server", "system") ?: "system"  // System DNS по умолчанию
    }
}
