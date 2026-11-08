package com.dskapp.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Snapshot logic only - no Context, so it runs as a plain JVM test. */
class NetworkRouteSnapshotTest {

    @Test
    fun tailsaleAddressIsSeparatedFromLan() {
        val snapshot = NetworkRoute.Snapshot(
            localIp = "192.168.0.10",
            tailscaleIp = "100.100.50.25",
            lanSubnet = "192.168.0"
        )
        assertTrue(snapshot.isOnLan)
        assertTrue(snapshot.hasTailscale)
        assertEquals("192.168.0", snapshot.lanSubnet)
    }

    @Test
    fun offlinePhoneHasNothing() {
        val snapshot = NetworkRoute.Snapshot()
        assertFalse(snapshot.isOnLan)
        assertFalse(snapshot.hasTailscale)
        assertEquals("", snapshot.lanSubnet)
    }

    @Test
    fun discoveryFoundBuildsAddressAndKeepsPort() {
        val found = PcDiscovery.Found("192.168.0.10", 3284)
        assertEquals("192.168.0.10:3284", found.url)
    }
}
