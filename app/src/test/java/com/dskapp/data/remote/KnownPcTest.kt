package com.dskapp.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The remembered-PC list decides which address the app tries first, so it must stay tidy. */
class KnownPcTest {

    @Test
    fun roundTripsThroughJson() {
        val pcs = listOf(
            KnownPc("192.168.0.10:3284", "Wi-Fi", KnownPc.KIND_LAN, 1000L),
            KnownPc("100.100.50.25:3284", "Tailscale", KnownPc.KIND_TAILSCALE, 2000L)
        )
        val decoded = KnownPcsCodec.decode(KnownPcsCodec.encode(pcs))
        assertEquals(pcs, decoded)
    }

    @Test
    fun decodesEmptyAndBrokenInput() {
        assertEquals(emptyList<KnownPc>(), KnownPcsCodec.decode(null))
        assertEquals(emptyList<KnownPc>(), KnownPcsCodec.decode(""))
        assertEquals(emptyList<KnownPc>(), KnownPcsCodec.decode("not json"))
        assertEquals(emptyList<KnownPc>(), KnownPcsCodec.decode("{}"))
    }

    @Test
    fun skipsEntriesWithoutAddress() {
        val raw = """[{"name":"x"},{"address":"1.2.3.4:3284"}]"""
        val pcs = KnownPcsCodec.decode(raw)
        assertEquals(1, pcs.size)
        assertEquals("1.2.3.4:3284", pcs[0].address)
    }

    @Test
    fun mergeReplacesSameAddressAndKeepsNewestFirst() {
        val old = listOf(
            KnownPc("192.168.0.10:3284", "", KnownPc.KIND_LAN, 100L),
            KnownPc("10.0.0.5:3284", "", KnownPc.KIND_LAN, 50L)
        )
        val merged = KnownPcsCodec.merge(old, KnownPc("10.0.0.5:3284", "", KnownPc.KIND_LAN, 900L))
        assertEquals(2, merged.size)
        assertEquals("10.0.0.5:3284", merged[0].address)
        assertEquals(900L, merged[0].lastUsed)
    }

    @Test
    fun mergeIsCapped() {
        val many = (1..20).map { KnownPc("10.0.0.$it:3284", "", KnownPc.KIND_LAN, it.toLong()) }
        val merged = KnownPcsCodec.merge(many, KnownPc("10.0.0.99:3284", "", KnownPc.KIND_LAN, 999L))
        assertEquals(8, merged.size)
        assertEquals("10.0.0.99:3284", merged[0].address)
    }

    @Test
    fun parsesHostAndPortWithDefaults() {
        assertEquals("100.100.50.25", KnownPc("100.100.50.25:3284").host)
        assertEquals(3284, KnownPc("100.100.50.25:3284").port)
        assertEquals(3284, KnownPc("192.168.0.4").port)
        assertTrue(KnownPc("x", kind = KnownPc.KIND_TAILSCALE).isTailscale)
        assertFalse(KnownPc("x", kind = KnownPc.KIND_LAN).isTailscale)
    }
}
