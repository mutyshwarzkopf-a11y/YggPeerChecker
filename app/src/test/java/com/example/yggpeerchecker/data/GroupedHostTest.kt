package com.example.yggpeerchecker.data

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit-тесты для GroupedHost и GroupedHostBuilder
 */
class GroupedHostTest {

    // === GroupedHostBuilder.extractSourceName() ===

    @Test
    fun `extractSourceName returns ygg neil for neilalexander URL`() {
        assertEquals("ygg:neil", GroupedHostBuilder.extractSourceName(
            "https://publicpeers.neilalexander.dev/publicnodes.json"
        ))
    }

    @Test
    fun `extractSourceName returns ygg link for yggdrasil link URL`() {
        assertEquals("ygg:link", GroupedHostBuilder.extractSourceName(
            "https://peers.yggdrasil.link/publicnodes.json"
        ))
    }

    @Test
    fun `extractSourceName returns whitelist`() {
        assertEquals("whitelist", GroupedHostBuilder.extractSourceName(
            "https://github.com/hxehex/russia-mobile-internet-whitelist/raw/refs/heads/main/whitelist.txt"
        ))
    }

    @Test
    fun `extractSourceName returns miniwhite`() {
        assertEquals("miniwhite", GroupedHostBuilder.extractSourceName(
            "https://github.com/mutyshwarzkopf-a11y/YggPeerChecker/raw/refs/heads/main/lists/miniwhite.txt"
        ))
    }

    @Test
    fun `extractSourceName returns miniblack`() {
        assertEquals("miniblack", GroupedHostBuilder.extractSourceName(
            "https://github.com/mutyshwarzkopf-a11y/YggPeerChecker/raw/refs/heads/main/lists/miniblack.txt"
        ))
    }

    @Test
    fun `extractSourceName returns vless for zieng URL`() {
        assertEquals("vless", GroupedHostBuilder.extractSourceName(
            "https://raw.githubusercontent.com/Zieng2/wl/refs/heads/main/vless_lite.txt"
        ))
    }

    @Test
    fun `extractSourceName returns clipboard`() {
        assertEquals("clipboard", GroupedHostBuilder.extractSourceName("clipboard"))
    }

    @Test
    fun `extractSourceName returns file`() {
        assertEquals("file", GroupedHostBuilder.extractSourceName("file"))
    }

    @Test
    fun `extractSourceName returns truncated last path segment for unknown`() {
        val result = GroupedHostBuilder.extractSourceName("https://example.com/some/unknown_source.txt")
        assertEquals("unknown_so", result)  // .take(10) от "unknown_source.txt"
    }

    // === GroupedHost.getBestResultMs() ===

    @Test
    fun `getBestResultMs returns minimum from all results`() {
        val group = createTestGroup(
            pingResults = listOf(100L, 50L),
            yggRttResults = listOf(80L),
            portDefaultResults = listOf(200L)
        )
        assertEquals(50L, group.getBestResultMs())
    }

    @Test
    fun `getBestResultMs ignores negative values`() {
        val group = createTestGroup(
            pingResults = listOf(-1L, -2L),
            yggRttResults = listOf(150L),
            portDefaultResults = listOf(-1L)
        )
        assertEquals(150L, group.getBestResultMs())
    }

    @Test
    fun `getBestResultMs returns MAX_VALUE when no positive results`() {
        val group = createTestGroup(
            pingResults = listOf(-1L, -2L),
            yggRttResults = listOf(-1L),
            portDefaultResults = listOf(-2L)
        )
        assertEquals(Long.MAX_VALUE, group.getBestResultMs())
    }

    // === GroupedHost.getBestResultForType() ===

    @Test
    fun `getBestResultForType PING returns min ping`() {
        val group = createTestGroup(pingResults = listOf(100L, 50L, 200L))
        assertEquals(50L, group.getBestResultForType("PING"))
    }

    @Test
    fun `getBestResultForType YGG_RTT returns min ygg rtt`() {
        val group = createTestGroup(yggRttResults = listOf(80L, 120L))
        assertEquals(80L, group.getBestResultForType("YGG_RTT"))
    }

    @Test
    fun `getBestResultForType PORT_DEFAULT returns min port default`() {
        val group = createTestGroup(portDefaultResults = listOf(200L, 150L))
        assertEquals(150L, group.getBestResultForType("PORT_DEFAULT"))
    }

