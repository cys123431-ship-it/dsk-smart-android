package com.dskapp.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tailnet plan decides which of the 4,194,304 tailnet addresses get probed.
 *
 * A shipped version of this failed in the field: it reserved 63 * 254 = 16,002 of an
 * 18,000 budget for the far slices, leaving the near tiers 7 blocks instead of the 768
 * they needed. The phone's own /24 and every neighbour /16 were silently dropped, and a
 * PC at 100.127.10.20 was never probed. These tests pin the properties that broke.
 */
class TailnetScanPlanTest {

    private val own = "100.70.136.43"
    private val plan = PcDiscovery.tailnetPlan(own)
    private val blocks = plan.map { it.substringBeforeLast('.') }.distinct()

    @Test
    fun thisPhonesOwnBlockComesFirst() {
        assertTrue(plan.isNotEmpty())
        assertEquals((1..254).map { "100.70.136.$it" }, plan.take(254))
        assertEquals("100.70.135", blocks[1])
        assertEquals("100.70.137", blocks[2])
    }

    @Test
    fun blocksAreAlwaysWhole() {
        val byBlock = plan.groupBy { it.substringBeforeLast('.') }
        assertTrue(byBlock.isNotEmpty())
        byBlock.forEach { (block, hosts) ->
            assertEquals("partial block $block", 254, hosts.size)
            assertEquals("duplicate in $block", 254, hosts.toSet().size)
        }
    }

    @Test
    fun thePhonesOwnSliceIsProbedWhole() {
        assertTrue(blocks.count { it.startsWith("100.70.") } >= 4)
        assertTrue("100.69.136" in blocks)
        assertTrue("100.71.136" in blocks)
    }

    /**
     * The bug that shipped: the near tiers were squeezed out by the far reservation, so
     * 100.69.* and 100.71.* had exactly one block each instead of a usable band.
     */
    @Test
    fun theNeighbourSlicesGetARealBand() {
        val perSlice = (69..71).associateWith { slice ->
            blocks.count { it.startsWith("100.$slice.") }
        }
        assertTrue("neighbour slices are nearly empty: $perSlice", perSlice.values.all { it >= 20 })
        val spread = perSlice.values.maxOrNull()!! - perSlice.values.minOrNull()!!
        assertTrue("neighbour slices are unbalanced: $perSlice", spread <= 2)
    }

    @Test
    fun everyTailnetSliceIsCovered() {
        for (second in 64..127) {
            assertTrue("missing 100.$second.", blocks.any { it.startsWith("100.$second.") })
        }
    }

    @Test
    fun farSlicesGetAnEqualShare() {
        val counts = (64..127)
            .filter { it !in 69..71 }
            .map { slice -> blocks.count { it.startsWith("100.$slice.") } }
        assertTrue("no far slices at all", counts.isNotEmpty())
        assertEquals("far slices are unbalanced: $counts", 1, counts.distinct().size)
    }

    /**
     * A far slice gets a whole /24 at a third octet that differs per slice, not always .0.
     * Checking .0 in every slice would be the same address pattern 64 times over.
     */
    @Test
    fun farSlicesDoNotAllCheckTheSameThirdOctet() {
        val farThirdOctets = (64..127)
            .filter { it !in 69..71 }
            .mapNotNull { slice ->
                blocks.firstOrNull { it.startsWith("100.$slice.") }?.substringAfterLast('.')
            }
        assertTrue("far slices all use one third octet", farThirdOctets.toSet().size > 1)
    }

    @Test
    fun nearSlicesComeBeforeFarOnes() {
        val firstFar = blocks.indexOfFirst { block ->
            val second = block.split('.')[1].toInt()
            second !in 69..71
        }
        assertTrue("no far slices at all", firstFar > 0)
        assertTrue("far slice probed too early: $firstFar", firstFar >= 5)
    }

    @Test
    fun planIsBounded() {
        assertTrue("too many probes: ${plan.size}", plan.size <= PcDiscovery.TAILNET_MAX_HOSTS)
        assertTrue("plan is suspiciously short: ${plan.size}", plan.size > 60_000)
        // 256 probes in flight at 350 ms each: the worst case has to stay near 90 seconds.
        assertTrue("scan would take too long", plan.size / 256 < 300)
    }

    @Test
    fun planHasNoDuplicates() {
        assertEquals(plan.size, plan.toSet().size)
    }

    @Test
    fun planNeverLeavesTheTailnetRange() {
        assertTrue(plan.all { NetworkRoute.isTailscale(it) })
        assertFalse(plan.any { it.endsWith(".0") || it.endsWith(".255") })
    }

    @Test
    fun aSmallBudgetStillBuysWholeBlocks() {
        val small = PcDiscovery.tailnetPlan(own, maxHosts = 254)
        assertEquals((1..254).map { "100.70.136.$it" }, small)
        assertTrue(PcDiscovery.tailnetPlan(own, maxHosts = 100).isEmpty())
    }

    @Test
    fun nonTailscaleInputGivesEmptyPlan() {
        assertTrue(PcDiscovery.tailnetPlan("192.168.0.10").isEmpty())
        assertTrue(PcDiscovery.tailnetPlan("").isEmpty())
        assertTrue(PcDiscovery.tailnetPlan("100.128.0.1").isEmpty())
        assertTrue(PcDiscovery.tailnetPlan(own, maxHosts = 0).isEmpty())
    }

    @Test
    fun lanAddressIsNotMistakenForTailscale() {
        assertFalse(NetworkRoute.isTailscale("10.0.0.5"))
        assertFalse(NetworkRoute.isTailscale("192.168.0.5"))
        assertTrue(NetworkRoute.isTailscale("100.64.0.1"))
        assertTrue(NetworkRoute.isTailscale("100.127.255.254"))
    }
}
