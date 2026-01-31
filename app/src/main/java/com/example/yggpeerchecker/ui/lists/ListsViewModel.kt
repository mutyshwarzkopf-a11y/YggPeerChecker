package com.example.yggpeerchecker.ui.lists

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.yggpeerchecker.data.database.Host
import com.example.yggpeerchecker.data.repository.HostRepository
import com.example.yggpeerchecker.utils.PersistentLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ListsUiState(
    val isLoading: Boolean = false,
    val isListLoading: Boolean = false,   // Загрузка списка активна (можно отменить)
    val isDnsLoading: Boolean = false,    // DNS операция активна
    val isGeoIpLoading: Boolean = false,  // GeoIP операция активна
    val statusMessage: String = "Ready",
    val hosts: List<Host> = emptyList(),
    val totalCount: Int = 0,
    val yggCount: Int = 0,
    val sniCount: Int = 0,
    val resolvedCount: Int = 0,
    val lastSkipCount: Int = 0,           // Пропущено дубликатов при последней загрузке
    val lastError: String? = null
)

class ListsViewModel(
    private val repository: HostRepository,
    private val logger: PersistentLogger
) : ViewModel() {

    private val _uiState = MutableStateFlow(ListsUiState())
    val uiState: StateFlow<ListsUiState> = _uiState.asStateFlow()

    // Job для отмены операций
    private var loadJob: Job? = null      // Загрузка списков
    private var dnsJob: Job? = null
    private var geoIpJob: Job? = null

    init {
        logger.appendLogSync("INFO", "ListsViewModel initialized")
        // Подписка на Flow из репозитория
        viewModelScope.launch {
            repository.getAllHostsFlow().collect { hosts ->
                _uiState.update { it.copy(hosts = hosts, totalCount = hosts.size) }
            }
        }
        viewModelScope.launch {
            repository.getYggHostsCountFlow().collect { count ->
                _uiState.update { it.copy(yggCount = count) }
            }
        }
        viewModelScope.launch {
            repository.getSniHostsCountFlow().collect { count ->
                _uiState.update { it.copy(sniCount = count) }
            }
        }
        viewModelScope.launch {
            repository.getResolvedCountFlow().collect { count ->
                _uiState.update { it.copy(resolvedCount = count) }
            }
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            logger.appendLogSync("INFO", "Clearing all hosts")
            _uiState.update { it.copy(isLoading = true, statusMessage = "Clearing...") }
            try {
                repository.clearAll()
                logger.appendLogSync("INFO", "All hosts cleared")
                _uiState.update { it.copy(
                    isLoading = false,
                    statusMessage = "List cleared",
                    lastError = null
                )}
            } catch (e: Exception) {
                logger.appendLogSync("ERROR", "Clear all failed: ${e.message}")
                _uiState.update { it.copy(
                    isLoading = false,
                    statusMessage = "Error: ${e.message}",
                    lastError = e.message
                )}
            }
        }
    }

    // Отмена загрузки списка
    fun cancelLoad() {
        loadJob?.cancel()
        loadJob = null
        _uiState.update { it.copy(
            isLoading = false,
            isListLoading = false,
            statusMessage = "Load cancelled"
        )}
        logger.appendLogSync("INFO", "List loading cancelled")
    }

    fun loadYggNeilalexander() {
        logger.appendLogSync("INFO", "Loading peers from neilalexander")
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isListLoading = true, lastError = null) }
            val result = repository.loadYggPeers(
                HostRepository.SOURCE_NEILALEXANDER
            ) { status ->
                _uiState.update { it.copy(statusMessage = status) }
            }
            result.fold(
                onSuccess = { (added, skipped) ->
                    val msg = if (skipped > 0) "Loaded $added peers, skip: $skipped (neilalexander)"
                              else "Loaded $added peers (neilalexander)"
                    logger.appendLogSync("INFO", msg)
                    _uiState.update { it.copy(
                        isLoading = false,
                        isListLoading = false,
                        statusMessage = msg,
                        lastSkipCount = skipped
                    )}
                },
                onFailure = { error ->
                    logger.appendLogSync("ERROR", "Load neilalexander failed: ${error.message}")
                    _uiState.update { it.copy(
                        isLoading = false,
                        isListLoading = false,
                        statusMessage = "Error: ${error.message}",
                        lastError = error.message
                    )}
                }
            )
        }
    }

    fun loadYggLink() {
        logger.appendLogSync("INFO", "Loading peers from yggdrasil.link")
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isListLoading = true, lastError = null) }
            val result = repository.loadYggPeers(
                HostRepository.SOURCE_YGGDRASIL_LINK
            ) { status ->
                _uiState.update { it.copy(statusMessage = status) }
            }
            result.fold(
                onSuccess = { (added, skipped) ->
                    val msg = if (skipped > 0) "Loaded $added peers, skip: $skipped (yggdrasil.link)"
                              else "Loaded $added peers (yggdrasil.link)"
                    logger.appendLogSync("INFO", msg)
                    _uiState.update { it.copy(
                        isLoading = false,
                        isListLoading = false,
                        statusMessage = msg,
                        lastSkipCount = skipped
                    )}
                },
                onFailure = { error ->
                    logger.appendLogSync("ERROR", "Load yggdrasil.link failed: ${error.message}")
                    _uiState.update { it.copy(
                        isLoading = false,
                        isListLoading = false,
                        statusMessage = "Error: ${error.message}",
                        lastError = error.message
                    )}
                }
            )
        }
    }

    fun loadWhitelist() {
        logger.appendLogSync("INFO", "Loading whitelist")
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isListLoading = true, lastError = null) }
            val result = repository.loadWhitelist { status ->
                _uiState.update { it.copy(statusMessage = status) }
            }
            result.fold(
                onSuccess = { (added, skipped) ->
                    val msg = if (skipped > 0) "Loaded $added hosts, skip: $skipped (whitelist)"
                              else "Loaded $added hosts (whitelist)"
                    logger.appendLogSync("INFO", msg)
                    _uiState.update { it.copy(
                        isLoading = false,
                        isListLoading = false,
                        statusMessage = msg,
                        lastSkipCount = skipped
                    )}
                },
                onFailure = { error ->
                    logger.appendLogSync("ERROR", "Load whitelist failed: ${error.message}")
                    _uiState.update { it.copy(
                        isLoading = false,
                        isListLoading = false,
                        statusMessage = "Error: ${error.message}",
                        lastError = error.message
                    )}
                }
            )
        }
    }

    fun loadFromClipboard(text: String) {
        logger.appendLogSync("INFO", "Loading from clipboard (${text.length} chars)")
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Loading from clipboard...", lastError = null) }
            val result = repository.loadFromText(text, "clipboard")
            result.fold(
                onSuccess = { (added, skipped) ->
                    val msg = if (skipped > 0) "Loaded $added hosts, skip: $skipped (clipboard)"
                              else "Loaded $added hosts from clipboard"
                    logger.appendLogSync("INFO", msg)
                    _uiState.update { it.copy(
                        isLoading = false,
                        statusMessage = msg,
                        lastSkipCount = skipped
                    )}
                },
                onFailure = { error ->
                    logger.appendLogSync("ERROR", "Load clipboard failed: ${error.message}")
                    _uiState.update { it.copy(
                        isLoading = false,
                        statusMessage = "Error: ${error.message}",
                        lastError = error.message
                    )}
                }
            )
        }
    }

    fun loadFromFile(text: String, fileName: String) {
        logger.appendLogSync("INFO", "Loading from file: $fileName")
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Loading from file...", lastError = null) }
            val result = repository.loadFromText(text, "file:$fileName")
            result.fold(
                onSuccess = { (added, skipped) ->
                    val msg = if (skipped > 0) "Loaded $added hosts, skip: $skipped ($fileName)"
                              else "Loaded $added hosts from $fileName"
                    logger.appendLogSync("INFO", msg)
                    _uiState.update { it.copy(
                        isLoading = false,
                        statusMessage = msg,
                        lastSkipCount = skipped
                    )}
                },
                onFailure = { error ->
                    logger.appendLogSync("ERROR", "Load file failed: ${error.message}")
                    _uiState.update { it.copy(
                        isLoading = false,
                        statusMessage = "Error: ${error.message}",
                        lastError = error.message
                    )}
                }
            )
        }
    }

    fun loadVlessList() {
        logger.appendLogSync("INFO", "Loading vless list")
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isListLoading = true, lastError = null) }
            val result = repository.loadVlessList(HostRepository.SOURCE_VLESS_SUB) { status ->
                _uiState.update { it.copy(statusMessage = status) }
            }
            result.fold(
                onSuccess = { (added, skipped) ->
                    val msg = if (skipped > 0) "Loaded $added vless, skip: $skipped"
                              else "Loaded $added vless hosts"
                    logger.appendLogSync("INFO", msg)
                    _uiState.update { it.copy(isLoading = false, isListLoading = false, statusMessage = msg, lastSkipCount = skipped) }
                },
                onFailure = { error ->
                    logger.appendLogSync("ERROR", "Load vless failed: ${error.message}")
                    _uiState.update { it.copy(isLoading = false, isListLoading = false, statusMessage = "Error: ${error.message}", lastError = error.message) }
                }
            )
        }
    }

    fun loadMiniWhite() {
        logger.appendLogSync("INFO", "Loading Mini White list")
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isListLoading = true, lastError = null) }
            val result = repository.loadMiniList(HostRepository.SOURCE_MINI_WHITE, "Mini White") { status ->
                _uiState.update { it.copy(statusMessage = status) }
            }
            result.fold(
                onSuccess = { (added, skipped) ->
                    val msg = if (skipped > 0) "Loaded $added hosts, skip: $skipped (Mini White)"
                              else "Loaded $added hosts (Mini White)"
                    logger.appendLogSync("INFO", msg)
                    _uiState.update { it.copy(isLoading = false, isListLoading = false, statusMessage = msg, lastSkipCount = skipped) }
                },
                onFailure = { error ->
                    logger.appendLogSync("ERROR", "Load Mini White failed: ${error.message}")
                    _uiState.update { it.copy(isLoading = false, isListLoading = false, statusMessage = "Error: ${error.message}", lastError = error.message) }
                }
            )
        }
    }

    fun loadMiniBlack() {
        logger.appendLogSync("INFO", "Loading Mini Black list")
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isListLoading = true, lastError = null) }
            val result = repository.loadMiniList(HostRepository.SOURCE_MINI_BLACK, "Mini Black") { status ->
                _uiState.update { it.copy(statusMessage = status) }
            }
            result.fold(
                onSuccess = { (added, skipped) ->
                    val msg = if (skipped > 0) "Loaded $added hosts, skip: $skipped (Mini Black)"
                              else "Loaded $added hosts (Mini Black)"
                    logger.appendLogSync("INFO", msg)
                    _uiState.update { it.copy(isLoading = false, isListLoading = false, statusMessage = msg, lastSkipCount = skipped) }
                },
                onFailure = { error ->
                    logger.appendLogSync("ERROR", "Load Mini Black failed: ${error.message}")
                    _uiState.update { it.copy(isLoading = false, isListLoading = false, statusMessage = "Error: ${error.message}", lastError = error.message) }
                }
            )
        }
    }

    fun fillDnsIps() {
        // Если уже выполняется - отменяем
        if (_uiState.value.isDnsLoading) {
            cancelDns()
            return
        }

        logger.appendLogSync("INFO", "Starting DNS fill")
        dnsJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isDnsLoading = true, lastError = null) }
            val result = repository.fillDnsIps { current, total ->
                _uiState.update { it.copy(statusMessage = "DNS resolving: $current/$total") }
            }
            result.fold(
                onSuccess = { (resolved, skipped) ->
                    val msg = if (skipped > 0) {
                        "Resolved $resolved hosts (skipped: $skipped)"
                    } else {
                        "Resolved $resolved hosts"
                    }
                    logger.appendLogSync("INFO", "DNS fill complete: $msg")
                    _uiState.update { it.copy(
                        isLoading = false,
                        isDnsLoading = false,
                        statusMessage = msg
                    )}
                },
                onFailure = { error ->
                    logger.appendLogSync("ERROR", "DNS fill failed: ${error.message}")
                    _uiState.update { it.copy(
                        isLoading = false,
                        isDnsLoading = false,
                        statusMessage = "Error: ${error.message}",
                        lastError = error.message
                    )}
                }
            )
        }
    }

    // Отмена DNS операции
    fun cancelDns() {
        dnsJob?.cancel()
        dnsJob = null
        _uiState.update { it.copy(
            isLoading = false,
            isDnsLoading = false,
            statusMessage = "DNS cancelled"
        )}
        logger.appendLogSync("INFO", "DNS operation cancelled")
    }

    fun clearDns() {
        logger.appendLogSync("INFO", "Clearing all DNS data")
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Clearing DNS...", lastError = null) }
            try {
                repository.clearDns()
                logger.appendLogSync("INFO", "DNS data cleared")
                _uiState.update { it.copy(
                    isLoading = false,
                    statusMessage = "DNS cleared"
                )}
            } catch (e: Exception) {
                logger.appendLogSync("ERROR", "Clear DNS failed: ${e.message}")
                _uiState.update { it.copy(
                    isLoading = false,
                    statusMessage = "Error: ${e.message}",
                    lastError = e.message
                )}
            }
        }
    }


    // Очистка DNS по списку ID (для Clear DNS по фильтру)
    fun clearDnsByIds(ids: List<String>) {
        logger.appendLogSync("INFO", "Clearing DNS for ${ids.size} hosts")
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Clearing DNS for ${ids.size} hosts...", lastError = null) }
            try {
                repository.clearDnsByIds(ids)
                logger.appendLogSync("INFO", "DNS cleared for ${ids.size} hosts")
                _uiState.update { it.copy(
                    isLoading = false,
                    statusMessage = "DNS cleared for ${ids.size} hosts"
                )}
            } catch (e: Exception) {
                logger.appendLogSync("ERROR", "Clear DNS by IDs failed: ${e.message}")
                _uiState.update { it.copy(
                    isLoading = false,
                    statusMessage = "Error: ${e.message}",
                    lastError = e.message
                )}
            }
        }
    }


    // Удаление видимых по фильтру хостов
    fun clearVisibleHosts(hostIds: List<String>) {
        logger.appendLogSync("INFO", "Deleting ${hostIds.size} visible hosts")
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Deleting ${hostIds.size} hosts...", lastError = null) }
            try {
                repository.deleteByIds(hostIds)
                logger.appendLogSync("INFO", "Deleted ${hostIds.size} hosts")
                _uiState.update { it.copy(
                    isLoading = false,
                    statusMessage = "Deleted ${hostIds.size} hosts"
                )}
            } catch (e: Exception) {
                logger.appendLogSync("ERROR", "Delete visible hosts failed: ${e.message}")
                _uiState.update { it.copy(
                    isLoading = false,
                    statusMessage = "Error: ${e.message}",
                    lastError = e.message
                )}
            }
        }
    }


    // GeoIP резолвинг для хостов
    fun fillGeoIp(hostIds: List<String>? = null) {
        // Если уже выполняется - отменяем
        if (_uiState.value.isGeoIpLoading) {
            cancelGeoIp()
            return
        }

        logger.appendLogSync("INFO", "Starting GeoIP fill${if (hostIds != null) " (${hostIds.size} hosts)" else ""}")
        geoIpJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isGeoIpLoading = true, lastError = null) }
            val result = repository.fillGeoIp(hostIds) { current, total ->
                _uiState.update { it.copy(statusMessage = "GeoIP resolving: $current/$total") }
            }
            result.fold(
                onSuccess = { (resolved, skipped) ->
                    val msg = if (skipped > 0) {
                        "GeoIP: $resolved resolved (skipped: $skipped)"
                    } else {
                        "GeoIP: $resolved resolved"
                    }
                    logger.appendLogSync("INFO", "GeoIP fill complete: $msg")
                    _uiState.update { it.copy(
                        isLoading = false,
                        isGeoIpLoading = false,
                        statusMessage = msg
                    )}
                },
                onFailure = { error ->
                    logger.appendLogSync("ERROR", "GeoIP fill failed: ${error.message}")
                    _uiState.update { it.copy(
                        isLoading = false,
                        isGeoIpLoading = false,
                        statusMessage = "Error: ${error.message}",
                        lastError = error.message
                    )}
                }
            )
        }
    }

    // Отмена GeoIP операции
    fun cancelGeoIp() {
        geoIpJob?.cancel()
        geoIpJob = null
        _uiState.update { it.copy(
            isLoading = false,
            isGeoIpLoading = false,
            statusMessage = "GeoIP cancelled"
        )}
        logger.appendLogSync("INFO", "GeoIP operation cancelled")
    }

    // Очистка GeoIP по списку ID (для фильтра)
    fun clearGeoIpByIds(hostIds: List<String>) {
        logger.appendLogSync("INFO", "Clearing GeoIP for ${hostIds.size} hosts")
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Clearing GeoIP for ${hostIds.size} hosts...", lastError = null) }
            try {
                repository.clearGeoIpByIds(hostIds)
                logger.appendLogSync("INFO", "GeoIP cleared for ${hostIds.size} hosts")
                _uiState.update { it.copy(
                    isLoading = false,
                    statusMessage = "GeoIP cleared for ${hostIds.size} hosts"
                )}
            } catch (e: Exception) {
                logger.appendLogSync("ERROR", "Clear GeoIP by IDs failed: ${e.message}")
                _uiState.update { it.copy(
                    isLoading = false,
                    statusMessage = "Error: ${e.message}",
                    lastError = e.message
                )}
            }
        }
    }


    // Очистка DNS и GeoIP по списку ID (комбинированная)
    fun clearDnsAndGeoIpByIds(hostIds: List<String>) {
        logger.appendLogSync("INFO", "Clearing DNS & GeoIP for ${hostIds.size} hosts")
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Clearing DNS & GeoIP for ${hostIds.size} hosts...", lastError = null) }
            try {
                repository.clearDnsByIds(hostIds)
                repository.clearGeoIpByIds(hostIds)
                logger.appendLogSync("INFO", "DNS & GeoIP cleared for ${hostIds.size} hosts")
                _uiState.update { it.copy(
                    isLoading = false,
                    statusMessage = "DNS & GeoIP cleared for ${hostIds.size} hosts"
                )}
            } catch (e: Exception) {
                logger.appendLogSync("ERROR", "Clear DNS & GeoIP failed: ${e.message}")
                _uiState.update { it.copy(
                    isLoading = false,
                    statusMessage = "Error: ${e.message}",
                    lastError = e.message
                )}
            }
        }
    }
}

class ListsViewModelFactory(
    private val context: Context,
    private val logger: PersistentLogger
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ListsViewModel::class.java)) {
            val repository = HostRepository(context, logger)
            return ListsViewModel(repository, logger) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
