package com.dskapp.data.remote

/**
 * The pairing code the PC draws as a QR in DSK-Remote.bat.
 *
 * Shape, one line:
 *
 *     DSK1|<host:port>|<kind>|<name>|<secret>
 *
 * - `DSK1`   format marker; a newer PC may add `DSK2` and the phone keeps working
 * - `host`   the address the PC believes the phone can reach (Wi-Fi, or Tailscale
 *            when the PC is on a tailnet), already including `:3284`
 * - `kind`   `tailscale` or `lan`; decides the label and which field is remembered
 * - `name`   the PC's computer name, so the known-PC list reads like a person's list
 * - `secret` the same key `DSK-Remote.bat` hands to goose
 *
 * `name` may be left out (the PC writes `DSK1|<host>|<kind>|<secret>`); the key is then
 * the last field and the PC shows as "PC" in the list.
 *
 * The PC builds this string from its *current* addresses every time it starts, so a
 * code on screen is never stale in the way a printed IP is. Parsing is deliberately
 * forgiving: spaces and line breaks from the QR payload are trimmed, the prefix is
 * case-insensitive, and a missing name still counts as valid.
 *
 * Why bother at all: finding a PC on a home network failed for every automatic route
 * we tried (tailnet scanning can only sample 0.4% of one /16, and goose rejects an
 * Origin it was not told about). A code the PC shows removes both problems - the phone
 * never has to guess an address, and typing a 32-character key by hand is gone too.
 */
data class PairingCode(
    val address: String,
    val kind: String,
    val name: String,
    val secret: String
) {
    companion object {
        const val PREFIX = "DSK1"
        private const val SEPARATOR = "|"

        fun parse(raw: String?): PairingCode? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            val parts = text.split(SEPARATOR)
            if (parts.size < 2) return null
            if (!parts[0].equals(PREFIX, ignoreCase = true) &&
                !parts[0].uppercase().startsWith(PREFIX)
            ) {
                return null
            }
            val address = parts[1].trim()
            if (address.isEmpty() || address.length > 64) return null
            val kind = parts.getOrNull(2)?.trim()?.lowercase().orEmpty()
            // A code with no name still has to work. Four fields means the last one is the
            // key, not the name: otherwise the key would be read as a PC name and the QR
            // that follows would fail only because it left the name out.
            val name: String
            val secret: String
            if (parts.size <= 4) {
                name = ""
                secret = parts.getOrNull(3)?.trim().orEmpty()
            } else {
                name = parts[3].trim().take(40)
                // Everything after the fourth separator is the key: a key may contain '|'.
                secret = parts.drop(4).joinToString(SEPARATOR).trim()
            }
            val resolvedKind = when {
                kind == KnownPc.KIND_TAILSCALE -> KnownPc.KIND_TAILSCALE
                kind == KnownPc.KIND_LAN -> KnownPc.KIND_LAN
                NetworkRoute.isTailscale(address) -> KnownPc.KIND_TAILSCALE
                else -> KnownPc.KIND_LAN
            }
            return PairingCode(address = address, kind = resolvedKind, name = name, secret = secret)
        }
    }
}
