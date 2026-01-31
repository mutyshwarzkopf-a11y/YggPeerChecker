package com.example.yggpeerchecker.ui.checks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Save
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.yggpeerchecker.data.DiscoveredPeer
import com.example.yggpeerchecker.data.GroupedHost
import com.example.yggpeerchecker.data.SessionManager
import com.example.yggpeerchecker.ui.theme.OnlineGreen
import com.example.yggpeerchecker.utils.PersistentLogger
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
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
    var showSortDialog by remember { mutableStateOf(false) }
    var showSessionsDialog by remember { mutableStateOf(false) }
    var useGroupedView by remember { mutableStateOf(false) }  // Переключатель вида: группы/плоский список
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

    // Обновляем группы когда поиск завершён или переключаемся в grouped view
    LaunchedEffect(uiState.isSearching, useGroupedView) {
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
    val filteredPeers = remember(uiState.peers, uiState.typeFilter, uiState.sourceFilter, uiState.sortType, uiState.filterMs) {
        var result = uiState.peers

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
    val filteredGroupedHosts = remember(uiState.groupedHosts, uiState.typeFilter, uiState.sourceFilter, uiState.sortType, uiState.filterMs) {
        var result = uiState.groupedHosts

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
        // Информация о БД - кликабельные фильтры
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatChip(
                    label = "All",
                    count = uiState.dbHostsCount,
                    isSelected = uiState.typeFilter == "All",
                    onClick = { viewModel.setTypeFilter("All") }
                )
                StatChip(
                    label = "Ygg",
                    count = uiState.dbYggCount,
                    isSelected = uiState.typeFilter == "Ygg",
                    onClick = { viewModel.setTypeFilter("Ygg") }
                )
                StatChip(
                    label = "SNI",
                    count = uiState.dbSniCount,
                    isSelected = uiState.typeFilter == "SNI",
                    onClick = { viewModel.setTypeFilter("SNI") }
                )
                StatChip(
                    label = "Vless",
                    count = uiState.dbVlessCount,
                    isSelected = uiState.typeFilter == "Vless",
                    onClick = { viewModel.setTypeFilter("Vless") }
                )
            }
        }

        // Динамический фильтр по источникам (из БД)
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

            // Sort/Filter icon
            IconButton(
                onClick = { showSortDialog = true },
                modifier = Modifier.size(50.dp)
            ) {
                Icon(Icons.Default.FilterList, contentDescription = "Sort/Filter", modifier = Modifier.size(24.dp))
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
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Краткая информация о настройках
        Text(
            text = "Checks: ${uiState.enabledCheckTypes.joinToString { it.displayName }}${if (uiState.fastMode) " [Quick]" else ""}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Settings Dialog
        if (showSettingsDialog) {
            AlertDialog(
                onDismissRequest = { showSettingsDialog = false },
                title = { Text("Check Settings") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Check types:", style = MaterialTheme.typography.labelMedium)
                        CheckType.entries.forEach { checkType ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // PING всегда активен - отображаем с пометкой
                                Text(
                                    text = if (checkType == CheckType.PING)
                                        "${checkType.displayName} (always)"
                                    else
                                        checkType.displayName,
                                    color = if (checkType == CheckType.PING)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurface
                                )
                                Checkbox(
                                    checked = uiState.enabledCheckTypes.contains(checkType),
                                    onCheckedChange = { viewModel.toggleCheckType(checkType) },
                                    enabled = checkType != CheckType.PING  // PING нельзя отключить
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Quick Mode", style = MaterialTheme.typography.labelMedium)
                                Text(
                                    "Stop on first success, check only first DNS IP",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = uiState.fastMode,
                                onCheckedChange = { viewModel.toggleQuickMode() }
                            )
                        }

                        // Tracert кнопка
                        Button(
                            onClick = { viewModel.runTracert() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !uiState.isSearching
                        ) {
                            Text(
                                if (uiState.isTracertRunning) "Stop Tracert (${uiState.tracertProgress})"
                                else "Run Tracert (alive hosts)"
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { showSettingsDialog = false }) {
                        Text("OK")
                    }
                }
            )
        }

        // Sort/Filter Dialog
        if (showSortDialog) {
            var tempFilterMs by remember { mutableIntStateOf(uiState.filterMs) }
            var sortDropdownExpanded by remember { mutableStateOf(false) }

            AlertDialog(
                onDismissRequest = { showSortDialog = false },
                title = { Text("Sort & Filter") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Sort by - Dropdown меню
                        Text("Sort by:", style = MaterialTheme.typography.labelMedium)
                        Box {
                            OutlinedButton(
                                onClick = { sortDropdownExpanded = true },
                                modifier = Modifier.fillMaxWidth()
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

                        Spacer(modifier = Modifier.height(4.dp))

                        // Filter by ms - Slider
                        Text(
                            text = "Filter by ms: ${if (tempFilterMs == 0) "off" else "${tempFilterMs}ms"}",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Slider(
                            value = tempFilterMs.toFloat(),
                            onValueChange = { tempFilterMs = it.toInt() },
                            valueRange = 0f..1000f,
                            steps = 19,  // 50ms шаг (0, 50, 100, ... 1000)
                            modifier = Modifier.fillMaxWidth()
                        )
                        // Быстрые кнопки
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(0 to "Off", 100 to "100", 300 to "300", 500 to "500", 1000 to "1s").forEach { (ms, label) ->
                                OutlinedButton(
                                    onClick = { tempFilterMs = ms },
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(32.dp),
                                    colors = if (tempFilterMs == ms)
                                        ButtonDefaults.outlinedButtonColors(
                                            containerColor = MaterialTheme.colorScheme.primaryContainer
                                        )
                                    else ButtonDefaults.outlinedButtonColors()
                                ) {
                                    Text(label, fontSize = 10.sp)
                                }
                            }
                        }

                        Text(
                            "Shows peers with ${uiState.sortType.displayName} ≤ filter value",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        viewModel.setFilterMs(tempFilterMs)
                        showSortDialog = false
                    }) {
                        Text("Apply")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showSortDialog = false }) {
                        Text("Cancel")
                    }
                }
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
                        onToggleFallbackSelection = { viewModel.toggleFallbackSelection(it) }
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
                IconButton(
                    onClick = {
                        val isAllFilters = uiState.typeFilter == "All" && uiState.sourceFilter == "All" && uiState.filterMs <= 0
                        if (isAllFilters) {
                            // Фильтр All-All: очищаем всё
                            viewModel.clearPeersList()
                            viewModel.clearEndpointSelection()
                        } else {
                            // Фильтр активен: очищаем только видимые
                            if (useGroupedView) {
                                val visibleKeys = filteredGroupedHosts.map { it.groupKey }.toSet()
                                viewModel.clearVisibleHosts(visibleKeys, emptySet(), isGroupedMode = true)
                            } else {
                                val visibleAddrs = filteredPeers.map { it.address }.toSet()
                                viewModel.clearVisibleHosts(emptySet(), visibleAddrs, isGroupedMode = false)
                            }
                        }
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear")
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
        MaterialTheme.colorScheme.secondaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isSelected)
        MaterialTheme.colorScheme.onSecondaryContainer
    else
        MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier = Modifier.clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = textColor
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "($count)",
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.6f),
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
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
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
                yggRttMs = peer.yggRttMs,
                portDefaultMs = peer.portDefaultMs,
                port80Ms = peer.port80Ms,
                port443Ms = peer.port443Ms,
                hops = peer.hops,
                isSelected = isSelected,
                onToggleSelection = onToggleSelection,
                errorReason = if (!peer.isAlive()) peer.getErrorReason() else null
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
                        protocol = peer.protocol, // протокол от основного
                        region = peer.region,     // регион от основного
                        geoIp = peer.geoIp,       // geoIp от основного
                        isAlive = fallbackIsAlive,
                        pingMs = result.pingTime,
                        yggRttMs = result.yggRtt,
                        portDefaultMs = result.portDefault,
                        port80Ms = result.port80,
                        port443Ms = result.port443,
                        isSelected = selectedFallbacks.contains(result.target),
                        onToggleSelection = { onToggleFallbackSelection(result.target) },
                        isFallback = true
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
    yggRttMs: Long,
    portDefaultMs: Long,
    port80Ms: Long,
    port443Ms: Long,
    hops: Int = -1,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    errorReason: String? = null,
    isFallback: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleSelection() }
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
            
            // Индикатор живости
            Text(
                text = if (isAlive) "*" else "x",
                color = if (isAlive) OnlineGreen else Color.Red,
                fontSize = 18.sp,
                modifier = Modifier.padding(start = 8.dp)
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
            CheckResultChip("Ping", pingMs)
            CheckResultChip("YRtt", yggRttMs)
            CheckResultChip("Pdef", portDefaultMs)
            CheckResultChip("P80", port80Ms)
            CheckResultChip("P443", port443Ms)
            // Hops (если есть)
            if (hops > 0) {
                Text(
                    text = "Hops:$hops",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
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
private fun CheckResultChip(label: String, value: Long) {
    // value: -1 = off (не проверялся), -2 = X (ошибка), >=0 = время в ms
    val color = when {
        value >= 0 -> OnlineGreen
        value == -2L -> Color.Red
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = when {
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