    @Test
    fun `getBestResultForType PORT_80 returns min port80`() {
        val group = createTestGroup(port80Results = listOf(90L, 110L))
        assertEquals(90L, group.getBestResultForType("PORT_80"))
    }

    @Test
    fun `getBestResultForType PORT_443 returns min port443`() {
        val group = createTestGroup(port443Results = listOf(180L, 160L))
        assertEquals(160L, group.getBestResultForType("PORT_443"))
    }

    @Test
    fun `getBestResultForType returns MAX_VALUE when no results`() {
        val group = createTestGroup(pingResults = listOf(-1L, -2L))
        assertEquals(Long.MAX_VALUE, group.getBestResultForType("PING"))
    }

    // === GroupedHost.isAlive ===

    @Test
    fun `isAlive returns true when address has positive ping`() {
        val group = createTestGroup(pingResults = listOf(50L))
        assertTrue(group.isAlive)
    }

    @Test
    fun `isAlive returns true when endpoint has positive ygg rtt`() {
        val group = createTestGroup(
            pingResults = listOf(-2L),
            yggRttResults = listOf(100L)
        )
        assertTrue(group.isAlive)
    }

    @Test
    fun `isAlive returns false when all results negative`() {
        val group = createTestGroup(
            pingResults = listOf(-2L),
            yggRttResults = listOf(-1L),
            portDefaultResults = listOf(-2L)
        )
        assertFalse(group.isAlive)
    }

    // === GroupedHost.shortSource() ===

    @Test
    fun `shortSource returns correct short names`() {
        assertEquals("ygg:neil", createGroupWithSource("https://publicpeers.neilalexander.dev/publicnodes.json").shortSource())
        assertEquals("ygg:link", createGroupWithSource("https://peers.yggdrasil.link/publicnodes.json").shortSource())
        assertEquals("miniblack", createGroupWithSource("miniblack").shortSource())
        assertEquals("clipboard", createGroupWithSource("clipboard").shortSource())
    }

    // === Хелперы для создания тестовых данных ===

    private fun createTestGroup(
        pingResults: List<Long> = listOf(-1L),
        port80Results: List<Long> = listOf(-1L),
        port443Results: List<Long> = listOf(-1L),
        yggRttResults: List<Long> = listOf(-1L),
        portDefaultResults: List<Long> = listOf(-1L)
    ): GroupedHost {
        // Количество адресов = максимум из адресных списков
        val addrCount = maxOf(pingResults.size, port80Results.size, port443Results.size)
        val addresses = (0 until addrCount).map { idx ->
            HostAddress(
                address = "addr$idx",
                type = if (idx == 0) AddressType.HST else AddressType.entries[idx.coerceAtMost(6)],
                pingResult = pingResults.getOrElse(idx) { -1L },
                port80Result = port80Results.getOrElse(idx) { -1L },
                port443Result = port443Results.getOrElse(idx) { -1L }
            )
        }

        // Количество endpoint результатов = максимум из endpoint списков
        val epCount = maxOf(yggRttResults.size, portDefaultResults.size)
        val endpointResults = (0 until epCount).map { idx ->
            EndpointCheckResult(
                addressType = if (idx == 0) AddressType.HST else AddressType.IP1,
                address = "addr$idx",
                yggRttMs = yggRttResults.getOrElse(idx) { -1L },
                portDefaultMs = portDefaultResults.getOrElse(idx) { -1L },
                fullUrl = "tcp://addr$idx:8080"
            )
        }

        val endpoints = if (endpointResults.isNotEmpty()) {
            listOf(
                HostEndpoint(
                    protocol = "tcp",
                    port = 8080,
                    originalUrl = "tcp://test:8080",
                    checkResults = endpointResults
                )
            )
        } else {
            emptyList()
        }

        return GroupedHost(
            groupKey = "test.host",
            displayName = "test.host",
            region = null,
            geoIp = null,
            source = "test",
            addresses = addresses,
            endpoints = endpoints
        )
    }

    private fun createGroupWithSource(source: String): GroupedHost {
        return GroupedHost(
            groupKey = "test",
            displayName = "test",
            region = null,
            geoIp = null,
            source = source,
            addresses = listOf(HostAddress("1.1.1.1", AddressType.HST)),
            endpoints = emptyList()
        )
    }
}
