package com.example.yggpeerchecker.utils

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit-тесты для UrlParser — парсинг URL всех форматов
 */
class UrlParserTest {

    // === parse() ===

    @Test
    fun `parse tcp URL with IPv4 and port`() {
        val result = UrlParser.parse("tcp://1.2.3.4:8080")
        assertNotNull(result)
        assertEquals("tcp", result!!.protocol)
        assertEquals("1.2.3.4", result.hostname)
        assertEquals(8080, result.port)
        assertTrue(result.isIpAddress)
        assertEquals(4, result.ipVersion)
    }

    @Test
    fun `parse tls URL with hostname and port`() {
        val result = UrlParser.parse("tls://example.com:443")
        assertNotNull(result)
        assertEquals("tls", result!!.protocol)
        assertEquals("example.com", result.hostname)
        assertEquals(443, result.port)
        assertFalse(result.isIpAddress)
        assertNull(result.ipVersion)
    }

    @Test
    fun `parse quic URL with IPv6 in brackets`() {
        val result = UrlParser.parse("quic://[2a12:5940:b1a0::2]:65535")
        assertNotNull(result)
        assertEquals("quic", result!!.protocol)
        assertEquals("2a12:5940:b1a0::2", result.hostname)
        assertEquals(65535, result.port)
        assertTrue(result.isIpAddress)
        assertEquals(6, result.ipVersion)
    }

    @Test
    fun `parse ws URL with query params`() {
        val result = UrlParser.parse("ws://myws.org:3333?key=xxx")
        assertNotNull(result)
        assertEquals("ws", result!!.protocol)
        assertEquals("myws.org", result.hostname)
        assertEquals(3333, result.port)
    }

    @Test
    fun `parse wss URL`() {
        val result = UrlParser.parse("wss://secure.host.com:4443")
        assertNotNull(result)
        assertEquals("wss", result!!.protocol)
        assertEquals("secure.host.com", result.hostname)
        assertEquals(4443, result.port)
    }

    @Test
    fun `parse vless URL`() {
        val result = UrlParser.parse("vless://uuid-1234@host.example.com:443?type=tcp")
        assertNotNull(result)
        assertEquals("vless", result!!.protocol)
        assertEquals("host.example.com", result.hostname)
        assertEquals(443, result.port)
    }

    @Test
    fun `parse bare hostname with port`() {
        val result = UrlParser.parse("example.com:443")
        assertNotNull(result)
        assertEquals("sni", result!!.protocol)
        assertEquals("example.com", result.hostname)
        assertEquals(443, result.port)
    }

    @Test
    fun `parse bare hostname without port gets default 443`() {
        val result = UrlParser.parse("example.com")
        assertNotNull(result)
        assertEquals("sni", result!!.protocol)
        assertEquals("example.com", result.hostname)
        assertEquals(443, result.port)
    }

    @Test
    fun `parse http(s) prefix normalizes to https`() {
        val result = UrlParser.parse("http(s)://example.com:8080")
        assertNotNull(result)
        assertEquals("https", result!!.protocol)
        assertEquals("example.com", result.hostname)
        assertEquals(8080, result.port)
    }

    @Test
    fun `parse http URL without port strips to hostname`() {
        // "http://host" → нормализуется к просто "host" (sni)
        val result = UrlParser.parse("http://example.com")
        assertNotNull(result)
        assertEquals("sni", result!!.protocol)
        assertEquals("example.com", result.hostname)
    }

    @Test
    fun `parse blank returns null`() {
        assertNull(UrlParser.parse(""))
        assertNull(UrlParser.parse("   "))
    }

    @Test
    fun `parse preserves groupingKey as lowercase hostname`() {
        val result = UrlParser.parse("tcp://MyHost.Example.COM:8080")
        assertNotNull(result)
        assertEquals("myhost.example.com", result!!.groupingKey)
    }

    @Test
    fun `parse IPv6 bare address with port`() {
        val result = UrlParser.parse("[2001:db8::1]:443")
        assertNotNull(result)
        assertEquals("sni", result!!.protocol)
        assertEquals("2001:db8::1", result.hostname)
        assertEquals(443, result.port)
        assertTrue(result.isIpAddress)
        assertEquals(6, result.ipVersion)
    }

    @Test
    fun `parse tls IPv6 URL`() {
        val result = UrlParser.parse("tls://[::1]:443")
        assertNotNull(result)
        assertEquals("tls", result!!.protocol)
        assertEquals("::1", result.hostname)
        assertEquals(443, result.port)
        assertTrue(result.isIpAddress)
    }

    // === isIpAddress() ===

    @Test
    fun `isIpAddress returns true for IPv4`() {
        assertTrue(UrlParser.isIpAddress("192.168.1.1"))
        assertTrue(UrlParser.isIpAddress("0.0.0.0"))
        assertTrue(UrlParser.isIpAddress("255.255.255.255"))
    }

    @Test
    fun `isIpAddress returns true for IPv6`() {
        assertTrue(UrlParser.isIpAddress("::1"))
        assertTrue(UrlParser.isIpAddress("2001:db8::1"))
        assertTrue(UrlParser.isIpAddress("[2a12:5940:b1a0::2]"))
    }

