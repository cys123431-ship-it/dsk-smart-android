package com.dskapp.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The QR the PC draws is the only thing standing between a user and a working connection, so
 * the parser has to be forgiving about what a camera hands over and strict about what it
 * accepts. A wrongly accepted code would silently point the app at someone else's machine.
 */
class PairingCodeTest {

    @Test
    fun `parses the code the PC draws`() {
        val code = PairingCode.parse("DSK1|192.168.0.10:3284|lan|MY-PC|abcdefgh12345678")
        assertEquals("192.168.0.10:3284", code?.address)
        assertEquals(KnownPc.KIND_LAN, code?.kind)
        assertEquals("MY-PC", code?.name)
        assertEquals("abcdefgh12345678", code?.secret)
    }

    @Test
    fun `tailscale codes keep their kind`() {
        val code = PairingCode.parse("DSK1|100.100.10.20:3284|tailscale|MY-PC|abcdefgh12345678")
        assertEquals(KnownPc.KIND_TAILSCALE, code?.kind)
    }

    @Test
    fun `kind is inferred from a tailscale address when the field is missing`() {
        val code = PairingCode.parse("DSK1|100.100.10.20:3284|||abcdefgh12345678")
        assertEquals(KnownPc.KIND_TAILSCALE, code?.kind)
        assertEquals("abcdefgh12345678", code?.secret)
    }

    @Test
    fun `a name with padding and newlines is trimmed`() {
        val code = PairingCode.parse("  DSK1 | 192.168.0.10:3284 | lan |  MY-PC  | abcdefgh12345678 \n")
        assertEquals("192.168.0.10:3284", code?.address)
        assertEquals("MY-PC", code?.name)
        assertEquals("abcdefgh12345678", code?.secret)
    }

    @Test
    fun `lowercase prefix is accepted`() {
        val code = PairingCode.parse("dsk1|192.168.0.10:3284|lan|MY-PC|abcdefgh12345678")
        assertEquals("192.168.0.10:3284", code?.address)
    }

    @Test
    fun `a secret containing the separator survives`() {
        val code = PairingCode.parse("DSK1|192.168.0.10:3284|lan|MY-PC|aaa|bbb")
        assertEquals("aaa|bbb", code?.secret)
    }

    @Test
    fun `other QR codes are rejected`() {
        assertNull(PairingCode.parse("https://example.com"))
        assertNull(PairingCode.parse("WIFI:S:MyNet;T:WPA;P:secret;;"))
        assertNull(PairingCode.parse("DSK2|192.168.0.10:3284"))
        assertNull(PairingCode.parse(""))
        assertNull(PairingCode.parse(null))
    }

    @Test
    fun `a code without an address is rejected`() {
        assertNull(PairingCode.parse("DSK1|"))
        assertNull(PairingCode.parse("DSK1||lan|MY-PC|abcdefgh12345678"))
    }

    @Test
    fun `an absurdly long address is rejected`() {
        val longAddress = "1".repeat(65) + ":3284"
        assertNull(PairingCode.parse("DSK1|$longAddress|lan|MY-PC|abcdefgh12345678"))
    }

    @Test
    fun `a missing name falls back to empty rather than failing`() {
        val code = PairingCode.parse("DSK1|192.168.0.10:3284|lan|abcdefgh12345678")
        assertEquals("192.168.0.10:3284", code?.address)
        assertEquals("", code?.name)
    }

    @Test
    fun `an over-long name is capped`() {
        val name = "N".repeat(80)
        val code = PairingCode.parse("DSK1|192.168.0.10:3284|lan|$name|abcdefgh12345678")
        assertEquals(40, code?.name?.length)
    }
}
