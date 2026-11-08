package com.dskapp.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Answers one question: how is this phone able to reach the PC?
 *
 * The user should never have to type an IP address. Three routes exist:
 *
 *  - LAN      the phone and the PC share a Wi-Fi network. The PC also sits on a
 *             subnet (192.168.x.x / 10.x.x.x / 172.16-31.x.x), so the phone
 *             scans that subnet for the open DSK port.
 *  - Tailscale the phone has the Tailscale app connected, which adds a 100.64.0.0/10
 *             address. The PC's tailnet address is stable, so it can be remembered.
 *  - Manual   the user typed something; it always wins.
 *
 * This class only *reads* the network. It never changes VPN state: Android does
 * not allow one app to switch another app's VPN on, so Tailscale is detected
 * rather than driven.
 */
object NetworkRoute {

    /** Tailscale hands out addresses from 100.64.0.0/10. */
    private const val TAILSCALE_PREFIX = "100."

    data class Snapshot(
        /** Wi-Fi / Ethernet IPv4 address of this phone, empty when offline. */
        val localIp: String = "",
        /** 100.x.y.z when the Tailscale app is connected, empty otherwise. */
        val tailscaleIp: String = "",
        /** The subnet to scan, for example "192.168.0". Empty when not on a LAN. */
        val lanSubnet: String = ""
    ) {
        val isOnLan: Boolean get() = localIp.isNotEmpty() && lanSubnet.isNotEmpty()
        val hasTailscale: Boolean get() = tailscaleIp.isNotEmpty()
    }

    fun snapshot(context: Context): Snapshot {
        val (local, tailscale) = readAddresses(context)
        return Snapshot(
            localIp = local,
            tailscaleIp = tailscale,
            lanSubnet = if (local.isEmpty()) "" else local.substringBeforeLast('.', "")
        )
    }

    /**
     * Reads every non-loopback IPv4 address of the phone and splits them into a
     * "normal" address (the LAN) and a Tailscale address. Uses [ConnectivityManager]
     * for the link properties because those reflect only the *active* networks,
     * unlike [NetworkInterface] which also reports dormant interfaces.
     */
    private fun readAddresses(context: Context): Pair<String, String> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        var local = ""
        var tailscale = ""

        fun consider(address: String) {
            if (address.startsWith("127.") || address.startsWith("169.254.")) return
            if (address.startsWith(TAILSCALE_PREFIX)) {
                if (tailscale.isEmpty()) tailscale = address
            } else if (local.isEmpty()) {
                local = address
            }
        }

        cm?.let { manager ->
            manager.allNetworks.forEach { network ->
                val props: LinkProperties = manager.getLinkProperties(network) ?: return@forEach
                props.linkAddresses.forEach { link: LinkAddress ->
                    val addr = link.address
                    if (addr is Inet4Address) consider(addr.hostAddress.orEmpty())
                }
            }
        }

        // Fallback for phones where allNetworks is restricted.
        if (local.isEmpty() && tailscale.isEmpty()) {
            runCatching {
                NetworkInterface.getNetworkInterfaces().toList().forEach { nic ->
                    if (!nic.isUp || nic.isLoopback) return@forEach
                    nic.inetAddresses.toList().forEach { addr ->
                        if (addr is Inet4Address) consider(addr.hostAddress.orEmpty())
                    }
                }
            }
        }
        return local to tailscale
    }
}
