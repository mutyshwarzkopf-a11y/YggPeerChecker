package com.example.yggpeerchecker.utils

/**
 * Единый парсер URL для всех форматов хостов.
 * Заменяет 16 дублирующихся функций парсинга в проекте.
 */
object UrlParser {

    /**
     * Результат парсинга URL
     */
    data class ParsedUrl(
        val protocol: String,        // tcp, tls, quic, ws, wss, http, https, sni, vless, vmess
        val hostname: String,        // чистое имя без скобок и www
        val port: Int?,              // порт или null
        val isIpAddress: Boolean,    // true для IP, false для hostname
        val ipVersion: Int?,         // 4 или 6 для IP, null для hostname
        val originalUrl: String,     // оригинальная строка
        val groupingKey: String      // ключ группировки: hostname lowercase
    )

    // Ygg протоколы
    val YGG_PROTOCOLS = setOf("tcp", "tls", "quic", "ws", "wss")
    // SNI протоколы
    val SNI_PROTOCOLS = setOf("sni", "http", "https")
    // Прокси протоколы
    val PROXY_PROTOCOLS = setOf("vless", "vmess")
    // Все известные протоколы
    val ALL_PROTOCOLS = YGG_PROTOCOLS + SNI_PROTOCOLS + PROXY_PROTOCOLS

    // Regex паттерны
    private val IPV4_REGEX = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
    // IPv6 с поддержкой сокращённых форм (::) - например 2a12:5940:b1a0::2
    private val IPV6_REGEX = Regex("""^([0-9a-fA-F]{0,4}:){1,7}[0-9a-fA-F]{0,4}$|^([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$|^::([0-9a-fA-F]{1,4}:){0,6}[0-9a-fA-F]{1,4}$|^([0-9a-fA-F]{1,4}:){1,6}:$|^([0-9a-fA-F]{1,4}:)+:([0-9a-fA-F]{1,4}:)*[0-9a-fA-F]{1,4}$""")
    private val IPV6_SIMPLE_REGEX = Regex("""^[0-9a-fA-F:]+$""")

    // Основной regex для URL: protocol://[host]:port или protocol://host:port
    private val URL_REGEX = Regex(
        """^([a-z]+)://(?:\[([^\]]+)\]|([^/:?#\[\]]+))(?::(\d+))?([/?#].*)?$""",
        RegexOption.IGNORE_CASE
    )

    // Regex для vless: vless://UUID@host:port?params#name
    private val VLESS_REGEX = Regex(
        """^vless://[^@]+@(?:\[([^\]]+)\]|([^/:?#\[\]]+)):(\d+)""",
        RegexOption.IGNORE_CASE
    )

    // Regex для host:port без протокола
    private val HOST_PORT_REGEX = Regex("""^(?:\[([^\]]+)\]|([^/:?#\[\]]+)):(\d+)$""")

