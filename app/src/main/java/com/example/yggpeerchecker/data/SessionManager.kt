package com.example.yggpeerchecker.data

import android.content.Context
import com.example.yggpeerchecker.ui.checks.HostCheckResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Менеджер сохраненных сессий проверок
 * Сохраняет результаты в JSON файлы
 */
class SessionManager(private val context: Context) {

    private val sessionsDir: File
        get() = File(context.filesDir, "sessions").also {
            if (!it.exists()) it.mkdirs()
        }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)

    data class SavedSession(
        val name: String,
        val fileName: String,
        val savedAt: Long,
        val peersCount: Int,
        val availableCount: Int
    )

    // Получение списка сохраненных сессий
    suspend fun getSessions(): List<SavedSession> = withContext(Dispatchers.IO) {
        sessionsDir.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    val json = JSONObject(file.readText())
                    SavedSession(
                        name = json.optString("name", file.nameWithoutExtension),
                        fileName = file.name,
                        savedAt = json.optLong("savedAt", file.lastModified()),
                        peersCount = json.optInt("peersCount", 0),
                        availableCount = json.optInt("availableCount", 0)
                    )
                } catch (e: Exception) {
                    null
                }
            }
            ?.sortedByDescending { it.savedAt }
            ?: emptyList()
    }

    // Сохранение сессии
    suspend fun saveSession(
        name: String,
        peers: List<DiscoveredPeer>,
        hostResults: Map<String, List<HostCheckResult>>
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val timestamp = System.currentTimeMillis()
            val fileName = "${dateFormat.format(Date(timestamp))}_${sanitizeFileName(name)}.json"
            val file = File(sessionsDir, fileName)

            val json = JSONObject().apply {
                put("name", name)
                put("savedAt", timestamp)
                put("peersCount", peers.size)
                put("availableCount", peers.count { it.isAlive() })

                // Сохраняем peers (полный формат для совместимости с экспортом)
                val peersArray = JSONArray()
                peers.forEach { peer ->
                    peersArray.put(peer.toSaveJson())
                }
                put("peers", peersArray)

                // Сохраняем hostResults
                val resultsObj = JSONObject()
                hostResults.forEach { (address, results) ->
                    val resultsArray = JSONArray()
                    results.forEach { result ->
                        resultsArray.put(JSONObject().apply {
                            put("target", result.target)
                            put("isMainAddress", result.isMainAddress)
                            put("pingTime", result.pingTime)
                            put("yggRtt", result.yggRtt)
                            put("portDefault", result.portDefault)
                            put("port80", result.port80)
                            put("port443", result.port443)
                            put("available", result.available)
                            put("error", result.error)
                        })
                    }
                    resultsObj.put(address, resultsArray)
                }
                put("hostResults", resultsObj)
            }

            file.writeText(json.toString(2))
            Result.success(fileName)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Загрузка сессии
    suspend fun loadSession(fileName: String): Result<Pair<List<DiscoveredPeer>, Map<String, List<HostCheckResult>>>> =
        withContext(Dispatchers.IO) {
            try {
                val file = File(sessionsDir, fileName)
                if (!file.exists()) {
                    return@withContext Result.failure(Exception("Session file not found"))
                }

                val json = JSONObject(file.readText())

                // Загружаем peers (поддерживаем полный и legacy формат)
                val peersArray = json.optJSONArray("peers") ?: JSONArray()
                val peers = mutableListOf<DiscoveredPeer>()
                for (i in 0 until peersArray.length()) {
                    try {
                        val peerJson = peersArray.getJSONObject(i)
                        peers.add(DiscoveredPeer.fromSaveJson(peerJson))
                    } catch (e: Exception) {
                        // Пропускаем битые записи
                    }
                }

                // Загружаем hostResults (lenient — старые сессии могут не иметь этого поля)
                val resultsObj = json.optJSONObject("hostResults")
                val hostResults = mutableMapOf<String, List<HostCheckResult>>()
                resultsObj?.keys()?.forEach { address ->
                    val resultsArray = resultsObj?.optJSONArray(address) ?: return@forEach
                    val results = mutableListOf<HostCheckResult>()
                    for (i in 0 until resultsArray.length()) {
                        val resultJson = resultsArray.getJSONObject(i)
                        results.add(HostCheckResult(
                            target = resultJson.optString("target", ""),
                            isMainAddress = resultJson.optBoolean("isMainAddress", true),
                            pingTime = resultJson.optLong("pingTime", -1),
                            yggRtt = resultJson.optLong("yggRtt", -1),
                            portDefault = resultJson.optLong("portDefault", -1),
                            port80 = resultJson.optLong("port80", -1),
                            port443 = resultJson.optLong("port443", -1),
                            available = resultJson.optBoolean("available", false),
                            error = resultJson.optString("error", "")
                        ))
                    }
                    hostResults[address] = results
                }

                Result.success(Pair(peers, hostResults))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // Удаление сессии
    suspend fun deleteSession(fileName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            File(sessionsDir, fileName).delete()
        } catch (e: Exception) {
            false
        }
    }

    // Генерация имени по умолчанию
    fun generateDefaultName(): String {
        return dateFormat.format(Date())
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(30)
    }
}
