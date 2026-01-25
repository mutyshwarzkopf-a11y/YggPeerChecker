package com.example.yggpeerchecker.ui.lists

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.yggpeerchecker.data.database.Host
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewTab(
    modifier: Modifier = Modifier,
    viewModel: ListsViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    // Два фильтра: тип (All/Ygg/SNI) и адрес (All/DNS/NoDNS/IPv6/IPv4/IpOnly)
    var typeFilter by remember { mutableStateOf("All") }
    var addressFilter by remember { mutableStateOf("All") }

    // Подсчет для фильтров типа
    val typeFilterCounts = remember(uiState.hosts) {
        val hosts = uiState.hosts
        mapOf(
            "All" to hosts.size,
            "Ygg" to hosts.count { Host.isYggType(it.hostType) },
            "SNI" to hosts.count { Host.isSniType(it.hostType) }
        )
    }

    // Фильтрация по типу (первый проход)
    val typeFilteredHosts = remember(uiState.hosts, typeFilter) {
        when (typeFilter) {
            "Ygg" -> uiState.hosts.filter { Host.isYggType(it.hostType) }
            "SNI" -> uiState.hosts.filter { Host.isSniType(it.hostType) }
            else -> uiState.hosts
        }
    }

    // Подсчет для фильтров адреса (на основе typeFilteredHosts)
    val addressFilterCounts = remember(typeFilteredHosts) {
        val hosts = typeFilteredHosts
        mapOf(
            "All" to hosts.size,
            "DNS" to hosts.count { it.dnsIp1 != null },
            "NoDNS" to hosts.count { !hasDnsResolved(it) && !isIpOnly(it) },
            "IPv6" to hosts.count { isIpv6Host(it) },
            "IPv4" to hosts.count { isIpv4Host(it) },
            "IpOnly" to hosts.count { isIpOnly(it) },
            "Geo" to hosts.count { it.geoIp != null },
            "NoGeo" to hosts.count { it.geoIp == null }
        )
    }

    // Финальная фильтрация по адресу (второй проход)
    val filteredHosts = remember(typeFilteredHosts, addressFilter) {
        when (addressFilter) {
            "DNS" -> typeFilteredHosts.filter { it.dnsIp1 != null }
            "NoDNS" -> typeFilteredHosts.filter { !hasDnsResolved(it) && !isIpOnly(it) }
            "IPv6" -> typeFilteredHosts.filter { isIpv6Host(it) }
            "IPv4" -> typeFilteredHosts.filter { isIpv4Host(it) }
            "IpOnly" -> typeFilteredHosts.filter { isIpOnly(it) }
            "Geo" -> typeFilteredHosts.filter { it.geoIp != null }
            "NoGeo" -> typeFilteredHosts.filter { it.geoIp == null }
            else -> typeFilteredHosts
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
    ) {
        // Первая строка - кнопки действий (порядок: Fill DNS, Fill GeoIP, Clear DNS&GEO, Delete)
        // Вычисляем переменные для всех кнопок заранее
        // GeoIP теперь может резолвить hostname на лету, поэтому включаем все без geoIp
        val allHostsNeedingGeoIp = uiState.hosts.filter { host ->
            host.geoIp.isNullOrEmpty()
        }
        val hostsWithDnsOrGeo = filteredHosts.filter { it.dnsIp1 != null || !it.geoIp.isNullOrEmpty() }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Fill DNS - с возможностью отмены
            OutlinedButton(
                onClick = { viewModel.fillDnsIps() },
                enabled = uiState.isDnsLoading || (!uiState.isLoading && uiState.totalCount > 0),
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
                colors = if (uiState.isDnsLoading) {
                    ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.outlinedButtonColors()
                }
            ) {
                if (uiState.isDnsLoading) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Dns,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (uiState.isDnsLoading) "Cancel" else "DNS", fontSize = 11.sp)
            }

            // 2. Fill GeoIP - счетчик показывает ВСЕ хосты без GeoIP
            OutlinedButton(
                onClick = {
                    if (uiState.isGeoIpLoading) {
                        viewModel.fillGeoIp(null)
                    } else {
                        val ids = allHostsNeedingGeoIp.map { it.id }
                        viewModel.fillGeoIp(ids)
                    }
                },
                enabled = uiState.isGeoIpLoading || (!uiState.isLoading && allHostsNeedingGeoIp.isNotEmpty()),
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
                colors = if (uiState.isGeoIpLoading) {
                    ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.secondary)
                }
            ) {
                if (uiState.isGeoIpLoading) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Public,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    if (uiState.isGeoIpLoading) "Cancel" else "Geo (${allHostsNeedingGeoIp.size})",
                    fontSize = 11.sp
                )
            }

            // 3. Clear DNS & GeoIP - оранжевая кнопка
            OutlinedButton(
                onClick = {
                    val ids = hostsWithDnsOrGeo.map { it.id }
                    viewModel.clearDnsAndGeoIpByIds(ids)
                },
                enabled = !uiState.isLoading && hostsWithDnsOrGeo.isNotEmpty(),
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.tertiary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Clr (${hostsWithDnsOrGeo.size})", fontSize = 11.sp)
            }

            // 4. Clear Visible - только красная иконка урны
            IconButton(
                onClick = {
                    val ids = filteredHosts.map { it.id }
                    viewModel.clearVisibleHosts(ids)
                },
                enabled = !uiState.isLoading && filteredHosts.isNotEmpty(),
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete visible (${filteredHosts.size})",
                    modifier = Modifier.size(20.dp),
                    tint = if (!uiState.isLoading && filteredHosts.isNotEmpty()) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    }
                )
            }
        }

        // Вторая строка - фильтры по типу (All/Ygg/SNI)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("All", "Ygg", "SNI").forEach { filter ->
                FilterChip(
                    selected = typeFilter == filter,
                    onClick = { typeFilter = filter },
                    label = { Text("$filter ${typeFilterCounts[filter] ?: 0}", fontSize = 11.sp) },
                    leadingIcon = if (typeFilter == filter) {
                        { Icon(Icons.Default.Check, null, Modifier.size(14.dp)) }
                    } else null
                )
            }
        }

        // Третья строка - фильтры по адресу (All/DNS/NoDNS/IPv6/IPv4/IpOnly/Geo/NoGeo)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("All", "DNS", "NoDNS", "IPv6", "IPv4", "IpOnly", "Geo", "NoGeo").forEach { filter ->
                FilterChip(
                    selected = addressFilter == filter,
                    onClick = { addressFilter = filter },
                    label = { Text("$filter ${addressFilterCounts[filter] ?: 0}", fontSize = 11.sp) },
                    leadingIcon = if (addressFilter == filter) {
                        { Icon(Icons.Default.Check, null, Modifier.size(14.dp)) }
                    } else null
                )
            }
        }

        // Статус
        if (uiState.isLoading || uiState.statusMessage.isNotEmpty()) {
            Text(
                text = uiState.statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        if (filteredHosts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = if (uiState.totalCount == 0) {
                            "No hosts loaded\n\nUse Management tab to load lists"
                        } else {
                            "No hosts match filter"
                        },
                        modifier = Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filteredHosts) { host ->
                    HostItem(host = host)
                }
            }
        }
    }
}

