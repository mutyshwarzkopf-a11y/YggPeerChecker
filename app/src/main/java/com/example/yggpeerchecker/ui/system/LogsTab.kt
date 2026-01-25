package com.example.yggpeerchecker.ui.system

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.yggpeerchecker.utils.PersistentLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TerminalGreen = Color(0xFF00FF00)
private val TerminalBlack = Color(0xFF0A0A0A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsTab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val logger = remember { PersistentLogger(context) }
    val scope = rememberCoroutineScope()

    var logText by remember { mutableStateOf("Loading logs...") }
    var selectedLevel by remember { mutableStateOf(PersistentLogger.LogLevel.OFF) }
    var showLevelMenu by remember { mutableStateOf(false) }

    // Загружаем настройки и логи при первой композиции
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            // Читаем настройки уровня логирования
            val level = logger.minLogLevel
            // Читаем логи безопасно
            val logs = try {
                val file = logger.getLogFile()
                if (file != null && file.exists() && file.length() > 0) {
                    // Читаем последние 50KB чтобы избежать OOM
                    val maxSize = 50 * 1024L
                    if (file.length() > maxSize) {
                        file.inputStream().use { stream ->
                            stream.skip(file.length() - maxSize)
                            stream.bufferedReader().readText()
                        }
                    } else {
                        file.readText()
                    }
                } else {
                    "No logs yet. Set log level above and use the app."
                }
            } catch (e: Exception) {
                "Error reading logs: ${e.message}"
            }
            withContext(Dispatchers.Main) {
                selectedLevel = level
                logText = logs
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        // Compact control row: Level dropdown + clear/share icons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Level toggle button (OFF/ERROR/ALL)
            Button(
                onClick = { showLevelMenu = true },
                modifier = Modifier.height(36.dp)
            ) {
                Text(
                    when (selectedLevel) {
                        PersistentLogger.LogLevel.ALL -> "ALL"
                        PersistentLogger.LogLevel.ERROR -> "ERROR"
                        else -> "OFF"
                    },
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }

            // Dropdown menu - OFF, ERROR, ALL
            DropdownMenu(
                expanded = showLevelMenu,
                onDismissRequest = { showLevelMenu = false }
            ) {
                listOf(
                    PersistentLogger.LogLevel.OFF to "OFF - disabled",
                    PersistentLogger.LogLevel.ERROR to "ERROR - errors only",
                    PersistentLogger.LogLevel.ALL to "ALL - all logs"
                ).forEach { (level, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            selectedLevel = level
                            showLevelMenu = false
                            scope.launch(Dispatchers.IO) {
                                logger.setLogLevel(level)
                                // Читаем логи безопасно
                                val logs = try {
                                    val file = logger.getLogFile()
                                    if (file != null && file.exists() && file.length() > 0) {
                                        val maxSize = 50 * 1024L
                                        if (file.length() > maxSize) {
                                            file.inputStream().use { stream ->
                                                stream.skip(file.length() - maxSize)
                                                stream.bufferedReader().readText()
                                            }
                                        } else {
                                            file.readText()
                                        }
                                    } else {
                                        "No logs yet. Set log level and use the app."
                                    }
                                } catch (e: Exception) {
                                    "Error reading logs: ${e.message}"
                                }
                                withContext(Dispatchers.Main) {
                                    logText = logs
                                }
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Clear icon
            IconButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        logger.getLogFile()?.delete()
                        withContext(Dispatchers.Main) {
                            logText = "Logs cleared"
                            Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.Clear,
                    contentDescription = "Clear",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            // Share icon
            IconButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        val logFile = logger.getLogFile()
                        if (logFile != null) {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                logFile
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            // startActivity должен вызываться на Main thread
                            withContext(Dispatchers.Main) {
                                context.startActivity(Intent.createChooser(intent, "Share Logs"))
                            }
                        }
                    }
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = "Share",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Log display - green on black terminal style
        Text(
            text = logText,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .background(TerminalBlack)
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            color = TerminalGreen
        )
    }
}
