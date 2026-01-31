package com.example.yggpeerchecker.ui.checks

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.yggpeerchecker.data.AddressType
import com.example.yggpeerchecker.data.DiscoveredPeer
import com.example.yggpeerchecker.data.EndpointCheckResult
import com.example.yggpeerchecker.data.GroupedHost
import com.example.yggpeerchecker.data.GroupedHostBuilder
import com.example.yggpeerchecker.data.HostAddress
import com.example.yggpeerchecker.data.HostEndpoint
import com.example.yggpeerchecker.data.SessionManager
import com.example.yggpeerchecker.data.database.AppDatabase
import com.example.yggpeerchecker.data.database.Host
import com.example.yggpeerchecker.utils.DnsResolver
import com.example.yggpeerchecker.utils.NetworkUtil
import com.example.yggpeerchecker.utils.PersistentLogger
import com.example.yggpeerchecker.utils.PingUtil
import com.example.yggpeerchecker.utils.SniChecker
import com.example.yggpeerchecker.utils.TracertUtil
import com.example.yggpeerchecker.utils.UrlParser
import com.example.yggpeerchecker.utils.YggConnectChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import mobile.LogCallback
import mobile.Manager
import mobile.Mobile
import mobile.ProgressCallback
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

// Типы проверок
enum class CheckType(val displayName: String) {
    PING("Ping"),
    YGG_RTT("Ygg RTT"),
    PORT_DEFAULT("Port (default)"),
    PORT_80("Port 80"),
    PORT_443("Port 443")
}

// Результаты проверки для отдельного хоста/IP
data class HostCheckResult(
    val target: String,
    val isMainAddress: Boolean,
    val pingTime: Long = -1,
    val yggRtt: Long = -1,
    val portDefault: Long = -1,
    val port80: Long = -1,
    val port443: Long = -1,
    val available: Boolean = false,
    val error: String = "",
    val resolvedIp: String? = null  // Текущий резолвленный IP (для ip0)
) {
    // Проверка нужна ли перепроверка по конкретному типу
    fun needsCheck(type: CheckType): Boolean = when (type) {
        CheckType.PING -> pingTime < 0
        CheckType.YGG_RTT -> yggRtt < 0
        CheckType.PORT_DEFAULT -> portDefault < 0
        CheckType.PORT_80 -> port80 < 0
        CheckType.PORT_443 -> port443 < 0
    }

    // Все выбранные проверки успешны?
    fun allChecksSuccessful(types: Set<CheckType>): Boolean {
        return types.all { type ->
            when (type) {
                CheckType.PING -> pingTime >= 0
                CheckType.YGG_RTT -> yggRtt >= 0
                CheckType.PORT_DEFAULT -> portDefault >= 0
                CheckType.PORT_80 -> port80 >= 0
                CheckType.PORT_443 -> port443 >= 0
            }
        }
    }

    // Объединение результатов
    fun merge(other: HostCheckResult): HostCheckResult {
        return HostCheckResult(
            target = target,
            isMainAddress = isMainAddress,
            pingTime = if (pingTime >= 0) pingTime else other.pingTime,
            yggRtt = if (yggRtt >= 0) yggRtt else other.yggRtt,
            portDefault = if (portDefault >= 0) portDefault else other.portDefault,
            port80 = if (port80 >= 0) port80 else other.port80,
            port443 = if (port443 >= 0) port443 else other.port443,
            available = available || other.available,
            error = if (available || other.available) "" else error,
            resolvedIp = resolvedIp ?: other.resolvedIp
        )
    }
}

// Информация об источнике для фильтра
data class SourceInfo(
    val fullName: String,      // Полное имя (URL)
    val shortName: String,     // Короткое имя (ygg:neil, miniblack и т.д.)
    val count: Int             // Количество хостов
)

data class ChecksUiState(
    val isSearching: Boolean = false,
    val peers: List<DiscoveredPeer> = emptyList(),
    val progress: Int = 0,
    val totalPeers: Int = 0,
    val checkedCount: Int = 0,
    val availableCount: Int = 0,
    val unavailableCount: Int = 0,
    val skippedCount: Int = 0,  // Пропущено (уже проверены)
    val statusMessage: String = "Ready",
    val selectedPeers: Set<String> = emptySet(),
    val selectedFallbacks: Set<String> = emptySet(),  // Выбранные fallback адреса
    val checkSource: String = "db",
    // Настройки проверок
    val enabledCheckTypes: Set<CheckType> = setOf(CheckType.PING, CheckType.YGG_RTT),
    val fastMode: Boolean = false,          // Quick Mode: stop on first success + only first DNS IP
    val alwaysCheckDnsIps: Boolean = true,  // Inverse of Quick Mode для совместимости
    val typeFilter: String = "All",      // Фильтр по типу (All, Ygg, SNI)
    val sourceFilter: String = "All",    // Фильтр по источнику (All, или shortName)
    // Сортировка и фильтр
    val sortType: CheckType = CheckType.PING,  // По какому типу сортировать
    val filterMs: Int = 0,  // 0 = выкл, >0 = фильтр по ms
    // Статистика по хостам
    val dbHostsCount: Int = 0,
    val dbYggCount: Int = 0,
    val dbSniCount: Int = 0,
    val dbVlessCount: Int = 0,  // vless/vmess отдельно
    // Источники для фильтра (динамически из БД)
    val availableSources: List<SourceInfo> = emptyList(),
    // Fallback результаты
    val hostResults: Map<String, List<HostCheckResult>> = emptyMap(),
    // Tracert
    val isTracertRunning: Boolean = false,
    val tracertProgress: Int = 0,
    // Группировка
    val groupedHosts: List<GroupedHost> = emptyList(),
    val selectedEndpoints: Set<String> = emptySet()  // Выбранные endpoint URLs для копирования
)

