package com.example.yggpeerchecker.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SNIHostName
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Active Probing — расширенные проверки хостов:
 * - HTTP fingerprinting (ISP stub detection)
 * - Certificate inspection (MITM detection)
 * - HTTP status codes
 */
object ActiveProber {

    /**
     * Результат probe
     */
    data class ProbeResult(
        val type: String,           // "http_fingerprint", "cert_fingerprint", "http_status", "redirect_chain", "response_size", "comparative_timing"
        val warning: String = "",   // "blocked", "cert_mismatch", "anomaly", ""
        val detail: String = "",    // Подробная информация
        val statusCode: Int = -1,   // HTTP status code
        val isBlocking: Boolean = false,  // Обнаружена блокировка
        val redirectUrl: String = "",    // Location header при 3xx
        val responseSize: Int = -1,      // Байт в ответе
        val port: Int = -1               // Порт проверки
    )

    // Паттерны ISP-заглушек
    private val BLOCK_PATTERNS = listOf(
        "access denied", "blocked", "filtered", "rkn", "zapret",
        "restricted", "роскомнадзор", "заблокирован", "blocking",
        "unavailable for legal reasons", "451", "nfgw",
        "internet filter", "content filter", "web filter"
    )

    /**
     * HTTP fingerprinting — обнаружение ISP-заглушек (generic port)
     * Raw TCP → отправляет GET / HTTP/1.1 → читает первые 512 байт
     */
    suspend fun probeHttpFingerprint(host: String, port: Int = 80, timeoutMs: Int = 5000): ProbeResult = withContext(Dispatchers.IO) {
        try {
            val result = withTimeoutOrNull(timeoutMs.toLong()) {
                val socket = Socket()
                try {
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    socket.soTimeout = timeoutMs

                    val writer = OutputStreamWriter(socket.getOutputStream())
                    writer.write("GET / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n")
                    writer.flush()

                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val response = StringBuilder()
                    var charsRead = 0
                    val buffer = CharArray(512)

                    // Читаем до 512 символов
                    while (charsRead < 512) {
                        val read = reader.read(buffer, 0, minOf(512 - charsRead, buffer.size))
                        if (read <= 0) break
                        response.append(buffer, 0, read)
                        charsRead += read
                    }

                    val responseStr = response.toString()
                    val statusCode = parseStatusCode(responseStr)

                    // Проверяем на блокировку
                    val lowerResponse = responseStr.lowercase()
                    val matchedPattern = BLOCK_PATTERNS.firstOrNull { lowerResponse.contains(it) }

                    if (matchedPattern != null) {
                        ProbeResult(
                            type = "http_fingerprint",
                            warning = "blocked",
                            detail = "ISP stub: '$matchedPattern' (HTTP $statusCode)",
                            statusCode = statusCode,
                            isBlocking = true,
                            port = port
                        )
                    } else {
                        ProbeResult(
                            type = "http_fingerprint",
                            detail = "HTTP:$port $statusCode",
                            statusCode = statusCode,
                            port = port
                        )
                    }
                } finally {
                    try { socket.close() } catch (_: Exception) {}
                }
            }

            result ?: ProbeResult(
                type = "http_fingerprint",
                warning = "timeout",
                detail = "HTTP probe timeout",
                statusCode = -1,
                port = port
            )
        } catch (e: Exception) {
            ProbeResult(
                type = "http_fingerprint",
                detail = "Error: ${e.message?.take(50)}",
                statusCode = -1,
                port = port
            )
        }
    }

    /** Обратная совместимость */
    suspend fun probeHttp80(host: String, timeoutMs: Int = 5000): ProbeResult =
        probeHttpFingerprint(host, 80, timeoutMs)

