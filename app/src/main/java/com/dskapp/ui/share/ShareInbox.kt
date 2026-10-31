package com.dskapp.ui.share

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Text and files another app handed to us through the system share sheet. */
data class SharedContent(
    val text: String?,
    val uris: List<Uri>,
    /** Distinguishes two identical shares so each one is delivered. */
    val receivedAt: Long = System.nanoTime()
)

/**
 * Hands a share from [com.dskapp.MainActivity] to the chat UI. The activity may receive the
 * intent before the conversation list or the chat screen exist, so the share waits here
 * until the UI picks it up with [consume].
 */
object ShareInbox {

    private val _pending = MutableStateFlow<SharedContent?>(null)
    val pending: StateFlow<SharedContent?> = _pending.asStateFlow()

    /** Returns true when [intent] was a share we accepted. */
    fun offer(intent: Intent?): Boolean {
        val content = parse(intent) ?: return false
        _pending.value = content
        return true
    }

    fun consume(content: SharedContent) {
        _pending.compareAndSet(content, null)
    }

    private fun parse(intent: Intent?): SharedContent? {
        if (intent == null) return null
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            )
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    .orEmpty()
            else -> return null
        }
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        val body = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        // Browsers send the page title as the subject and the URL as the text.
        val text = listOfNotNull(subject, body)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
            .ifBlank { null }
        if (text == null && uris.isEmpty()) return null
        return SharedContent(text = text, uris = uris.take(MAX_SHARED_FILES))
    }

    /** More photos than this in one message is rarely useful and costs a lot of tokens. */
    private const val MAX_SHARED_FILES = 6
}
