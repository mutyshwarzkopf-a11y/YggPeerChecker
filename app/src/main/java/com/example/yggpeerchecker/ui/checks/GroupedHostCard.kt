package com.example.yggpeerchecker.ui.checks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.yggpeerchecker.R
import com.example.yggpeerchecker.data.AddressType
import com.example.yggpeerchecker.data.EndpointCheckResult
import com.example.yggpeerchecker.data.GroupedHost
import com.example.yggpeerchecker.data.HostAddress
import com.example.yggpeerchecker.data.HostEndpoint
import com.example.yggpeerchecker.ui.theme.OnlineGreen

/**
 * Карточка сгруппированного хоста
 */
@Composable
fun GroupedHostCard(
    group: GroupedHost,
    selectedEndpoints: Set<String>,
    onToggleEndpoint: (String) -> Unit,
    onSelectAllAlive: (List<String>) -> Unit,  // Callback для Select All живых endpoints
    modifier: Modifier = Modifier
) {
    // Собираем все живые endpoints для Select All
    val aliveEndpointUrls = group.endpoints.flatMap { endpoint ->
        endpoint.checkResults.filter { it.isAlive }.map { it.fullUrl }
    }
    val allAliveSelected = aliveEndpointUrls.isNotEmpty() &&
        aliveEndpointUrls.all { it in selectedEndpoints }

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
            // === HEADER: метаданные группы с Select All ===
            GroupHeaderRow(
                group = group,
                hasAliveEndpoints = aliveEndpointUrls.isNotEmpty(),
                allAliveSelected = allAliveSelected,
                onSelectAll = { onSelectAllAlive(aliveEndpointUrls) }
            )

            // === ADDRESSES: список адресов с общими проверками ===
            group.addresses.forEach { addr ->
                AddressRow(addr)
            }

            Divider(
                modifier = Modifier.padding(vertical = 6.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )

            // === ENDPOINTS: варианты подключения (с checkbox) ===
            group.endpoints.forEach { endpoint ->
                EndpointSection(
                    endpoint = endpoint,
                    selectedEndpoints = selectedEndpoints,
                    onToggleEndpoint = onToggleEndpoint
                )
            }
        }
    }
}

/**
 * Заголовок группы: Select All + source region GeoIP hops
 */
