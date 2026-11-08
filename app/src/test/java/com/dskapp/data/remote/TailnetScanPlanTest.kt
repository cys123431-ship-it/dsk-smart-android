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
    private val plan = PcDiscovery.tailnetPlan(own, maxHosts = 6000)

    @Test
    fun thisPhonesOwnBlockComesFirst() {
        assertTrue(plan.isNotEmpty())
        // Tier 1: all 254 hosts of the phone's own /24, in ascending order.
        assertEquals((1..254).map { "100.70.136.$it" }, plan.take(254))
    }

    @Test
    fun ownBlockAroundTheLabelAndInNeighbourSlices() {
        // Tier 2: the same /24 in the two neighbouring /16s.
        assertEquals(254, plan.count { it.startsWith("100.69.136.") })
        assertEquals(254, plan.count { it.startsWith("100.71.136.") })
        // Tier 3: the first hosts of every /24 around us.
        assertTrue("100.70.5.1 missing", "100.70.5.1" in plan)
        assertTrue("100.69.5.1 missing", "100.69.5.1" in plan)
        assertTrue("100.71.5.1 missing", "100.71.5.1" in plan)
        // Everything around us is probed before any far slice.
        val firstFar = plan.indexOfFirst { !it.startsWith("100.69.") && !it.startsWith("100.70.") && !it.startsWith("100.71.") }
        assertTrue("no far slices at all", firstFar > 0)
        assertTrue("far slice probed too early: $firstFar", firstFar >= 2000)
    }

    @Test
    fun everyTailnetSliceIsCovered() {
        // The bug this guards against: the budget ran out somewhere in 100.64..100.71 and
        // a PC that tailscale had placed at 100.101.x.x was never even probed.
        for (second in 64..127) {
            val prefix = "100.$second."
            assertTrue("missing $prefix", plan.any { it.startsWith(prefix) })
        }
    }

    @Test
    fun planIsBounded() {
        assertTrue("too many probes: ${plan.size}", plan.size <= PcDiscovery.TAILNET_MAX_HOSTS)
        assertTrue("plan is suspiciously short: ${plan.size}", plan.size > 4000)
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
    fun smallBudgetsAreRespected() {
        assertEquals(100, PcDiscovery.tailnetPlan(own, maxHosts = 100).size)
        assertEquals((1..100).map { "100.70.136.$it" }, PcDiscovery.tailnetPlan(own, maxHosts = 100))
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
