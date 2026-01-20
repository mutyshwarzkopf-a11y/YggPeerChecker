package com.example.yggpeerchecker.data.repository

import android.content.Context
import com.example.yggpeerchecker.data.database.AppDatabase
import com.example.yggpeerchecker.data.database.CheckResult
import com.example.yggpeerchecker.data.database.DnsCache
import com.example.yggpeerchecker.data.database.Host
import com.example.yggpeerchecker.utils.PersistentLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class HostRepository(context: Context, private val logger: PersistentLogger) {
    private val database = AppDatabase.getDatabase(context)
    private val hostDao = database.hostDao()
    private val checkResultDao = database.checkResultDao()
    private val dnsCacheDao = database.dnsCacheDao()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        // Источники Ygg пиров
        const val SOURCE_NEILALEXANDER = "https://publicpeers.neilalexander.dev/publicnodes.json"
        const val SOURCE_YGGDRASIL_LINK = "https://peers.yggdrasil.link/publicnodes.json"
        // Источники whitelist
        const val SOURCE_RU_WHITELIST = "https://github.com/hxehex/russia-mobile-internet-whitelist/raw/refs/heads/main/whitelist.txt"
        const val SOURCE_MINI_WHITE = "https://raw.githubusercontent.com/mutyshwarzkopf-a11y/YggPeerChecker/main/miniwhite.txt"
        const val SOURCE_MINI_BLACK = "https://raw.githubusercontent.com/mutyshwarzkopf-a11y/YggPeerChecker/main/miniblack.txt"
        // Источники vless
        const val SOURCE_VLESS_SUB = "https://raw.githubusercontent.com/yebekhe/TVC/main/subscriptions/xray/normal/vless"
    }

    // Flow для наблюдения за списком хостов
    fun getAllHostsFlow(): Flow<List<Host>> = hostDao.getAllHosts()
    fun getHostsCountFlow(): Flow<Int> = hostDao.getHostsCount()
    fun getYggHostsCountFlow(): Flow<Int> = hostDao.getYggHostsCount()
    fun getSniHostsCountFlow(): Flow<Int> = hostDao.getSniHostsCount()
    fun getResolvedCountFlow(): Flow<Int> = hostDao.getResolvedHostsCount()

    // Получение хостов
    suspend fun getAllHosts(): List<Host> = hostDao.getAllHostsList()
    suspend fun getYggHosts(): List<Host> = hostDao.getYggHostsList()
    suspend fun getSniHosts(): List<Host> = hostDao.getSniHostsList()

    // Очистка
    suspend fun clearAll() {
        hostDao.deleteAll()
        checkResultDao.deleteAll()
        logger.appendLogSync("INFO", "All hosts and results cleared")
    }

    suspend fun clearBySource(source: String) {
        hostDao.deleteBySource(source)
        logger.appendLogSync("INFO", "Hosts from $source cleared")
    }

    // Загрузка Ygg пиров из JSON
    suspend fun loadYggPeers(sourceUrl: String, onProgress: (String) -> Unit): Result<Int> = withContext(Dispatchers.IO) {
        try {
            onProgress("Connecting to $sourceUrl...")
            logger.appendLogSync("INFO", "Loading Ygg peers from: $sourceUrl")

            val request = Request.Builder()
                .url(sourceUrl)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val error = "HTTP ${response.code}: ${response.message}"
                logger.appendLogSync("ERROR", "Failed to load peers: $error")
                return@withContext Result.failure(Exception(error))
            }

            val body = response.body?.string() ?: ""
            onProgress("Parsing JSON...")
            logger.appendLogSync("DEBUG", "Received ${body.length} bytes")

            val json = JSONObject(body)
            val hosts = mutableListOf<Host>()
            val now = System.currentTimeMillis()

            // Парсим JSON формат publicnodes.json
            val regions = json.keys()
            while (regions.hasNext()) {
                val regionKey = regions.next()
                // Убираем .md суффикс из имени региона (если есть в JSON)
                val region = regionKey.removeSuffix(".md")
                val regionObj = json.getJSONObject(regionKey)
                val peers = regionObj.keys()

                while (peers.hasNext()) {
                    val peerUrl = peers.next()
                    try {
                        val host = parseYggPeerUrl(peerUrl, sourceUrl, now, region)
                        if (host != null) {
                            hosts.add(host)
                        }
                    } catch (e: Exception) {
                        logger.appendLogSync("WARN", "Failed to parse peer: $peerUrl - ${e.message}")
                    }
                }
            }

            onProgress("Saving ${hosts.size} peers...")
            hostDao.insertAll(hosts)
            logger.appendLogSync("INFO", "Loaded ${hosts.size} peers from $sourceUrl")

            Result.success(hosts.size)
        } catch (e: Exception) {
            logger.appendLogSync("ERROR", "Failed to load Ygg peers: ${e.message}")
            Result.failure(e)
        }
    }

    // Парсинг URL Ygg пира
    private fun parseYggPeerUrl(peerUrl: String, source: String, timestamp: Long, region: String? = null): Host? {
        // Формат: protocol://[ip]:port или protocol://host:port
        val regex = Regex("^(tcp|tls|quic|ws|wss)://\\[?([^\\]/:]+)\\]?:(\\d+)(.*)?$")
        val match = regex.find(peerUrl) ?: return null

        val protocol = match.groupValues[1]
        val address = match.groupValues[2]
        val port = match.groupValues[3].toIntOrNull() ?: return null

        val id = generateHostId(peerUrl)

        return Host(
            id = id,
            source = source,
            dateAdded = timestamp,
            hostType = protocol,
            hostString = peerUrl,
            address = address,
            port = port,
            region = region,  // Сохраняем регион из JSON
            dnsIp1 = null  // Не заполняем dns_ip для IP адресов, они не нуждаются в резолвинге
        )
    }

    // Загрузка whitelist
    suspend fun loadWhitelist(onProgress: (String) -> Unit): Result<Int> = withContext(Dispatchers.IO) {
        try {
            onProgress("Connecting to whitelist...")
            logger.appendLogSync("INFO", "Loading whitelist from: $SOURCE_RU_WHITELIST")

            val request = Request.Builder()
                .url(SOURCE_RU_WHITELIST)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val error = "HTTP ${response.code}: ${response.message}"
                logger.appendLogSync("ERROR", "Failed to load whitelist: $error")
                return@withContext Result.failure(Exception(error))
            }

            val body = response.body?.string() ?: ""
            onProgress("Parsing whitelist...")

            val hosts = parseHostLines(body, SOURCE_RU_WHITELIST)
            onProgress("Saving ${hosts.size} hosts...")
            hostDao.insertAll(hosts)
            logger.appendLogSync("INFO", "Loaded ${hosts.size} hosts from whitelist")

            Result.success(hosts.size)
        } catch (e: Exception) {
            logger.appendLogSync("ERROR", "Failed to load whitelist: ${e.message}")
            Result.failure(e)
        }
    }

    // Загрузка из текста (буфер/файл)
    suspend fun loadFromText(text: String, source: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            logger.appendLogSync("INFO", "Loading hosts from text, source: $source")
            val hosts = parseHostLines(text, source)
            hostDao.insertAll(hosts)
            logger.appendLogSync("INFO", "Loaded ${hosts.size} hosts from $source")
            Result.success(hosts.size)
        } catch (e: Exception) {
            logger.appendLogSync("ERROR", "Failed to load from text: ${e.message}")
            Result.failure(e)
        }
    }

    // Парсинг строк с хостами
    private fun parseHostLines(text: String, source: String): List<Host> {
        val hosts = mutableListOf<Host>()
        val now = System.currentTimeMillis()

        text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .forEach { line ->
                try {
                    val host = parseHostLine(line, source, now)
                    if (host != null) {
                        hosts.add(host)
                    }
                } catch (e: Exception) {
                    logger.appendLogSync("WARN", "Failed to parse line: $line - ${e.message}")
                }
            }

        return hosts
    }

    // Парсинг одной строки хоста
    private fun parseHostLine(line: String, source: String, timestamp: Long): Host? {
        val id = generateHostId(line)

        // Проверка Ygg формата (tcp://, tls://, etc)
        val yggRegex = Regex("^(tcp|tls|quic|ws|wss)://.*")
        if (yggRegex.matches(line)) {
            return parseYggPeerUrl(line, source, timestamp)
        }

        // Проверка vless:// формата (vless://UUID@host:port?params#name)
        val vlessRegex = Regex("^vless://[^@]+@([^:/?#]+):(\\d+)")
        val vlessMatch = vlessRegex.find(line)
        if (vlessMatch != null) {
            val address = vlessMatch.groupValues[1]
            val port = vlessMatch.groupValues[2].toIntOrNull() ?: 443
            return Host(
                id = id,
                source = source,
                dateAdded = timestamp,
                hostType = "vless",
                hostString = line,
                address = address,
                port = port,
                dnsIp1 = null
            )
        }

        // Проверка vmess:// (base64 encoded JSON)
        if (line.startsWith("vmess://")) {
            return parseVmessUrl(line, source, timestamp, id)
        }

        // Проверка http(s):// формата
        val httpRegex = Regex("^(https?)://([^/:]+)(?::(\\d+))?(/.*)?$")
        val httpMatch = httpRegex.find(line)
        if (httpMatch != null) {
            val protocol = httpMatch.groupValues[1]
            val address = httpMatch.groupValues[2]
            val port = httpMatch.groupValues[3].toIntOrNull()
                ?: if (protocol == "https") 443 else 80

            return Host(
                id = id,
                source = source,
                dateAdded = timestamp,
                hostType = protocol,
                hostString = line,
                address = address,
                port = port,
                dnsIp1 = null  // Не заполняем - будет заполнено через Fill DNS
            )
        }

        // Проверка host:port формата
        val hostPortRegex = Regex("^([^/:]+):(\\d+)$")
        val hostPortMatch = hostPortRegex.find(line)
        if (hostPortMatch != null) {
            val address = hostPortMatch.groupValues[1]
            val port = hostPortMatch.groupValues[2].toIntOrNull() ?: return null

            return Host(
                id = id,
                source = source,
                dateAdded = timestamp,
                hostType = "sni",
                hostString = line,
                address = address,
                port = port,
                dnsIp1 = null  // Не заполняем - будет заполнено через Fill DNS
            )
        }

        // Просто адрес (IP или hostname)
        val address = line.trim()
        if (address.isEmpty()) return null

        return Host(
            id = id,
            source = source,
            dateAdded = timestamp,
            hostType = "sni",
            hostString = address,
            address = address,
            port = null,
            dnsIp1 = null  // Не заполняем - будет заполнено через Fill DNS (для IP не нужен резолвинг)
        )
    }

    // DNS резолвинг для всех хостов
    // Возвращает Pair(resolved, skipped) где skipped = уже имеющие DNS или чистые IP
    suspend fun fillDnsIps(onProgress: (Int, Int) -> Unit): Result<Pair<Int, Int>> = withContext(Dispatchers.IO) {
        try {
            val hosts = hostDao.getAllHostsList()
            // Хосты для резолва: не IP адрес И ещё нет DNS записи
            val hostsToResolve = hosts.filter { !isIpAddress(it.address) && it.dnsIp1 == null }
            val skipped = hosts.size - hostsToResolve.size
            val total = hostsToResolve.size
            var resolved = 0
            val now = System.currentTimeMillis()

            logger.appendLogSync("INFO", "Starting DNS resolution for $total hosts (skipped: $skipped)")

            hostsToResolve.forEachIndexed { index, host ->
                onProgress(index + 1, total)

                try {
                    val ips = resolveDns(host.address)
                    if (ips.isNotEmpty()) {
                        hostDao.updateDnsIps(
                            hostId = host.id,
                            ip1 = ips.getOrNull(0),
                            ip2 = ips.getOrNull(1),
                            ip3 = ips.getOrNull(2),
                            timestamp = now
                        )

                        // Сохраняем в DNS кэш
                        dnsCacheDao.insert(
                            DnsCache(
                                hostname = host.address,
                                ip1 = ips.getOrNull(0),
                                ip2 = ips.getOrNull(1),
                                ip3 = ips.getOrNull(2),
                                cachedAt = now
                            )
                        )

                        resolved++
                        logger.appendLogSync("DEBUG", "Resolved ${host.address}: ${ips.joinToString()}")
                    }
                } catch (e: Exception) {
                    logger.appendLogSync("WARN", "DNS resolution failed for ${host.address}: ${e.message}")
                }
            }

            logger.appendLogSync("INFO", "DNS resolution completed: $resolved/$total resolved, $skipped skipped")
            Result.success(Pair(resolved, skipped))
        } catch (e: Exception) {
            logger.appendLogSync("ERROR", "DNS resolution failed: ${e.message}")
            Result.failure(e)
        }
    }

    // DNS резолв одного хоста (lowercase + IDN поддержка)
    private fun resolveDns(hostname: String): List<String> {
        return try {
            // Приводим к lowercase и конвертируем IDN (кириллица и т.д.) в Punycode
            val normalizedHost = try {
                java.net.IDN.toASCII(hostname.lowercase().trim())
            } catch (e: Exception) {
                hostname.lowercase().trim()
            }
            
            InetAddress.getAllByName(normalizedHost)
                .map { it.hostAddress ?: "" }
                .filter { it.isNotEmpty() }
                .take(3)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Сохранение результата проверки
    suspend fun saveCheckResult(result: CheckResult) {
        checkResultDao.insert(result)
    }

    suspend fun saveCheckResults(results: List<CheckResult>) {
        checkResultDao.insertAll(results)
    }

    // Получение результатов
    fun getCheckResults(): Flow<List<CheckResult>> = checkResultDao.getAllResults()

    suspend fun getLatestResult(hostId: String): CheckResult? {
        return checkResultDao.getLatestResultForHost(hostId)
    }

    // Парсинг vmess:// URL (base64 encoded JSON)
    private fun parseVmessUrl(line: String, source: String, timestamp: Long, id: String): Host? {
        return try {
            val base64Part = line.removePrefix("vmess://")
            val decoded = android.util.Base64.decode(base64Part, android.util.Base64.DEFAULT)
            val json = JSONObject(String(decoded))
            val address = json.optString("add", "").ifEmpty { json.optString("host", "") }
            val port = json.optInt("port", 443)
            if (address.isEmpty()) return null
            Host(
                id = id,
                source = source,
                dateAdded = timestamp,
                hostType = "vmess",
                hostString = line,
                address = address,
                port = port,
                dnsIp1 = null
            )
        } catch (e: Exception) {
            logger.appendLogSync("WARN", "Failed to parse vmess: ${e.message}")
            null
        }
    }

    // Загрузка vless/vmess списка
    suspend fun loadVlessList(sourceUrl: String, onProgress: (String) -> Unit): Result<Int> = withContext(Dispatchers.IO) {
        try {
            onProgress("Loading vless list...")
            logger.appendLogSync("INFO", "Loading vless from: $sourceUrl")

            val request = Request.Builder().url(sourceUrl).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val error = "HTTP ${response.code}: ${response.message}"
                logger.appendLogSync("ERROR", "Failed to load vless: $error")
                return@withContext Result.failure(Exception(error))
            }

            val body = response.body?.string() ?: ""
            val hosts = parseHostLines(body, sourceUrl)
            hostDao.insertAll(hosts)
            logger.appendLogSync("INFO", "Loaded ${hosts.size} hosts from vless list")
            Result.success(hosts.size)
        } catch (e: Exception) {
            logger.appendLogSync("ERROR", "Failed to load vless: ${e.message}")
            Result.failure(e)
        }
    }

    // Загрузка mini списков (google/hosts format)
    suspend fun loadMiniList(sourceUrl: String, listName: String, onProgress: (String) -> Unit): Result<Int> = withContext(Dispatchers.IO) {
        try {
            onProgress("Loading $listName...")
            logger.appendLogSync("INFO", "Loading mini list from: $sourceUrl")

            val request = Request.Builder().url(sourceUrl).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val error = "HTTP ${response.code}: ${response.message}"
                logger.appendLogSync("ERROR", "Failed to load $listName: $error")
                return@withContext Result.failure(Exception(error))
            }

            val body = response.body?.string() ?: ""
            val hosts = parseMiniHostLines(body, sourceUrl)
            hostDao.insertAll(hosts)
            logger.appendLogSync("INFO", "Loaded ${hosts.size} hosts from $listName")
            Result.success(hosts.size)
        } catch (e: Exception) {
            logger.appendLogSync("ERROR", "Failed to load $listName: ${e.message}")
            Result.failure(e)
        }
    }

    // Парсинг hosts-формата (0.0.0.0 domain или просто domain)
    private fun parseMiniHostLines(text: String, source: String): List<Host> {
        val hosts = mutableListOf<Host>()
        val now = System.currentTimeMillis()

        text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .forEach { line ->
                try {
                    // Формат: "0.0.0.0 domain" или "127.0.0.1 domain" или просто "domain"
                    val parts = line.split(Regex("\\s+"))
                    val domain = when {
                        parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1") -> parts[1]
                        parts.size == 1 && !parts[0].contains(".") -> return@forEach // skip
                        parts.size == 1 -> parts[0]
                        else -> return@forEach
                    }
                    // Пропускаем localhost и подобные
                    if (domain == "localhost" || domain.endsWith(".local")) return@forEach

                    val id = generateHostId(domain)
                    hosts.add(Host(
                        id = id,
                        source = source,
                        dateAdded = now,
                        hostType = "sni",
                        hostString = domain,
                        address = domain,
                        port = 443,
                        dnsIp1 = null
                    ))
                } catch (e: Exception) {
                    // Пропускаем невалидные строки
                }
            }

        return hosts
    }

    // Генерация ID хоста
    private fun generateHostId(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // Проверка IP адреса
    private fun isIpAddress(address: String): Boolean {
        // IPv4
        val ipv4Regex = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")
        if (ipv4Regex.matches(address)) {
            return address.split(".").all { it.toIntOrNull() in 0..255 }
        }

        // IPv6
        val ipv6Regex = Regex("^([0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}$")
        return ipv6Regex.matches(address)
    }

    // Очистка DNS резолвов (не чистит чистые IP адреса)
    suspend fun clearDns() = withContext(Dispatchers.IO) {
        try {
            hostDao.clearResolvedDns()
            dnsCacheDao.deleteAll()
            logger.appendLogSync("INFO", "DNS data cleared (IP addresses preserved)")
        } catch (e: Exception) {
            logger.appendLogSync("ERROR", "Failed to clear DNS: ${e.message}")
            throw e
        }
    }


    // Очистка DNS по списку ID (для фильтра)
    suspend fun clearDnsByIds(ids: List<String>) = withContext(Dispatchers.IO) {
        try {
            if (ids.isEmpty()) return@withContext
            hostDao.clearDnsByIds(ids)
            logger.appendLogSync("INFO", "DNS cleared for ${ids.size} hosts")
        } catch (e: Exception) {
            logger.appendLogSync("ERROR", "Failed to clear DNS by IDs: ${e.message}")
            throw e
        }
    }

    // Удаление хостов по списку ID
    suspend fun deleteByIds(ids: List<String>) = withContext(Dispatchers.IO) {
        try {
            hostDao.deleteByIds(ids)
            logger.appendLogSync("INFO", "Deleted ${ids.size} hosts")
        } catch (e: Exception) {
            logger.appendLogSync("ERROR", "Failed to delete hosts: ${e.message}")
            throw e
        }
    }


    // GeoIP резолвинг для хостов
    // Возвращает Pair(resolved, skipped) где skipped = уже имеющие geoIp или нет IP
    suspend fun fillGeoIp(
        hostIds: List<String>? = null,
        onProgress: (Int, Int) -> Unit
    ): Result<Pair<Int, Int>> = withContext(Dispatchers.IO) {
        try {
            val allHosts = if (hostIds != null) {
                hostIds.mapNotNull { hostDao.getHostById(it) }
            } else {
                hostDao.getAllHostsList()
            }

            // Хосты для резолва: есть IP (dnsIp1 или address если IP) И нет geoIp
            val hostsToResolve = allHosts.filter { host ->
                host.geoIp == null && (host.dnsIp1 != null || isIpAddress(host.address))
            }
            val skipped = allHosts.size - hostsToResolve.size
            val total = hostsToResolve.size
            var resolved = 0

            logger.appendLogSync("INFO", "Starting GeoIP resolution for $total hosts (skipped: $skipped)")

            hostsToResolve.forEachIndexed { index, host ->
                onProgress(index + 1, total)

                // Используем dnsIp1 если есть, иначе address
                val ip = host.dnsIp1 ?: host.address

                try {
                    val geoInfo = resolveGeoIp(ip)
                    if (geoInfo != null) {
                        hostDao.updateGeoIp(host.id, geoInfo)
                        resolved++
                        logger.appendLogSync("DEBUG", "GeoIP for $ip: $geoInfo")
                    }
                } catch (e: Exception) {
                    logger.appendLogSync("WARN", "GeoIP failed for $ip: ${e.message}")
                }

                // Небольшая задержка чтобы не перегружать API (ip-api.com rate limit)
                kotlinx.coroutines.delay(100)
            }

            logger.appendLogSync("INFO", "GeoIP completed: $resolved/$total resolved, $skipped skipped")
            Result.success(Pair(resolved, skipped))
        } catch (e: Exception) {
            logger.appendLogSync("ERROR", "GeoIP resolution failed: ${e.message}")
            Result.failure(e)
        }
    }

    // Резолвинг GeoIP через ip-api.com
    private suspend fun resolveGeoIp(ip: String): String? = withContext(Dispatchers.IO) {
        try {
            logger.appendLogSync("DEBUG", "GeoIP request for: $ip")
            val request = okhttp3.Request.Builder()
                .url("http://ip-api.com/json/$ip?fields=countryCode,city,status,message")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string()
                logger.appendLogSync("DEBUG", "GeoIP response for $ip: code=${response.code}, body=$body")

                if (response.isSuccessful && body != null) {
                    // Проверяем статус API
                    if (body.contains("\"status\":\"fail\"")) {
                        val msgMatch = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(body)
                        logger.appendLogSync("WARN", "GeoIP API fail for $ip: ${msgMatch?.groupValues?.get(1)}")
                        return@withContext null
                    }

                    // Парсим JSON: {"countryCode":"US","city":"Washington"}
                    val ccMatch = Regex("\"countryCode\"\\s*:\\s*\"([^\"]+)\"").find(body)
                    val cityMatch = Regex("\"city\"\\s*:\\s*\"([^\"]+)\"").find(body)

                    val cc = ccMatch?.groupValues?.get(1)
                    val city = cityMatch?.groupValues?.get(1) ?: ""

                    if (cc == null) {
                        logger.appendLogSync("WARN", "GeoIP no countryCode for $ip")
                        return@withContext null
                    }

                    val result = if (city.isNotEmpty()) "$cc:$city" else cc
                    logger.appendLogSync("DEBUG", "GeoIP parsed for $ip: $result")
                    return@withContext result
                } else {
                    logger.appendLogSync("WARN", "GeoIP HTTP error for $ip: ${response.code}")
                }
            }
            null
        } catch (e: Exception) {
            logger.appendLogSync("ERROR", "GeoIP exception for $ip: ${e.message}")
            null
        }
    }

    // Очистка GeoIP по списку ID
    suspend fun clearGeoIpByIds(ids: List<String>) = withContext(Dispatchers.IO) {
        try {
            if (ids.isEmpty()) return@withContext
            hostDao.clearGeoIpByIds(ids)
            logger.appendLogSync("INFO", "GeoIP cleared for ${ids.size} hosts")
        } catch (e: Exception) {
            logger.appendLogSync("ERROR", "Failed to clear GeoIP by IDs: ${e.message}")
            throw e
        }
    }
}