    @Test
    fun `isIpAddress returns false for hostname`() {
        assertFalse(UrlParser.isIpAddress("example.com"))
        assertFalse(UrlParser.isIpAddress("my-host.org"))
        assertFalse(UrlParser.isIpAddress("localhost"))
    }

    // === isIpv4() ===

    @Test
    fun `isIpv4 valid addresses`() {
        assertTrue(UrlParser.isIpv4("1.2.3.4"))
        assertTrue(UrlParser.isIpv4("0.0.0.0"))
        assertTrue(UrlParser.isIpv4("255.255.255.255"))
        assertTrue(UrlParser.isIpv4("127.0.0.1"))
    }

    @Test
    fun `isIpv4 invalid addresses`() {
        assertFalse(UrlParser.isIpv4("256.1.1.1"))
        assertFalse(UrlParser.isIpv4("1.2.3"))
        assertFalse(UrlParser.isIpv4("1.2.3.4.5"))
        assertFalse(UrlParser.isIpv4("abc.def.ghi.jkl"))
        assertFalse(UrlParser.isIpv4(""))
    }

    // === isIpv6() ===

    @Test
    fun `isIpv6 valid addresses`() {
        assertTrue(UrlParser.isIpv6("::1"))
        assertTrue(UrlParser.isIpv6("2001:db8::1"))
        assertTrue(UrlParser.isIpv6("2a12:5940:b1a0::2"))
        assertTrue(UrlParser.isIpv6("[::1]"))  // с квадратными скобками
    }

    @Test
    fun `isIpv6 rejects hostnames`() {
        assertFalse(UrlParser.isIpv6("example.com"))
        assertFalse(UrlParser.isIpv6("localhost"))
    }

    // === replaceHost() ===

    @Test
    fun `replaceHost replaces hostname with IPv4`() {
        val result = UrlParser.replaceHost("ws://myws.org:3333?key=xxx", "1.1.1.1")
        assertEquals("ws://1.1.1.1:3333?key=xxx", result)
    }

    @Test
    fun `replaceHost replaces hostname with IPv6`() {
        val result = UrlParser.replaceHost("tcp://example.com:8080", "2001:db8::1")
        assertEquals("tcp://[2001:db8::1]:8080", result)
    }

    @Test
    fun `replaceHost replaces IPv4 with different IPv4`() {
        val result = UrlParser.replaceHost("tls://1.2.3.4:443", "5.6.7.8")
        assertEquals("tls://5.6.7.8:443", result)
    }

    @Test
    fun `replaceHost replaces IPv6 with IPv4`() {
        val result = UrlParser.replaceHost("quic://[::1]:9001", "1.2.3.4")
        assertEquals("quic://1.2.3.4:9001", result)
    }

    // === getDefaultPort() ===

    @Test
    fun `getDefaultPort returns correct defaults`() {
        assertEquals(80, UrlParser.getDefaultPort("http"))
        assertEquals(443, UrlParser.getDefaultPort("https"))
        assertEquals(443, UrlParser.getDefaultPort("tls"))
        assertEquals(443, UrlParser.getDefaultPort("sni"))
        assertNull(UrlParser.getDefaultPort("tcp"))
        assertNull(UrlParser.getDefaultPort("quic"))
        assertNull(UrlParser.getDefaultPort("ws"))
    }

    // === normalize() ===

    @Test
    fun `normalize lowercases protocol and host`() {
        val result = UrlParser.normalize("TCP://MyHost.COM:8080")
        assertEquals("tcp://myhost.com:8080", result)
    }

    // === isYggProtocol / isSniProtocol / isProxyProtocol ===

    @Test
    fun `protocol type checks`() {
        assertTrue(UrlParser.isYggProtocol("tcp"))
        assertTrue(UrlParser.isYggProtocol("tls"))
        assertTrue(UrlParser.isYggProtocol("quic"))
        assertTrue(UrlParser.isYggProtocol("ws"))
        assertTrue(UrlParser.isYggProtocol("wss"))
        assertFalse(UrlParser.isYggProtocol("http"))

        assertTrue(UrlParser.isSniProtocol("sni"))
        assertTrue(UrlParser.isSniProtocol("http"))
        assertTrue(UrlParser.isSniProtocol("https"))
        assertFalse(UrlParser.isSniProtocol("tcp"))

        assertTrue(UrlParser.isProxyProtocol("vless"))
        assertTrue(UrlParser.isProxyProtocol("vmess"))
        assertFalse(UrlParser.isProxyProtocol("tcp"))
    }

    // === extractHostname() ===

    @Test
    fun `extractHostname from various URLs`() {
        assertEquals("example.com", UrlParser.extractHostname("tcp://example.com:8080"))
        assertEquals("1.2.3.4", UrlParser.extractHostname("tls://1.2.3.4:443"))
        assertEquals("2001:db8::1", UrlParser.extractHostname("quic://[2001:db8::1]:9001"))
        assertEquals("myhost.org", UrlParser.extractHostname("myhost.org:443"))
    }
}
