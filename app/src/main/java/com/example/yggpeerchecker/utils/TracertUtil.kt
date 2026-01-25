package com.example.yggpeerchecker.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress

/**
 * Утилита для определения количества хопов до хоста.
 * Использует ping с увеличивающимся TTL для симуляции traceroute.
 */
object TracertUtil {

    private const val MAX_HOPS = 30
    private const val TIMEOUT_MS = 2000

    /**
     * Определяет количество хопов до хоста.
     * Возвращает количество хопов или -1 если не удалось определить.
     */
    suspend fun getHops(host: String): Int = withContext(Dispatchers.IO) {
        try {
            // Резолвим hostname в IP если нужно
            val ip = try {
                InetAddress.getByName(host).hostAddress ?: host
            } catch (e: Exception) {
                host
            }

            // Пробуем через ping с TTL (работает на большинстве Android устройств)
            for (ttl in 1..MAX_HOPS) {
                val result = pingWithTtl(ip, ttl)
                if (result.reachedTarget) {
                    return@withContext ttl
                }
                if (!result.gotResponse) {
                    // Нет ответа на этот TTL, продолжаем
                    continue
                }
            }

            // Не удалось определить за MAX_HOPS
            -1
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Быстрая проверка хопов - только для живых хостов.
     * Сначала проверяем доступность, потом считаем хопы.
     */
    suspend fun getHopsFast(host: String): Int = withContext(Dispatchers.IO) {
        try {
            // Сначала проверяем что хост доступен
            val reachable = InetAddress.getByName(host).isReachable(TIMEOUT_MS)
            if (!reachable) {
                return@withContext -1
            }

            // Используем бинарный поиск для ускорения
            val ip = InetAddress.getByName(host).hostAddress ?: host
            var low = 1
            var high = MAX_HOPS
            var result = -1

            while (low <= high) {
                val mid = (low + high) / 2
                val pingResult = pingWithTtl(ip, mid)

                if (pingResult.reachedTarget) {
                    result = mid
                    high = mid - 1  // Ищем меньшее значение
                } else {
                    low = mid + 1  // Нужно больше хопов
                }
            }

            result
        } catch (e: Exception) {
            -1
        }
    }

    private data class PingResult(
        val gotResponse: Boolean,
        val reachedTarget: Boolean
    )

    /**
     * Пингует хост с заданным TTL.
     * Возвращает информацию о результате.
     */
    private fun pingWithTtl(ip: String, ttl: Int): PingResult {
        return try {
            // ping -c 1 -W 2 -t TTL IP
            // -c 1: один пакет
            // -W 2: таймаут 2 секунды
            // -t TTL: устанавливаем TTL
            val process = ProcessBuilder("ping", "-c", "1", "-W", "2", "-t", ttl.toString(), ip)
                .redirectErrorStream(true)
                .start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }

            val exitCode = process.waitFor()
            val outputStr = output.toString()

            // Анализируем вывод
            when {
                // Успешный пинг - достигли цели
                exitCode == 0 && (outputStr.contains("bytes from") || outputStr.contains("ttl=")) -> {
                    PingResult(gotResponse = true, reachedTarget = true)
                }
                // TTL exceeded - промежуточный хоп
                outputStr.contains("Time to live exceeded") || outputStr.contains("TTL expired") -> {
                    PingResult(gotResponse = true, reachedTarget = false)
                }
                // Нет ответа
                else -> {
                    PingResult(gotResponse = false, reachedTarget = false)
                }
            }
        } catch (e: Exception) {
            PingResult(gotResponse = false, reachedTarget = false)
        }
    }
}
