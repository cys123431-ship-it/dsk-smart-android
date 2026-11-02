package com.dskapp.data.schedule

import kotlinx.serialization.Serializable

/**
 * A user-defined task that runs once per matching day at a fixed Korea-local time.
 *
 * Persisted as JSON, so every field has a default where it is safe to do so: older
 * payloads keep deserializing after the schema grows.
 */
@Serializable
data class ScheduledTask(
    val id: String,
    val name: String,
    val prompt: String,
    val hour: Int,                 // 0..23, Korea time
    val minute: Int,               // 0..59
    val days: Set<Int> = ALL_DAYS, // ISO day numbers 1 = Monday .. 7 = Sunday
    val model: String? = null,     // null = default model
    val enabled: Boolean = true,
    val conversationId: Long? = null,
    val lastRunAt: Long? = null,
    val lastError: String? = null, // null after a successful run
    /** Run through the phone agent (open apps, scroll, read only) instead of a plain AI request. */
    val usePhoneAgent: Boolean = false
) {
    companion object {
        val ALL_DAYS: Set<Int> = (1..7).toSet()
    }
}
