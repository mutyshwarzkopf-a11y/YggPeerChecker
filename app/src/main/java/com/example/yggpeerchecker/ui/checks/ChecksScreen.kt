package com.example.yggpeerchecker.ui.checks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.yggpeerchecker.data.DiscoveredPeer
import com.example.yggpeerchecker.data.GroupedHost
import com.example.yggpeerchecker.data.SessionManager
import com.example.yggpeerchecker.ui.theme.OnlineGreen
import com.example.yggpeerchecker.ui.theme.OfflineRed
import com.example.yggpeerchecker.ui.theme.WarningOrange
import com.example.yggpeerchecker.ui.theme.WarningYellow
import com.example.yggpeerchecker.utils.PersistentLogger
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChecksScreen(
    modifier: Modifier = Modifier,
    logger: PersistentLogger? = null
) {
    val context = LocalContext.current
    val actualLogger = logger ?: remember { PersistentLogger(context) }
    val viewModel: ChecksViewModel = viewModel(
        factory = ChecksViewModelFactory(actualLogger, context)
    )

    val uiState by viewModel.uiState.collectAsState()
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showProbingMenu by remember { mutableStateOf(false) }
    var showSessionsDialog by remember { mutableStateOf(false) }
    var useGroupedView by remember { mutableStateOf(false) }  // Переключатель вида: группы/плоский список
    var showClearMenu by remember { mutableStateOf(false) }
    // Детальный диалог для пира
    var detailPeer by remember { mutableStateOf<DiscoveredPeer?>(null) }
    var detailFallbacks by remember { mutableStateOf<List<HostCheckResult>>(emptyList()) }
    val scope = rememberCoroutineScope()

    // File picker для импорта сессий
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importSession(uri)
            Toast.makeText(context, "Importing session...", Toast.LENGTH_SHORT).show()
        }
    }

    // Сохранение сессии в выбранную папку
    var sessionFileToSave by remember { mutableStateOf<String?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null && sessionFileToSave != null) {
            val sourceFile = viewModel.getSessionFilePath(sessionFileToSave!!)
            if (sourceFile != null) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        sourceFile.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                    Toast.makeText(context, "Session saved", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Save error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        sessionFileToSave = null
    }

    // Обновляем группы когда поиск завершён, переключаемся в grouped view, или меняется сортировка
    LaunchedEffect(uiState.isSearching, useGroupedView, uiState.sortType) {
        if (!uiState.isSearching && uiState.peers.isNotEmpty() && useGroupedView) {
            viewModel.updateGroupsWithResults()
        }
    }

    // Синхронизация выбора при переключении между режимами
    LaunchedEffect(useGroupedView) {
        if (useGroupedView) {
            // Переключились в grouped режим - конвертируем выбор из flat
            viewModel.syncSelectionToGrouped()
        } else {
            // Переключились в flat режим - конвертируем выбор из grouped
            viewModel.syncSelectionToFlat()
        }
    }

    // Применяем фильтры и сортировки к списку результатов
    val filteredPeers = remember(uiState.peers, uiState.typeFilter, uiState.sourceFilter, uiState.sortType, uiState.filterMs, uiState.searchQuery) {
        var result = uiState.peers

        // Быстрый текстовый фильтр
        if (uiState.searchQuery.isNotBlank()) {
            val q = uiState.searchQuery.lowercase()
            result = result.filter { peer ->
                peer.address.lowercase().contains(q) ||
                peer.protocol.lowercase().contains(q) ||
                peer.sourceShort.lowercase().contains(q) ||
                peer.source.lowercase().contains(q) ||
                peer.region.lowercase().contains(q) ||
                peer.geoIp.lowercase().contains(q) ||
                // Поиск среди fallback IP
                (uiState.hostResults[peer.address]?.any { r ->
                    r.target.lowercase().contains(q) ||
                    (r.resolvedIp?.lowercase()?.contains(q) == true)
                } == true)
            }
        }

        // Фильтр по типу (для отображения)
        result = when (uiState.typeFilter) {
            "Ygg" -> result.filter { peer ->
                peer.protocol.lowercase() in listOf("tcp", "tls", "quic", "ws", "wss")
            }
            "SNI" -> result.filter { peer ->
                // SNI включает все не-Ygg типы: sni, http, https, vless, vmess
                peer.protocol.lowercase() in listOf("sni", "http", "https", "vless", "vmess")
            }
            "Vless" -> result.filter { peer ->
                peer.protocol.lowercase() in listOf("vless", "vmess")
            }
            else -> result
        }

        // Фильтр по источнику
        if (uiState.sourceFilter != "All") {
            result = result.filter { peer -> peer.sourceShort == uiState.sourceFilter }
        }

        // Фильтр по ms (если включен)
        if (uiState.filterMs > 0) {
            result = result.filter { peer ->
                val ms = when (uiState.sortType) {
                    CheckType.PING -> peer.pingMs
                    CheckType.YGG_RTT -> peer.yggRttMs
                    CheckType.PORT_DEFAULT -> peer.portDefaultMs
                    CheckType.PORT_80 -> peer.port80Ms
                    CheckType.PORT_443 -> peer.port443Ms
                }
                ms in 0..uiState.filterMs
            }
        }

        // Сортировка по выбранному типу: живые хосты сначала
        result = result.sortedWith(
            compareByDescending<DiscoveredPeer> { it.isAlive() }
                .thenBy { peer ->
                    val ms = when (uiState.sortType) {
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
                .thenBy { it.address }
        )

        result
    }

    // Фильтрованный список групп для grouped view
    val filteredGroupedHosts = remember(uiState.groupedHosts, uiState.typeFilter, uiState.sourceFilter, uiState.sortType, uiState.filterMs, uiState.searchQuery) {
        var result = uiState.groupedHosts

        // Быстрый текстовый фильтр — фильтрует карточку целиком
        if (uiState.searchQuery.isNotBlank()) {
            val q = uiState.searchQuery.lowercase()
            result = result.filter { group ->
                group.displayName.lowercase().contains(q) ||
                group.groupKey.lowercase().contains(q) ||
                group.source.lowercase().contains(q) ||
                group.shortSource().lowercase().contains(q) ||
                (group.geoIp?.lowercase()?.contains(q) == true) ||
                (group.region?.lowercase()?.contains(q) == true) ||
                group.addresses.any { addr -> addr.address.lowercase().contains(q) } ||
                group.endpoints.any { ep ->
                    ep.protocol.lowercase().contains(q) ||
                    ep.originalUrl.lowercase().contains(q)
                }
            }
        }

        // Фильтр по источнику
        if (uiState.sourceFilter != "All") {
            result = result.filter { group -> group.shortSource() == uiState.sourceFilter }
        }

        // Фильтр по типу
        result = when (uiState.typeFilter) {
            "Ygg" -> result.filter { group ->
                group.endpoints.any { ep ->
                    ep.protocol.lowercase() in listOf("tcp", "tls", "quic", "ws", "wss")
                }
            }
            "SNI" -> result.filter { group ->
                group.endpoints.any { ep ->
                    ep.protocol.lowercase() in listOf("sni", "http", "https", "vless", "vmess")
                }
            }
            "Vless" -> result.filter { group ->
                group.endpoints.any { ep ->
                    ep.protocol.lowercase() in listOf("vless", "vmess")
                }
            }
            else -> result
        }

        // Фильтр по ms (если включен) - по лучшему результату выбранного типа
        if (uiState.filterMs > 0) {
            result = result.filter { group ->
                val best = group.getBestResultForType(uiState.sortType.name)
                best in 0..uiState.filterMs
            }
        }

        // Сортировка по выбранному типу проверки (лучший результат среди адресов)
        result = result.sortedWith(
            compareByDescending<GroupedHost> { it.isAlive }
                .thenBy { it.getBestResultForType(uiState.sortType.name) }
        )

        result
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Фильтр по источникам (из БД)
        if (uiState.availableSources.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item {
                    SourceFilterChip(
                        label = "All",
                        count = uiState.dbHostsCount,
                        isSelected = uiState.sourceFilter == "All",
                        onClick = { viewModel.setSourceFilter("All") }
                    )
                }
                items(uiState.availableSources) { source ->
                    SourceFilterChip(
                        label = source.shortName,
                        count = source.count,
                        isSelected = uiState.sourceFilter == source.shortName,
                        onClick = { viewModel.setSourceFilter(source.shortName) }
                    )
                }
            }
        }

        // Главная строка: Find/Stop + Type + Settings
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Find/Stop button (расширенная кнопка)
            Button(
                onClick = {
                    if (uiState.isSearching) {
                        viewModel.stopSearch()
                    } else {
                        viewModel.startPeerDiscovery()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.isSearching)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = if (uiState.isSearching) Icons.Default.Stop else Icons.Default.Search,
                    contentDescription = if (uiState.isSearching) "Stop" else "Find",
                    modifier = Modifier.size(28.dp)
                )
            }

            // Settings icon
            IconButton(
                onClick = { showSettingsDialog = true },
                modifier = Modifier.size(50.dp)
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Check Settings", modifier = Modifier.size(24.dp))
            }

            // Active Probing icon
            Box {
                IconButton(
                    onClick = {
                        if (uiState.isActiveProbing) {
                            viewModel.stopActiveProbing()
                        } else {
                            showProbingMenu = true
                        }
                    },
                    modifier = Modifier.size(50.dp)
                ) {
                    Icon(
                        imageVector = if (uiState.isActiveProbing) Icons.Default.Close else Icons.Default.Sensors,
                        contentDescription = "Active Probing",
                        modifier = Modifier.size(24.dp),
                        tint = if (uiState.isActiveProbing)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurface
                    )
                }
                // Active Probing DropdownMenu
                DropdownMenu(
                    expanded = showProbingMenu,
                    onDismissRequest = { showProbingMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Traceroute (alive)") },
                        onClick = {
                            showProbingMenu = false
                            viewModel.startActiveProbing("tracert")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("HTTP Fingerprint") },
                        onClick = {
                            showProbingMenu = false
                            viewModel.startActiveProbing("http_fingerprint")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Certificate Check") },
                        onClick = {
                            showProbingMenu = false
                            viewModel.startActiveProbing("cert_check")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("HTTP Status Codes") },
                        onClick = {
                            showProbingMenu = false
                            viewModel.startActiveProbing("http_status")
                        }
                    )
                    Divider()
                    DropdownMenuItem(
                        text = { Text("Comparative Timing") },
                        onClick = {
                            showProbingMenu = false
                            viewModel.startActiveProbing("comparative_timing")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Redirect Chain") },
                        onClick = {
                            showProbingMenu = false
                            viewModel.startActiveProbing("redirect_chain")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Response Size") },
                        onClick = {
                            showProbingMenu = false
                            viewModel.startActiveProbing("response_size")
                        }
                    )
                }
            }

            // Sessions icon
            IconButton(
                onClick = { showSessionsDialog = true },
                modifier = Modifier.size(50.dp)
            ) {
                Icon(Icons.Default.Save, contentDescription = "Sessions", modifier = Modifier.size(24.dp))
            }

            // View toggle icon (grouped/flat)
            IconButton(
                onClick = { useGroupedView = !useGroupedView },
                modifier = Modifier.size(50.dp)
            ) {
                Icon(
                    imageVector = if (useGroupedView) Icons.Default.ViewList else Icons.Default.ViewModule,
                    contentDescription = if (useGroupedView) "Flat view" else "Grouped view",
                    modifier = Modifier.size(24.dp),
                    tint = if (useGroupedView)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Быстрый поиск/фильтр результатов
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = { viewModel.setSearchQuery(it) },
            placeholder = { Text("Filter: host, IP, protocol...", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium,
            trailingIcon = {
                if (uiState.searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = { viewModel.setSearchQuery("") },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        )

        // Краткая информация о настройках
        Text(
            text = "Checks: ${uiState.enabledCheckTypes.joinToString { it.displayName }}${if (uiState.fastMode) " [Quick]" else ""}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Settings Dialog
        // Merged Settings Dialog (Check Settings + Sort/Filter)
        if (showSettingsDialog) {
            var tempFilterMs by remember { mutableIntStateOf(uiState.filterMs) }
            var sortDropdownExpanded by remember { mutableStateOf(false) }

            AlertDialog(
                onDismissRequest = { showSettingsDialog = false },
                title = { Text("Settings") },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Чекбоксы для типов проверок
                        Text("Check types:", style = MaterialTheme.typography.labelMedium)
                        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                            CheckType.entries.forEach { checkType ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.toggleCheckType(checkType) }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = uiState.enabledCheckTypes.contains(checkType),
                                        onCheckedChange = { viewModel.toggleCheckType(checkType) },
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(checkType.displayName, fontSize = 13.sp)
                                }
                            }
                        }

                        // Quick Mode toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Quick Mode", style = MaterialTheme.typography.labelMedium)
                                Text(
                                    "First success stops, only first DNS IP",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = uiState.fastMode,
                                onCheckedChange = { viewModel.toggleQuickMode() }
                            )
                        }

                        Divider()

                        // Sort by - Dropdown
                        Text("Sort by:", style = MaterialTheme.typography.labelMedium)
                        Box {
                            OutlinedButton(
                                onClick = { sortDropdownExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(uiState.sortType.displayName, modifier = Modifier.weight(1f))
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = sortDropdownExpanded,
                                onDismissRequest = { sortDropdownExpanded = false }
                            ) {
                                CheckType.entries.forEach { type ->
                                    DropdownMenuItem(
                                        text = { Text(type.displayName) },
                                        onClick = {
                                            viewModel.setSortType(type)
                                            sortDropdownExpanded = false
                                        },
                                        leadingIcon = if (uiState.sortType == type) {
                                            { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                                        } else null
                                    )
                                }
                            }
                        }

                        // Filter by ms - Slider
                        Text(
                            text = "Max ms: ${if (tempFilterMs == 0) "off" else "${tempFilterMs}ms"}",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Slider(
                            value = tempFilterMs.toFloat(),
                            onValueChange = { tempFilterMs = it.toInt() },
                            valueRange = 0f..1000f,
                            steps = 19,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "Shows peers with ${uiState.sortType.displayName} ≤ ${if (tempFilterMs == 0) "any" else "${tempFilterMs}ms"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        viewModel.setFilterMs(tempFilterMs)
                        showSettingsDialog = false
                    }) {
                        Text("Apply")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showSettingsDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Peer Detail Dialog
        detailPeer?.let { peer ->
            PeerDetailDialog(
                peer = peer,
                fallbackResults = detailFallbacks,
                onDismiss = { detailPeer = null }
            )
        }

        // Sessions Dialog
        if (showSessionsDialog) {
            var sessions by remember { mutableStateOf<List<SessionManager.SavedSession>>(emptyList()) }
            var showSaveDialog by remember { mutableStateOf(false) }
            var sessionName by remember { mutableStateOf(viewModel.generateSessionName()) }

            // Загружаем список сессий
            LaunchedEffect(Unit) {
                sessions = viewModel.getSessions()
            }

            AlertDialog(
                onDismissRequest = { showSessionsDialog = false },
                title = { Text("Sessions") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Кнопки Save + Import
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { showSaveDialog = true },
                                modifier = Modifier.weight(1f),
                                enabled = uiState.peers.isNotEmpty()
                            ) {
                                Icon(Icons.Default.Save, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Save (${uiState.peers.size})", fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    importLauncher.launch(arrayOf("application/json", "*/*"))
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.FileDownload, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Import", fontSize = 12.sp)
                            }
                        }

                        if (sessions.isEmpty()) {
                            Text(
                                "No saved sessions",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text("Saved sessions:", style = MaterialTheme.typography.labelMedium)
                            sessions.forEach { session ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.loadSession(session.fileName)
                                            showSessionsDialog = false
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(session.name, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                "${session.peersCount} peers (${session.availableCount} ok)",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        // Кнопка сохранения в папку
                                        IconButton(
                                            onClick = {
                                                sessionFileToSave = session.fileName
                                                saveLauncher.launch(session.fileName)
                                            },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Save, null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        // Кнопка экспорта (Share)
                                        IconButton(
                                            onClick = {
                                                val file = viewModel.getSessionFilePath(session.fileName)
                                                if (file != null) {
                                                    try {
                                                        val uri = FileProvider.getUriForFile(
                                                            context,
                                                            "${context.packageName}.fileprovider",
                                                            file
                                                        )
                                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                            type = "application/json"
                                                            putExtra(Intent.EXTRA_STREAM, uri)
                                                            putExtra(Intent.EXTRA_SUBJECT, "YggPeerChecker: ${session.name}")
                                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                        }
                                                        context.startActivity(Intent.createChooser(shareIntent, "Export session"))
                                                    } catch (e: Exception) {
                                                        Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Share, null,
                                                tint = MaterialTheme.colorScheme.secondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        // Кнопка удаления
                                        IconButton(
                                            onClick = {
                                                viewModel.deleteSession(session.fileName)
                                                scope.launch { sessions = viewModel.getSessions() }
                                            },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Delete, null,
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    OutlinedButton(onClick = { showSessionsDialog = false }) {
                        Text("Close")
                    }
                }
            )

            // Диалог для ввода имени сессии
            if (showSaveDialog) {
                AlertDialog(
                    onDismissRequest = { showSaveDialog = false },
                    title = { Text("Save Session") },
                    text = {
                        OutlinedTextField(
                            value = sessionName,
                            onValueChange = { sessionName = it },
                            label = { Text("Session name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                viewModel.saveSession(sessionName)
                                showSaveDialog = false
                                showSessionsDialog = false
                            },
                            enabled = sessionName.isNotBlank()
                        ) {
                            Text("Save")
                        }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = { showSaveDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }

        // Progress section
        if (uiState.isSearching) {
            LinearProgressIndicator(
                progress = uiState.progress / 100f,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Status text
        Text(
            text = uiState.statusMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        // Peer list - grouped или flat
        LazyColumn(
            modifier = Modifier.weight(1f)
        ) {
            if (useGroupedView && filteredGroupedHosts.isNotEmpty()) {
                // Grouped view
                items(
                    items = filteredGroupedHosts,
                    key = { it.groupKey }
                ) { group ->
                    GroupedHostCard(
                        group = group,
                        selectedEndpoints = uiState.selectedEndpoints,
                        onToggleEndpoint = { viewModel.toggleEndpointSelection(it) },
                        onSelectAllAlive = { urls -> viewModel.selectAllEndpoints(urls) }
                    )
                }
            } else {
                // Flat view (legacy)
                items(
                    items = filteredPeers,
                    key = { it.address }
                ) { peer ->
                    val fallbackResults = viewModel.getHostFallbackResults(peer.address)
                    PeerListItem(
                        peer = peer,
                        fallbackResults = fallbackResults,
                        isSelected = uiState.selectedPeers.contains(peer.address),
                        onToggleSelection = { viewModel.togglePeerSelection(peer.address) },
                        selectedFallbacks = uiState.selectedFallbacks,
                        onToggleFallbackSelection = { viewModel.toggleFallbackSelection(it) },
                        onShowDetail = {
                            detailPeer = peer
                            detailFallbacks = fallbackResults
                        }
                    )
                }
            }
        }

        // Bottom actions - разные для grouped и flat режимов
        val hasContent = if (useGroupedView) uiState.groupedHosts.isNotEmpty() else filteredPeers.isNotEmpty()

        if (hasContent) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box {
                    IconButton(
                        onClick = { showClearMenu = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear")
                    }
                    DropdownMenu(
                        expanded = showClearMenu,
                        onDismissRequest = { showClearMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Clear All") },
                            onClick = {
                                showClearMenu = false
                                viewModel.clearPeersList()
                                viewModel.clearEndpointSelection()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Clear Filtered (visible)") },
                            onClick = {
                                showClearMenu = false
                                if (useGroupedView) {
                                    val visibleKeys = filteredGroupedHosts.map { it.groupKey }.toSet()
                                    viewModel.clearVisibleHosts(visibleKeys, emptySet(), isGroupedMode = true)
                                } else {
                                    val visibleAddrs = filteredPeers.map { it.address }.toSet()
                                    viewModel.clearVisibleHosts(emptySet(), visibleAddrs, isGroupedMode = false)
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Clear Active Probing") },
                            onClick = {
                                showClearMenu = false
                                viewModel.clearActiveProbingResults()
                            }
                        )
                    }
                }

                if (useGroupedView) {
                    // Grouped mode: работа с endpoints
                    val hasSelection = uiState.selectedEndpoints.isNotEmpty()
                    // Собираем все живые endpoint URL из видимых групп
                    val allAliveEndpointUrls = remember(filteredGroupedHosts) {
                        filteredGroupedHosts.flatMap { group ->
                            group.endpoints.flatMap { endpoint ->
                                endpoint.checkResults.filter { it.isAlive }.map { it.fullUrl }
                            }
                        }
                    }
                    IconButton(
                        onClick = {
                            if (allAliveEndpointUrls.isNotEmpty()) {
                                viewModel.selectAllEndpoints(allAliveEndpointUrls)
                            }
                        },
                        enabled = allAliveEndpointUrls.isNotEmpty()
                    ) {
                        Icon(
                            imageVector = if (hasSelection) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                            contentDescription = if (hasSelection) "Deselect All" else "Select All Alive",
                            tint = if (hasSelection) OnlineGreen else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Button(
                        onClick = {
                            val urls = viewModel.getSelectedEndpointUrls()
                            if (urls.isNotEmpty()) {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Yggdrasil peers", urls.joinToString("\n")))
                                Toast.makeText(context, "Copied ${urls.size} URLs", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "No endpoints selected", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("${uiState.selectedEndpoints.size}")
                    }
                } else {
                    // Flat mode: работа с peers
                    val hasSelection = uiState.selectedPeers.isNotEmpty()
                    IconButton(
                        onClick = { viewModel.toggleSelectAll() }
                    ) {
                        Icon(
                            imageVector = if (hasSelection) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                            contentDescription = if (hasSelection) "Deselect All" else "Select All",
                            tint = if (hasSelection) OnlineGreen else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Button(
                        onClick = {
                            val addresses = viewModel.getSelectedAddresses()
                            if (addresses.isNotEmpty()) {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Yggdrasil peers", addresses.joinToString("\n")))
                                Toast.makeText(context, "Copied ${addresses.size} peers", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "No peers selected", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("${uiState.selectedPeers.size}")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatChip(
    label: String,
    count: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surface
    val textColor = if (isSelected)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onSurface

    Card(
        modifier = Modifier.clickable { onClick() },
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = backgroundColor
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = textColor
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = 0.7f)
            )
        }
    }
}

// Чип для фильтра по источнику (горизонтальный, компактный)
@Composable
private fun SourceFilterChip(
    label: String,
    count: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.surface
    val textColor = if (isSelected)
        MaterialTheme.colorScheme.onPrimary
    else
        MaterialTheme.colorScheme.onSurface
    val borderColor = if (isSelected)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)

    Card(
        modifier = Modifier.clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        border = androidx.compose.foundation.BorderStroke(
            if (isSelected) 2.dp else 1.dp,
            borderColor
        ),
        elevation = if (isSelected) CardDefaults.cardElevation(defaultElevation = 3.dp)
            else CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.Bold else null
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "($count)",
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = if (isSelected) 0.9f else 0.6f),
                fontSize = 10.sp
            )
        }
    }
}

@Composable
fun PeerListItem(
    peer: DiscoveredPeer,
    fallbackResults: List<HostCheckResult>,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    selectedFallbacks: Set<String> = emptySet(),
    onToggleFallbackSelection: (String) -> Unit = {},
    onShowDetail: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onShowDetail() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            // === Основная запись ===
            PeerEntryRow(
                address = peer.address,
                protocol = peer.protocol,
                region = peer.region,
                geoIp = peer.geoIp,
                isAlive = peer.isAlive(),
                pingMs = peer.pingMs,
                pingTtl = peer.pingTtl,
                yggRttMs = peer.yggRttMs,
                portDefaultMs = peer.portDefaultMs,
                port80Ms = peer.port80Ms,
                port443Ms = peer.port443Ms,
                hops = peer.hops,
                isSelected = isSelected,
                onToggleSelection = onToggleSelection,
                errorReason = if (!peer.isAlive()) peer.getErrorReason() else null,
                activeWarning = peer.activeWarning,
                httpStatusCode = peer.httpStatusCode,
                httpsStatusCode = peer.httpsStatusCode,
                port80Blocked = peer.port80Blocked,
                port443Blocked = peer.port443Blocked
            )

            // === Fallback записи (если есть) ===
            if (fallbackResults.isNotEmpty()) {
                fallbackResults.forEach { result ->
                    // Разделитель
                    Divider(
                        modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )
                    
                    // Fallback запись повторяет основной шаблон
                    val fallbackIsAlive = result.pingTime >= 0 || result.yggRtt >= 0 || 
                        result.portDefault >= 0 || result.port80 >= 0 || result.port443 >= 0
                    
                    PeerEntryRow(
                        address = result.target,
                        protocol = peer.protocol,
                        region = peer.region,
                        geoIp = peer.geoIp,
                        isAlive = fallbackIsAlive,
                        pingMs = result.pingTime,
                        pingTtl = result.pingTtl,
                        yggRttMs = result.yggRtt,
                        portDefaultMs = result.portDefault,
                        port80Ms = result.port80,
                        port443Ms = result.port443,
                        isSelected = selectedFallbacks.contains(result.target),
                        onToggleSelection = { onToggleFallbackSelection(result.target) },
                        isFallback = true,
                        dnsSource = result.dnsSource
                    )
                }
            }
        }
    }
}

/**
 * Универсальная строка записи (для основного хоста и fallback)
 */
@Composable
private fun PeerEntryRow(
    address: String,
    protocol: String,
    region: String,
    geoIp: String = "",
    isAlive: Boolean,
    pingMs: Long,
    pingTtl: Int = -1,
    yggRttMs: Long,
    portDefaultMs: Long,
    port80Ms: Long,
    port443Ms: Long,
    hops: Int = -1,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    errorReason: String? = null,
    isFallback: Boolean = false,
    // Active probing данные
    activeWarning: String = "",
    httpStatusCode: Int = -1,
    httpsStatusCode: Int = -1,
    port80Blocked: Boolean = false,
    port443Blocked: Boolean = false,
    // DNS source для flat mode
    dnsSource: String? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (isFallback) 16.dp else 0.dp) // отступ для fallback
    ) {
        // Первая строка: checkbox + адрес
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelection() },
                modifier = Modifier.size(32.dp)
            )
            
            Spacer(modifier = Modifier.width(4.dp))
            
            // Полный адрес (не обрезаем!)
            Text(
                text = address,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )

            // DNS source иконка (для fallback записей в flat mode)
            if (isFallback && dnsSource != null) {
                FlatDnsSourceIndicator(dnsSource)
            }

            // Определяем подозрительные условия
            val isSuspicious = hops in 1..4 ||
                (pingMs in 1..9 && pingMs >= 0) ||
                (port443Ms == -2L && port80Ms >= 0) ||  // 443 failed но 80 OK
                activeWarning.isNotEmpty()

            // Индикатор статуса
            val statusText = when {
                !isAlive -> "x"
                isSuspicious -> "\u26A0"  // ⚠
                else -> "+"
            }
            val statusColor = when {
                !isAlive -> Color.Red
                isSuspicious -> WarningYellow
                else -> OnlineGreen
            }

            Text(
                text = statusText,
                color = statusColor,
                fontSize = if (statusText == "+") 16.sp else 18.sp,
                fontWeight = if (statusText == "+") FontWeight.Bold else null,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .then(
                        if (statusText == "+") Modifier
                            .background(
                                OnlineGreen.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 4.dp)
                        else Modifier
                    )
            )
        }
        
        // Вторая строка: протокол + регион + GeoIP
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 36.dp), // выравнивание под адрес
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = protocol.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            if (region.isNotEmpty()) {
                Text(
                    text = region.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (geoIp.isNotEmpty()) {
                Text(
                    text = geoIp,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
        
        // Третья строка: результаты проверок
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 36.dp, top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CheckResultChip("Ping", pingMs, ttl = pingTtl)
            CheckResultChip("YRtt", yggRttMs)
            CheckResultChip("Pdef", portDefaultMs)
            CheckResultChip("P80", port80Ms, isBlocked = port80Blocked)
            CheckResultChip("P443", port443Ms, isBlocked = port443Blocked)
        }

        // Hops на отдельной строке (если есть)
        if (hops > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 36.dp, top = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val hopsSuspicious = hops in 1..4
                Text(
                    text = if (hopsSuspicious) "Hops: $hops \u26A0" else "Hops: $hops",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hopsSuspicious) WarningOrange else MaterialTheme.colorScheme.tertiary,
                    fontSize = 9.sp
                )
            }
        }

        // Active probing результаты (если есть)
        val hasActiveResults = httpStatusCode > 0 || httpsStatusCode > 0 || activeWarning.isNotEmpty() ||
            port80Blocked || port443Blocked
        if (hasActiveResults) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 36.dp, top = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (httpStatusCode > 0) {
                    StatusCodeChip("HTTP", httpStatusCode, port80Blocked)
                }
                if (httpsStatusCode > 0) {
                    StatusCodeChip("HTTPS", httpsStatusCode, port443Blocked)
                }
                if (port80Blocked && httpStatusCode <= 0) {
                    Text(
                        text = "P80:\u26A0",
                        style = MaterialTheme.typography.labelSmall,
                        color = WarningOrange,
                        fontSize = 9.sp
                    )
                }
                if (port443Blocked && httpsStatusCode <= 0) {
                    Text(
                        text = "P443:\u26A0",
                        style = MaterialTheme.typography.labelSmall,
                        color = WarningOrange,
                        fontSize = 9.sp
                    )
                }
                if (activeWarning.isNotEmpty()) {
                    Text(
                        text = when (activeWarning) {
                            "blocked" -> "Stub:\u26A0"
                            "cert_mismatch" -> "Cert:\u26A0"
                            "anomaly" -> "DPI:\u26A0"
                            else -> activeWarning
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = WarningYellow,
                        fontSize = 9.sp
                    )
                }
            }
        }
        
        // Ошибка (если есть и хост мертв)
        if (errorReason != null) {
            Text(
                text = errorReason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 36.dp, top = 2.dp)
            )
        }
    }
}

@Composable
private fun CheckResultChip(
    label: String,
    value: Long,
    ttl: Int = -1,
    isBlocked: Boolean = false
) {
    val color = when {
        isBlocked && value >= 0 -> WarningOrange
        value >= 0 -> OnlineGreen
        value == -2L -> Color.Red
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = when {
        value >= 0 && ttl > 0 -> "$label:$value($ttl)"
        value >= 0 -> "$label:$value"
        value == -2L -> "$label:X"
        else -> "$label:off"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontSize = 9.sp
    )
}

/**
 * Цветной чип HTTP status code
 * 2xx = зелёный, 3xx = оранжевый, 4xx-5xx = красный
 */
@Composable
private fun StatusCodeChip(label: String, code: Int, isBlocked: Boolean = false) {
    val color = when {
        isBlocked -> WarningOrange
        code in 200..299 -> OnlineGreen
        code in 300..399 -> WarningOrange
        code in 400..599 -> Color.Red
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = "$label:$code",
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontSize = 9.sp
    )
}

/**
 * DNS source иконка для flat mode (рядом с адресом fallback)
 */
@Composable
private fun FlatDnsSourceIndicator(source: String) {
    Spacer(modifier = Modifier.width(2.dp))
    Text(
        text = when (source.lowercase()) {
            "yandex" -> "Ya"
            "cloudflare" -> "CF"
            "google" -> "Go"
            "system" -> "Sys"
            else -> source.take(3)
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary,
        fontSize = 8.sp,
        modifier = Modifier.padding(start = 2.dp)
    )
}

/**
 * Диалог детальной информации о пире
 */
@Composable
fun PeerDetailDialog(
    peer: DiscoveredPeer,
    fallbackResults: List<HostCheckResult>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = peer.address,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 2
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Базовая инфо
                DetailRow("Protocol", peer.protocol)
                DetailRow("Region", peer.region)
                if (peer.geoIp.isNotEmpty()) DetailRow("GeoIP", peer.geoIp)
                DetailRow("Source", peer.sourceShort)
                DetailRow("Status", if (peer.isAlive()) "Alive" else "Dead")

                Divider(modifier = Modifier.padding(vertical = 4.dp))

                // Результаты проверок
                Text("Check Results", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                DetailResultRow("Ping", peer.pingMs, peer.pingTtl)
                DetailResultRow("Ygg RTT", peer.yggRttMs)
                DetailResultRow("Port (default)", peer.portDefaultMs)
                DetailResultRow("Port 80", peer.port80Ms, isBlocked = peer.port80Blocked)
                DetailResultRow("Port 443", peer.port443Ms, isBlocked = peer.port443Blocked)

                if (peer.hops > 0) DetailRow("Hops", "${peer.hops}")

                // Active Probing
                if (peer.httpStatusCode > 0 || peer.httpsStatusCode > 0 ||
                    peer.httpFingerprint.isNotEmpty() || peer.certFingerprint.isNotEmpty() ||
                    peer.redirectChain.isNotEmpty() || peer.responseSize >= 0 ||
                    peer.comparativeTimingRatio >= 0) {

                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("Active Probing", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)

                    if (peer.httpStatusCode > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("HTTP: ", style = MaterialTheme.typography.bodySmall)
                            StatusCodeChip("", peer.httpStatusCode, peer.port80Blocked)
                            Text(
                                text = " ${httpStatusDescription(peer.httpStatusCode)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 9.sp
                            )
                        }
                    }
                    if (peer.httpsStatusCode > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("HTTPS: ", style = MaterialTheme.typography.bodySmall)
                            StatusCodeChip("", peer.httpsStatusCode, peer.port443Blocked)
                            Text(
                                text = " ${httpStatusDescription(peer.httpsStatusCode)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 9.sp
                            )
                        }
                    }
                    if (peer.httpFingerprint.isNotEmpty()) {
                        DetailRow("HTTP Fingerprint", peer.httpFingerprint)
                    }
                    if (peer.certFingerprint.isNotEmpty()) {
                        DetailRow("Certificate", peer.certFingerprint)
                    }
                    if (peer.redirectChain.isNotEmpty()) {
                        DetailRow("Redirect Chain", peer.redirectChain)
                    }
                    if (peer.redirectUrl.isNotEmpty()) {
                        DetailRow("Redirect To", peer.redirectUrl)
                    }
                    if (peer.responseSize >= 0) {
                        DetailRow("Response Size", "${peer.responseSize}B")
                    }
                    if (peer.comparativeTimingRatio >= 0) {
                        val ratioStr = String.format("%.1fx", peer.comparativeTimingRatio)
                        val color = if (peer.comparativeTimingRatio > 10) WarningOrange else OnlineGreen
                        Row {
                            Text("Timing Ratio: ", style = MaterialTheme.typography.bodySmall)
                            Text(ratioStr, style = MaterialTheme.typography.bodySmall, color = color)
                        }
                    }
                    if (peer.activeWarning.isNotEmpty()) {
                        DetailRow("Warning", peer.activeWarning)
                    }
                }

                // Fallback результаты
                if (fallbackResults.isNotEmpty()) {
                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("Fallback IPs (${fallbackResults.size})", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    fallbackResults.forEach { fb ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = fb.target.take(30),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    if (fb.dnsSource != null) {
                                        Text(
                                            text = " (${fb.dnsSource})",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.tertiary,
                                            fontSize = 8.sp
                                        )
                                    }
                                }
                                Text(
                                    text = if (fb.available) "\u25CF" else "x",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (fb.available) OnlineGreen else Color.Red,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                            // Подробные результаты проверок для каждого IP
                            Row(
                                modifier = Modifier.padding(start = 8.dp, top = 1.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (fb.pingTime != -1L) {
                                    Text(
                                        text = if (fb.pingTime >= 0) "Ping:${fb.pingTime}" else "Ping:X",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (fb.pingTime >= 0) OnlineGreen else Color.Red,
                                        fontSize = 9.sp
                                    )
                                }
                                if (fb.port80 != -1L) {
                                    Text(
                                        text = if (fb.port80 >= 0) "P80:${fb.port80}" else "P80:X",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (fb.port80 >= 0) OnlineGreen else Color.Red,
                                        fontSize = 9.sp
                                    )
                                }
                                if (fb.port443 != -1L) {
                                    Text(
                                        text = if (fb.port443 >= 0) "P443:${fb.port443}" else "P443:X",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (fb.port443 >= 0) OnlineGreen else Color.Red,
                                        fontSize = 9.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.6f)
        )
    }
}

@Composable
private fun DetailResultRow(label: String, value: Long, ttl: Int = -1, isBlocked: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f)
        )
        val displayText = when {
            value >= 0 && ttl > 0 -> "${value}ms (TTL:$ttl)"
            value >= 0 -> "${value}ms"
            value == -2L -> "Failed"
            else -> "off"
        }
        val color = when {
            isBlocked && value >= 0 -> WarningOrange
            value >= 0 -> OnlineGreen
            value == -2L -> Color.Red
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(
            text = displayText,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            modifier = Modifier.weight(0.6f)
        )
    }
}

/**
 * Краткое описание HTTP статус-кода
 */
private fun httpStatusDescription(code: Int): String = when (code) {
    200 -> "OK"
    301 -> "Moved"
    302 -> "Redirect"
    303 -> "See Other"
    307 -> "Temp Redirect"
    308 -> "Perm Redirect"
    400 -> "Bad Request"
    403 -> "Forbidden"
    404 -> "Not Found"
    451 -> "Censored"
    500 -> "Server Error"
    502 -> "Bad Gateway"
    503 -> "Unavailable"
    else -> when (code / 100) {
        2 -> "Success"
        3 -> "Redirect"
        4 -> "Client Err"
        5 -> "Server Err"
        else -> ""
    }
}


