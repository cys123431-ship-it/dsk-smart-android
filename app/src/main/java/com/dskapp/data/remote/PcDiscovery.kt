package com.dskapp.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetAddress
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

    /**
     * Probes in flight at once. A dead tailnet address costs the full [CONNECT_TIMEOUT_MS]
     * because nothing ever answers, so this is what decides how long a tailnet scan takes:
     * 18k addresses at 128 parallel is roughly 50 seconds, at 48 it is over two minutes.
     */
    private const val PARALLELISM = 256

    /**
     * How many tailnet addresses one "PC 자동 찾기" is allowed to probe.
     *
     * A tailnet holds fewer than a hundred devices, but they can live anywhere in the four
     * million addresses of 100.64.0.0/10 and the phone knows only its own address. Picking a
     * few hosts per /24 looked clever and failed in the field (the PC was at
     * 100.100.50.25 and was never probed), so the budget buys WHOLE /24 blocks instead.
     */
    /**
     * 63 far slices (16,002) plus a near tier worth having: the phone's own /24, the 8
     * around it, the neighbour /24s, and the neighbour /16s. 24 near blocks (6,096) makes
     * 22,098 addresses, about 60 seconds at 128 parallel probes.
     */
    internal const val TAILNET_MAX_HOSTS = 68_400

    /**
     * Near blocks worth having: the phone's own /24, the 8 around it, the neighbour /24s,
     * and the neighbour /16s (3 x 256). Spending less than this leaves a real hole - a PC
     * in the same /16 but a far /24 was never probed.
     */
    private const val NEAR_BLOCKS = 206

    /** A whole /24 is always probed, or not at all - never a sample of it. */
    private const val BLOCK = 254

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
     * A tailnet gives us no subnet hint: the phone knows only its own 100.x address and the
     * PC can sit anywhere in 100.64.0.0/10 - four million hosts. The budget is spent in
     * tiers, most likely first, and every tier adds WHOLE /24 blocks:
     *
     *  1. the phone's own /24, then the 8 around it in the same /16 - tailscale hands out
     *     addresses in sequence, so the PC is often a few numbers away;
     *  2. the phone's own /24 in the two neighbour /16s (100.69.x, 100.71.x for 100.70.x);
     *  3. the rest of the phone's /16 and of the two neighbour /16s;
     *  4. the remaining 63 /16 slices, each getting the same share. Inside a slice the /24s
     *     are spread (0, 16, 32, ...) rather than taken from the front, because a tailnet's
     *     PCs cluster around one third-octet and the front blocks of a slice are unlikely.
     *
     * The far slices get their share RESERVED before the near tiers run; otherwise the near
     * tiers eat the whole budget and a PC such as 100.100.50.25 is never reached - that was
     * a real bug. The result never exceeds [maxHosts].
     */
    internal fun tailnetPlan(ownTailscaleIp: String, maxHosts: Int = TAILNET_MAX_HOSTS): List<String> {
        val parts = ownTailscaleIp.split('.')
        if (parts.size != 4) return emptyList()
        if (!NetworkRoute.isTailscale(ownTailscaleIp)) return emptyList()
        val ownSecond = parts[1].toIntOrNull() ?: return emptyList()
        val ownThird = parts[2].toIntOrNull() ?: return emptyList()
        if (maxHosts < BLOCK) return emptyList()

        val blocks = ArrayList<Pair<Int, Int>>()
        val taken = HashSet<Long>()

        fun addBlock(second: Int, third: Int, limit: Int): Boolean {
            if (second < 64 || second > 127) return false
            if (third < 0 || third > 255) return false
            if (blocks.size * BLOCK + BLOCK > limit) return false
            val key = second.toLong() * 256L + third
            if (!taken.add(key)) return false
            blocks.add(second to third)
            return true
        }

        val neighbourSeconds = listOf(ownSecond, ownSecond - 1, ownSecond + 1)
            .filter { it in 64..127 }
        val otherSlices = (64..127).filter { it != ownSecond }

        // The far slices' share is RESERVED before the near tiers run, so the near tiers
        // cannot starve them. The earlier code reserved 63 * 254 = 16,002 of an 18,000
        // budget, which left the near tier only 7 blocks and silently dropped the phone's
        // own /24 and every neighbour /16 - so a PC at 100.127.10.20 was never probed at
        // all. The default budget now covers both tiers with room to spare.
        //
        // A far slice gets SEVERAL blocks spread over its third octets. One block at .0 is
        // useless: tailscale hands out /32s from the whole /16, so a PC at 100.127.10.x
        // sits in block .139, not .0. This was the actual reason the phone never found the
        // PC even though the scan looked like it covered every slice.
        val farSlices = otherSlices.size
        val nearWanted = NEAR_BLOCKS
        val leftForFar = (maxHosts / BLOCK) - nearWanted
        val perFarSlice = if (farSlices == 0) 0 else (leftForFar / farSlices).coerceAtLeast(1)
        val farReserve = farSlices * perFarSlice * BLOCK
        val nearLimit = (maxHosts - farReserve).coerceAtLeast(BLOCK)

        // 1. Our own /24, then the 8 around it in the same /16.
        addBlock(ownSecond, ownThird, nearLimit)
        for (third in listOf(ownThird - 1, ownThird + 1, ownThird - 2, ownThird + 2)) {
            addBlock(ownSecond, third, nearLimit)
        }

        // 2. Our own /24 in the neighbour /16s.
        for (second in neighbourSeconds) {
            if (second != ownSecond) addBlock(second, ownThird, nearLimit)
        }

        // 3. The rest of our /16 and of the neighbour /16s, nearest third octet first and
        //    interleaved across the three /16s. Adding one whole /16 before the next would
        //    let the first eat the budget and leave 100.69.* and 100.71.* unprobed.
        for (distance in 1..128) {
            for (second in neighbourSeconds) {
                addBlock(second, ownThird + distance, nearLimit)
                addBlock(second, ownThird - distance, nearLimit)
            }
        }

        // 4. The far slices, each with its reserved share, spread over the slice's third
        //    octets. With one block per slice the stride is 256 and it lands on .0, which is
        //    the least likely place for a PC; spreading means every slice is sampled at a
        //    different third octet instead of all of them checking .0.
        if (perFarSlice > 0) {
            val stride = 256 / perFarSlice
            for ((sliceIndex, second) in otherSlices.withIndex()) {
                var added = 0
                // Offset each slice so the samples do not line up: slice 0 starts at 0,
                // slice 1 a little further on, and so around.
                val offset = (sliceIndex * 37) % 256
                for (step in 0 until perFarSlice) {
                    val third = (offset + step * stride) % 256
                    if (addBlock(second, third, maxHosts)) added++
                }
                // Top up from zero if a collision left the share short.
                var fill = 0
                while (added < perFarSlice && fill <= 255) {
                    if (addBlock(second, fill, maxHosts)) added++
                    fill++
                }
            }
        }

        val out = ArrayList<String>(blocks.size * BLOCK)
        for ((second, third) in blocks) {
            for (host in 1..254) out.add("100.$second.$third.$host")
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
     * Resolves a MagicDNS name to the tailnet addresses behind it.
     *
     * This is the reliable way to find the PC and it costs milliseconds. A tailnet is
     * 4,194,304 addresses; guessing with whole /24 blocks can only ever cover a small
     * slice of one /16, so a scan is a last resort, not the plan. MagicDNS turns the PC's
     * machine name into its 100.x address directly.
     */
    suspend fun resolveName(name: String): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            InetAddress.getAllByName(name)
                .map { it.hostAddress.orEmpty().substringBefore('%') }
                .filter { it.isNotBlank() && it.contains('.') }
        }.getOrDefault(emptyList())
    }

    /**
     * Convenience: find the PC on whatever the phone is currently on.
     *
     * Order matters. MagicDNS name first (instant, exact), then the remembered addresses,
     * then the tailnet scan, then the Wi-Fi subnet.
     */
    suspend fun find(
        context: android.content.Context,
        magicDnsName: String = ""
    ): List<Found> {
        val snapshot = NetworkRoute.snapshot(context)

        // 1. MagicDNS: the name never changes, so this works from home and from outside.
        if (snapshot.hasTailscale && magicDnsName.isNotBlank()) {
            val resolved = resolveName(magicDnsName)
            val answering = resolved.filter { isOpen(it, DEFAULT_PORT) }
            if (answering.isNotEmpty()) return answering.map { Found(it, DEFAULT_PORT) }
        }

        if (snapshot.hasTailscale) {
            // 2. The phone's own address is not the PC, but a wrong guess is cheap.
            if (isOpen(snapshot.tailscaleIp, DEFAULT_PORT)) {
                return listOf(Found(snapshot.tailscaleIp, DEFAULT_PORT))
            }
            // 3. Last resort: walk the tailnet in /24 blocks.
            val tailnet = scanTailnet(snapshot.tailscaleIp, DEFAULT_PORT)
            if (tailnet.isNotEmpty()) return tailnet
        }
        return scanSubnet(snapshot.lanSubnet, DEFAULT_PORT, snapshot.localIp)
    }
}