// Проверка: есть ли DNS резолв (не считая чистые IP)
private fun hasDnsResolved(host: Host): Boolean {
    // Если это чистый IP - считаем что "резолв" есть (он не нужен)
    if (isIpOnly(host)) return true
    // Иначе проверяем наличие dnsIp1
    return host.dnsIp1 != null
}

// Проверка на IPv6 адрес (включая резолвленные и ip6./ipv6. в имени)
private fun isIpv6Host(host: Host): Boolean {
    val addr = host.address.lowercase()

    // "ip6." или "ipv6." в имени хоста (например ip6.example.com)
    if (addr.contains("ip6.") || addr.contains("ipv6.")) {
        return true
    }

    // IPv6 адрес без скобок (содержит двоеточия, но не как часть URL схемы)
    if (addr.contains(":") && !addr.contains("://") && !addr.contains("[")) {
        return true
    }

    // IPv6 в квадратных скобках в hostString - это IP литерал
    val bracketMatch = Regex("\\[([^\\]]+)\\]").find(host.hostString)
    if (bracketMatch != null) {
        val inBrackets = bracketMatch.groupValues[1]
        if (inBrackets.contains(":")) {
            return true
        }
    }

    // Резолвленные DNS IP - IPv6 (содержат двоеточия)
    if (host.dnsIp1?.contains(":") == true ||
        host.dnsIp2?.contains(":") == true ||
        host.dnsIp3?.contains(":") == true) {
        return true
    }

    return false
}


// Проверка на IPv4 (включая резолвленные DNS IP)
private fun isIpv4Host(host: Host): Boolean {
    val ipv4Regex = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")
    // Сам адрес IPv4
    if (ipv4Regex.matches(host.address)) return true
    // Резолвленный DNS IP - IPv4
    if (host.dnsIp1 != null && ipv4Regex.matches(host.dnsIp1)) return true
    if (host.dnsIp2 != null && ipv4Regex.matches(host.dnsIp2)) return true
    if (host.dnsIp3 != null && ipv4Regex.matches(host.dnsIp3)) return true
    return false
}

