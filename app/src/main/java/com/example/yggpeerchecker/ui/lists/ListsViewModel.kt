package com.example.yggpeerchecker.ui.lists

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.yggpeerchecker.data.database.Host
import com.example.yggpeerchecker.data.repository.HostRepository
import com.example.yggpeerchecker.utils.PersistentLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ListsUiState(
    val isLoading: Boolean = false,
    val statusMessage: String = "Ready",
    val hosts: List<Host> = emptyList(),
    val totalCount: Int = 0,
    val yggCount: Int = 0,
    val sniCount: Int = 0,
    val resolvedCount: Int = 0,
    val lastError: String? = null
)

class ListsViewModel(
    private val repository: HostRepository,
    private val logger: PersistentLogger
) : ViewModel() {

    private val _uiState = MutableStateFlow(ListsUiState())
    val uiState: StateFlow<ListsUiState> = _uiState.asStateFlow()

    init {
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
            _uiState.update { it.copy(isLoading = true, statusMessage = "Clearing...") }
            try {
                repository.clearAll()
                _uiState.update { it.copy(
                    isLoading = false,
                    statusMessage = "List cleared",
                    lastError = null
                )}
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isLoading = false,
                    statusMessage = "Error: ${e.message}",
                    lastError = e.message
                )}
            }
        }
    }

    fun loadYggNeilalexander() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, lastError = null) }
            val result = repository.loadYggPeers(
                HostRepository.SOURCE_NEILALEXANDER
            ) { status ->
                _uiState.update { it.copy(statusMessage = status) }
            }
            result.fold(
                onSuccess = { count ->
                    _uiState.update { it.copy(
                        isLoading = false,
                        statusMessage = "Loaded $count peers (neilalexander)"
                    )}
                },
                onFailure = { error ->
                    _uiState.update { it.copy(
                        isLoading = false,
                        statusMessage = "Error: ${error.message}",
                        lastError = error.message
                    )}
                }
            )
        }
    }

    fun loadYggLink() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, lastError = null) }
            val result = repository.loadYggPeers(
                HostRepository.SOURCE_YGGDRASIL_LINK
            ) { status ->
                _uiState.update { it.copy(statusMessage = status) }
            }
            result.fold(
                onSuccess = { count ->
                    _uiState.update { it.copy(
                        isLoading = false,
                        statusMessage = "Loaded $count peers (yggdrasil.link)"
                    )}
                },
                onFailure = { error ->
                    _uiState.update { it.copy(
                        isLoading = false,
                        statusMessage = "Error: ${error.message}",
                        lastError = error.message
                    )}
                }
            )
        }
    }

    fun loadWhitelist() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, lastError = null) }
            val result = repository.loadWhitelist { status ->
                _uiState.update { it.copy(statusMessage = status) }
            }
            result.fold(
                onSuccess = { count ->
                    _uiState.update { it.copy(
                        isLoading = false,
                        statusMessage = "Loaded $count hosts (whitelist)"
                    )}
                },
                onFailure = { error ->
                    _uiState.update { it.copy(
                        isLoading = false,
                        statusMessage = "Error: ${error.message}",
                        lastError = error.message
                    )}
                }
            )
        }
    }

    fun loadFromClipboard(text: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Loading from clipboard...", lastError = null) }
            val result = repository.loadFromText(text, "clipboard")
            result.fold(
                onSuccess = { count ->
                    _uiState.update { it.copy(
                        isLoading = false,
                        statusMessage = "Loaded $count hosts from clipboard"
                    )}
                },
                onFailure = { error ->
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
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Loading from file...", lastError = null) }
            val result = repository.loadFromText(text, "file:$fileName")
            result.fold(
                onSuccess = { count ->
                    _uiState.update { it.copy(
                        isLoading = false,
                        statusMessage = "Loaded $count hosts from $fileName"
                    )}
                },
                onFailure = { error ->
                    _uiState.update { it.copy(
                        isLoading = false,
                        statusMessage = "Error: ${error.message}",
                        lastError = error.message
                    )}
                }
            )
        }
    }

    fun fillDnsIps() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, lastError = null) }
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
                    _uiState.update { it.copy(
                        isLoading = false,
                        statusMessage = msg
                    )}
                },
                onFailure = { error ->
                    _uiState.update { it.copy(
                        isLoading = false,
                        statusMessage = "Error: ${error.message}",
                        lastError = error.message
                    )}
                }
            )
        }
    }

    fun clearDns() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Clearing DNS...", lastError = null) }
            try {
                repository.clearDns()
                _uiState.update { it.copy(
                    isLoading = false,
                    statusMessage = "DNS cleared"
                )}
            } catch (e: Exception) {
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
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Clearing DNS for ${ids.size} hosts...", lastError = null) }
            try {
                repository.clearDnsByIds(ids)
                _uiState.update { it.copy(
                    isLoading = false,
                    statusMessage = "DNS cleared for ${ids.size} hosts"
                )}
            } catch (e: Exception) {
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
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Deleting ${hostIds.size} hosts...", lastError = null) }
            try {
                repository.deleteByIds(hostIds)
                _uiState.update { it.copy(
                    isLoading = false,
                    statusMessage = "Deleted ${hostIds.size} hosts"
                )}
            } catch (e: Exception) {
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
