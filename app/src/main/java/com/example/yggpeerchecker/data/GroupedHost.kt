package com.example.yggpeerchecker.data

/**
 * Тип адреса в группе
 */
enum class AddressType(val displayName: String) {
    HST("hst"),   // Основной hostname
    IP0("ip0"),   // Текущий резолвленный IP (при проверке, если отличается от кэшированных)
    IP1("ip1"),   // Первый DNS IP (кэшированный)
    IP2("ip2"),   // Второй DNS IP (кэшированный)
    IP3("ip3")    // Третий DNS IP (кэшированный)
}

/**
 * Адрес хоста (hostname или IP) с результатами общих проверок
 */
data class HostAddress(
    val address: String,             // "myws.org" или "1.1.1.1"
    val type: AddressType,           // HST, IP1, IP2, IP3
    val pingResult: Long = -1,       // -1 = off, -2 = X, >=0 = ms
    val port80Result: Long = -1,
    val port443Result: Long = -1
) {
    val isAlive: Boolean
        get() = pingResult >= 0 || port80Result >= 0 || port443Result >= 0
}

/**
 * Результат проверки endpoint для конкретного адреса
 */
data class EndpointCheckResult(
    val addressType: AddressType,    // HST, IP1, IP2, IP3
    val address: String,             // реальный адрес
    val yggRttMs: Long = -1,
    val portDefaultMs: Long = -1,
    val fullUrl: String              // URL с подставленным IP для копирования
) {
    val isAlive: Boolean
        get() = yggRttMs >= 0 || portDefaultMs >= 0
}

/**
 * Endpoint (протокол + порт для подключения)
 */
data class HostEndpoint(
    val protocol: String,            // ws, tcp, tls, quic, http, https, vless, vmess
    val port: Int,                   // 3333
    val originalUrl: String,         // оригинальная строка для отображения
    val checkResults: List<EndpointCheckResult> = emptyList()
) {
    /**
     * Ключ для уникальности endpoint (protocol:port)
     */
    val key: String get() = "$protocol:$port"

    /**
     * Есть ли хотя бы один живой результат
     */
    val hasAliveResult: Boolean get() = checkResults.any { it.isAlive }

    /**
     * Краткое отображение: ws://hst:3333
     * Для sni протокола не добавляем "sni://" - это внутреннее обозначение
     */
    fun shortDisplay(addressType: AddressType): String {
        return if (protocol == "sni") {
            "${addressType.displayName}:$port"
        } else {
            "$protocol://${addressType.displayName}:$port"
        }
    }
}

/**
 * Группа хостов по общему hostname
 * Объединяет записи с разными протоколами/портами
 */
data class GroupedHost(
    val groupKey: String,            // hostname в lowercase (ключ группировки)
    val displayName: String,         // "myws.org" для отображения

    // Метаданные группы (общие)
    val region: String?,             // регион из источника
    val geoIp: String?,              // GeoIP CC:City
    val source: String,              // источник (neil, miniblack, etc.)
    val hops: Int = -1,              // tracert hops (общий для группы)

    // Адреса в группе (hst + ip1/ip2/ip3)
    val addresses: List<HostAddress>,

    // Варианты подключения (протокол + порт)
    val endpoints: List<HostEndpoint>
) {
    /**
     * Есть ли хотя бы один живой адрес
     */
    val hasAliveAddress: Boolean get() = addresses.any { it.isAlive }

    /**
     * Есть ли хотя бы один живой endpoint
     */
    val hasAliveEndpoint: Boolean get() = endpoints.any { it.hasAliveResult }

    /**
     * Общий статус группы
     */
    val isAlive: Boolean get() = hasAliveAddress || hasAliveEndpoint

    /**
     * Количество живых адресов
     */
    val aliveAddressCount: Int get() = addresses.count { it.isAlive }

    /**
     * Лучший (минимальный) результат проверки среди всех адресов и endpoints
     * Используется для сортировки групп
     * @return минимальное значение ms или Long.MAX_VALUE если нет успешных проверок
     */
    fun getBestResultMs(): Long {
        val addressResults = addresses.flatMap { addr ->
            listOf(addr.pingResult, addr.port80Result, addr.port443Result)
        }.filter { it >= 0 }

        val endpointResults = endpoints.flatMap { endpoint ->
            endpoint.checkResults.flatMap { result ->
                listOf(result.yggRttMs, result.portDefaultMs)
            }
        }.filter { it >= 0 }

        val allResults = addressResults + endpointResults
        return allResults.minOrNull() ?: Long.MAX_VALUE
    }

    /**
     * Получить лучший результат для конкретного типа проверки
     * Используется для сортировки групп по выбранному типу
     */
    fun getBestResultForType(checkType: String): Long {
        return when (checkType) {
            "PING" -> {
                addresses.mapNotNull { it.pingResult.takeIf { r -> r >= 0 } }.minOrNull() ?: Long.MAX_VALUE
            }
            "YGG_RTT" -> {
                endpoints.flatMap { endpoint ->
                    endpoint.checkResults.mapNotNull { it.yggRttMs.takeIf { r -> r >= 0 } }
                }.minOrNull() ?: Long.MAX_VALUE
            }
            "PORT_DEFAULT" -> {
                endpoints.flatMap { endpoint ->
                    endpoint.checkResults.mapNotNull { it.portDefaultMs.takeIf { r -> r >= 0 } }
                }.minOrNull() ?: Long.MAX_VALUE
            }
            "PORT_80" -> {
                addresses.mapNotNull { it.port80Result.takeIf { r -> r >= 0 } }.minOrNull() ?: Long.MAX_VALUE
            }
            "PORT_443" -> {
                addresses.mapNotNull { it.port443Result.takeIf { r -> r >= 0 } }.minOrNull() ?: Long.MAX_VALUE
            }
            else -> getBestResultMs()  // По умолчанию - любой лучший результат
        }
    }

    /**
     * Краткий source (ygg:neil, miniblack, etc.)
     */
    fun shortSource(): String {
        return when {
            source.contains("neilalexander") -> "ygg:neil"
            source.contains("yggdrasil.link") -> "ygg:link"
            source.contains("whitelist") -> "whitelist"
            source.contains("miniwhite") -> "miniwhite"
            source.contains("miniblack") -> "miniblack"
            source.contains("vless") || source.contains("zieng") -> "vless"
            source.contains("clipboard") -> "clipboard"
            source.contains("file") -> "file"
            else -> source.substringAfterLast("/").take(10)
        }
    }

    /**
     * Получить все выбранные URL для копирования
     */
    fun getUrlsForCopy(selectedEndpointUrls: Set<String>): List<String> {
        return endpoints.flatMap { endpoint ->
            endpoint.checkResults
                .filter { selectedEndpointUrls.contains(it.fullUrl) }
                .map { it.fullUrl }
        }
    }
}

/**
 * Builder для создания GroupedHost из списка Host и результатов проверок
 */
object GroupedHostBuilder {

    /**
     * Извлечение короткого имени источника
     */
    fun extractSourceName(source: String): String {
        return when {
            source.contains("neilalexander") -> "ygg:neil"
            source.contains("yggdrasil.link") -> "ygg:link"
            source.contains("whitelist") -> "whitelist"
            source.contains("miniwhite") -> "miniwhite"
            source.contains("miniblack") -> "miniblack"
            source.contains("vless") || source.contains("zieng") -> "vless"
            source.contains("clipboard") -> "clipboard"
            source.contains("file") -> "file"
            else -> source.substringAfterLast("/").take(10)
        }
    }
}
