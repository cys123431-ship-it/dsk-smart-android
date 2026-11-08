package com.dskapp.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tailnet addresses we probe have to be chosen without any hint from the network:
 * a tailnet is 100.64.0.0/10, four million hosts, and the phone only knows its own
 * address. These tests pin down the shape of that choice.
 */
class TailnetScanPlanTest {

    private val own = "100.70.136.43"
    private val plan = PcDiscovery.tailnetPlan(own, maxHosts = 18_000)
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
        // Sampling a few hosts per /24 is what missed the real PC at 100.100.50.25.
        val byBlock = plan.groupBy { it.substringBeforeLast('.') }
        assertTrue(byBlock.isNotEmpty())
        byBlock.forEach { (block, hosts) ->
            assertEquals("partial block $block", 254, hosts.size)
            assertEquals("duplicate in $block", 254, hosts.toSet().size)
        }
    }

    @Test
    fun thePhonesOwnSliceIsProbedWhole() {
        // The phone's own /16 and the two neighbours get far more than one block each.
        assertTrue(blocks.count { it.startsWith("100.70.") } >= 4)
        assertTrue("100.69.136" in blocks)
        assertTrue("100.71.136" in blocks)
        assertTrue("100.69.0" in blocks)
        assertTrue("100.71.0" in blocks)
    }

    @Test
    fun everyTailnetSliceIsCovered() {
        // The bug this guards against: the budget ran out part-way and a PC in a later
        // slice was never even probed.
        for (second in 64..127) {
            assertTrue("missing 100.$second.", blocks.any { it.startsWith("100.$second.") })
        }
    }

    @Test
    fun farSlicesGetAnEqualShare() {
        val counts = (64..127)
            .filter { it !in 69..71 }
            .map { second -> blocks.count { it.startsWith("100.$second.") } }
            .distinct()
        assertEquals("far slices are unbalanced: $counts", 1, counts.size)
    }

    @Test
    fun nearSlicesComeBeforeFarOnes() {
        val firstFar = blocks.indexOfFirst {
            val second = it.split('.')[1].toInt()
            second !in 69..71
        }
        assertTrue("no far slices at all", firstFar > 0)
        assertTrue("far slice probed too early: $firstFar", firstFar >= 5)
    }

    @Test
    fun planIsBounded() {
        assertTrue("too many probes: ${plan.size}", plan.size <= PcDiscovery.TAILNET_MAX_HOSTS)
        assertTrue("plan is suspiciously short: ${plan.size}", plan.size > 15_000)
        // The plan must stay short enough to finish: dead hosts cost the full timeout.
        assertTrue("scan would take too long", plan.size / 128 < 200)
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
        // Not even one whole /24 fits.
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
