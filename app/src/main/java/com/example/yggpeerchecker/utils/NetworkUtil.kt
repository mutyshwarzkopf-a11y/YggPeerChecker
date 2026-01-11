package com.example.yggpeerchecker.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.net.NetworkInterface

object NetworkUtil {
    private const val TAG = "NetworkUtil"

    // Проверка через ConnectivityManager - надежный способ для Android
    fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            if (network == null) {
                Log.w(TAG, "No active network")
                return false
            }
            val caps = cm.getNetworkCapabilities(network)
            if (caps == null) {
                Log.w(TAG, "No network capabilities")
                return false
            }
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            Log.d(TAG, "Network available: $hasInternet")
            hasInternet
        } catch (e: Exception) {
            Log.e(TAG, "Error checking network: ${e.message}", e)
            false
        }
    }

    // Старый метод через NetworkInterface - оставлен для совместимости
    fun hasAvailableInterfaces(): Boolean {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()

            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                Log.d(TAG, "Checking interface: ${iface.name}, isUp=${iface.isUp}, isLoopback=${iface.isLoopback}")

                // Пропускаем loopback и неактивные интерфейсы
                if (!iface.isUp || iface.isLoopback) {
                    continue
                }

                // Проверяем наличие IP адресов
                val addresses = iface.inetAddresses
                if (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    Log.d(TAG, "Found active interface: ${iface.name} with address: ${addr.hostAddress}")
                    return true
                }
            }

            Log.w(TAG, "No available network interfaces found")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking interfaces: ${e.message}", e)
            false
        }
    }
}