// Проверка на чистый IP (без DNS имени) - IPv4 или IPv6
private fun isIpOnly(host: Host): Boolean {
    // Убираем порт из address если есть (example.com:443 -> example.com)
    val cleanAddress = host.address.substringBefore(":")

    // IPv4 проверка
    val ipv4Regex = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")
    if (ipv4Regex.matches(cleanAddress)) return true

    // IPv6 проверка - должно быть минимум 2 двоеточия (например 2001:db8::1)
    // Также IPv6 может быть в скобках [2001:db8::1]
    val ipv6Regex = Regex("""^[0-9a-fA-F:]+$""")
    if (host.address.count { it == ':' } >= 2 && ipv6Regex.matches(host.address)) return true

    // IPv6 в квадратных скобках в hostString (например [2001:db8::1]:port)
    val bracketMatch = Regex("\\[([^\\]]+)\\]").find(host.hostString)
    if (bracketMatch != null) {
        val inBrackets = bracketMatch.groupValues[1]
        // IPv6 литерал в скобках (минимум 2 двоеточия)
        if (inBrackets.count { it == ':' } >= 2) return true
        // IPv4 литерал в скобках (редко)
        if (ipv4Regex.matches(inBrackets)) return true
    }

    return false
}

@Composable
private fun HostItem(host: Host) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Первая строка: тип хоста + регион + GeoIP CC:City + иконки
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Левая часть: тип + TLD + регион + GeoIP CC:City
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Тип хоста (TCP/TLS/QUIC/WS/WSS/SNI/HTTP/HTTPS)
                    Text(
                        text = host.hostType.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            Host.isYggType(host.hostType) -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.secondary
                        }
                    )
                    // TLD (домен первого уровня: .com, .org, .ru)
                    extractTld(host.address)?.let { tld ->
                        Text(
                            text = tld,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    // Регион из ygg списков (например Armenia)
                    host.region?.let { region ->
                        Text(
                            text = region.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // GeoIP CC:City (например US:Washington)
                    host.geoIp?.takeIf { it.isNotEmpty() }?.let { geo ->
                        Text(
                            text = geo,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }

                // Правая часть: иконки (GeoIP + DNS)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Иконка GeoIP (если заполнено)
                    if (!host.geoIp.isNullOrEmpty()) {
                        Icon(
                            imageVector = Icons.Default.Public,
                            contentDescription = "GeoIP resolved",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    // Иконка DNS (если резолвлено)
                    if (host.dnsIp1 != null) {
                        Icon(
                            imageVector = Icons.Default.Dns,
                            contentDescription = "DNS resolved",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Полная строка хоста (protocol://address:port?params)
            Text(
                text = host.hostString,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Адрес и порт отдельно
            Text(
                text = host.address + (host.port?.let { ":$it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // DNS IP - дата резолва отдельной строкой, затем только заполненные адреса
            val dnsIps = listOfNotNull(host.dnsIp1, host.dnsIp2, host.dnsIp3)
            if (dnsIps.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    // Дата резолва над всеми IP
                    host.dnsTimestamp?.let { ts ->
                        Text(
                            text = "Resolved: ${formatDate(ts)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }

                    // Только заполненные IP
                    dnsIps.forEachIndexed { index, ip ->
                        Text(
                            text = "IP${index + 1}: $ip",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Источник и дата добавления
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatSource(host.source),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f)
                )
                Text(
                    text = formatDate(host.dateAdded),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatSource(source: String): String {
    return when {
        source.contains("neilalexander") -> "ygg:neil"
        source.contains("yggdrasil.link") -> "ygg:link"
        source.contains("miniwhite") -> "miniwhite"
        source.contains("miniblack") -> "miniblack"
        source.contains("zieng2") || source.contains("vless") -> "vless:zieng"
        source.contains("whitelist") -> "whitelist"
        source == "clipboard" -> "clipboard"
        source == "file" -> "file"
        source.startsWith("file:") -> source.substringAfter("file:").take(15)
        else -> source.take(15)
    }
}

// Извлечение TLD (домен первого уровня) из адреса
private fun extractTld(address: String): String? {
    // Пропускаем IP адреса
    val ipv4Regex = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")
    if (ipv4Regex.matches(address)) return null
    if (address.contains(":") && !address.contains(".")) return null // IPv6 без домена

    // Извлекаем домен из адреса
    val domain = address
        .substringBefore(":")  // убираем порт
        .substringBefore("/")  // убираем путь
        .trim()

    // Ищем TLD (последнюю часть после точки)
    val parts = domain.split(".")
    if (parts.size < 2) return null

    val tld = parts.last().lowercase()
    // Фильтруем невалидные TLD (слишком длинные или пустые)
    if (tld.isEmpty() || tld.length > 10) return null

    return ".$tld"
}