    /**
     * Certificate check — generic port (TLS)
     * TLS connect с trust-all → извлекает CN/SAN, SHA256 fingerprint
     * Сравнивает CN/SAN с hostname для MITM detection
     */
    suspend fun probeCert(
        host: String,
        port: Int = 443,
        hostname: String? = null,
        timeoutMs: Int = 5000
    ): ProbeResult = withContext(Dispatchers.IO) {
        val sniName = hostname ?: host
        try {
            val result = withTimeoutOrNull(timeoutMs.toLong()) {
                val socket = Socket()
                try {
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    socket.soTimeout = timeoutMs

                    val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                    })

                    val sslContext = SSLContext.getInstance("TLS")
                    sslContext.init(null, trustAllCerts, java.security.SecureRandom())

                    val sslSocket = sslContext.socketFactory.createSocket(
                        socket, sniName, port, true
                    ) as SSLSocket

                    try {
                        val sslParams = SSLParameters()
                        sslParams.serverNames = listOf(SNIHostName(sniName))
                        sslSocket.sslParameters = sslParams
                        sslSocket.startHandshake()

                        val certs = sslSocket.session.peerCertificates
                        if (certs.isNotEmpty() && certs[0] is X509Certificate) {
                            val x509 = certs[0] as X509Certificate
                            val cn = extractCN(x509.subjectX500Principal.name)
                            val sans = try {
                                x509.subjectAlternativeNames
                                    ?.filter { it[0] == 2 }
                                    ?.map { it[1].toString() }
                                    ?: emptyList()
                            } catch (_: Exception) { emptyList() }
                            val issuerCn = extractCN(x509.issuerX500Principal.name)
                            val sha256 = MessageDigest.getInstance("SHA-256")
                                .digest(x509.encoded)
                                .joinToString(":") { "%02X".format(it) }
                                .take(23)

                            val allNames = (listOfNotNull(cn) + sans).map { it.lowercase() }
                            val hostLower = sniName.lowercase()
                            val certMatches = allNames.any { name ->
                                name == hostLower || (name.startsWith("*.") &&
                                    hostLower.endsWith(name.removePrefix("*")))
                            }

                            val detail = buildString {
                                append("CN=$cn")
                                if (sans.isNotEmpty()) append(" SAN=${sans.take(3).joinToString(",")}")
                                append(" Issuer=$issuerCn")
                                append(" FP=$sha256")
                            }

                            if (!certMatches && !UrlParser.isIpAddress(sniName)) {
                                ProbeResult(
                                    type = "cert_fingerprint",
                                    warning = "cert_mismatch",
                                    detail = "MISMATCH: host=$sniName $detail",
                                    isBlocking = true,
                                    port = port
                                )
                            } else {
                                ProbeResult(type = "cert_fingerprint", detail = detail, port = port)
                            }
                        } else {
                            ProbeResult(type = "cert_fingerprint", warning = "no_cert", detail = "No certificate received", port = port)
                        }
                    } finally {
                        try { sslSocket.close() } catch (_: Exception) {}
                    }
                } finally {
                    try { socket.close() } catch (_: Exception) {}
                }
            }

