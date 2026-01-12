package com.example.yggpeerchecker.ui.checks

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.yggpeerchecker.data.DiscoveredPeer
import com.example.yggpeerchecker.data.database.AppDatabase
import com.example.yggpeerchecker.data.database.Host
import com.example.yggpeerchecker.utils.NetworkUtil
import com.example.yggpeerchecker.utils.PersistentLogger
import com.example.yggpeerchecker.utils.PingUtil
import com.example.yggpeerchecker.utils.SniChecker
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
import org.json.JSONObject
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
    val error: String = ""
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
            error = if (available || other.available) "" else error
        )
    }
}

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
    val fastMode: Boolean = false,
    val alwaysCheckDnsIps: Boolean = true,  // Всегда проверять DNS IP (default: ON)
    val typeFilter: String = "All",
    // Сортировка и фильтр
    val sortType: CheckType = CheckType.PING,  // По какому типу сортировать
    val filterMs: Int = 0,  // 0 = выкл, >0 = фильтр по ms
    // Статистика по хостам
    val dbHostsCount: Int = 0,
    val dbYggCount: Int = 0,
    val dbSniCount: Int = 0,
    // Fallback результаты
    val hostResults: Map<String, List<HostCheckResult>> = emptyMap()
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

    private fun getConcurrentStreams(): Int {
        val prefs = context.getSharedPreferences("ygg_prefs", Context.MODE_PRIVATE)
        return prefs.getInt("concurrent_streams", 10)
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
            logger.appendLogSync("INFO", "ChecksViewModel initialized")
            loadDbStats()
        } catch (e: Exception) {
            logger.appendLogSync("ERROR", "Failed to create manager: ${e.message}")
        }
    }

    private fun loadDbStats() {
        viewModelScope.launch(Dispatchers.IO) {
            val total = database.hostDao().getHostCount()
            val ygg = database.hostDao().getYggHostsCountSync()
            val sni = database.hostDao().getSniHostsCountSync()
            _uiState.update { it.copy(
                dbHostsCount = total,
                dbYggCount = ygg,
                dbSniCount = sni
            )}
        }
    }

    fun toggleCheckType(type: CheckType) {
        _uiState.update { state ->
            val newTypes = if (state.enabledCheckTypes.contains(type)) {
                state.enabledCheckTypes - type
            } else {
                state.enabledCheckTypes + type
            }
            state.copy(enabledCheckTypes = newTypes)
        }
        logger.appendLogSync("DEBUG", "Check types changed: ${_uiState.value.enabledCheckTypes}")
    }

    fun toggleFastMode() {
        _uiState.update { it.copy(fastMode = !it.fastMode) }
        logger.appendLogSync("DEBUG", "Fast mode: ${_uiState.value.fastMode}")
    }


    fun toggleAlwaysCheckDnsIps() {
        _uiState.update { it.copy(alwaysCheckDnsIps = !it.alwaysCheckDnsIps) }
        logger.appendLogSync("DEBUG", "Always Check DNS IPs: ${_uiState.value.alwaysCheckDnsIps}")
    }

    fun setSortType(type: CheckType) {
        _uiState.update { it.copy(sortType = type) }
        // Пересортировать peers
        sortPeers()
    }

    fun setFilterMs(ms: Int) {
        _uiState.update { it.copy(filterMs = ms) }
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

    // Фильтрация хостов - пропускаем уже проверенные
    private fun filterHostsForCheck(allHosts: List<Host>): List<Host> {
        val existingPeers = _uiState.value.peers.associateBy { it.address }
        val existingResults = _uiState.value.hostResults
        val enabledTypes = _uiState.value.enabledCheckTypes

        return allHosts.filter { host ->
            val existingPeer = existingPeers[host.hostString]
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

    private fun startDbDiscovery(hosts: List<Host>, skipped: Int = 0) {
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
            logger.appendLogSync("INFO", "Starting discovery: ${hosts.size} hosts, $concurrentStreams streams, $skipped skipped")
            logger.appendLogSync("INFO", "Check types: ${_uiState.value.enabledCheckTypes}")
            logger.appendLogSync("INFO", "Fast mode: ${_uiState.value.fastMode}")

            val skippedInfo = if (skipped > 0) " | Skipped: $skipped" else ""
            _uiState.update { it.copy(
                isSearching = true,
                progress = 0,
                totalPeers = hosts.size,
                checkedCount = 0,
                skippedCount = skipped,
                statusMessage = "Checking ${hosts.size} hosts...$skippedInfo"
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
                        val results = checkHostWithFallback(host, existingResult)
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
                val skippedInfo = if (state.skippedCount > 0) " | Skipped: ${state.skippedCount}" else ""
                state.copy(
                    isSearching = false,
                    statusMessage = "Done | OK: ${state.availableCount} | Fail: ${state.unavailableCount}$skippedInfo"
                )
            }
            logger.appendLogSync("INFO", "Discovery completed")
        }
    }

    // Проверка с учетом предыдущих результатов и fallback
    private suspend fun checkHostWithFallback(host: Host, existingResult: HostCheckResult?): List<HostCheckResult> {
        val results = mutableListOf<HostCheckResult>()
        val enabledTypes = _uiState.value.enabledCheckTypes
        val fastMode = _uiState.value.fastMode
        val alwaysCheckDns = _uiState.value.alwaysCheckDnsIps

        // Определяем какие проверки нужны
        val typesToCheck = if (existingResult != null) {
            enabledTypes.filter { existingResult.needsCheck(it) }.toSet()
        } else {
            enabledTypes
        }

        // Основной адрес
        val mainResult = checkSingleTarget(
            target = host.address,
            port = host.port,
            hostType = host.hostType,
            hostString = host.hostString,
            enabledTypes = typesToCheck,
            fastMode = fastMode,
            isMainAddress = true
        )

        // Объединяем с предыдущими результатами
        val mergedMain = existingResult?.merge(mainResult) ?: mainResult
        results.add(mergedMain)

        // DNS IP адреса
        val dnsIps = listOfNotNull(host.dnsIp1, host.dnsIp2, host.dnsIp3)
            .filter { it != host.address && it.isNotEmpty() }

        // Проверяем DNS IP если:
        // 1. alwaysCheckDns включен - всегда проверяем
        // 2. Основной адрес недоступен - fallback
        val shouldCheckDns = alwaysCheckDns || !mergedMain.available

        if (shouldCheckDns && dnsIps.isNotEmpty()) {
            // Для DNS IP проверяем ВСЕ типы включая YGG_RTT - IP подставляется в URL
            for (dnsIp in dnsIps) {
                if (shouldStop) break

                val dnsResult = checkSingleTarget(
                    target = dnsIp,
                    port = host.port,
                    hostType = host.hostType,
                    hostString = buildFallbackHostString(host.hostString, dnsIp),
                    enabledTypes = typesToCheck,
                    fastMode = fastMode,
                    isMainAddress = false
                )
                results.add(dnsResult)

                // В fastMode останавливаемся при первом успехе (только если не alwaysCheckDns)
                if (fastMode && !alwaysCheckDns && dnsResult.available) break
            }
        }

        return results
    }

    private fun buildFallbackHostString(originalHostString: String, ip: String): String {
        return try {
            // Сохраняем протокол и параметры, заменяем только хост
            val regex = Regex("(\\w+://)([^:/\\[\\]]+|\\[[^\\]]+\\])(.*)")
            regex.replace(originalHostString) { match ->
                val proto = match.groupValues[1]
                val rest = match.groupValues[3]
                // Для IPv6 оборачиваем в скобки
                val formattedIp = if (ip.contains(":") && !ip.startsWith("[")) "[$ip]" else ip
                "$proto$formattedIp$rest"
            }
        } catch (e: Exception) {
            ip
        }
    }

    private suspend fun checkSingleTarget(
        target: String,
        port: Int?,
        hostType: String,
        hostString: String,
        enabledTypes: Set<CheckType>,
        fastMode: Boolean,
        isMainAddress: Boolean
    ): HostCheckResult {
        var pingTime: Long = -1
        var yggRtt: Long = -1
        var portDefault: Long = -1
        var port80: Long = -1
        var port443: Long = -1
        var available = false
        var error = ""

        try {
            // PING
            if (enabledTypes.contains(CheckType.PING)) {
                logger.appendLogSync("DEBUG", "Ping check: $target")
                val ping = PingUtil.ping(target, 3000)
                if (ping >= 0) {
                    pingTime = ping.toLong()
                    available = true
                    logger.appendLogSync("DEBUG", "Ping OK: $target = ${pingTime}ms")
                    if (fastMode) return HostCheckResult(target, isMainAddress, pingTime, yggRtt, portDefault, port80, port443, true)
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
                    if (fastMode) return HostCheckResult(target, isMainAddress, pingTime, yggRtt, portDefault, port80, port443, true)
                }
            }

            // Port default
            if (enabledTypes.contains(CheckType.PORT_DEFAULT) && port != null && port > 0) {
                logger.appendLogSync("DEBUG", "Port $port check: $target")
                val result = SniChecker.checkPort(target, port, 3000)
                if (result.available) {
                    portDefault = result.responseTime
                    available = true
                    logger.appendLogSync("DEBUG", "Port $port OK: $target = ${portDefault}ms")
                    if (fastMode) return HostCheckResult(target, isMainAddress, pingTime, yggRtt, portDefault, port80, port443, true)
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
                    if (fastMode) return HostCheckResult(target, isMainAddress, pingTime, yggRtt, portDefault, port80, port443, true)
                }
            }

            // Port 443
            if (enabledTypes.contains(CheckType.PORT_443)) {
                logger.appendLogSync("DEBUG", "Port 443 check: $target")
                val result = SniChecker.checkPort(target, 443, 3000)
                if (result.available) {
                    port443 = result.responseTime
                    available = true
                    logger.appendLogSync("DEBUG", "Port 443 OK: $target = ${port443}ms")
                    if (fastMode) return HostCheckResult(target, isMainAddress, pingTime, yggRtt, portDefault, port80, port443, true)
                }
            }

        } catch (e: Exception) {
            error = e.message ?: "Check failed"
            logger.appendLogSync("ERROR", "Check failed $target: $error")
        }

        if (!available) error = "All checks failed"

        return HostCheckResult(target, isMainAddress, pingTime, yggRtt, portDefault, port80, port443, available, error)
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

    private fun addCheckResult(host: Host, results: List<HostCheckResult>, current: Int, total: Int) {
        val mainResult = results.firstOrNull { it.isMainAddress } ?: return
        val bestResult = results.firstOrNull { it.available } ?: mainResult
        val enabledTypes = _uiState.value.enabledCheckTypes

        // Конвертируем результат в значение: -1 = не проверялось, -2 = failed, >=0 = ms
        fun checkValue(value: Long, type: CheckType): Long = when {
            !enabledTypes.contains(type) -> -1  // не проверялось
            value >= 0 -> value                  // успех
            else -> -2                           // failed
        }

        val peer = DiscoveredPeer(
            address = host.hostString,
            protocol = host.hostType,
            region = host.region ?: extractRegion(host.source),
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
            // Индивидуальные результаты
            pingMs = checkValue(bestResult.pingTime, CheckType.PING),
            yggRttMs = checkValue(bestResult.yggRtt, CheckType.YGG_RTT),
            portDefaultMs = checkValue(bestResult.portDefault, CheckType.PORT_DEFAULT),
            port80Ms = checkValue(bestResult.port80, CheckType.PORT_80),
            port443Ms = checkValue(bestResult.port443, CheckType.PORT_443)
        )

        _uiState.update { state ->
            // Обновляем существующий или добавляем новый
            val updatedPeers = state.peers.toMutableList()
            val existingIndex = updatedPeers.indexOfFirst { it.address == peer.address }
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

            state.copy(
                peers = sortedPeers,
                progress = progress,
                checkedCount = current,
                availableCount = sortedPeers.count { it.available },
                unavailableCount = sortedPeers.count { !it.available },
                statusMessage = "Checking: $current/$total | Available: ${sortedPeers.count { it.available }}",
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
                statusMessage = "Stopped | Available: ${state.availableCount} | Unavailable: ${state.unavailableCount}"
            )
        }
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
            if (state.selectedPeers.isNotEmpty()) {
                state.copy(selectedPeers = emptySet())
            } else {
                state.copy(selectedPeers = state.peers.map { it.address }.toSet())
            }
        }
    }

    fun getSelectedAddresses(): List<String> {
        val state = _uiState.value
        // Объединяем основные и fallback выбранные адреса
        return (state.selectedPeers + state.selectedFallbacks).toList()
    }

    fun clearPeersList() {
        logger.appendLogSync("INFO", "Clearing peers list")
        _uiState.update { it.copy(
            peers = emptyList(),
            selectedPeers = emptySet(),
            selectedFallbacks = emptySet(),
            availableCount = 0,
            unavailableCount = 0,
            progress = 0,
            checkedCount = 0,
            totalPeers = 0,
            statusMessage = "Ready",
            hostResults = emptyMap()
        )}
    }

    fun getHostFallbackResults(hostAddress: String): List<HostCheckResult> {
        return _uiState.value.hostResults[hostAddress]?.filter { !it.isMainAddress } ?: emptyList()
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
