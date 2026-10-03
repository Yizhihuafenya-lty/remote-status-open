package com.example.remotestatus.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostsTest {
    @Test
    fun `ipv6 endpoint uses brackets and port after bracket`() {
        assertEquals(
            "http://[2001:db8::10]:50001",
            Hosts.endpoint("http", "2001:db8::10", 50001)
        )
    }

    @Test
    fun `ipv4 endpoint stays unbracketed`() {
        assertEquals("http://192.0.2.10:50001", Hosts.endpoint("http", "192.0.2.10", 50001))
    }

    @Test
    fun `existing brackets are normalized exactly once`() {
        assertEquals("http://[2001:db8::11]:50000", Hosts.endpoint("http", "[2001:db8::11]", 50000))
    }

    @Test
    fun `ipv6 detection is strict`() {
        assertTrue(Hosts.isIpv6("2001:db8::10"))
        assertFalse(Hosts.isIpv6("192.0.2.10"))
    }
}
