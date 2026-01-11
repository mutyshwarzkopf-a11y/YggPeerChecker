package com.example.yggpeerchecker.ui.checks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.yggpeerchecker.data.DiscoveredPeer
import com.example.yggpeerchecker.ui.theme.OnlineGreen
import com.example.yggpeerchecker.utils.PersistentLogger

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

    // Применяем фильтры и сортировки к списку результатов
    val filteredPeers = remember(uiState.peers, uiState.typeFilter, uiState.sortType, uiState.filterMs) {
        var result = uiState.peers

        // Фильтр по типу (для отображения)
        result = when (uiState.typeFilter) {
            "Ygg" -> result.filter { peer ->
                peer.protocol.lowercase() in listOf("tcp", "tls", "quic", "ws", "wss")
            }
            "SNI" -> result.filter { peer ->
                peer.protocol.lowercase() in listOf("sni", "http", "https")
            }
            else -> result
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
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (uiState.isSearching) "STOP" else "FIND",
                    fontSize = 16.sp
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
        }

        // Краткая информация о настройках
        Text(
            text = "Checks: ${uiState.enabledCheckTypes.joinToString { it.displayName }}${if (uiState.fastMode) " [Fast]" else ""}",
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
                                Text(checkType.displayName)
                                Checkbox(
                                    checked = uiState.enabledCheckTypes.contains(checkType),
                                    onCheckedChange = { viewModel.toggleCheckType(checkType) }
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Fast Mode", style = MaterialTheme.typography.labelMedium)
                                Text(
                                    "Stop on first success",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = uiState.fastMode,
                                onCheckedChange = { viewModel.toggleFastMode() }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Always Check DNS IPs", style = MaterialTheme.typography.labelMedium)
                                Text(
                                    "Check fallback IPs even if main succeeds",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = uiState.alwaysCheckDnsIps,
                                onCheckedChange = { viewModel.toggleAlwaysCheckDnsIps() }
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
            
            AlertDialog(
                onDismissRequest = { showSortDialog = false },
                title = { Text("Sort & Filter") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Sort by:", style = MaterialTheme.typography.labelMedium)
                        
                        // Первая строка: Ping, YggRTT
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(CheckType.PING, CheckType.YGG_RTT).forEach { type ->
                                FilterChip(
                                    selected = uiState.sortType == type,
                                    onClick = { viewModel.setSortType(type) },
                                    label = { Text(type.displayName, fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        
                        // Вторая строка: Port Default, Port 80, Port 443
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(CheckType.PORT_DEFAULT, CheckType.PORT_80, CheckType.PORT_443).forEach { type ->
                                FilterChip(
                                    selected = uiState.sortType == type,
                                    onClick = { viewModel.setSortType(type) },
                                    label = { Text(type.displayName, fontSize = 10.sp) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text("Filter by ms (0 = off):", style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = if (tempFilterMs > 0) tempFilterMs.toString() else "",
                                onValueChange = { tempFilterMs = it.toIntOrNull() ?: 0 },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("0") },
                                suffix = { Text("ms") },
                                singleLine = true
                            )
                            // Быстрые кнопки
                            listOf(100, 500, 1000).forEach { ms ->
                                OutlinedButton(
                                    onClick = { tempFilterMs = ms },
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                    modifier = Modifier.height(40.dp)
                                ) {
                                    Text("$ms", fontSize = 10.sp)
                                }
                            }
                        }
                        
                        Text(
                            "Shows only peers with ${uiState.sortType.displayName} < filter value",
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

        // Peer list
        LazyColumn(
            modifier = Modifier.weight(1f)
        ) {
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

        // Bottom actions
        if (filteredPeers.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = { viewModel.clearPeersList() },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear")
                }

                OutlinedButton(
                    onClick = { viewModel.toggleSelectAll() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (uiState.selectedPeers.isNotEmpty()) "Deselect All" else "Select All")
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
                    Text("Copy (${uiState.selectedPeers.size})")
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
                isAlive = peer.isAlive(),
                pingMs = peer.pingMs,
                yggRttMs = peer.yggRttMs,
                portDefaultMs = peer.portDefaultMs,
                port80Ms = peer.port80Ms,
                port443Ms = peer.port443Ms,
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
    isAlive: Boolean,
    pingMs: Long,
    yggRttMs: Long,
    portDefaultMs: Long,
    port80Ms: Long,
    port443Ms: Long,
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
        
        // Вторая строка: протокол + регион
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
            Text(
                text = region.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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


