package com.example.yggpeerchecker.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocketFactory

/**
 * Проверка Yggdrasil пиров через прямое TCP/TLS соединение.
 * Измеряет RTT (время до установления соединения).
 * 
 * Поддерживаемые протоколы:
 * - tcp:// - прямой TCP connect
 * - tls:// - TLS handshake
 * - ws:// - WebSocket (TCP на тот же порт)
 * - wss:// - WebSocket Secure (TLS на тот же порт)
 * - quic:// - UDP/QUIC (пока не поддерживается, возвращает -1)
 */
object YggConnectChecker {

    private const val CONNECT_TIMEOUT_MS = 5000

    /**
     * Проверяет доступность Ygg пира и возвращает RTT в миллисекундах.
     * 
     * @param hostString Полный URL пира (например, "tcp://host:port?key=...")
     * @return RTT в ms при успехе, -1 при ошибке или неподдерживаемом протоколе
     */
    suspend fun checkPeer(hostString: String): Long = withContext(Dispatchers.IO) {
        try {
            val (host, port, protocol) = parseUri(hostString)
            
            when (protocol) {
                "tcp", "ws" -> checkTcp(host, port)
                "tls", "wss" -> checkTls(host, port)
                "quic" -> {
                    // QUIC требует UDP и специальную библиотеку
                    // Пока возвращаем -1 (не поддерживается)
                    -1L
                }
                else -> -1L
            }
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * Проверка через TCP connect
     */
    private fun checkTcp(host: String, port: Int): Long {
        val startTime = System.currentTimeMillis()
        val socket = Socket()
        
        return try {
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            val rtt = System.currentTimeMillis() - startTime
            rtt
        } catch (e: Exception) {
            -1L
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                // Игнорируем ошибки закрытия
            }
        }
    }

    /**
     * Проверка через TLS handshake
     */
    private fun checkTls(host: String, port: Int): Long {
        val startTime = System.currentTimeMillis()
        val socket = Socket()
        
        return try {
            // Сначала TCP connect
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = CONNECT_TIMEOUT_MS
            
            // Затем TLS handshake
            val sslSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val sslSocket = sslSocketFactory.createSocket(
                socket,
                host,
                port,
                true // autoClose
            ) as javax.net.ssl.SSLSocket
            
            // Yggdrasil использует самоподписанные сертификаты
            // Для проверки доступности достаточно установить соединение
            sslSocket.startHandshake()
            
            val rtt = System.currentTimeMillis() - startTime
            
            sslSocket.close()
            rtt
        } catch (e: Exception) {
            -1L
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                // Игнорируем ошибки закрытия
            }
        }
    }

    /**
     * Парсит URI и извлекает host, port, protocol
     */
    private fun parseUri(uriString: String): Triple<String, Int, String> {
        // Убираем query параметры для парсинга
        val cleanUri = uriString.split("?")[0]
        
        return try {
            val uri = URI(cleanUri)
            var host = uri.host ?: throw IllegalArgumentException("No host in URI")
            val port = uri.port
            val protocol = uri.scheme?.lowercase() ?: "tcp"
            
            if (port <= 0) {
                throw IllegalArgumentException("Invalid port in URI")
            }
            
            // Убираем IPv6 скобки если есть
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length - 1)
            }
            
            Triple(host, port, protocol)
        } catch (e: Exception) {
            // Fallback: ручной парсинг
            val regex = Regex("""^([a-z]+)://\[?([^\]/:]+)\]?:(\d+)""")
            val match = regex.find(uriString)
            if (match != null) {
                val protocol = match.groupValues[1]
                val host = match.groupValues[2]
                val port = match.groupValues[3].toInt()
                Triple(host, port, protocol)
            } else {
                throw IllegalArgumentException("Cannot parse URI: $uriString")
            }
        }
    }
}
