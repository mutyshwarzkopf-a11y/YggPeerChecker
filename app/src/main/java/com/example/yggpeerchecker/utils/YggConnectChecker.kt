package com.example.yggpeerchecker.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mobile.CheckCallback
import mobile.Manager
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocketFactory

/**
 * Проверка Yggdrasil пиров через прямое TCP/TLS/QUIC соединение.
 * Измеряет RTT (время до установления соединения).
 *
 * Поддерживаемые протоколы:
 * - tcp:// - прямой TCP connect
 * - tls:// - TLS handshake
 * - ws:// - WebSocket (TCP на тот же порт)
 * - wss:// - WebSocket Secure (TLS на тот же порт)
 * - quic:// - UDP/QUIC через yggpeers.aar (quic-go)
 */
object YggConnectChecker {

    private const val CONNECT_TIMEOUT_MS = 5000
    private const val QUIC_TIMEOUT_MS = 6000L

    // Manager из yggpeers.aar — устанавливается из ChecksViewModel
    var manager: Manager? = null

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
                "quic" -> checkQuic(host, port, hostString)
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
     * Проверка QUIC через yggpeers.aar Manager.checkPeersAsync().
     * Конструируем одно-пировый JSON, мост callback -> CompletableDeferred.
     */
    private suspend fun checkQuic(host: String, port: Int, hostString: String): Long {
        val mgr = manager ?: return -1L  // Manager не инициализирован

        val deferred = CompletableDeferred<Long>()

        // JSON для Go: Address, Protocol, Host, Port (Port — строка в Go)
        val peerJson = JSONArray().put(
            JSONObject().apply {
                put("Address", hostString)
                put("Protocol", "quic")
                put("Host", host)
                put("Port", port.toString())
            }
        ).toString()

        mgr.checkPeersAsync(peerJson, object : CheckCallback {
            override fun onPeerChecked(address: String?, available: Boolean, rtt: Long) {
                if (available && rtt >= 0) {
                    deferred.complete(rtt)
                } else {
                    deferred.complete(-1L)
                }
            }

            override fun onCheckComplete(available: Long, total: Long) {
                // Страховка: если onPeerChecked не вызван
                if (!deferred.isCompleted) {
                    deferred.complete(-1L)
                }
            }
        })

        return try {
            withTimeout(QUIC_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * Парсит URI и извлекает host, port, protocol (делегируем UrlParser)
     */
    private fun parseUri(uriString: String): Triple<String, Int, String> {
        val parsed = UrlParser.parse(uriString)
            ?: throw IllegalArgumentException("Cannot parse URI: $uriString")

        val port = parsed.port
            ?: throw IllegalArgumentException("Invalid port in URI: $uriString")

        return Triple(parsed.hostname, port, parsed.protocol)
    }
}
