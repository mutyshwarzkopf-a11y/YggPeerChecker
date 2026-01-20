package com.example.yggpeerchecker.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class PersistentLogger(private val context: Context) {
    // Два режима: OFF - выключено, ALL - пишет всё
    enum class LogLevel {
        OFF,   // Выключено
        ALL    // Пишет всё
    }

    private val logFilePath: File
        get() = File(context.filesDir, "yggpeerchecker.log")

    private val settingsFile: File
        get() = File(context.filesDir, "logger_settings.txt")

    private val maxLogSize = 2 * 1024 * 1024 // 2 MB
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    var minLogLevel: LogLevel = loadLogLevel()
        private set

    private fun loadLogLevel(): LogLevel {
        return try {
            if (settingsFile.exists()) {
                val level = settingsFile.readText().trim()
                LogLevel.valueOf(level)
            } else {
                LogLevel.OFF
            }
        } catch (e: Exception) {
            LogLevel.OFF
        }
    }

    fun setLogLevel(level: LogLevel) {
        minLogLevel = level
        try {
            settingsFile.writeText(level.name)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Синхронная версия для callback из Go
    @Synchronized
    fun appendLogSync(level: String, message: String) {
        try {
            // OFF = не пишем, ALL = пишем всё
            if (minLogLevel == LogLevel.OFF) return

            val timestamp = dateFormat.format(Date())
            val logEntry = "[$timestamp] [$level] $message\n"

            if (logFilePath.exists() && logFilePath.length() >= maxLogSize) {
                trimLogFile()
            }

            logFilePath.appendText(logEntry)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun appendLog(message: String) = withContext(Dispatchers.IO) {
        appendLogSync("INFO", message)
    }

    suspend fun readLogs(): List<String> = withContext(Dispatchers.IO) {
        try {
            if (!logFilePath.exists()) {
                return@withContext emptyList()
            }
            logFilePath.readLines().takeLast(500)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun clearLogs() = withContext(Dispatchers.IO) {
        try {
            if (logFilePath.exists()) {
                logFilePath.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getLogFile(): File? {
        return if (logFilePath.exists()) logFilePath else null
    }

    fun getLogSize(): Long {
        return if (logFilePath.exists()) logFilePath.length() else 0L
    }

    private fun trimLogFile() {
        try {
            val lines = logFilePath.readLines()
            val keepLines = (lines.size * 0.5).toInt()
            val trimmedContent = lines.takeLast(keepLines).joinToString("\n") + "\n"
            logFilePath.writeText(trimmedContent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
