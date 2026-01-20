package com.example.yggpeerchecker.ui.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.yggpeerchecker.ui.theme.ThemeManager
import com.example.yggpeerchecker.ui.theme.ThemeMode
import kotlin.math.roundToInt

private const val CURRENT_VERSION = "0.6.1"
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

    // Check interfaces toggle
    var checkInterfaces by remember {
        mutableStateOf(prefs.getBoolean("check_interfaces", true))
    }

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
                    valueRange = 1f..30f,
                    steps = 28,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Controls parallel network checks (1-30)",
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
                                        conn.connectTimeout = 10000
                                        conn.readTimeout = 10000

                                        if (conn.responseCode == 200) {
                                            val response = conn.inputStream.bufferedReader().readText()
                                            val releases = JSONArray(response)
                                            if (releases.length() > 0) {
                                                val latest = releases.getJSONObject(0)
                                                latest.getString("tag_name").removePrefix("v")
                                            } else null
                                        } else null
                                    }

                                    if (result != null && result != CURRENT_VERSION) {
                                        updateStatus = "New version: $result"
                                        Toast.makeText(context, "Update available: $result", Toast.LENGTH_LONG).show()
                                    } else if (result == CURRENT_VERSION) {
                                        updateStatus = "Up to date"
                                    } else {
                                        updateStatus = "No releases found"
                                    }
                                } catch (e: Exception) {
                                    updateStatus = "Error: ${e.message?.take(30)}"
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
                        color = if (updateStatus.startsWith("New"))
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
