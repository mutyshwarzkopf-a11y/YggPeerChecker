package com.example.yggpeerchecker.data.repository

import android.content.Context
import com.example.yggpeerchecker.data.database.AppDatabase
import com.example.yggpeerchecker.data.database.CheckResult
import com.example.yggpeerchecker.data.database.DnsCache
import com.example.yggpeerchecker.data.database.Host
import com.example.yggpeerchecker.utils.PersistentLogger
import com.example.yggpeerchecker.utils.UrlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class HostRepository(private val context: Context, private val logger: PersistentLogger) {
    private val database = AppDatabase.getDatabase(context)
    private val hostDao = database.hostDao()
    private val checkResultDao = database.checkResultDao()
    private val dnsCacheDao = database.dnsCacheDao()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Получение concurrent_streams из SharedPreferences (config_prefs)
    private fun getConcurrentStreams(): Int {
        val prefs = context.getSharedPreferences("config_prefs", Context.MODE_PRIVATE)
        return prefs.getInt("concurrent_streams", 10)
    }

    companion object {
        // Источники Ygg пиров
        const val SOURCE_NEILALEXANDER = "https://publicpeers.neilalexander.dev/publicnodes.json"
        const val SOURCE_YGGDRASIL_LINK = "https://peers.yggdrasil.link/publicnodes.json"
        // Источники whitelist
        const val SOURCE_RU_WHITELIST = "https://github.com/hxehex/russia-mobile-internet-whitelist/raw/refs/heads/main/whitelist.txt"
        const val SOURCE_MINI_WHITE = "https://github.com/mutyshwarzkopf-a11y/YggPeerChecker/raw/refs/heads/main/lists/miniwhite.txt"
        const val SOURCE_MINI_BLACK = "https://github.com/mutyshwarzkopf-a11y/YggPeerChecker/raw/refs/heads/main/lists/miniblack.txt"
        // Источники vless
        const val SOURCE_VLESS_SUB = "https://raw.githubusercontent.com/Zieng2/wl/refs/heads/main/vless_lite.txt"
    }

    // Flow для наблюдения за списком хостов
    fun getAllHostsFlow(): Flow<List<Host>> = hostDao.getAllHosts()
    fun getHostsCountFlow(): Flow<Int> = hostDao.getHostsCount()
    fun getYggHostsCountFlow(): Flow<Int> = hostDao.getYggHostsCount()
    fun getSniHostsCountFlow(): Flow<Int> = hostDao.getSniHostsCount()
    fun getVlessHostsCountFlow(): Flow<Int> = hostDao.getVlessHostsCount()
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
    // Возвращает Pair(actuallyAdded, skipped)
    suspend fun loadYggPeers(sourceUrl: String, onProgress: (String) -> Unit): Result<Pair<Int, Int>> = withContext(Dispatchers.IO) {
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
            val countBefore = hostDao.getHostCount()
            hostDao.insertAll(hosts)
            val countAfter = hostDao.getHostCount()
            val actuallyAdded = countAfter - countBefore
            val skipped = hosts.size - actuallyAdded
            logger.appendLogSync("INFO", "Loaded ${hosts.size} peers from $sourceUrl (new: $actuallyAdded, skip: $skipped)")

            Result.success(Pair(actuallyAdded, skipped))
        } catch (e: Exception) {
            logger.appendLogSync("ERROR", "Failed to load Ygg peers: ${e.message}")
            Result.failure(e)
        }
    }

    // Парсинг URL Ygg пира (использует UrlParser)
    private fun parseYggPeerUrl(peerUrl: String, source: String, timestamp: Long, region: String? = null): Host? {
        val parsed = UrlParser.parse(peerUrl) ?: return null

        // Проверяем что это Ygg протокол
        if (!UrlParser.isYggProtocol(parsed.protocol)) return null

        val id = generateHostId(peerUrl.lowercase())

        return Host(
            id = id,
            source = source,
            dateAdded = timestamp,
            hostType = parsed.protocol,
            hostString = peerUrl.lowercase(),
            address = parsed.hostname,
            port = parsed.port,
            region = region,
            dnsIp1 = null
        )
    }

    // Загрузка whitelist
    // Возвращает Pair(actuallyAdded, skipped)
    suspend fun loadWhitelist(onProgress: (String) -> Unit): Result<Pair<Int, Int>> = withContext(Dispatchers.IO) {
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
            val countBefore = hostDao.getHostCount()
            hostDao.insertAll(hosts)
            val countAfter = hostDao.getHostCount()
            val actuallyAdded = countAfter - countBefore
            val skipped = hosts.size - actuallyAdded
            logger.appendLogSync("INFO", "Loaded ${hosts.size} hosts from whitelist (new: $actuallyAdded, skip: $skipped)")

            Result.success(Pair(actuallyAdded, skipped))
        } catch (e: Exception) {
            logger.appendLogSync("ERROR", "Failed to load whitelist: ${e.message}")
            Result.failure(e)
        }
    }

    // Загрузка из текста (буфер/файл)
    // Возвращает Pair(actuallyAdded, skipped)
    suspend fun loadFromText(text: String, source: String): Result<Pair<Int, Int>> = withContext(Dispatchers.IO) {
        try {
            logger.appendLogSync("INFO", "Loading hosts from text, source: $source")
            val hosts = parseHostLines(text, source)
            val countBefore = hostDao.getHostCount()
            hostDao.insertAll(hosts)
            val countAfter = hostDao.getHostCount()
            val actuallyAdded = countAfter - countBefore
            val skipped = hosts.size - actuallyAdded
            logger.appendLogSync("INFO", "Loaded ${hosts.size} hosts from $source (new: $actuallyAdded, skip: $skipped)")
            Result.success(Pair(actuallyAdded, skipped))
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

    // Парсинг одной строки хоста (использует UrlParser)
    private fun parseHostLine(line: String, source: String, timestamp: Long): Host? {
        if (line.isBlank()) return null

        // Для vless/vmess сохраняем оригинальную строку (UUID case-sensitive)
        val isProxy = line.lowercase().startsWith("vless://") || line.lowercase().startsWith("vmess://")
        val originalLine = if (isProxy) line else line.lowercase().trim()

        val parsed = UrlParser.parse(line) ?: run {
            logger.appendLogSync("WARN", "Failed to parse host line: $line")
            return null
        }

        val id = generateHostId(parsed.originalUrl.lowercase())

        // Определяем hostType
        val hostType = when {
            UrlParser.isYggProtocol(parsed.protocol) -> parsed.protocol
            UrlParser.isProxyProtocol(parsed.protocol) -> parsed.protocol
            UrlParser.isSniProtocol(parsed.protocol) -> parsed.protocol
            else -> "sni"
        }

        return Host(
            id = id,
            source = source,
            dateAdded = timestamp,
            hostType = hostType,
            hostString = if (isProxy) originalLine else parsed.originalUrl.lowercase(),
            address = parsed.hostname,
            port = parsed.port,
            dnsIp1 = null
        )
    }

    // DNS резолвинг для всех хостов (параллельный с Semaphore)
    // Возвращает Pair(resolved, skipped) где skipped = уже имеющие DNS или чистые IP
    suspend fun fillDnsIps(onProgress: (Int, Int) -> Unit): Result<Pair<Int, Int>> = withContext(Dispatchers.IO) {
        try {
            val hosts = hostDao.getAllHostsList()
            // Хосты для резолва: не IP адрес И ещё нет DNS записи
            val hostsToResolve = hosts.filter { !isIpAddress(it.address) && it.dnsIp1 == null }
            val skipped = hosts.size - hostsToResolve.size
            val total = hostsToResolve.size
            val now = System.currentTimeMillis()

            val concurrentStreams = getConcurrentStreams()
            logger.appendLogSync("INFO", "Starting DNS resolution for $total hosts (skipped: $skipped, streams: $concurrentStreams)")

            val resolvedCount = java.util.concurrent.atomic.AtomicInteger(0)
            val progressCount = java.util.concurrent.atomic.AtomicInteger(0)
            val semaphore = Semaphore(concurrentStreams)

            coroutineScope {
                hostsToResolve.map { host ->
                    async {
                        semaphore.withPermit {
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

                                    resolvedCount.incrementAndGet()
                                    logger.appendLogSync("DEBUG", "Resolved ${host.address}: ${ips.joinToString()}")
                                } else {
                                    // DNS резолв не вернул результатов - логируем
                                    logger.appendLogSync("WARN", "DNS empty result for ${host.address}")
                                }
                            } catch (e: Exception) {
                                logger.appendLogSync("WARN", "DNS resolution failed for ${host.address}: ${e.message}")
                            }
                            onProgress(progressCount.incrementAndGet(), total)
                        }
                    }
                }.awaitAll()
            }

            val resolved = resolvedCount.get()
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

    // Загрузка vless/vmess списка
    // Возвращает Pair(actuallyAdded, skipped)
    suspend fun loadVlessList(sourceUrl: String, onProgress: (String) -> Unit): Result<Pair<Int, Int>> = withContext(Dispatchers.IO) {
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
            val countBefore = hostDao.getHostCount()
            hostDao.insertAll(hosts)
            val countAfter = hostDao.getHostCount()
            val actuallyAdded = countAfter - countBefore
            val skipped = hosts.size - actuallyAdded
            logger.appendLogSync("INFO", "Loaded ${hosts.size} hosts from vless list (new: $actuallyAdded, skip: $skipped)")
            Result.success(Pair(actuallyAdded, skipped))
        } catch (e: Exception) {
            logger.appendLogSync("ERROR", "Failed to load vless: ${e.message}")
            Result.failure(e)
        }
    }

    // Загрузка mini списков (google/hosts format)
    // Возвращает Pair(actuallyAdded, skipped)
    suspend fun loadMiniList(sourceUrl: String, listName: String, onProgress: (String) -> Unit): Result<Pair<Int, Int>> = withContext(Dispatchers.IO) {
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
            val countBefore = hostDao.getHostCount()
            hostDao.insertAll(hosts)
            val countAfter = hostDao.getHostCount()
            val actuallyAdded = countAfter - countBefore
            val skipped = hosts.size - actuallyAdded
            logger.appendLogSync("INFO", "Loaded ${hosts.size} hosts from $listName (new: $actuallyAdded, skip: $skipped)")
            Result.success(Pair(actuallyAdded, skipped))
        } catch (e: Exception) {
            logger.appendLogSync("ERROR", "Failed to load $listName: ${e.message}")
            Result.failure(e)
        }
    }

    // Парсинг hosts-формата (0.0.0.0 domain или просто domain)
    // Также понимает URL с протоколом (http://..., vless://... и т.д.)
    private fun parseMiniHostLines(text: String, source: String): List<Host> {
        val hosts = mutableListOf<Host>()
        val now = System.currentTimeMillis()

        text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .forEach { line ->
                try {
                    // Если строка содержит протокол (xxx://) - делегируем в универсальный парсер
                    if (line.contains("://")) {
                        val host = parseHostLine(line, source, now)
                        if (host != null) {
                            hosts.add(host)
                        }
                        return@forEach
                    }

                    // Формат hosts: "0.0.0.0 domain" или "127.0.0.1 domain" или просто "domain"
                    val parts = line.split(Regex("\\s+"))
                    val domain = when {
                        parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1") -> parts[1]
                        parts.size == 1 && !parts[0].contains(".") -> return@forEach // skip
                        parts.size == 1 -> parts[0]
                        else -> return@forEach
                    }.lowercase()  // НЕ удаляем www./m. - могут иметь разные IP

                    // Пропускаем localhost и подобные
                    if (domain == "localhost" || domain.endsWith(".local")) return@forEach

                    // Используем UrlParser для корректного парсинга (включая IP адреса)
                    val parsed = UrlParser.parse(domain)
                    if (parsed != null) {
                        val id = generateHostId(parsed.originalUrl.lowercase())
                        hosts.add(Host(
                            id = id,
                            source = source,
                            dateAdded = now,
                            hostType = parsed.protocol,
                            hostString = parsed.originalUrl.lowercase(),
                            address = parsed.hostname,
                            port = parsed.port,
                            dnsIp1 = null
                        ))
                    }
                } catch (e: Exception) {
                    logger.appendLogSync("WARN", "Failed to parse mini line: $line - ${e.message}")
                }
            }

        return hosts
    }

    // Генерация ID хоста
    private fun generateHostId(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // Проверка IP адреса (делегируем UrlParser)
    private fun isIpAddress(address: String): Boolean = UrlParser.isIpAddress(address)

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


    // GeoIP резолвинг для хостов (параллельный с Semaphore, ограничен для API rate limit)
    // Возвращает Pair(resolved, skipped) где skipped = уже имеющие geoIp
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

            // Хосты для резолва: нет geoIp (или пустая строка)
            val hostsToResolve = allHosts.filter { host ->
                host.geoIp.isNullOrEmpty()
            }
            val skipped = allHosts.size - hostsToResolve.size
            val total = hostsToResolve.size

            // GeoIP API имеет rate limit 45 запросов/мин = 1 запрос каждые 1.3 сек
            // Ограничиваем до 1 потока чтобы контролировать rate
            val concurrentStreams = 1
            logger.appendLogSync("INFO", "Starting GeoIP resolution for $total hosts (skipped: $skipped, streams: $concurrentStreams)")

            val resolvedCount = java.util.concurrent.atomic.AtomicInteger(0)
            val progressCount = java.util.concurrent.atomic.AtomicInteger(0)
            val semaphore = Semaphore(concurrentStreams)

            coroutineScope {
                hostsToResolve.map { host ->
                    async {
                        semaphore.withPermit {
                            // Определяем что использовать для GeoIP запроса
                            // Приоритет: dnsIp1 -> dnsIp2 -> dnsIp3 -> address (если IP) -> hostname (резолв по имени)
                            // НЕ делаем автоматический DNS lookup - GeoIP и DNS это разные операции
                            val target: String? = when {
                                host.dnsIp1 != null -> host.dnsIp1
                                host.dnsIp2 != null -> host.dnsIp2
                                host.dnsIp3 != null -> host.dnsIp3
                                isIpAddress(host.address) -> host.address
                                else -> {
                                    // Для hostname без DNS IP - пробуем резолв по имени хоста напрямую
                                    // ip-api.com поддерживает резолв по hostname
                                    host.address
                                }
                            }

                            if (target != null) {
                                try {
                                    val geoInfo = resolveGeoIp(target)
                                    if (geoInfo != null) {
                                        hostDao.updateGeoIp(host.id, geoInfo)
                                        resolvedCount.incrementAndGet()
                                        logger.appendLogSync("DEBUG", "GeoIP for $target: $geoInfo")
                                    }
                                } catch (e: Exception) {
                                    logger.appendLogSync("WARN", "GeoIP failed for $target: ${e.message}")
                                }

                                // Задержка для rate limit ip-api.com (45 запросов/мин = 1.33 сек между запросами)
                                kotlinx.coroutines.delay(50)
                            }
                            onProgress(progressCount.incrementAndGet(), total)
                        }
                    }
                }.awaitAll()
            }

            val resolved = resolvedCount.get()
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

                    val cc = ccMatch?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
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