            result ?: ProbeResult(type = "cert_fingerprint", warning = "timeout", detail = "TLS probe timeout", port = port)
        } catch (e: Exception) {
            ProbeResult(type = "cert_fingerprint", detail = "Error: ${e.message?.take(50)}", port = port)
        }
    }

    /** Обратная совместимость */
    suspend fun probeCert443(host: String, hostname: String? = null, timeoutMs: Int = 5000): ProbeResult =
        probeCert(host, 443, hostname, timeoutMs)

    /**
     * HTTP status code probe
     * Port 80: Raw TCP GET
     * Port 443: OkHttp GET с trust-all
     * Другие порты: Raw TCP GET
     */
    suspend fun probeHttpStatus(
        host: String,
        port: Int,
        timeoutMs: Int = 5000
    ): ProbeResult = withContext(Dispatchers.IO) {
        try {
            val result = withTimeoutOrNull(timeoutMs.toLong()) {
                if (port == 443) {
                    probeHttpsStatus(host, timeoutMs)
                } else {
                    probeRawHttpStatus(host, port, timeoutMs)
                }
            }

            result ?: ProbeResult(
                type = "http_status",
                detail = "Timeout",
                statusCode = -1
            )
        } catch (e: Exception) {
            ProbeResult(
                type = "http_status",
                detail = "Error: ${e.message?.take(50)}",
                statusCode = -1
            )
        }
    }

    /**
     * Raw TCP HTTP status probe (порт 80 и другие)
     */
    private fun probeRawHttpStatus(host: String, port: Int, timeoutMs: Int): ProbeResult {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs

            val writer = OutputStreamWriter(socket.getOutputStream())
            writer.write("GET / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n")
            writer.flush()

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val firstLine = reader.readLine() ?: ""

            val statusCode = parseStatusCode(firstLine)

            // Проверяем блокировку по status code
            val isBlocking = statusCode == 451 || statusCode == 403

            return ProbeResult(
                type = "http_status",
                warning = if (isBlocking) "blocked" else "",
                detail = "HTTP/$port: $statusCode",
                statusCode = statusCode,
                isBlocking = isBlocking
            )
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /**
     * HTTPS status probe через OkHttp
     */
    private fun probeHttpsStatus(host: String, timeoutMs: Int): ProbeResult {
        try {
            // Trust-all для HTTPS
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

            val client = okhttp3.OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .build()

            val request = okhttp3.Request.Builder()
                .url("https://$host/")
                .build()

            val response = client.newCall(request).execute()
            val statusCode = response.code
            response.close()

            val isBlocking = statusCode == 451 || statusCode == 403

            return ProbeResult(
                type = "http_status",
                warning = if (isBlocking) "blocked" else "",
                detail = "HTTPS: $statusCode",
                statusCode = statusCode,
                isBlocking = isBlocking
            )
        } catch (e: Exception) {
            return ProbeResult(
                type = "http_status",
                detail = "HTTPS error: ${e.message?.take(40)}",
                statusCode = -1
            )
        }
    }

    // === Новые активные проверки ===

    /**
     * Comparative Timing — сравнение TCP connect time с known ping
     * ratio > 10 → аномалия (DPI throttling)
     */
    suspend fun probeComparativeTiming(
        host: String,
        port: Int,
        knownPingMs: Long,
        timeoutMs: Int = 5000
    ): ProbeResult = withContext(Dispatchers.IO) {
        if (knownPingMs <= 0) {
            return@withContext ProbeResult(
                type = "comparative_timing",
                detail = "No ping data",
                port = port
            )
        }
        try {
            val result = withTimeoutOrNull(timeoutMs.toLong()) {
                val startTime = System.currentTimeMillis()
                val socket = Socket()
                try {
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    val connectTime = System.currentTimeMillis() - startTime
                    val ratio = connectTime.toFloat() / knownPingMs.toFloat()

                    val isAnomaly = ratio > 10f
                    ProbeResult(
                        type = "comparative_timing",
                        warning = if (isAnomaly) "anomaly" else "",
                        detail = "TCP:${connectTime}ms/Ping:${knownPingMs}ms=${String.format("%.1f", ratio)}x",
                        isBlocking = isAnomaly,
                        port = port
                    )
                } finally {
                    try { socket.close() } catch (_: Exception) {}
                }
            }
            result ?: ProbeResult(type = "comparative_timing", warning = "timeout", detail = "Timeout", port = port)
        } catch (e: Exception) {
            ProbeResult(type = "comparative_timing", detail = "Error: ${e.message?.take(50)}", port = port)
        }
    }

    /**
     * Redirect Chain — следование 3xx редиректам (до maxRedirects)
     * Обнаружение ISP-доменов в цепочке
     */
    suspend fun probeRedirectChain(
        host: String,
        port: Int,
        maxRedirects: Int = 5,
        timeoutMs: Int = 5000
    ): ProbeResult = withContext(Dispatchers.IO) {
        val chain = mutableListOf<String>()
        var currentHost = host
        var currentPort = port
        var isHttps = port == 443
        var finalStatusCode = -1
        var ispDetected = false

        try {
            for (i in 0 until maxRedirects) {
                val startUrl = "${if (isHttps) "https" else "http"}://$currentHost:$currentPort"
                chain.add(startUrl)

                val response = if (isHttps) {
                    fetchRedirectHttps(currentHost, currentPort, timeoutMs)
                } else {
                    fetchRedirectHttp(currentHost, currentPort, timeoutMs)
                }

                finalStatusCode = response.first
                val location = response.second

                if (finalStatusCode !in 300..399 || location.isNullOrEmpty()) break

                // Парсим Location header
                val parsed = parseRedirectUrl(location, currentHost, currentPort, isHttps)
                currentHost = parsed.first
                currentPort = parsed.second
                isHttps = parsed.third

                // Проверяем ISP домены в цепочке
                val lowerHost = currentHost.lowercase()
                if (BLOCK_PATTERNS.any { lowerHost.contains(it) }) {
                    ispDetected = true
                }
            }

            val chainStr = chain.joinToString(" → ")
            ProbeResult(
                type = "redirect_chain",
                warning = if (ispDetected) "blocked" else "",
                detail = if (chain.size > 1) "$chainStr (HTTP $finalStatusCode)" else "No redirect (HTTP $finalStatusCode)",
                statusCode = finalStatusCode,
                isBlocking = ispDetected,
                redirectUrl = chain.lastOrNull() ?: "",
                port = port
            )
        } catch (e: Exception) {
            ProbeResult(
                type = "redirect_chain",
                detail = "Error: ${e.message?.take(50)}",
                port = port
            )
        }
    }

    /**
     * Response Size — размер ответа, обнаружение ISP-заглушек по маленькому размеру
     * <500B + block pattern → блокировка
     */
    suspend fun probeResponseSize(
        host: String,
        port: Int,
        timeoutMs: Int = 5000
    ): ProbeResult = withContext(Dispatchers.IO) {
        try {
            val result = withTimeoutOrNull(timeoutMs.toLong()) {
                val socket = Socket()
                try {
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    socket.soTimeout = timeoutMs

                    val writer = OutputStreamWriter(socket.getOutputStream())
                    writer.write("GET / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n")
                    writer.flush()

                    val input = socket.getInputStream()
                    val buffer = ByteArray(10240) // До 10KB
                    var totalRead = 0
                    val allData = StringBuilder()

                    while (totalRead < 10240) {
                        val read = input.read(buffer, 0, minOf(10240 - totalRead, buffer.size))
                        if (read <= 0) break
                        totalRead += read
                        allData.append(String(buffer, 0, read))
                    }

                    val fullResponse = allData.toString()
                    val statusCode = parseStatusCode(fullResponse)

                    // Считаем размер body (после \r\n\r\n)
                    val bodyIdx = fullResponse.indexOf("\r\n\r\n")
                    val bodySize = if (bodyIdx >= 0) fullResponse.length - bodyIdx - 4 else totalRead

                    // Малый ответ + блокировочный паттерн
                    val lowerResponse = fullResponse.lowercase()
                    val hasBlockPattern = BLOCK_PATTERNS.any { lowerResponse.contains(it) }
                    val isSmallStub = bodySize < 500 && hasBlockPattern

                    ProbeResult(
                        type = "response_size",
                        warning = if (isSmallStub) "blocked" else "",
                        detail = "${bodySize}B (HTTP $statusCode)" + if (isSmallStub) " ISP stub" else "",
                        statusCode = statusCode,
                        isBlocking = isSmallStub,
                        responseSize = bodySize,
                        port = port
                    )
                } finally {
                    try { socket.close() } catch (_: Exception) {}
                }
            }
            result ?: ProbeResult(type = "response_size", detail = "Timeout", port = port)
        } catch (e: Exception) {
            ProbeResult(type = "response_size", detail = "Error: ${e.message?.take(50)}", port = port)
        }
    }

    // === Вспомогательные функции redirect chain ===

    /** HTTP GET с чтением Location header (raw TCP) */
    private fun fetchRedirectHttp(host: String, port: Int, timeoutMs: Int): Pair<Int, String?> {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            val writer = OutputStreamWriter(socket.getOutputStream())
            writer.write("GET / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n")
            writer.flush()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val headers = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break
                headers.append(line).append("\n")
            }
            val status = parseStatusCode(headers.toString())
            val location = Regex("""(?i)Location:\s*(.+)""").find(headers)?.groupValues?.get(1)?.trim()
            return Pair(status, location)
        } catch (e: Exception) {
            return Pair(-1, null)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /** HTTPS GET с чтением Location header */
    private fun fetchRedirectHttps(host: String, port: Int, timeoutMs: Int): Pair<Int, String?> {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

            val client = okhttp3.OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .build()

            val request = okhttp3.Request.Builder()
                .url("https://$host:$port/")
                .build()

            val response = client.newCall(request).execute()
            val code = response.code
            val location = response.header("Location")
            response.close()
            return Pair(code, location)
        } catch (e: Exception) {
            return Pair(-1, null)
        }
    }

    /** Парсит URL из Location header, возвращает (host, port, isHttps) */
    private fun parseRedirectUrl(url: String, defaultHost: String, defaultPort: Int, defaultHttps: Boolean): Triple<String, Int, Boolean> {
        return try {
            val isHttps = url.startsWith("https")
            val withoutProto = url.removePrefix("https://").removePrefix("http://")
            val hostPort = withoutProto.substringBefore("/").substringBefore("?")
            val host = hostPort.substringBefore(":")
            val port = if (hostPort.contains(":")) {
                hostPort.substringAfter(":").toIntOrNull() ?: if (isHttps) 443 else 80
            } else {
                if (isHttps) 443 else 80
            }
            Triple(host.ifEmpty { defaultHost }, port, isHttps)
        } catch (e: Exception) {
            Triple(defaultHost, defaultPort, defaultHttps)
        }
    }

    // === Вспомогательные функции ===

    /**
     * Парсит HTTP status code из строки вида "HTTP/1.1 200 OK"
     */
    private fun parseStatusCode(response: String): Int {
        val firstLine = response.lineSequence().firstOrNull() ?: return -1
        val match = Regex("""HTTP/\d\.\d\s+(\d{3})""").find(firstLine)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
    }

    /**
     * Извлекает CN из X.500 Distinguished Name
     */
    private fun extractCN(dn: String): String? {
        return Regex("""CN=([^,]+)""").find(dn)?.groupValues?.getOrNull(1)?.trim()
    }
}
