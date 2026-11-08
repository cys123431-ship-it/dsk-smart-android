package com.dskapp.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Finds the PC on the local network so the user never has to type an IP address.
 *
 * The PC's DSK server opens one TCP port (3284 by default) on every interface.
 * We walk the phone's own subnet and keep the addresses where that port answers.
 * A subnet has at most 254 hosts, checked in parallel with a short timeout, so
 * the whole scan finishes in a couple of seconds.
 *
 * The port answering is *not* proof that it is our PC, so the caller still has to
 * do the ACP handshake (which needs the secret key) before trusting the result.
 */
object PcDiscovery {

    const val DEFAULT_PORT = 3284

    private const val CONNECT_TIMEOUT_MS = 350
    private const val PARALLELISM = 48

    /** How many tailnet addresses one "PC 자동 찾기" is allowed to probe. */
    internal const val TAILNET_MAX_HOSTS = 6000

    data class Found(val host: String, val port: Int) {
        val url: String get() = "$host:$port"
    }

    /**
     * Scans [subnet] (e.g. "192.168.0") for hosts with [port] open.
     * Returns the phone's own address first when it answers, then the others in
     * ascending order.
     */
    suspend fun scanSubnet(
        subnet: String,
        port: Int = DEFAULT_PORT,
        ownIp: String = ""
    ): List<Found> = withContext(Dispatchers.IO) {
        if (subnet.isBlank()) return@withContext emptyList()

        val hosts = (1..254).map { "$subnet.$it" }
        val found: List<String> = coroutineScope {
            hosts.chunked(PARALLELISM).map { chunk ->
                async { chunk.filter { host -> isOpen(host, port) } }
            }.map { it.await() }.flatten()
        }

        found
            .sortedWith(
                compareByDescending<String> { it == ownIp }
                    .thenBy { it.substringAfterLast('.').toIntOrNull() ?: 0 }
            )
            .map { Found(it, port) }
    }

    /**
     * Scans the Tailscale tailnet (100.64.0.0/10) for the PC, using [tailnetPlan] to decide
     * which addresses are worth a probe. Probes run in parallel in order; the scan stops
     * at the first chunk that answers something, because the plan is ordered by likelihood.
     */
    suspend fun scanTailnet(
        ownTailscaleIp: String,
        port: Int = DEFAULT_PORT,
        maxHosts: Int = TAILNET_MAX_HOSTS
    ): List<Found> = withContext(Dispatchers.IO) {
        val plan = tailnetPlan(ownTailscaleIp, maxHosts)
        if (plan.isEmpty()) return@withContext emptyList()
        val hits: List<String> = coroutineScope {
            val chunks = plan.chunked(PARALLELISM)
            val jobs = chunks.map { chunk -> async { chunk.filter { host -> isOpen(host, port) } } }
            val acc = ArrayList<String>()
            for (job in jobs) {
                acc.addAll(job.await())
                if (acc.isNotEmpty()) break
            }
            acc
        }
        hits.map { Found(it, port) }
    }

    /**
     * The ordered list of tailnet addresses [scanTailnet] probes. Pure, so the shape can be
     * tested without opening a single socket.
     *
     * A tailnet gives us no subnet hint: the phone knows only its own 100.x address, and the
     * PC can sit anywhere in 100.64.0.0/10 - four million hosts. The probe budget is spent
     * in tiers, most likely first:
     *
     *  1. the phone's own /24, all 254 hosts - tailscale hands out addresses in sequence, so
     *     a PC set up around the same time is often a few numbers away;
     *  2. the phone's own /24 in the two neighbour /16s (100.69.x, 100.71.x for 100.70.x) -
     *     that is where the PC lands when the phone's own address is near the /16 boundary;
     *  3. the first 4 hosts of every /24 in the phone's own /16, then in the two neighbour
     *     /16s, which covers "same tailnet, adjacent block";
     *  4. whatever budget is left, shared EVENLY over all 63 other /16 slices, four staggered
     *     hosts per /24. Every slice gets a share, so a PC at 100.101.x.x is not crowded out
     *     by 100.64.x.x - that starvation was a real bug in the previous version.
     *
     * Tiers stop adding once [maxHosts] is reached; the result never exceeds it.
     */
    internal fun tailnetPlan(ownTailscaleIp: String, maxHosts: Int = TAILNET_MAX_HOSTS): List<String> {
        val parts = ownTailscaleIp.split('.')
        if (parts.size != 4) return emptyList()
        if (!NetworkRoute.isTailscale(ownTailscaleIp)) return emptyList()
        val ownSecond = parts[1].toIntOrNull() ?: return emptyList()
        val ownThird = parts[2].toIntOrNull() ?: return emptyList()
        if (maxHosts <= 0) return emptyList()

        val out = ArrayList<String>(minOf(maxHosts, 8192))
        val seen = HashSet<String>(minOf(maxHosts, 8192))

        fun add(second: Int, third: Int, host: Int) {
            if (out.size >= maxHosts) return
            if (second < 64 || second > 127) return
            if (third < 0 || third > 255) return
            if (host < 1 || host > 254) return
            val address = "100.$second.$third.$host"
            if (seen.add(address)) out.add(address)
        }

        val neighbourSeconds = listOf(ownSecond, ownSecond - 1, ownSecond + 1)
            .filter { it in 64..127 }

        // 1. Our own /24, in full.
        for (host in 1..254) add(ownSecond, ownThird, host)

        // 2. Our own /24 in the neighbour /16s.
        for (second in neighbourSeconds) {
            if (second == ownSecond) continue
            for (host in 1..254) add(second, ownThird, host)
        }

        // 3. First few hosts of every /24 around us.
        for (host in 1..4) {
            for (second in neighbourSeconds) {
                for (third in 0..255) add(second, third, host)
            }
        }

        // 4. The rest, split evenly over every other /16 so no slice can starve another.
        val otherSlices = (64..127).filter { it != ownSecond }
        val remaining = maxHosts - out.size
        val perSlice = if (otherSlices.isEmpty()) 0 else remaining / otherSlices.size
        if (perSlice <= 0) return out

        for (second in otherSlices) {
            var added = 0
            var host = 1
            while (added < perSlice && host <= 254) {
                for (third in 0..255) {
                    if (added >= perSlice) break
                    // Stagger the host per /24 so each block is sampled, not just the first one.
                    var pick = host + ((second + third) % 4)
                    if (pick > 254) pick -= 4
                    val before = out.size
                    add(second, third, pick)
                    if (out.size > before) added++
                }
                host += 4
            }
        }
        return out
    }

    /** True when a TCP connection to host:port succeeds within the timeout. */
    private fun isOpen(host: String, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            true
        }
    }.getOrDefault(false)

    /**
     * Convenience: scan whatever the phone is currently on - the Tailscale tailnet first
     * (that address works from anywhere), then the Wi-Fi subnet.
     */
    suspend fun find(context: android.content.Context): List<Found> {
        val snapshot = NetworkRoute.snapshot(context)
        if (snapshot.hasTailscale) {
            if (isOpen(snapshot.tailscaleIp, DEFAULT_PORT)) {
                return listOf(Found(snapshot.tailscaleIp, DEFAULT_PORT))
            }
            val tailnet = scanTailnet(snapshot.tailscaleIp, DEFAULT_PORT)
            if (tailnet.isNotEmpty()) return tailnet
        }
        return scanSubnet(snapshot.lanSubnet, DEFAULT_PORT, snapshot.localIp)
    }
}