    /**
     * Главная функция парсинга URL
     */
    fun parse(url: String): ParsedUrl? {
        if (url.isBlank()) return null

        var normalized = url.trim()

        // Обработка http(s):// -> https://
        if (normalized.startsWith("http(s)://", ignoreCase = true)) {
            normalized = "https://" + normalized.substring(10)
        }

        // Нормализация http://host (без явного порта) -> просто host
        // Пользователи часто вводят http://host по привычке из браузера
        val httpPrefixRegex = Regex("""^https?://([^/:?#\[\]]+)$""", RegexOption.IGNORE_CASE)
        httpPrefixRegex.find(normalized)?.let { match ->
            normalized = match.groupValues[1]
        }

        // 1. Попытка парсинга vless://
        if (normalized.startsWith("vless://", ignoreCase = true)) {
            return parseVless(normalized)
        }

        // 2. Попытка парсинга vmess:// (base64)
        if (normalized.startsWith("vmess://", ignoreCase = true)) {
            return parseVmess(normalized)
        }

        // 3. Попытка парсинга стандартного URL с протоколом
        URL_REGEX.find(normalized)?.let { match ->
            val protocol = match.groupValues[1].lowercase()
            // IPv6 в скобках или обычный хост
            val rawHost = match.groupValues[2].ifEmpty { match.groupValues[3] }
            val port = match.groupValues[4].toIntOrNull()

            return buildParsedUrl(
                protocol = protocol,
                rawHost = rawHost,
                port = port ?: getDefaultPort(protocol),
                originalUrl = normalized
            )
        }

        // 4. Попытка парсинга [IPv6]:port или host:port
        HOST_PORT_REGEX.find(normalized)?.let { match ->
            val rawHost = match.groupValues[1].ifEmpty { match.groupValues[2] }
            val port = match.groupValues[3].toIntOrNull()

            return buildParsedUrl(
                protocol = "sni",
                rawHost = rawHost,
                port = port,
                originalUrl = normalized
            )
        }

        // 5. Просто hostname или IP без порта
        val cleanHost = normalized
            .substringBefore("/")
            .substringBefore("?")
            .substringBefore("#")
            .trim()

        if (cleanHost.isEmpty()) return null

        return buildParsedUrl(
            protocol = "sni",
            rawHost = cleanHost,
            port = getDefaultPort("sni"),  // Дефолтный порт 443 для SNI
            originalUrl = normalized
        )
    }

    /**
     * Парсинг vless:// URL
     */
    private fun parseVless(url: String): ParsedUrl? {
        VLESS_REGEX.find(url)?.let { match ->
            val rawHost = match.groupValues[1].ifEmpty { match.groupValues[2] }
            val port = match.groupValues[3].toIntOrNull() ?: 443

            return buildParsedUrl(
                protocol = "vless",
                rawHost = rawHost,
                port = port,
                originalUrl = url
            )
        }
        return null
    }