@Composable
private fun GroupHeaderRow(
    group: GroupedHost,
    hasAliveEndpoints: Boolean,
    allAliveSelected: Boolean,
    onSelectAll: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Левая часть: Select All кнопка (если есть живые endpoints)
        if (hasAliveEndpoints) {
            IconButton(
                onClick = onSelectAll,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SelectAll,
                    contentDescription = "Select All Alive",
                    tint = if (allAliveSelected) OnlineGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            Spacer(modifier = Modifier.width(28.dp))
        }

        // Правая часть: метаданные
        Row(
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Source (ygg:neil, miniblack, etc.)
            Text(
                text = group.shortSource(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Region
            if (!group.region.isNullOrEmpty()) {
                Text(
                    text = group.region,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
            }

            // GeoIP
            if (!group.geoIp.isNullOrEmpty()) {
                Text(
                    text = group.geoIp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.width(12.dp))
            }

            // Hops
            if (group.hops > 0) {
                Text(
                    text = "hops:${group.hops}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

/**
 * Строка адреса: type: address  Ping P80 P443  status
 */
@Composable
private fun AddressRow(addr: HostAddress) {
    // Определяем тип адреса для визуального отличия
    val isHst = addr.type == AddressType.HST
    val isIp0 = addr.type == AddressType.IP0  // Текущий резолвленный IP (отличается от кэшированных)
    val isFallback = !isHst && !isIp0  // ip1-ip5
    // Обнаружение DNS spoofing (localhost адреса) — для любого типа IP
    val localhostSet = setOf("127.0.0.1", "0.0.0.0", "::1", "localhost", "127.0.0.0", "0:0:0:0:0:0:0:1")
    val isSpoofed = (isIp0 || isFallback) && addr.address.lowercase().trim() in localhostSet

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Тип и адрес
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Тип (hst, ip0, ip1, ip2, ip3) с визуальным отличием
            Text(
                text = "${addr.type.displayName}:",
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    isIp0 -> MaterialTheme.colorScheme.error  // ip0 выделен красным (текущий, отличается)
                    isHst -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.tertiary
                },
                modifier = Modifier.width(32.dp),
                fontWeight = if (isHst || isIp0) FontWeight.Medium else FontWeight.Normal
            )

            // Адрес
            Text(
                text = addr.address,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                color = when {
                    isSpoofed -> Color.Red
                    isFallback -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
            // Иконка DNS сервера-источника (для ip1-ip5)
            if (addr.dnsSource != null && isFallback) {
                Spacer(modifier = Modifier.width(2.dp))
                DnsSourceIndicator(addr.dnsSource)
            }
            // Индикатор DNS spoofing
            if (isSpoofed) {
                Text(
                    text = " DNS!",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Red,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp
                )
            }
        }

        // Результаты общих проверок
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CheckChip("Ping", addr.pingResult)
            CheckChip("P80", addr.port80Result)
            CheckChip("P443", addr.port443Result)

            // Индикатор живости
            Text(
                text = if (addr.isAlive) "*" else "x",
                color = if (addr.isAlive) OnlineGreen else Color.Red,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

/**
 * Секция endpoint с результатами для всех адресов
 */
@Composable
private fun EndpointSection(
    endpoint: HostEndpoint,
    selectedEndpoints: Set<String>,
    onToggleEndpoint: (String) -> Unit
) {
    endpoint.checkResults.forEach { result ->
        EndpointRow(
            endpoint = endpoint,
            result = result,
            isSelected = selectedEndpoints.contains(result.fullUrl),
            onToggle = { onToggleEndpoint(result.fullUrl) }
        )
    }
}

/**
 * Строка endpoint: checkbox protocol://type:port  YRtt Pdef  status
 */
@Composable
private fun EndpointRow(
    endpoint: HostEndpoint,
    result: EndpointCheckResult,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    // Определяем, является ли endpoint fallback (ip1/ip2/ip3)
    val isFallback = result.addressType != AddressType.HST

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Checkbox
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() },
            modifier = Modifier.size(28.dp)
        )

        Spacer(modifier = Modifier.width(4.dp))

        // protocol://type:port с визуальным отличием для fallback
        Text(
            text = endpoint.shortDisplay(result.addressType),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
            color = if (isFallback) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )

        // Результаты специфичных проверок
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CheckChip("YRtt", result.yggRttMs)
            CheckChip("Pdef", result.portDefaultMs)

            // Индикатор живости
            Text(
                text = if (result.isAlive) "*" else "x",
                color = if (result.isAlive) OnlineGreen else Color.Red,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

/**
 * Индикатор DNS сервера-источника рядом с IP адресом
 */
@Composable
private fun DnsSourceIndicator(source: String) {
    when (source.lowercase()) {
        "yandex" -> androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.ic_dns_yandex),
            contentDescription = "Yandex DNS",
            modifier = Modifier.size(12.dp)
        )
        "cloudflare" -> androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.ic_dns_cloudflare),
            contentDescription = "Cloudflare DNS",
            modifier = Modifier.size(12.dp)
        )
        "google" -> androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.ic_dns_google),
            contentDescription = "Google DNS",
            modifier = Modifier.size(12.dp)
        )
        "system" -> androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.ic_dns_system),
            contentDescription = "System DNS",
            modifier = Modifier.size(12.dp)
        )
        else -> Text(
            text = "($source)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 8.sp
        )
    }
}

/**
 * Чип с результатом проверки
 */
@Composable
private fun CheckChip(label: String, value: Long) {
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
