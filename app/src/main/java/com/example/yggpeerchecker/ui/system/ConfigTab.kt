package com.example.yggpeerchecker.ui.system

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.yggpeerchecker.ui.theme.ThemeManager
import com.example.yggpeerchecker.ui.theme.ThemeMode
import kotlin.math.roundToInt

// Предустановленные DNS серверы
object DnsServers {
    const val SYSTEM = "system"
    const val YANDEX = "77.88.8.8"
    const val YANDEX_SECONDARY = "77.88.8.1"
    const val CLOUDFLARE = "1.1.1.1"
    const val CLOUDFLARE_SECONDARY = "1.0.0.1"
    const val GOOGLE = "8.8.8.8"
    const val GOOGLE_SECONDARY = "8.8.4.4"
}

// Версия берётся из BuildConfig (fallback "unknown" если недоступен)
private val CURRENT_VERSION: String
    get() = try {
        com.example.yggpeerchecker.BuildConfig.VERSION_NAME
    } catch (e: Exception) {
        "unknown"
    }
private const val GITHUB_RELEASES_API = "https://api.github.com/repos/mutyshwarzkopf-a11y/YggPeerChecker/releases"

@Composable
fun ConfigTab(modifier: Modifier = Modifier, themeManager: ThemeManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentTheme by themeManager.themeMode.collectAsState()
    val prefs = context.getSharedPreferences("ygg_prefs", android.content.Context.MODE_PRIVATE)

    // Состояние для проверки обновлений
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf("") }

    // Concurrent streams
    var concurrentStreams by remember {
        mutableFloatStateOf(prefs.getInt("concurrent_streams", 10).toFloat())
    }

    // GeoIP delay (40-100ms)
    var geoIpDelay by remember {
        mutableFloatStateOf(prefs.getInt("geoip_delay_ms", 40).toFloat())
    }

    // Check interfaces toggle
    var checkInterfaces by remember {
        mutableStateOf(prefs.getBoolean("check_interfaces", true))
    }

    // DNS Settings
    var selectedDns by remember {
        mutableStateOf(prefs.getString("dns_server", DnsServers.YANDEX) ?: DnsServers.YANDEX)
    }
    var customDns by remember {
        mutableStateOf(prefs.getString("custom_dns", "") ?: "")
    }
    var systemDns by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Network Settings section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Network Settings",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Concurrent streams slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Concurrent streams:")
                    Text(
                        text = "${concurrentStreams.roundToInt()}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = concurrentStreams,
                    onValueChange = { newValue ->
                        concurrentStreams = newValue
                        prefs.edit().putInt("concurrent_streams", newValue.roundToInt()).apply()
                    },
                    valueRange = 1f..20f,
                    steps = 18,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Controls parallel network checks (1-20)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // GeoIP delay slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("GeoIP request delay:")
                    Text(
                        text = "${geoIpDelay.roundToInt()} ms",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = geoIpDelay,
                    onValueChange = { newValue ->
                        geoIpDelay = newValue
                        prefs.edit().putInt("geoip_delay_ms", newValue.roundToInt()).apply()
                    },
                    valueRange = 40f..100f,
                    steps = 5,  // 40, 50, 60, 70, 80, 90, 100
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Delay between GeoIP API requests (40-100ms)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Check interfaces toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Check network interfaces")
                        Text(
                            text = "Verify network before scan",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = checkInterfaces,
                        onCheckedChange = { newValue ->
                            checkInterfaces = newValue
                            prefs.edit().putBoolean("check_interfaces", newValue).apply()
                        }
                    )
                }
            }
        }

        // DNS Settings section
        @OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "DNS Settings",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "DNS server for hostname resolution (app only)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Чипы с DNS серверами (компактно в одну строку)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    DnsChip(
                        label = "Yandex",
                        ip = DnsServers.YANDEX,
                        icon = Icons.Default.Shield,
                        isSelected = selectedDns == DnsServers.YANDEX,
                        onClick = {
                            selectedDns = DnsServers.YANDEX
                            customDns = DnsServers.YANDEX
                            prefs.edit().putString("dns_server", DnsServers.YANDEX).apply()
                        }
                    )
                    DnsChip(
                        label = "Google",
                        ip = DnsServers.GOOGLE,
                        icon = Icons.Default.Language,
                        isSelected = selectedDns == DnsServers.GOOGLE,
                        onClick = {
                            selectedDns = DnsServers.GOOGLE
                            customDns = DnsServers.GOOGLE
                            prefs.edit().putString("dns_server", DnsServers.GOOGLE).apply()
                        }
                    )
                    DnsChip(
                        label = "Cloudflare",
                        ip = DnsServers.CLOUDFLARE,
                        icon = Icons.Default.Cloud,
                        isSelected = selectedDns == DnsServers.CLOUDFLARE,
                        onClick = {
                            selectedDns = DnsServers.CLOUDFLARE
                            customDns = DnsServers.CLOUDFLARE
                            prefs.edit().putString("dns_server", DnsServers.CLOUDFLARE).apply()
                        }
                    )
                    DnsChip(
                        label = "System",
                        ip = if (systemDns.isNotEmpty()) systemDns else "detect",
                        icon = Icons.Default.Settings,
                        isSelected = systemDns.isNotEmpty() && selectedDns == systemDns,
                        onClick = {
                            // Получаем системный DNS
                            val detected = getSystemDns(context)
                            if (detected.isNotEmpty()) {
                                systemDns = detected
                                selectedDns = detected
                                customDns = detected
                                prefs.edit().putString("dns_server", detected).apply()
                                Toast.makeText(context, "System DNS: $detected", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Could not detect system DNS", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Поле DNS адреса + кнопка Set
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = customDns,
                        onValueChange = { customDns = it },
                        label = { Text("DNS server IP") },
                        placeholder = { Text("e.g. 9.9.9.9") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val ip = customDns.trim()
                            if (ip.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))) {
                                selectedDns = ip
                                prefs.edit().putString("dns_server", ip).apply()
                                Toast.makeText(context, "DNS set: $ip", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Invalid IP address", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = customDns.isNotEmpty()
                    ) {
                        Text("Set")
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Active: $selectedDns",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Theme section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Theme",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThemeMode.entries.forEach { mode ->
                        if (mode == currentTheme) {
                            Button(
                                onClick = { themeManager.setThemeMode(mode) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
                            }
                        } else {
                            OutlinedButton(
                                onClick = { themeManager.setThemeMode(mode) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                            ) {
                                Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
                            }
                        }
                    }
                }
            }
        }

        // About section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "About",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("Version: $CURRENT_VERSION")

                Spacer(modifier = Modifier.height(8.dp))

                // GitHub link button - Yggdrasil Network
                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/yggdrasil-network"))
                        context.startActivity(intent)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Code,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Yggdrasil Network")
                }

                Spacer(modifier = Modifier.height(8.dp))

                // GitHub link button - YggPeerChecker project
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mutyshwarzkopf-a11y/YggPeerChecker"))
                        context.startActivity(intent)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Code,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("YggPeerChecker Project")
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Check for updates button
                OutlinedButton(
                    onClick = {
                        if (!isCheckingUpdate) {
                            isCheckingUpdate = true
                            updateStatus = "Checking..."
                            scope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        val url = java.net.URL(GITHUB_RELEASES_API)
                                        val conn = url.openConnection() as java.net.HttpURLConnection
                                        conn.requestMethod = "GET"
                                        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                                        conn.setRequestProperty("User-Agent", "YggPeerChecker/$CURRENT_VERSION")
                                        conn.connectTimeout = 10000
                                        conn.readTimeout = 10000

                                        val responseCode = conn.responseCode
                                        if (responseCode == 200) {
                                            val response = conn.inputStream.bufferedReader().readText()
                                            val releases = JSONArray(response)
                                            if (releases.length() > 0) {
                                                val latest = releases.getJSONObject(0)
                                                latest.getString("tag_name").removePrefix("v")
                                            } else "no_releases"
                                        } else {
                                            "http_$responseCode"
                                        }
                                    }

                                    when {
                                        result == "no_releases" -> {
                                            updateStatus = "No releases yet (v$CURRENT_VERSION is dev)"
                                        }
                                        result.startsWith("http_") -> {
                                            updateStatus = "GitHub API error: ${result.removePrefix("http_")}"
                                        }
                                        compareVersions(result, CURRENT_VERSION) > 0 -> {
                                            updateStatus = "New: v$result (current: v$CURRENT_VERSION)"
                                            Toast.makeText(context, "Update available: v$result", Toast.LENGTH_LONG).show()
                                        }
                                        compareVersions(result, CURRENT_VERSION) < 0 -> {
                                            updateStatus = "Dev version (latest release: v$result)"
                                        }
                                        else -> {
                                            updateStatus = "Up to date (v$CURRENT_VERSION)"
                                        }
                                    }
                                } catch (e: java.net.UnknownHostException) {
                                    updateStatus = "No internet connection"
                                } catch (e: java.net.SocketTimeoutException) {
                                    updateStatus = "Connection timeout"
                                } catch (e: Exception) {
                                    updateStatus = "Error: ${e.javaClass.simpleName}"
                                }
                                isCheckingUpdate = false
                            }
                        }
                    },
                    enabled = !isCheckingUpdate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                ) {
                    if (isCheckingUpdate) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Check for Updates")
                }

                if (updateStatus.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = updateStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            updateStatus.startsWith("New") -> MaterialTheme.colorScheme.primary
                            updateStatus.startsWith("Dev") -> MaterialTheme.colorScheme.tertiary
                            updateStatus.startsWith("Up to date") -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

// Сравнение семантических версий: 1 если v1 > v2, -1 если v1 < v2, 0 если равны
private fun compareVersions(v1: String, v2: String): Int {
    val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
    val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }

    val maxLen = maxOf(parts1.size, parts2.size)
    for (i in 0 until maxLen) {
        val p1 = parts1.getOrElse(i) { 0 }
        val p2 = parts2.getOrElse(i) { 0 }
        if (p1 > p2) return 1
        if (p1 < p2) return -1
    }
    return 0
}

// Компактный чип DNS сервера с иконкой (влезает в одну строку)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DnsChip(
    label: String,
    @Suppress("UNUSED_PARAMETER") ip: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    ElevatedFilterChip(
        selected = isSelected,
        onClick = onClick,
        label = { Text(label, fontSize = 10.sp, maxLines = 1) },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(12.dp)
            )
        },
        modifier = Modifier.height(28.dp),
        colors = FilterChipDefaults.elevatedFilterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

// Получение системного DNS
@Suppress("DEPRECATION")
private fun getSystemDns(context: Context): String {
    return try {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork
            val linkProperties = connectivityManager.getLinkProperties(network)
            val dnsServers = linkProperties?.dnsServers
            dnsServers?.firstOrNull()?.hostAddress ?: ""
        } else {
            // Для старых версий Android
            @Suppress("DEPRECATION")
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val dhcpInfo = wifiManager.dhcpInfo
            if (dhcpInfo.dns1 != 0) {
                intToIp(dhcpInfo.dns1)
            } else ""
        }
    } catch (e: Exception) {
        ""
    }
}

// Конвертация int в IP адрес
private fun intToIp(ip: Int): String {
    return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
}
