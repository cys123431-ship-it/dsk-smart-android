package com.dskapp.data.chat

/**
 * Escapes LIKE wildcards so user input matches literally; pair with `ESCAPE '\'`.
 * Without this, searching "50%" or "a_b" would match unrelated text.
 */
fun escapeLikePattern(raw: String): String =
    raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