class ChecksViewModel(
    private val logger: PersistentLogger,
    private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChecksUiState())
    val uiState: StateFlow<ChecksUiState> = _uiState.asStateFlow()

    private var manager: Manager? = null
    private var shouldStop = false
    private var discoveryJob: Job? = null
    private val database = AppDatabase.getDatabase(context)
    private val sessionManager = SessionManager(context)
    private val checksPrefs = context.getSharedPreferences("checks_prefs", Context.MODE_PRIVATE)

    private fun getConcurrentStreams(): Int {
        val prefs = context.getSharedPreferences("ygg_prefs", Context.MODE_PRIVATE)
        return prefs.getInt("concurrent_streams", 10)
    }

    // Загрузка сохранённых настроек проверок
    private fun loadChecksPrefs() {
        val savedTypes = checksPrefs.getStringSet("enabled_check_types", null)
        val enabledTypes = if (savedTypes != null) {
            savedTypes.mapNotNull { name ->
                try { CheckType.valueOf(name) } catch (e: Exception) { null }
            }.toSet() + CheckType.PING  // PING всегда активен
        } else {
            setOf(CheckType.PING, CheckType.YGG_RTT)  // default
        }

        val fastMode = checksPrefs.getBoolean("fast_mode", false)
        val alwaysCheckDns = checksPrefs.getBoolean("always_check_dns_ips", true)
        val sortTypeName = checksPrefs.getString("sort_type", "PING") ?: "PING"
        val sortType = try { CheckType.valueOf(sortTypeName) } catch (e: Exception) { CheckType.PING }
        val filterMs = checksPrefs.getInt("filter_ms", 0)

        _uiState.update { it.copy(
            enabledCheckTypes = enabledTypes,
            fastMode = fastMode,
            alwaysCheckDnsIps = alwaysCheckDns,
            sortType = sortType,
            filterMs = filterMs
        )}
        logger.appendLogSync("DEBUG", "Loaded checks prefs: types=$enabledTypes, fast=$fastMode, dns=$alwaysCheckDns")
    }

    // Сохранение настроек проверок
    private fun saveChecksPrefs() {
        val state = _uiState.value
        checksPrefs.edit().apply {
            putStringSet("enabled_check_types", state.enabledCheckTypes.map { it.name }.toSet())
            putBoolean("fast_mode", state.fastMode)
            putBoolean("always_check_dns_ips", state.alwaysCheckDnsIps)
            putString("sort_type", state.sortType.name)
            putInt("filter_ms", state.filterMs)
            apply()
        }
    }

    // Файл для persist результатов проверок
    private val checksFile = File(context.filesDir, "checks_results.json")

    // Сохранение результатов проверок на диск
    private fun persistCheckResults() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = _uiState.value
                val json = JSONObject()

                // Сохраняем peers
                val peersArray = JSONArray()
                state.peers.forEach { peer ->
                    peersArray.put(peer.toSaveJson())
                }
                json.put("peers", peersArray)

                // Сохраняем hostResults
                val resultsObj = JSONObject()
                state.hostResults.forEach { (key, results) ->
                    val arr = JSONArray()
                    results.forEach { result ->
                        arr.put(JSONObject().apply {
                            put("target", result.target)
                            put("isMainAddress", result.isMainAddress)
                            put("pingTime", result.pingTime)
                            put("yggRtt", result.yggRtt)
                            put("portDefault", result.portDefault)
                            put("port80", result.port80)
                            put("port443", result.port443)
                            put("available", result.available)
                            put("error", result.error)
                            put("resolvedIp", result.resolvedIp ?: "")
                        })
                    }
                    resultsObj.put(key, arr)
                }
                json.put("hostResults", resultsObj)
                json.put("savedAt", System.currentTimeMillis())

                checksFile.writeText(json.toString())
            } catch (e: Exception) {
                logger.appendLogSync("WARN", "Failed to persist check results: ${e.message}")
            }
        }
    }

    // Загрузка сохранённых результатов проверок с диска
    private fun loadPersistedCheckResults() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!checksFile.exists()) return@launch

                val json = JSONObject(checksFile.readText())

                // Загружаем peers
                val peersArray = json.optJSONArray("peers") ?: return@launch
                val peers = mutableListOf<DiscoveredPeer>()
                for (i in 0 until peersArray.length()) {
                    try {
                        peers.add(DiscoveredPeer.fromSaveJson(peersArray.getJSONObject(i)))
                    } catch (e: Exception) {
                        // Пропускаем битые записи
                    }
                }

                // Загружаем hostResults
                val resultsObj = json.optJSONObject("hostResults")
                val hostResults = mutableMapOf<String, List<HostCheckResult>>()
                if (resultsObj != null) {
                    resultsObj.keys().forEach { key ->
                        val arr = resultsObj.optJSONArray(key) ?: return@forEach
                        val results = mutableListOf<HostCheckResult>()
                        for (i in 0 until arr.length()) {
                            val r = arr.getJSONObject(i)
                            results.add(HostCheckResult(
                                target = r.optString("target"),
                                isMainAddress = r.optBoolean("isMainAddress"),
                                pingTime = r.optLong("pingTime", -1),
                                yggRtt = r.optLong("yggRtt", -1),
                                portDefault = r.optLong("portDefault", -1),
                                port80 = r.optLong("port80", -1),
                                port443 = r.optLong("port443", -1),
                                available = r.optBoolean("available"),
                                error = r.optString("error", ""),
                                resolvedIp = r.optString("resolvedIp", "").ifEmpty { null }
                            ))
                        }
                        hostResults[key] = results
                    }
                }

                if (peers.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(
                            peers = peers,
                            hostResults = hostResults,
                            totalPeers = peers.size,
                            checkedCount = peers.size,
                            availableCount = peers.count { p -> p.isAlive() },
                            unavailableCount = peers.count { p -> !p.isAlive() },
                            statusMessage = "Restored ${peers.size} results (${peers.count { p -> p.isAlive() }} alive)"
                        )}
                        // Перестраиваем группы для grouped view
                        updateGroupsWithResults()
                    }
                    logger.appendLogSync("INFO", "Restored ${peers.size} check results from disk")
                }
            } catch (e: Exception) {
                logger.appendLogSync("WARN", "Failed to load persisted check results: ${e.message}")
            }
        }
    }

    init {
        try {
            manager = Mobile.newManager()
            manager?.setLogCallback(object : LogCallback {
                override fun onLog(level: String?, message: String?) {
                    val msg = message ?: ""
                    logger.appendLogSync(level ?: "INFO", "[yggpeers] $msg")
                }
            })
            // Передаём Manager в YggConnectChecker для QUIC проверок
            YggConnectChecker.manager = manager
            logger.appendLogSync("INFO", "ChecksViewModel initialized (QUIC enabled: ${manager != null})")
            loadChecksPrefs()  // Загрузка сохранённых настроек
            loadDbStats()
            loadPersistedCheckResults()  // Восстанавливаем результаты
        } catch (e: Exception) {
            logger.appendLogSync("ERROR", "Failed to create manager: ${e.message}")
        }
    }

    private fun loadDbStats() {
        viewModelScope.launch(Dispatchers.IO) {
            val total = database.hostDao().getHostCount()
            val ygg = database.hostDao().getYggHostsCountSync()
            val sni = database.hostDao().getSniHostsCountSync()
            val vless = database.hostDao().getVlessHostsCountSync()

            // Загружаем уникальные источники
            val distinctSources = database.hostDao().getDistinctSources()
            val sourceInfoList = distinctSources.map { source ->
                val count = database.hostDao().getHostCountBySource(source)
                SourceInfo(
                    fullName = source,
                    shortName = extractShortSourceName(source),
                    count = count
                )
            }.sortedByDescending { it.count }

            _uiState.update { it.copy(
                dbHostsCount = total,
                dbYggCount = ygg,
                dbSniCount = sni,
                dbVlessCount = vless,
                availableSources = sourceInfoList
            )}
        }
    }

    // Извлечение короткого имени источника
    private fun extractShortSourceName(source: String): String {
        return when {
            source.contains("neilalexander") -> "ygg:neil"
            source.contains("yggdrasil.link") -> "ygg:link"
            source.contains("whitelist") -> "whitelist"
            source.contains("miniwhite") -> "miniwhite"
            source.contains("miniblack") -> "miniblack"
            source.contains("vless") || source.contains("zieng") -> "vless"
            source.contains("clipboard") -> "clipboard"
            source.contains("file://") || source.contains("file:") -> "file"
            else -> source.substringAfterLast("/").take(12)
        }
    }

    fun setSourceFilter(filter: String) {
        _uiState.update { it.copy(sourceFilter = filter) }
        logger.appendLogSync("DEBUG", "Source filter: $filter")
    }

    fun toggleCheckType(type: CheckType) {
        // PING нельзя отключить - всегда активен
        if (type == CheckType.PING) return

        _uiState.update { state ->
            val newTypes = if (state.enabledCheckTypes.contains(type)) {
                state.enabledCheckTypes - type
            } else {
                state.enabledCheckTypes + type
            }
            state.copy(enabledCheckTypes = newTypes)
        }
        saveChecksPrefs()
        logger.appendLogSync("DEBUG", "Check types changed: ${_uiState.value.enabledCheckTypes}")
    }

    // Quick Mode: объединяет Fast Mode + Only First DNS IP
    // ON: останавливается при первом успехе, проверяет только первый DNS IP
    // OFF: проверяет все типы, проверяет все DNS IPs
    fun toggleQuickMode() {
        _uiState.update { state ->
            val newQuick = !state.fastMode
            state.copy(
                fastMode = newQuick,
                alwaysCheckDnsIps = !newQuick  // Quick mode = не проверять все DNS IPs
            )
        }
        saveChecksPrefs()
        logger.appendLogSync("DEBUG", "Quick mode: ${_uiState.value.fastMode}")
    }

    fun setSortType(type: CheckType) {
        _uiState.update { it.copy(sortType = type) }
        saveChecksPrefs()
        // Пересортировать peers
        sortPeers()
    }

    fun setFilterMs(ms: Int) {
        _uiState.update { it.copy(filterMs = ms) }
        saveChecksPrefs()
    }

    private fun sortPeers() {
        val sortType = _uiState.value.sortType
        val filterMs = _uiState.value.filterMs
        val sorted = _uiState.value.peers.sortedWith(
            compareBy { peer ->
                val ms = when (sortType) {
                    CheckType.PING -> peer.pingMs
                    CheckType.YGG_RTT -> peer.yggRttMs
                    CheckType.PORT_DEFAULT -> peer.portDefaultMs
                    CheckType.PORT_80 -> peer.port80Ms
                    CheckType.PORT_443 -> peer.port443Ms
                }
                // -1 = не проверялось, -2 = failed -> в конец
                when {
                    ms >= 0 -> ms
                    ms == -1L -> Long.MAX_VALUE - 1
                    else -> Long.MAX_VALUE
                }
            }
        ).let { list ->
            if (filterMs > 0) {
                list.filter { peer ->
                    val ms = when (sortType) {
                        CheckType.PING -> peer.pingMs
                        CheckType.YGG_RTT -> peer.yggRttMs
                        CheckType.PORT_DEFAULT -> peer.portDefaultMs
                        CheckType.PORT_80 -> peer.port80Ms
                        CheckType.PORT_443 -> peer.port443Ms
                    }
                    ms in 0..filterMs
                }
            } else list
        }
        _uiState.update { it.copy(peers = sorted) }
    }

    fun setTypeFilter(filter: String) {
        _uiState.update { it.copy(typeFilter = filter) }
        logger.appendLogSync("DEBUG", "Type filter: $filter")
    }

    // Главная функция - режим дозаполнения (не обнуляет результаты!)
    fun startPeerDiscovery() {
        if (discoveryJob?.isActive == true) {
            logger.appendLogSync("WARN", "Previous discovery running, cancelling...")
            shouldStop = true
            discoveryJob?.cancel()
        }

        viewModelScope.launch {
            discoveryJob?.join()
            loadDbStats()

            val dbHosts = withContext(Dispatchers.IO) {
                when (_uiState.value.typeFilter) {
                    "Ygg" -> database.hostDao().getYggHosts()
                    "SNI" -> database.hostDao().getSniHosts()
                    else -> database.hostDao().getAllHostsList()
                }
            }

            if (dbHosts.isEmpty()) {
                logger.appendLogSync("WARN", "No hosts in DB for filter: ${_uiState.value.typeFilter}")
                _uiState.update { it.copy(
                    isSearching = false,
                    statusMessage = "No hosts in Lists. Load hosts first!"
                )}
                return@launch
            }

            // Фильтруем хосты - оставляем только те что нужно проверить
            val hostsToCheck = filterHostsForCheck(dbHosts)
            val skippedCount = dbHosts.size - hostsToCheck.size

            if (hostsToCheck.isEmpty()) {
                logger.appendLogSync("INFO", "All hosts already checked with current settings")
                _uiState.update { it.copy(
                    isSearching = false,
                    skippedCount = skippedCount,
                    statusMessage = "All ${dbHosts.size} hosts already checked. Clear to recheck."
                )}
                return@launch
            }

            startDbDiscovery(hostsToCheck, skippedCount)
        }
    }

    // Фильтрация хостов - пропускаем уже проверенные (используем normalizedKey)
    private fun filterHostsForCheck(allHosts: List<Host>): List<Host> {
        val existingPeers = _uiState.value.peers.associateBy { it.normalizedKey }
        val existingResults = _uiState.value.hostResults
        val enabledTypes = _uiState.value.enabledCheckTypes

        return allHosts.filter { host ->
            val hostKey = normalizeHostKey(host.address, host.port, host.hostType)
            val existingPeer = existingPeers[hostKey]
            val existingResult = existingResults[host.hostString]?.firstOrNull { it.isMainAddress }

            // Если пир еще не проверялся - проверяем
            if (existingPeer == null) return@filter true

            // Если есть результат и все текущие проверки успешны - пропускаем
            if (existingResult != null && existingResult.allChecksSuccessful(enabledTypes)) {
                return@filter false
            }

            // Если failed по всем или есть недостающие проверки - проверяем
            true
        }
    }

    private fun startDbDiscovery(hosts: List<Host>, initialSkipped: Int = 0) {
        shouldStop = false

        discoveryJob = viewModelScope.launch {
            val prefs = context.getSharedPreferences("ygg_prefs", Context.MODE_PRIVATE)
            val checkInterfaces = prefs.getBoolean("check_interfaces", true)

            if (checkInterfaces && !NetworkUtil.isNetworkAvailable(context)) {
                logger.appendLogSync("WARN", "No network connection")
                _uiState.update { it.copy(isSearching = false, statusMessage = "No network connection") }
                return@launch
            }

            val concurrentStreams = getConcurrentStreams()
            logger.appendLogSync("INFO", "Starting discovery: ${hosts.size} hosts, $concurrentStreams streams")
            logger.appendLogSync("INFO", "Check types: ${_uiState.value.enabledCheckTypes}")
            logger.appendLogSync("INFO", "Fast mode: ${_uiState.value.fastMode}")

            val skipInfo = if (initialSkipped > 0) " (skipped $initialSkipped already checked)" else ""
            _uiState.update { it.copy(
                isSearching = true,
                progress = 0,
                totalPeers = hosts.size,
                checkedCount = 0,
                skippedCount = initialSkipped,  // Начинаем с количества пропущенных хостов
                statusMessage = "Checking ${hosts.size} hosts$skipInfo..."
            )}

            val semaphore = Semaphore(concurrentStreams)
            val checkedCounter = AtomicInteger(0)

            val jobs = hosts.map { host ->
                launch(Dispatchers.IO) {
                    if (shouldStop) return@launch

                    semaphore.withPermit {
                        if (shouldStop) return@withPermit

                        logger.appendLogSync("DEBUG", "Checking: ${host.address} (stream ${Thread.currentThread().name})")

                        val existingResult = _uiState.value.hostResults[host.hostString]?.firstOrNull { it.isMainAddress }
                        val (results, _) = checkHostWithFallback(host, existingResult)
                        val checked = checkedCounter.incrementAndGet()

                        withContext(Dispatchers.Main) {
                            if (!shouldStop) {
                                addCheckResult(host, results, checked, hosts.size)
                            }
                        }
                    }
                }
            }

            jobs.forEach { it.join() }

            _uiState.update { state ->
                val finalSkipInfo = if (state.skippedCount > 0) " | Skip: ${state.skippedCount}" else ""
                state.copy(
                    isSearching = false,
                    statusMessage = "Done | OK: ${state.availableCount} | Fail: ${state.unavailableCount}$finalSkipInfo"
                )
            }
            persistCheckResults()  // Сохраняем результаты после завершения
            logger.appendLogSync("INFO", "Discovery completed. Skipped: $initialSkipped hosts")
        }
    }

    // Проверка с учетом предыдущих результатов и fallback
    // Возвращает пару: список результатов и количество пропущенных проверок
    private suspend fun checkHostWithFallback(host: Host, existingResult: HostCheckResult?): Pair<List<HostCheckResult>, Int> {
        val results = mutableListOf<HostCheckResult>()
        val enabledTypes = _uiState.value.enabledCheckTypes
        val fastMode = _uiState.value.fastMode
        val alwaysCheckDns = _uiState.value.alwaysCheckDnsIps
        var skippedChecks = 0

        // Резолвим текущий IP для hostname (для ip0 и проверки localhost)
        var currentResolvedIp: String? = null
        var resolvedIsLocalhost = false
        val isHostname = !UrlParser.isIpAddress(host.address)

        if (isHostname) {
            try {
                val dnsServer = DnsResolver.getSelectedDnsServer(context)
                val resolveResult = DnsResolver.resolve(host.address, dnsServer, 5000)
                val firstIp = resolveResult.ips.firstOrNull()

                if (firstIp != null) {
                    // Проверяем localhost подмену (включая spoofed IPs которые DnsResolver уже отфильтровал)
                    resolvedIsLocalhost = isLocalhost(firstIp) || resolveResult.isSpoofed
                    if (resolvedIsLocalhost) {
                        logger.appendLogSync("WARN", "DNS resolved ${host.address} -> localhost ($firstIp), marking as X")
                    }

                    // ip0: показываем текущий резолвленный IP если отличается от кэшированных
                    // Включая localhost/spoofed — пользователь должен видеть подмену
                    val cachedIps = listOfNotNull(host.dnsIp1, host.dnsIp2, host.dnsIp3)
                    if (cachedIps.isEmpty() || firstIp !in cachedIps) {
                        currentResolvedIp = firstIp
                        if (resolvedIsLocalhost) {
                            logger.appendLogSync("WARN", "ip0 SPOOFED for ${host.address}: $firstIp (cached: ${cachedIps.joinToString().ifEmpty { "none" }})")
                        } else {
                            logger.appendLogSync("INFO", "ip0 for ${host.address}: $firstIp (cached: ${cachedIps.joinToString().ifEmpty { "none" }})")
                        }
                    }
                } else if (resolveResult.isSpoofed) {
                    // DNS подменён и валидных IP нет
                    resolvedIsLocalhost = true
                    logger.appendLogSync("WARN", "DNS spoofed for ${host.address}, all IPs filtered")
                } else {
                    // DNS не дал результатов - пробуем системный резолв напрямую для проверки localhost
                    try {
                        val sysIp = java.net.InetAddress.getByName(host.address).hostAddress
                        if (sysIp != null && isLocalhost(sysIp)) {
                            resolvedIsLocalhost = true
                            logger.appendLogSync("WARN", "System DNS resolved ${host.address} -> $sysIp (localhost)")
                        } else if (sysIp != null) {
                            // Системный резолв дал не-localhost IP - показываем как ip0
                            val cachedIps = listOfNotNull(host.dnsIp1, host.dnsIp2, host.dnsIp3)
                            if (cachedIps.isEmpty() || sysIp !in cachedIps) {
                                currentResolvedIp = sysIp
                                logger.appendLogSync("INFO", "ip0 (sys fallback) for ${host.address}: $sysIp")
                            }
                        }
                    } catch (e: Exception) {
                        logger.appendLogSync("DEBUG", "System resolve also failed for ${host.address}: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                logger.appendLogSync("WARN", "DNS resolve error for ${host.address}: ${e.message}")
            }
        } else {
            // Если target уже IP - проверяем на localhost напрямую
            resolvedIsLocalhost = isLocalhost(host.address)
        }

        // Определяем какие проверки нужны для основного адреса
        val typesToCheck = if (existingResult != null) {
            enabledTypes.filter { existingResult.needsCheck(it) }.toSet()
        } else {
            enabledTypes
        }

        // Считаем пропущенные проверки для основного адреса
        skippedChecks += enabledTypes.size - typesToCheck.size

        // Основной адрес
        val mainResult = if (typesToCheck.isNotEmpty()) {
            checkSingleTarget(
                target = host.address,
                port = host.port,
                hostType = host.hostType,
                hostString = host.hostString,
                enabledTypes = typesToCheck,
                fastMode = fastMode,
                isMainAddress = true,
                forceLocalhostFail = resolvedIsLocalhost  // Форсируем X если DNS подменён
            ).copy(resolvedIp = currentResolvedIp)  // Добавляем ip0
        } else {
            // Все проверки уже успешны - возвращаем существующий результат
            existingResult!!.copy(resolvedIp = currentResolvedIp ?: existingResult.resolvedIp)
        }

        // Объединяем с предыдущими результатами
        val mergedMain = existingResult?.merge(mainResult) ?: mainResult
        results.add(mergedMain)

        // DNS IP адреса
        val allDnsIps = listOfNotNull(host.dnsIp1, host.dnsIp2, host.dnsIp3)
            .filter { it != host.address && it.isNotEmpty() }

        // Quick Mode (fastMode + !alwaysCheckDns): только первый DNS IP
        // Full Mode: все DNS IPs
        val dnsIps = if (fastMode && !alwaysCheckDns) allDnsIps.take(1) else allDnsIps

        // Проверяем DNS IP если:
        // 1. alwaysCheckDns включен - всегда проверяем
        // 2. Основной адрес недоступен - fallback
        val shouldCheckDns = alwaysCheckDns || !mergedMain.available

        if (shouldCheckDns && dnsIps.isNotEmpty()) {
            val existingFallbacks = _uiState.value.hostResults[host.hostString]
                ?.filter { !it.isMainAddress }
                ?.associateBy { it.target }
                ?: emptyMap()

            for (dnsIp in dnsIps) {
                if (shouldStop) break

                // Подставляем IP в полный адрес для отображения и поиска
                val fallbackHostString = buildFallbackHostString(host.hostString, dnsIp)

                // Проверяем существующий результат для этого DNS IP (ищем по URL с IP)
                val existingDnsResult = existingFallbacks[fallbackHostString]
                val dnsTypesToCheck = if (existingDnsResult != null) {
                    enabledTypes.filter { existingDnsResult.needsCheck(it) }.toSet()
                } else {
                    enabledTypes
                }

                // Считаем пропущенные проверки для этого DNS IP
                skippedChecks += enabledTypes.size - dnsTypesToCheck.size

                // Пропускаем если все проверки уже успешны
                if (dnsTypesToCheck.isEmpty()) {
                    existingDnsResult?.let { results.add(it) }
                    continue
                }

                // Выполняем проверку по IP адресу
                // Для SNI хостов передаём оригинальный hostname для TLS handshake
                val sniHostname = if (Host.isSniType(host.hostType)) host.address else null

                val dnsResult = checkSingleTarget(
                    target = dnsIp,
                    port = host.port,
                    hostType = host.hostType,
                    hostString = fallbackHostString,
                    enabledTypes = dnsTypesToCheck,
                    fastMode = fastMode,
                    isMainAddress = false,
                    displayAddress = fallbackHostString,
                    originalHostname = sniHostname,
                    forceLocalhostFail = isLocalhost(dnsIp)  // DNS IP тоже проверяем
                )

                // Объединяем с существующими результатами
                val mergedDnsResult = existingDnsResult?.merge(dnsResult) ?: dnsResult
                results.add(mergedDnsResult)

                // В fastMode останавливаемся при первом успехе (только если не alwaysCheckDns)
                if (fastMode && !alwaysCheckDns && mergedDnsResult.available) break
            }
        }

        return Pair(results, skippedChecks)
    }

    private fun buildFallbackHostString(originalHostString: String, ip: String): String {
        return try {
            // Для IPv6 оборачиваем в скобки
            val formattedIp = if (ip.contains(":") && !ip.startsWith("[")) "[$ip]" else ip

            // Сохраняем протокол и параметры, заменяем только хост
            val regex = Regex("(\\w+://)([^:/\\[\\]]+|\\[[^\\]]+\\])(.*)")
            val match = regex.find(originalHostString)

            if (match != null) {
                // Есть протокол - заменяем хост на IP
                val proto = match.groupValues[1]
                val rest = match.groupValues[3]
                "$proto$formattedIp$rest"
            } else {
                // Нет протокола - возвращаем просто IP (sni:// условное обозначение, не реальный протокол)
                formattedIp
            }
        } catch (e: Exception) {
            // Fallback: просто IP
            ip
        }
    }

    // Localhost адреса и имена для которых ping = X (неудача)
    private val LOCALHOST_ADDRESSES = setOf(
        "127.0.0.1", "0.0.0.0", "::1", "localhost",
        "127.0.0.0", "0:0:0:0:0:0:0:1"
    )

    // Проверка является ли адрес localhost
    private fun isLocalhost(address: String): Boolean {
        return address.lowercase().trim() in LOCALHOST_ADDRESSES
    }

    private suspend fun checkSingleTarget(
        target: String,
        port: Int?,
        hostType: String,
        hostString: String,
        enabledTypes: Set<CheckType>,
        fastMode: Boolean,
        isMainAddress: Boolean,
        displayAddress: String? = null,  // Адрес для отображения (для DNS fallback - полный URL)
        originalHostname: String? = null,  // Оригинальное доменное имя для SNI (если target - IP)
        forceLocalhostFail: Boolean = false  // Форсировать X для всех проверок (DNS подмена)
    ): HostCheckResult {
        val resultTarget = displayAddress ?: target  // Что показывать в UI
        var pingTime: Long = -1
        var yggRtt: Long = -1
        var portDefault: Long = -1
        var port80: Long = -1
        var port443: Long = -1
        var available = false
        var error = ""

        // Localhost или DNS-подмена: все проверки -> X
        // Дополнительная проверка: резолвим hostname через системный DNS чтобы поймать 127.0.0.1
        var isLocalhostTarget = isLocalhost(target) || forceLocalhostFail
        if (!isLocalhostTarget && !UrlParser.isIpAddress(target)) {
            // target - hostname, резолвим чтобы узнать реальный IP
            try {
                val resolvedIp = java.net.InetAddress.getByName(target).hostAddress
                if (resolvedIp != null && isLocalhost(resolvedIp)) {
                    isLocalhostTarget = true
                    logger.appendLogSync("WARN", "Pre-check: $target resolves to localhost ($resolvedIp)")
                }
            } catch (e: Exception) {
                // Не удалось резолвить - продолжаем проверку
                logger.appendLogSync("DEBUG", "Pre-check resolve failed for $target: ${e.message}")
            }
        }

        if (isLocalhostTarget) {
            logger.appendLogSync("DEBUG", "LOCALHOST/SPOOFED target: $target (forceLocalhostFail=$forceLocalhostFail)")
            return HostCheckResult(
                target = resultTarget,
                isMainAddress = isMainAddress,
                pingTime = if (enabledTypes.contains(CheckType.PING)) -2 else -1,
                yggRtt = if (enabledTypes.contains(CheckType.YGG_RTT) && Host.isYggType(hostType)) -2 else -1,
                portDefault = if (enabledTypes.contains(CheckType.PORT_DEFAULT)) -2 else -1,
                port80 = if (enabledTypes.contains(CheckType.PORT_80)) -2 else -1,
                port443 = if (enabledTypes.contains(CheckType.PORT_443)) -2 else -1,
                available = false,
                error = "localhost/DNS spoofed"
            )
        }

        try {
            // PING
            if (enabledTypes.contains(CheckType.PING)) {
                logger.appendLogSync("DEBUG", "Ping check: $target")
                val ping = PingUtil.ping(target, 3000)
                if (ping >= 0) {
                    pingTime = ping.toLong()
                    available = true
                    logger.appendLogSync("DEBUG", "Ping OK: $target = ${pingTime}ms")
                    if (fastMode) return HostCheckResult(resultTarget, isMainAddress, pingTime, yggRtt, portDefault, port80, port443, true)
                } else {
                    pingTime = -2  // Failed
                    logger.appendLogSync("DEBUG", "Ping FAILED: $target")
                }
            }

            // YGG RTT - используем библиотеку для Ygg типов
            if (enabledTypes.contains(CheckType.YGG_RTT) && Host.isYggType(hostType)) {
                logger.appendLogSync("DEBUG", "Ygg RTT check: $hostString")
                val rtt = checkYggRtt(hostString)
                if (rtt >= 0) {
                    yggRtt = rtt
                    available = true
                    logger.appendLogSync("DEBUG", "Ygg RTT OK: $hostString = ${yggRtt}ms")
                    if (fastMode) return HostCheckResult(resultTarget, isMainAddress, pingTime, yggRtt, portDefault, port80, port443, true)
                } else {
                    yggRtt = -2  // Failed
                    logger.appendLogSync("DEBUG", "Ygg RTT FAILED: $hostString")
                }
            } else if (enabledTypes.contains(CheckType.YGG_RTT) && !Host.isYggType(hostType)) {
                // Для не-Ygg типов YGG_RTT не применим - оставляем -1 (off/n/a)
                logger.appendLogSync("DEBUG", "Ygg RTT N/A for SNI type: $hostString")
            }

            // Port default
            if (enabledTypes.contains(CheckType.PORT_DEFAULT) && port != null && port > 0) {
                logger.appendLogSync("DEBUG", "Port $port check: $target")
                val result = SniChecker.checkPort(target, port, 3000)
                if (result.available) {
                    portDefault = result.responseTime
                    available = true
                    logger.appendLogSync("DEBUG", "Port $port OK: $target = ${portDefault}ms")
                    if (fastMode) return HostCheckResult(resultTarget, isMainAddress, pingTime, yggRtt, portDefault, port80, port443, true)
                } else {
                    portDefault = -2  // Failed
                    logger.appendLogSync("DEBUG", "Port $port FAILED: $target")
                }
            }

            // Port 80
            if (enabledTypes.contains(CheckType.PORT_80)) {
                logger.appendLogSync("DEBUG", "Port 80 check: $target")
                val result = SniChecker.checkPort(target, 80, 3000)
                if (result.available) {
                    port80 = result.responseTime
                    available = true
                    logger.appendLogSync("DEBUG", "Port 80 OK: $target = ${port80}ms")
                    if (fastMode) return HostCheckResult(resultTarget, isMainAddress, pingTime, yggRtt, portDefault, port80, port443, true)
                } else {
                    port80 = -2  // Failed
                    logger.appendLogSync("DEBUG", "Port 80 FAILED: $target")
                }
            }

            // Port 443
            if (enabledTypes.contains(CheckType.PORT_443)) {
                logger.appendLogSync("DEBUG", "Port 443 check: $target" +
                    (if (originalHostname != null) " (SNI: $originalHostname)" else ""))

                // Для SNI хостов (когда target - IP, а originalHostname - доменное имя)
                // используем TLS handshake с указанием hostname в SNI
                val result = if (originalHostname != null && !Host.isYggType(hostType)) {
                    SniChecker.checkPortWithSni(target, originalHostname, 443, 5000)
                } else {
                    SniChecker.checkPort(target, 443, 3000)
                }

                if (result.available) {
                    port443 = result.responseTime
                    available = true
                    logger.appendLogSync("DEBUG", "Port 443 OK: $target = ${port443}ms")
                    if (fastMode) return HostCheckResult(resultTarget, isMainAddress, pingTime, yggRtt, portDefault, port80, port443, true)
                } else {
                    port443 = -2  // Failed
                    logger.appendLogSync("DEBUG", "Port 443 FAILED: $target" +
                        (result.error?.let { " ($it)" } ?: ""))
                }
            }

        } catch (e: Exception) {
            error = e.message ?: "Check failed"
            logger.appendLogSync("ERROR", "Check failed $target: $error")
        }

        if (!available) error = "All checks failed"

        return HostCheckResult(resultTarget, isMainAddress, pingTime, yggRtt, portDefault, port80, port443, available, error)
    }

    // Ygg RTT через прямой TCP/TLS connect
    private suspend fun checkYggRtt(hostString: String): Long {
        val rtt = YggConnectChecker.checkPeer(hostString)
        if (rtt >= 0) {
            logger.appendLogSync("DEBUG", "Ygg connect OK: $hostString RTT=${rtt}ms")
        } else {
            logger.appendLogSync("DEBUG", "Ygg connect FAILED: $hostString")
        }
        return rtt
    }

    // Извлечение региона из source URL
    private fun extractRegion(source: String): String {
        return when {
            source.contains("neilalexander") -> "neilalexander"
            source.contains("yggdrasil.link") -> "yggdrasil.link"
            source.contains("whitelist") -> "RU"
            source.contains("clipboard") -> "clipboard"
            source.contains("file") -> "file"
            else -> source.substringAfterLast("/").take(15)
        }
    }

    // Нормализованный ключ для сопоставления пиров (protocol://address:port)
    // Включает протокол чтобы tcp://x:123 и quic://x:123 были разными записями
    private fun normalizeHostKey(address: String, port: Int?, protocol: String? = null): String {
        val prefix = if (!protocol.isNullOrEmpty()) "${protocol.lowercase()}://" else ""
        return if (port != null) {
            "$prefix${address.lowercase()}:$port"
        } else {
            "$prefix${address.lowercase()}"
        }
    }

    private fun addCheckResult(host: Host, results: List<HostCheckResult>, current: Int, total: Int) {
        val mainResult = results.firstOrNull { it.isMainAddress } ?: return
        val bestResult = results.firstOrNull { it.available } ?: mainResult
        val enabledTypes = _uiState.value.enabledCheckTypes

        // Нормализованный ключ для сопоставления (включает протокол)
        val normalizedKey = normalizeHostKey(host.address, host.port, host.hostType)

        // Получаем существующий пир для сохранения предыдущих результатов
        val existingPeer = _uiState.value.peers.find { it.normalizedKey == normalizedKey }

        // Конвертируем результат в значение с сохранением предыдущих:
        // -1 = не проверялось/не применимо (off), -2 = failed (X), >=0 = ms
        // Если тип не выбран, сохраняем предыдущее значение (режим дополнения)
        fun checkValue(value: Long, type: CheckType, previousValue: Long): Long = when {
            !enabledTypes.contains(type) -> previousValue  // сохраняем предыдущее
            value >= 0 -> value                             // успех
            value == -1L -> -1                              // off/n/a (не применимо для данного типа хоста)
            else -> -2                                      // failed
        }

        // Для SNI хостов yggRtt не применим - оставляем -1 (off)
        val yggRttValue = if (!Host.isYggType(host.hostType)) {
            -1L  // off для SNI
        } else {
            checkValue(bestResult.yggRtt, CheckType.YGG_RTT, existingPeer?.yggRttMs ?: -1)
        }

        val peer = DiscoveredPeer(
            address = host.hostString,
            protocol = host.hostType,
            region = host.region ?: extractRegion(host.source),
            geoIp = host.geoIp ?: "",
            source = host.source,
            sourceShort = extractShortSourceName(host.source),
            rtt = when {
                bestResult.yggRtt >= 0 -> bestResult.yggRtt
                bestResult.portDefault >= 0 -> bestResult.portDefault
                bestResult.port80 >= 0 -> bestResult.port80
                bestResult.port443 >= 0 -> bestResult.port443
                bestResult.pingTime >= 0 -> bestResult.pingTime
                else -> 0
            },
            available = bestResult.available,
            responseMs = if (bestResult.pingTime >= 0) bestResult.pingTime.toInt() else 0,
            lastSeen = System.currentTimeMillis(),
            checkError = if (!bestResult.available) bestResult.error else "",
            ping = bestResult.pingTime,
            // Индивидуальные результаты (сохраняем предыдущие если тип не выбран)
            pingMs = checkValue(bestResult.pingTime, CheckType.PING, existingPeer?.pingMs ?: -1),
            yggRttMs = yggRttValue,  // Для SNI всегда -1 (off)
            portDefaultMs = checkValue(bestResult.portDefault, CheckType.PORT_DEFAULT, existingPeer?.portDefaultMs ?: -1),
            port80Ms = checkValue(bestResult.port80, CheckType.PORT_80, existingPeer?.port80Ms ?: -1),
            port443Ms = checkValue(bestResult.port443, CheckType.PORT_443, existingPeer?.port443Ms ?: -1),
            normalizedKey = normalizedKey
        )

        _uiState.update { state ->
            // Обновляем существующий или добавляем новый (по normalizedKey для избежания дублей)
            val updatedPeers = state.peers.toMutableList()
            val existingIndex = updatedPeers.indexOfFirst { it.normalizedKey == peer.normalizedKey }
            if (existingIndex >= 0) {
                updatedPeers[existingIndex] = peer
            } else {
                updatedPeers.add(peer)
            }

            // Сортируем
            val sortedPeers = updatedPeers.sortedWith(
                compareByDescending<DiscoveredPeer> { it.available }
                    .thenBy { if (it.rtt > 0) it.rtt else Long.MAX_VALUE }
                    .thenBy { it.address }
            )

            val progress = if (total > 0) ((current * 100) / total) else 0

            val updatedHostResults = state.hostResults.toMutableMap()
            updatedHostResults[host.hostString] = results

            // skippedCount содержит initialSkipped (пропущенные хосты)
            val skipInfo = if (state.skippedCount > 0) " | Skip: ${state.skippedCount}" else ""
            state.copy(
                peers = sortedPeers,
                progress = progress,
                checkedCount = current,
                availableCount = sortedPeers.count { it.available },
                unavailableCount = sortedPeers.count { !it.available },
                statusMessage = "Checking: $current/$total | OK: ${sortedPeers.count { it.available }}$skipInfo",
                hostResults = updatedHostResults
            )
        }
    }

    fun stopSearch() {
        logger.appendLogSync("INFO", "Stopping search")
        shouldStop = true
        discoveryJob?.cancel()

        _uiState.update { state ->
            state.copy(
                isSearching = false,
                progress = 0,
                statusMessage = "Stopped | OK: ${state.availableCount} | Fail: ${state.unavailableCount}" +
                    if (state.skippedCount > 0) " | Skip: ${state.skippedCount}" else ""
            )
        }
        persistCheckResults()  // Сохраняем результаты
    }

    fun togglePeerSelection(address: String) {
        _uiState.update { state ->
            val newSelection = if (state.selectedPeers.contains(address)) {
                state.selectedPeers - address
            } else {
                state.selectedPeers + address
            }
            state.copy(selectedPeers = newSelection)
        }
    }

    fun toggleFallbackSelection(address: String) {
        _uiState.update { state ->
            val newSelection = if (state.selectedFallbacks.contains(address)) {
                state.selectedFallbacks - address
            } else {
                state.selectedFallbacks + address
            }
            state.copy(selectedFallbacks = newSelection)
        }
    }

    fun selectAll() {
        _uiState.update { state ->
            state.copy(selectedPeers = state.peers.map { it.address }.toSet())
        }
    }

    fun deselectAll() {
        _uiState.update { state ->
            state.copy(selectedPeers = emptySet())
        }
    }

    fun toggleSelectAll() {
        _uiState.update { state ->
            if (state.selectedPeers.isNotEmpty() || state.selectedFallbacks.isNotEmpty()) {
                // Снимаем выбор со всех
                state.copy(selectedPeers = emptySet(), selectedFallbacks = emptySet())
            } else {
                // Выбираем все ЖИВЫЕ хосты (включая fallback ip1/2/3)
                val alivePeers = state.peers.filter { it.isAlive() }.map { it.address }.toSet()

                // Собираем живые fallback из hostResults
                val aliveFallbacks = mutableSetOf<String>()
                state.hostResults.values.forEach { results ->
                    results.filter { !it.isMainAddress && it.available }
                        .forEach { aliveFallbacks.add(it.target) }
                }

                state.copy(selectedPeers = alivePeers, selectedFallbacks = aliveFallbacks)
            }
        }
    }

    fun getSelectedAddresses(): List<String> {
        val state = _uiState.value
        // Объединяем основные и fallback выбранные адреса
        return (state.selectedPeers + state.selectedFallbacks).toList()
    }

    fun clearPeersList() {
        logger.appendLogSync("INFO", "Clearing peers list (all)")
        _uiState.update { it.copy(
            peers = emptyList(),
            selectedPeers = emptySet(),
            selectedFallbacks = emptySet(),
            selectedEndpoints = emptySet(),
            groupedHosts = emptyList(),
            availableCount = 0,
            unavailableCount = 0,
            progress = 0,
            checkedCount = 0,
            totalPeers = 0,
            statusMessage = "Ready",
            hostResults = emptyMap()
        )}
        // Удаляем persist файл при ручной очистке
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (checksFile.exists()) checksFile.delete()
            } catch (e: Exception) {
                logger.appendLogSync("WARN", "Failed to delete checks file: ${e.message}")
            }
        }
    }

    /**
     * Очистка только видимых (отфильтрованных) хостов.
     * Если фильтры All/All и ms=0 → очищает всё.
     * Иначе → удаляет только хосты, совпадающие с фильтром.
     */
    fun clearVisibleHosts(
        visibleGroupKeys: Set<String>,
        visiblePeerAddresses: Set<String>,
        isGroupedMode: Boolean
    ) {
        _uiState.update { state ->
            if (isGroupedMode) {
                // Удаляем группы по groupKey
                val remaining = state.groupedHosts.filter { it.groupKey !in visibleGroupKeys }
                // Удаляем результаты для удалённых групп
                val remainingResults = state.hostResults.filterKeys { hostString ->
                    val parsed = UrlParser.parse(hostString)
                    parsed?.groupingKey !in visibleGroupKeys
                }
                // Удаляем peers, принадлежащие удалённым группам
                val remainingPeers = state.peers.filter { peer ->
                    val parsed = UrlParser.parse(peer.address)
                    parsed?.groupingKey !in visibleGroupKeys
                }
                // Удаляем выбранные endpoints удалённых групп
                val removedEndpointUrls = state.groupedHosts
                    .filter { it.groupKey in visibleGroupKeys }
                    .flatMap { g -> g.endpoints.flatMap { ep -> ep.checkResults.map { it.fullUrl } } }
                    .toSet()
                val remainingEndpoints = state.selectedEndpoints - removedEndpointUrls

                val alive = remainingPeers.count { it.isAlive() }
                val dead = remainingPeers.count { !it.isAlive() && (it.pingMs != -1L || it.yggRttMs != -1L) }

                logger.appendLogSync("INFO", "Cleared ${visibleGroupKeys.size} filtered groups, ${remaining.size} remaining")
                state.copy(
                    peers = remainingPeers,
                    groupedHosts = remaining,
                    hostResults = remainingResults,
                    selectedEndpoints = remainingEndpoints,
                    availableCount = alive,
                    unavailableCount = dead,
                    totalPeers = remainingPeers.size,
                    checkedCount = alive + dead,
                    statusMessage = if (remaining.isEmpty()) "Ready" else "${remaining.size} groups"
                )
            } else {
                // Flat mode: удаляем по адресу
                val remainingPeers = state.peers.filter { it.address !in visiblePeerAddresses }
                val remainingResults = state.hostResults.filterKeys { it !in visiblePeerAddresses }
                val remainingSelected = state.selectedPeers - visiblePeerAddresses

                val alive = remainingPeers.count { it.isAlive() }
                val dead = remainingPeers.count { !it.isAlive() && (it.pingMs != -1L || it.yggRttMs != -1L) }

                logger.appendLogSync("INFO", "Cleared ${visiblePeerAddresses.size} filtered peers, ${remainingPeers.size} remaining")
                state.copy(
                    peers = remainingPeers,
                    hostResults = remainingResults,
                    selectedPeers = remainingSelected,
                    availableCount = alive,
                    unavailableCount = dead,
                    totalPeers = remainingPeers.size,
                    checkedCount = alive + dead,
                    statusMessage = if (remainingPeers.isEmpty()) "Ready" else "${remainingPeers.size} peers"
                )
            }
        }
    }

    fun getHostFallbackResults(hostAddress: String): List<HostCheckResult> {
        return _uiState.value.hostResults[hostAddress]?.filter { !it.isMainAddress } ?: emptyList()
    }

    // Получение списка сохраненных сессий
    suspend fun getSessions(): List<SessionManager.SavedSession> {
        return sessionManager.getSessions()
    }

    // Сохранение текущей сессии
    fun saveSession(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = sessionManager.saveSession(
                name = name,
                peers = _uiState.value.peers,
                hostResults = _uiState.value.hostResults
            )
            result.fold(
                onSuccess = { fileName ->
                    logger.appendLogSync("INFO", "Session saved: $fileName")
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(statusMessage = "Session saved: $name") }
                    }
                },
                onFailure = { error ->
                    logger.appendLogSync("ERROR", "Failed to save session: ${error.message}")
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(statusMessage = "Error: ${error.message}") }
                    }
                }
            )
        }
    }

    // Загрузка сессии
    fun loadSession(fileName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = sessionManager.loadSession(fileName)
            result.fold(
                onSuccess = { (peers, hostResults) ->
                    logger.appendLogSync("INFO", "Session loaded: ${peers.size} peers")
                    withContext(Dispatchers.Main) {
                        _uiState.update { state ->
                            state.copy(
                                peers = peers,
                                hostResults = hostResults,
                                totalPeers = peers.size,
                                checkedCount = peers.size,
                                availableCount = peers.count { it.isAlive() },
                                unavailableCount = peers.count { !it.isAlive() },
                                statusMessage = "Loaded ${peers.size} peers (${peers.count { it.isAlive() }} alive)"
                            )
                        }
                        // Перестраиваем группы для grouped view
                        updateGroupsWithResults()
                    }
                },
                onFailure = { error ->
                    logger.appendLogSync("ERROR", "Failed to load session: ${error.message}")
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(statusMessage = "Error: ${error.message}") }
                    }
                }
            )
        }
    }

    // Получить путь к файлу сессии для экспорта
    fun getSessionFilePath(fileName: String): File? {
        val file = File(File(context.filesDir, "sessions"), fileName)
        return if (file.exists()) file else null
    }

    // Импорт сессии из URI
    fun importSession(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw Exception("Cannot open file")
                val jsonText = inputStream.bufferedReader().readText()
                inputStream.close()

                // Валидация JSON
                val json = JSONObject(jsonText)
                val name = json.optString("name", "imported_${System.currentTimeMillis()}")
                val peersArray = json.optJSONArray("peers")
                    ?: throw Exception("Invalid session format: no peers")

                // Сохраняем в sessions директорию
                val sessionsDir = File(context.filesDir, "sessions").also { if (!it.exists()) it.mkdirs() }
                val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm", java.util.Locale.US)
                    .format(java.util.Date())
                val fileName = "${timestamp}_imported_${name.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(20)}.json"
                val outFile = File(sessionsDir, fileName)
                outFile.writeText(jsonText)

                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(
                        statusMessage = "Imported: $name (${peersArray.length()} peers)"
                    )}
                }
                logger.appendLogSync("INFO", "Session imported: $fileName (${peersArray.length()} peers)")
            } catch (e: Exception) {
                logger.appendLogSync("ERROR", "Import failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(statusMessage = "Import error: ${e.message}") }
                }
            }
        }
    }

    // Удаление сессии
    fun deleteSession(fileName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            sessionManager.deleteSession(fileName)
            logger.appendLogSync("INFO", "Session deleted: $fileName")
        }
    }

    // Генерация имени сессии по умолчанию
    fun generateSessionName(): String = sessionManager.generateDefaultName()

    // Tracert для живых хостов
    private var tracertJob: Job? = null

    fun runTracert() {
        if (_uiState.value.isTracertRunning) {
            // Отмена текущего Tracert
            tracertJob?.cancel()
            tracertJob = null
            _uiState.update { it.copy(isTracertRunning = false, statusMessage = "Tracert cancelled") }
            return
        }

        tracertJob = viewModelScope.launch {
            val alivePeers = _uiState.value.peers.filter { it.isAlive() }
            if (alivePeers.isEmpty()) {
                _uiState.update { it.copy(statusMessage = "No alive peers for tracert") }
                return@launch
            }

            _uiState.update { it.copy(
                isTracertRunning = true,
                tracertProgress = 0,
                statusMessage = "Tracert: 0/${alivePeers.size}"
            )}

            logger.appendLogSync("INFO", "Starting tracert for ${alivePeers.size} alive peers")

            val updatedPeers = _uiState.value.peers.toMutableList()
            var completed = 0

            withContext(Dispatchers.IO) {
                alivePeers.forEachIndexed { index, peer ->
                    if (!_uiState.value.isTracertRunning) return@forEachIndexed

                    // Извлекаем hostname из адреса
                    val host = extractHostFromAddress(peer.address)
                    if (host.isNotEmpty()) {
                        val hops = TracertUtil.getHopsFast(host)
                        val peerIndex = updatedPeers.indexOfFirst { it.normalizedKey == peer.normalizedKey }
                        if (peerIndex >= 0 && hops > 0) {
                            updatedPeers[peerIndex] = updatedPeers[peerIndex].copy(hops = hops)
                            logger.appendLogSync("DEBUG", "Tracert $host: $hops hops")
                        }
                    }

                    completed = index + 1
                    _uiState.update { it.copy(
                        tracertProgress = completed,
                        statusMessage = "Tracert: $completed/${alivePeers.size}",
                        peers = updatedPeers.toList()
                    )}
                }
            }

            _uiState.update { it.copy(
                isTracertRunning = false,
                statusMessage = "Tracert completed: $completed peers",
                peers = updatedPeers.toList()
            )}
            logger.appendLogSync("INFO", "Tracert completed for $completed peers")
        }
    }

    // Извлечение hostname (делегируем UrlParser)
    private fun extractHostFromAddress(address: String): String {
        return UrlParser.extractHostname(address) ?: ""
    }

    // === ГРУППИРОВКА ХОСТОВ ===

    /**
     * Группировка хостов по hostname
     */
    fun buildGroupedHosts(hosts: List<Host>): List<GroupedHost> {
        // Группируем по groupingKey (hostname без www)
        val groups = hosts.groupBy { host ->
            UrlParser.parse(host.hostString)?.groupingKey ?: host.address.lowercase()
        }

        return groups.map { (groupKey, hostList) ->
            buildSingleGroup(groupKey, hostList)
        }.sortedWith(
            compareByDescending<GroupedHost> { it.isAlive }
                .thenBy { it.getBestResultMs() }  // Сортировка по лучшему результату (меньше = лучше)
        )
    }

    private fun buildSingleGroup(groupKey: String, hosts: List<Host>): GroupedHost {
        val firstHost = hosts.first()

        // Собираем адреса (hst + dns ip1/2/3)
        val addresses = mutableListOf<HostAddress>()
        addresses.add(HostAddress(
            address = firstHost.address,
            type = AddressType.HST
        ))

        // DNS IP адреса (уникальные)
        val dnsIps = hosts.flatMap { host ->
            listOfNotNull(host.dnsIp1, host.dnsIp2, host.dnsIp3)
        }.distinct().filter { it != firstHost.address }

        dnsIps.forEachIndexed { index, ip ->
            val type = when (index) {
                0 -> AddressType.IP1
                1 -> AddressType.IP2
                else -> AddressType.IP3
            }
            if (index < 3) {
                addresses.add(HostAddress(address = ip, type = type))
            }
        }

        // Собираем endpoints (уникальные protocol:port)
        val endpoints = hosts.map { host ->
            val parsed = UrlParser.parse(host.hostString)
            HostEndpoint(
                protocol = parsed?.protocol ?: host.hostType,
                port = parsed?.port ?: host.port ?: 443,
                originalUrl = host.hostString
            )
        }.distinctBy { it.key }

        return GroupedHost(
            groupKey = groupKey,
            displayName = firstHost.address,
            region = firstHost.region,
            geoIp = firstHost.geoIp,
            source = firstHost.source,
            addresses = addresses,
            endpoints = endpoints
        )
    }

    /**
     * Обновление групп с результатами проверок
     */
    fun updateGroupsWithResults() {
        val hostResults = _uiState.value.hostResults
        val sortType = _uiState.value.sortType

        // Получаем хосты из БД для группировки
        viewModelScope.launch(Dispatchers.IO) {
            val dbHosts = database.hostDao().getAllHostsList()
            val groups = buildGroupedHosts(dbHosts)

            // Обновляем результаты в группах
            val updatedGroups = groups.map { group ->
                updateGroupWithResults(group, hostResults)
            }.sortedWith(
                compareByDescending<GroupedHost> { it.isAlive }
                    .thenBy { it.getBestResultForType(sortType.name) }  // Сортировка по выбранному типу
            )

            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(groupedHosts = updatedGroups) }
            }
        }
    }

    private fun updateGroupWithResults(
        group: GroupedHost,
        hostResults: Map<String, List<HostCheckResult>>
    ): GroupedHost {
        // Находим результаты для этой группы (все hostString с тем же hostname)
        val groupResults = hostResults.filterKeys { hostString ->
            val parsed = UrlParser.parse(hostString)
            parsed?.groupingKey == group.groupKey
        }
        val matchingResults = groupResults.values.flatten()

        if (matchingResults.isEmpty()) return group

        // Проверяем наличие ip0 (текущий резолвленный IP, отличающийся от кэшированных)
        val mainResult = matchingResults.firstOrNull { it.isMainAddress }
        val resolvedIp = mainResult?.resolvedIp
        val existingAddresses = group.addresses.map { it.address }

        // Добавляем ip0 если: есть resolvedIp и IP отличается от всех существующих адресов
        val addressesWithIp0 = if (resolvedIp != null && resolvedIp !in existingAddresses) {
            val ip0Address = HostAddress(
                address = resolvedIp,
                type = AddressType.IP0
            )
            val hstIndex = group.addresses.indexOfFirst { it.type == AddressType.HST }
            val result = group.addresses.toMutableList()
            result.add(hstIndex + 1, ip0Address)
            result
        } else {
            group.addresses
        }

        // Обновляем адреса с результатами общих проверок (Ping/P80/P443)
        // Для адресных проверок берём ЛЮБОЙ результат для данного адреса (они одинаковы для всех протоколов)
        val updatedAddresses = addressesWithIp0.map { addr ->
            // ip0 наследует результаты HST (тот же resolved IP)
            val targetAddr = if (addr.type == AddressType.IP0) {
                group.addresses.firstOrNull { it.type == AddressType.HST }?.address
            } else {
                addr.address
            }
            val result = matchingResults.find { r ->
                r.target == targetAddr || r.target == addr.address
            }
            if (result != null) {
                addr.copy(
                    pingResult = result.pingTime,
                    port80Result = result.port80,
                    port443Result = result.port443
                )
            } else addr
        }

        // Обновляем endpoints — КАЖДЫЙ endpoint использует результаты своего hostString
        val updatedEndpoints = group.endpoints.map { endpoint ->
            // Результаты именно для ЭТОГО endpoint (по его originalUrl)
            val endpointResultList = groupResults[endpoint.originalUrl] ?: emptyList()

            val checkResults = updatedAddresses.mapNotNull { addr ->
                // Для HST/ip0: ищем mainAddress результат
                // Для IP1/2/3: ищем fallback результат по IP
                val result = if (addr.type == AddressType.HST || addr.type == AddressType.IP0) {
                    endpointResultList.firstOrNull { it.isMainAddress }
                } else {
                    // Для DNS IP ищем fallback результат (target содержит IP)
                    endpointResultList.firstOrNull { !it.isMainAddress && it.target == addr.address }
                        ?: endpointResultList.firstOrNull { !it.isMainAddress && it.target.contains(addr.address) }
                }

                if (result != null) {
                    val fullUrl = UrlParser.replaceHost(endpoint.originalUrl, addr.address)
                    EndpointCheckResult(
                        addressType = addr.type,
                        address = addr.address,
                        yggRttMs = result.yggRtt,
                        portDefaultMs = result.portDefault,
                        fullUrl = fullUrl
                    )
                } else null
            }
            endpoint.copy(checkResults = checkResults)
        }

        return group.copy(
            addresses = updatedAddresses,
            endpoints = updatedEndpoints
        )
    }

    /**
     * Toggle выбора endpoint
     */
    fun toggleEndpointSelection(fullUrl: String) {
        _uiState.update { state ->
            val newSelection = if (state.selectedEndpoints.contains(fullUrl)) {
                state.selectedEndpoints - fullUrl
            } else {
                state.selectedEndpoints + fullUrl
            }
            state.copy(selectedEndpoints = newSelection)
        }
    }

    /**
     * Выбор/снятие выбора списка endpoints (для Select All)
     */
    fun selectAllEndpoints(urls: List<String>) {
        _uiState.update { state ->
            // Если все уже выбраны - снимаем выбор, иначе добавляем все
            val allSelected = urls.all { it in state.selectedEndpoints }
            val newSelection = if (allSelected) {
                state.selectedEndpoints - urls.toSet()
            } else {
                state.selectedEndpoints + urls.toSet()
            }
            state.copy(selectedEndpoints = newSelection)
        }
    }

    /**
     * Получение выбранных URL для копирования (из групп)
     */
    fun getSelectedEndpointUrls(): List<String> {
        return _uiState.value.selectedEndpoints.toList()
    }

    /**
     * Очистка выбора endpoints
     */
    fun clearEndpointSelection() {
        _uiState.update { it.copy(selectedEndpoints = emptySet()) }
    }

    /**
     * Синхронизация выбора при переключении из flat в grouped режим
     * Конвертирует выбранные address из flat режима в endpoint URLs для grouped режима
     */
    fun syncSelectionToGrouped() {
        val selectedPeers = _uiState.value.selectedPeers
        val selectedFallbacks = _uiState.value.selectedFallbacks

        if (selectedPeers.isEmpty() && selectedFallbacks.isEmpty()) return

        // Собираем все endpoint URLs которые соответствуют выбранным адресам
        val selectedEndpointUrls = mutableSetOf<String>()

        _uiState.value.groupedHosts.forEach { group ->
            group.endpoints.forEach { endpoint ->
                endpoint.checkResults.forEach { result ->
                    // Проверяем совпадение с выбранными адресами или fallback
                    if (selectedPeers.any { result.fullUrl.contains(it) } ||
                        selectedFallbacks.any { result.fullUrl.contains(it) }) {
                        selectedEndpointUrls.add(result.fullUrl)
                    }
                }
            }
        }

        _uiState.update { it.copy(selectedEndpoints = selectedEndpointUrls) }
        logger.appendLogSync("DEBUG", "Synced selection to grouped: ${selectedEndpointUrls.size} endpoints")
    }

    /**
     * Синхронизация выбора при переключении из grouped в flat режим
     * Конвертирует выбранные endpoint URLs из grouped режима в addresses для flat режима
     */
    fun syncSelectionToFlat() {
        val selectedEndpoints = _uiState.value.selectedEndpoints

        if (selectedEndpoints.isEmpty()) return

        // Извлекаем адреса из выбранных endpoints
        val selectedPeers = mutableSetOf<String>()
        val selectedFallbacks = mutableSetOf<String>()

        _uiState.value.groupedHosts.forEach { group ->
            group.endpoints.forEach { endpoint ->
                endpoint.checkResults.forEach { result ->
                    if (selectedEndpoints.contains(result.fullUrl)) {
                        // Проверяем основной адрес или fallback
                        if (result.addressType == AddressType.HST) {
                            // Ищем соответствующий peer по address
                            _uiState.value.peers.find { it.address.contains(result.address) }?.let {
                                selectedPeers.add(it.address)
                            }
                        } else {
                            // Fallback IP
                            selectedFallbacks.add(result.fullUrl)
                        }
                    }
                }
            }
        }

        _uiState.update { it.copy(
            selectedPeers = selectedPeers,
            selectedFallbacks = selectedFallbacks
        )}
        logger.appendLogSync("DEBUG", "Synced selection to flat: ${selectedPeers.size} peers, ${selectedFallbacks.size} fallbacks")
    }

    override fun onCleared() {
        super.onCleared()
        shouldStop = true
    }
}

class ChecksViewModelFactory(
    private val logger: PersistentLogger,
    private val context: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChecksViewModel::class.java)) {
            return ChecksViewModel(logger, context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
