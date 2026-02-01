package com.example.yggpeerchecker.ui.system

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.painterResource
import com.example.yggpeerchecker.R
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
    var latestVersion by remember { mutableStateOf("") }
    var downloadUrl by remember { mutableStateOf("") }
    var showDownloadDialog by remember { mutableStateOf(false) }

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

    // Ping count (1-5)
    var pingCount by remember {
        mutableFloatStateOf(prefs.getInt("ping_count", 1).toFloat())
    }

    // DNS Settings — дефолт system, влияет только на Checks
    var selectedDns by remember {
        mutableStateOf(prefs.getString("dns_server", DnsServers.SYSTEM) ?: DnsServers.SYSTEM)
    }
    var customDns by remember {
        mutableStateOf(prefs.getString("dns_server", DnsServers.SYSTEM) ?: DnsServers.SYSTEM)
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

                Spacer(modifier = Modifier.height(16.dp))

                // Ping count slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Ping count:")
                    Text(
                        text = "${pingCount.roundToInt()}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = pingCount,
                    onValueChange = { newValue ->
                        pingCount = newValue
                        prefs.edit().putInt("ping_count", newValue.roundToInt()).apply()
                    },
                    valueRange = 1f..5f,
                    steps = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Number of ping packets (1-5). Higher = more accurate avg RTT",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // DNS Settings — влияет на резолв при Checks проверках
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "DNS Settings",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "DNS server for Checks resolution",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Вертикальный список DNS серверов с иконками и IP
                val dnsServers = listOf(
                    Triple("Yandex", DnsServers.YANDEX, R.drawable.ic_dns_yandex),
                    Triple("Cloudflare", DnsServers.CLOUDFLARE, R.drawable.ic_dns_cloudflare),
                    Triple("Google", DnsServers.GOOGLE, R.drawable.ic_dns_google)
                )
                dnsServers.forEach { (name, ip, iconRes) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedDns = ip
                                customDns = ip
                                prefs.edit().putString("dns_server", ip).apply()
                            }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Image(
                            painter = painterResource(iconRes),
                            contentDescription = name,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = ip,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = name,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selectedDns == ip)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (selectedDns == ip) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                // System DNS — отдельная строка
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val detected = getSystemDns(context)
                            if (detected.isNotEmpty()) {
                                systemDns = detected
                            }
                            selectedDns = DnsServers.SYSTEM
                            customDns = if (systemDns.isNotEmpty()) systemDns else DnsServers.SYSTEM
                            prefs.edit().putString("dns_server", DnsServers.SYSTEM).apply()
                        }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_dns_system),
                        contentDescription = "System",
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = if (systemDns.isNotEmpty()) systemDns else "auto-detect",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "System",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selectedDns == DnsServers.SYSTEM)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (selectedDns == DnsServers.SYSTEM) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Поле DNS адреса + кнопка Set (можно ввести custom IP)
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
                            if (ip == DnsServers.SYSTEM || ip.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))) {
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
                                    val checkResult = withContext(Dispatchers.IO) {
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
                                                val version = latest.getString("tag_name").removePrefix("v")
                                                // Ищем APK в assets релиза
                                                val assets = latest.optJSONArray("assets")
                                                var apkUrl = ""
                                                if (assets != null) {
                                                    for (i in 0 until assets.length()) {
                                                        val asset = assets.getJSONObject(i)
                                                        val name = asset.optString("name", "")
                                                        if (name.endsWith(".apk")) {
                                                            apkUrl = asset.optString("browser_download_url", "")
                                                            break
                                                        }
                                                    }
                                                }
                                                Triple(version, apkUrl, "")
                                            } else Triple("", "", "no_releases")
                                        } else {
                                            Triple("", "", "http_$responseCode")
                                        }
                                    }

                                    val (version, apkUrl, error) = checkResult
                                    when {
                                        error == "no_releases" -> {
                                            updateStatus = "No releases yet (v$CURRENT_VERSION is dev)"
                                        }
                                        error.startsWith("http_") -> {
                                            updateStatus = "GitHub API error: ${error.removePrefix("http_")}"
                                        }
                                        version.isNotEmpty() && compareVersions(version, CURRENT_VERSION) > 0 -> {
                                            updateStatus = "New: v$version (current: v$CURRENT_VERSION)"
                                            latestVersion = version
                                            downloadUrl = apkUrl
                                            showDownloadDialog = true
                                        }
                                        version.isNotEmpty() && compareVersions(version, CURRENT_VERSION) < 0 -> {
                                            updateStatus = "Dev version (latest release: v$version)"
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

        // Диалог скачивания обновления
        if (showDownloadDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showDownloadDialog = false },
                title = { Text("Update Available") },
                text = {
                    Text("Version v$latestVersion is available.\nCurrent: v$CURRENT_VERSION\n\n" +
                        if (downloadUrl.isNotEmpty()) "Download and install?" else "No APK found in release. Open GitHub?")
                },
                confirmButton = {
                    Button(onClick = {
                        showDownloadDialog = false
                        if (downloadUrl.isNotEmpty()) {
                            // Скачиваем APK через DownloadManager
                            try {
                                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                val request = DownloadManager.Request(Uri.parse(downloadUrl))
                                    .setTitle("YggPeerChecker v$latestVersion")
                                    .setDescription("Downloading update...")
                                    .setDestinationInExternalPublicDir(
                                        Environment.DIRECTORY_DOWNLOADS,
                                        "YggPeerChecker-v$latestVersion.apk"
                                    )
                                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                    .setMimeType("application/vnd.android.package-archive")
                                dm.enqueue(request)
                                Toast.makeText(context, "Download started. Check notifications.", Toast.LENGTH_LONG).show()
                                updateStatus = "Downloading v$latestVersion..."
                            } catch (e: Exception) {
                                Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                                // Fallback — открываем в браузере
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)))
                            }
                        } else {
                            // Нет APK — открываем страницу релиза
                            context.startActivity(Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/mutyshwarzkopf-a11y/YggPeerChecker/releases")))
                        }
                    }) {
                        Text(if (downloadUrl.isNotEmpty()) "Download" else "Open GitHub")
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showDownloadDialog = false }) {
                        Text("Later")
                    }
                }
            )
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

// Компактный чип DNS сервера с иконкой из drawable ресурсов
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DnsChip(
    label: String,
    @Suppress("UNUSED_PARAMETER") ip: String,
    iconPainter: Painter,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    ElevatedFilterChip(
        selected = isSelected,
        onClick = onClick,
        label = { Text(label, fontSize = 10.sp, maxLines = 1) },
        leadingIcon = {
            androidx.compose.foundation.Image(
                painter = iconPainter,
                contentDescription = null,
                modifier = Modifier.size(14.dp)
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
