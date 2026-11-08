package com.dskapp.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
                async {
                    chunk.filter { host -> isOpen(host, port) }
                }
            }.awaitAll().flatten()
        }

        found
            .sortedWith(
                compareByDescending<String> { it == ownIp }
                    .thenBy { it.substringAfterLast('.').toIntOrNull() ?: 0 }
            )
            .map { Found(it, port) }
    }

    /** True when a TCP connection to host:port succeeds within the timeout. */
    private fun isOpen(host: String, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            true
        }
    }.getOrDefault(false)

    /**
     * Convenience: scan whatever the phone is currently on.
     * Tailscale is checked first because that address is a single host, not a subnet.
     */
    suspend fun find(context: android.content.Context): List<Found> {
        val snapshot = NetworkRoute.snapshot(context)
        if (snapshot.hasTailscale && isOpen(snapshot.tailscaleIp, DEFAULT_PORT)) {
            return listOf(Found(snapshot.tailscaleIp, DEFAULT_PORT))
        }
        return scanSubnet(snapshot.lanSubnet, DEFAULT_PORT, snapshot.localIp)
    }
}
