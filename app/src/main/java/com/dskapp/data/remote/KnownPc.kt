package com.dskapp.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A PC the app has connected to before.
 *
 * One phone may reach the same PC two ways: over Wi-Fi (`192.168.0.12:3284`) and
 * over Tailscale (`100.100.50.25:3284`). They are stored as separate entries
 * because only one of them can work at a time, and the app picks whichever the
 * phone can actually reach right now.
 */
@Serializable
data class KnownPc(
    /** host:port - what goes into the settings field. */
    val address: String,
    /** Friendly name shown in the list. */
    val name: String = "",
    /** "lan" or "tailscale" - how this address was found. */
    val kind: String = "lan",
    /** Epoch millis of the last successful connection. */
    val lastUsed: Long = 0L
) {
    val isTailscale: Boolean get() = kind == KIND_TAILSCALE
    val host: String get() = address.substringBefore(':')
    val port: Int get() = address.substringAfter(':', "3284").toIntOrNull() ?: 3284

    companion object {
        const val KIND_LAN = "lan"
        const val KIND_TAILSCALE = "tailscale"
    }
}

/** JSON (de)serialisation for the remembered PC list, kept defensive on purpose. */
object KnownPcsCodec {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun encode(pcs: List<KnownPc>): String = buildJsonArray {
        pcs.forEach { pc ->
            add(
                buildJsonObject {
                    put("address", pc.address)
                    put("name", pc.name)
                    put("kind", pc.kind)
                    put("lastUsed", pc.lastUsed)
                }
            )
        }
    }.toString()

    fun decode(raw: String?): List<KnownPc> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
                val obj = (element as? JsonObject) ?: return@mapNotNull null
                val address = obj["address"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (address.isBlank()) return@mapNotNull null
                KnownPc(
                    address = address,
                    name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    kind = obj["kind"]?.jsonPrimitive?.contentOrNull ?: KnownPc.KIND_LAN,
                    lastUsed = obj["lastUsed"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Adds [pc], replacing an entry with the same address, and keeps the newest
     * [limit] entries so the list cannot grow without bound.
     */
    fun merge(existing: List<KnownPc>, pc: KnownPc, limit: Int = 8): List<KnownPc> {
        val without = existing.filterNot { it.address.equals(pc.address, ignoreCase = true) }
        return (listOf(pc) + without).sortedByDescending { it.lastUsed }.take(limit)
    }
}
