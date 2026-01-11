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
        // Источник whitelist
        const val SOURCE_RU_WHITELIST = "https://github.com/hxehex/russia-mobile-internet-whitelist/raw/refs/heads/main/whitelist.txt"
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
}
