package com.example.yggpeerchecker.ui.lists

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.BufferedReader
import java.io.InputStreamReader

@Composable
fun ManagementTab(
    modifier: Modifier = Modifier,
    viewModel: ListsViewModel
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    
    // Состояние для диалога Clipboard
    var showClipboardDialog by remember { mutableStateOf(false) }
    var clipboardText by remember { mutableStateOf("") }

    // Лаунчер для выбора файла
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val reader = BufferedReader(InputStreamReader(inputStream))
                val text = reader.readText()
                reader.close()
                val fileName = it.lastPathSegment ?: "unknown"
                viewModel.loadFromFile(text, fileName)
            } catch (e: Exception) {
                Toast.makeText(context, "Error reading file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Статусная карточка
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = uiState.statusMessage,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatItem("Total", uiState.totalCount)
                    StatItem("Ygg", uiState.yggCount)
                    StatItem("SNI", uiState.sniCount)
                    StatItem("DNS", uiState.resolvedCount)
                }
            }
        }

        // Кнопка очистки
        OutlinedButton(
            onClick = { viewModel.clearAll() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !uiState.isLoading && uiState.totalCount > 0,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Icon(Icons.Default.Delete, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Clear All Lists")
        }

        // Загрузка Ygg peers
        Text(
            text = "Ygg Peers",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp)
        )

        Button(
            onClick = { viewModel.loadYggNeilalexander() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !uiState.isLoading
        ) {
            Icon(Icons.Default.Download, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Load (neilalexander)")
        }

        Button(
            onClick = { viewModel.loadYggLink() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !uiState.isLoading
        ) {
            Icon(Icons.Default.Download, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Load (yggdrasil.link)")
        }

        // Загрузка whitelist
        Text(
            text = "Whitelists",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp)
        )

        Button(
            onClick = { viewModel.loadWhitelist() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !uiState.isLoading
        ) {
            Icon(Icons.Default.Security, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Load RU Whitelists")
        }

        // Загрузка из файла/буфера
        Text(
            text = "Custom Lists",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp)
        )

        Button(
            onClick = { filePickerLauncher.launch("text/*") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !uiState.isLoading
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Load from File")
        }

        Button(
            onClick = {
                // Открываем диалог, предварительно читая буфер обмена
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = clipboard.primaryClip
                val pastedText = if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0).text?.toString() ?: ""
                } else ""
                clipboardText = pastedText
                showClipboardDialog = true
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !uiState.isLoading
        ) {
            Icon(Icons.Default.ContentPaste, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Load from Clipboard")
        }
        
        // Диалог для ввода текста из буфера или вручную
        if (showClipboardDialog) {
            AlertDialog(
                onDismissRequest = { showClipboardDialog = false },
                title = { Text("Paste or Enter Hosts") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Enter hosts (one per line) or paste from clipboard:",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            value = clipboardText,
                            onValueChange = { clipboardText = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("tcp://host:port\nhttps://example.com\n...") },
                            minLines = 5,
                            maxLines = 10
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = clipboard.primaryClip
                                    if (clip != null && clip.itemCount > 0) {
                                        val text = clip.getItemAt(0).text?.toString() ?: ""
                                        clipboardText = if (clipboardText.isNotEmpty()) {
                                            clipboardText + "\n" + text
                                        } else {
                                            text
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Paste", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (clipboardText.isNotEmpty()) {
                                viewModel.loadFromClipboard(clipboardText)
                                showClipboardDialog = false
                                clipboardText = ""
                            } else {
                                Toast.makeText(context, "Text is empty", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text("Load")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { 
                        showClipboardDialog = false
                        clipboardText = ""
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Подсказка про DNS
        Text(
            text = "* Use View tab to Fill/Clear DNS IPs",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp)
        )
    }
}

@Composable
private fun StatItem(label: String, count: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