    /**
     * Парсинг vmess:// URL (base64 encoded JSON)
     */
    private fun parseVmess(url: String): ParsedUrl? {
        return try {
            val base64Part = url.removePrefix("vmess://").substringBefore("?").substringBefore("#")
            val decoded = android.util.Base64.decode(base64Part, android.util.Base64.DEFAULT)
            val json = org.json.JSONObject(String(decoded))
            val address = json.optString("add", "").ifEmpty { json.optString("host", "") }
            val port = json.optInt("port", 443)

            if (address.isEmpty()) return null

            buildParsedUrl(
                protocol = "vmess",
                rawHost = address,
                port = port,
                originalUrl = url
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Построение ParsedUrl с нормализацией hostname
     */
    private fun buildParsedUrl(
        protocol: String,
        rawHost: String,
        port: Int?,
        @Suppress("UNUSED_PARAMETER") originalUrl: String
    ): ParsedUrl {
        // Убираем квадратные скобки если есть
        var hostname = rawHost
        if (hostname.startsWith("[") && hostname.endsWith("]")) {
            hostname = hostname.substring(1, hostname.length - 1)
        }

        // Убираем trailing slash и пробелы
        hostname = hostname.trimEnd('/', ' ').lowercase()

        // Ключ группировки = hostname как есть (www. и m. НЕ удаляем - могут иметь разные IP)
        val groupingKey = hostname

        // Определяем тип адреса
        val isIp = isIpAddress(hostname)
        val ipVersion = if (isIp) getIpVersion(hostname) else null

        // Нормализованный URL без trailing slash и лишних параметров
        val normalizedUrl = buildNormalizedUrl(protocol, hostname, port, ipVersion)

        return ParsedUrl(
            protocol = protocol,
            hostname = hostname,
            port = port,
            isIpAddress = isIp,
            ipVersion = ipVersion,
            originalUrl = normalizedUrl,
            groupingKey = groupingKey
        )
    }

    /**
     * Построение нормализованного URL
     * НЕ добавляем порт если он совпадает с дефолтным для протокола
     * Для "sni" протокола не добавляем префикс "sni://" - это внутреннее обозначение
     */
    private fun buildNormalizedUrl(protocol: String, hostname: String, port: Int?, ipVersion: Int?): String {
        val host = if (ipVersion == 6) "[$hostname]" else hostname
        val defaultPort = getDefaultPort(protocol)

        // Для "sni" не добавляем протокол - это условное внутреннее обозначение
        if (protocol == "sni") {
            return if (port != null && port != defaultPort) {
                "$host:$port"
            } else {
                host
            }
        }

        // Добавляем порт ТОЛЬКО если он отличается от дефолтного
        return if (port != null && port != defaultPort) {
            "$protocol://$host:$port"
        } else {
            "$protocol://$host"
        }
    }

    /**
     * Извлечение только hostname из URL (для ping, tracert)
     */
    fun extractHostname(url: String): String? {
        return parse(url)?.hostname
    }

    /**
     * Проверка является ли строка IP адресом
     */
    fun isIpAddress(address: String): Boolean {
        val clean = address.trim().removePrefix("[").removeSuffix("]")
        return isIpv4(clean) || isIpv6(clean)
    }

    /**
     * Проверка IPv4
     */
    fun isIpv4(address: String): Boolean {
        val match = IPV4_REGEX.find(address) ?: return false
        return match.groupValues.drop(1).all {
            val num = it.toIntOrNull() ?: return false
            num in 0..255
        }
    }

    /**
     * Проверка IPv6
     */
    fun isIpv6(address: String): Boolean {
        val clean = address.removePrefix("[").removeSuffix("]")
        // Должен содержать минимум 2 двоеточия
        if (clean.count { it == ':' } < 2) return false
        // Не должен содержать буквы кроме a-f (исключает hostname)
        if (clean.any { it.isLetter() && it.lowercaseChar() !in 'a'..'f' }) return false
        return IPV6_REGEX.matches(clean) || IPV6_SIMPLE_REGEX.matches(clean)
    }

    /**
     * Получение версии IP (4 или 6)
     */
    fun getIpVersion(address: String): Int? {
        val clean = address.trim().removePrefix("[").removeSuffix("]")
        return when {
            isIpv4(clean) -> 4
            isIpv6(clean) -> 6
            else -> null
        }
    }

    /**
     * Построение URL с подменой хоста (для fallback IP)
     */
    fun replaceHost(originalUrl: String, newHost: String): String {
        val parsed = parse(originalUrl) ?: return originalUrl

        // Формируем хост с учётом IPv6
        val formattedHost = if (newHost.contains(":") && !newHost.startsWith("[")) {
            "[$newHost]"
        } else {
            newHost
        }

        // Заменяем хост в URL
        return try {
            // Regex корректно обрабатывает IPv6 в скобках: [2a12:5940:b1a0::2]:65535
            val regex = Regex("""(://)(?:\[[^\]]+\]|[^/:?#\[\]]+)(:\d+)?""")
            val port = if (parsed.port != null) ":${parsed.port}" else ""
            regex.replace(originalUrl) { _: MatchResult ->
                "://$formattedHost$port"
            }
        } catch (e: Exception) {
            // Fallback: просто возвращаем новый хост
            newHost
        }
    }

    /**
     * Нормализация URL (lowercase протокол и хост)
     */
    fun normalize(url: String): String {
        val parsed = parse(url) ?: return url.lowercase().trim()

        val port = if (parsed.port != null) ":${parsed.port}" else ""
        val host = if (parsed.ipVersion == 6) "[${parsed.hostname}]" else parsed.hostname

        return "${parsed.protocol}://$host$port"
    }

    /**
     * Получение дефолтного порта для протокола
     */
    fun getDefaultPort(protocol: String): Int? {
        return when (protocol.lowercase()) {
            "http" -> 80
            "https", "tls", "sni" -> 443
            else -> null
        }
    }

    /**
     * Проверка типа протокола
     */
    fun isYggProtocol(protocol: String): Boolean = protocol.lowercase() in YGG_PROTOCOLS
    fun isSniProtocol(protocol: String): Boolean = protocol.lowercase() in SNI_PROTOCOLS
    fun isProxyProtocol(protocol: String): Boolean = protocol.lowercase() in PROXY_PROTOCOLS
}
