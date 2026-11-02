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
    val uris: List<Uri>
)

/** Why the app was opened, beyond a plain launch. */
sealed interface LaunchAction {
    /** Distinguishes two identical requests so each one is delivered. */
    val receivedAt: Long

    data class Share(val content: SharedContent, override val receivedAt: Long = System.nanoTime()) : LaunchAction
    data class NewChat(override val receivedAt: Long = System.nanoTime()) : LaunchAction
    data class Camera(override val receivedAt: Long = System.nanoTime()) : LaunchAction
    data class Voice(override val receivedAt: Long = System.nanoTime()) : LaunchAction
    data class OpenConversation(val conversationId: Long, override val receivedAt: Long = System.nanoTime()) : LaunchAction
}

/**
 * Hands launch requests (shares, launcher shortcuts, notification taps) from
 * [com.dskapp.MainActivity] to the chat UI. The intent may arrive before the conversation
 * list or the chat screen exist, so the request waits here until the UI takes it with
 * [consume].
 */
object LaunchInbox {

    const val ACTION_NEW_CHAT = "com.dskapp.action.NEW_CHAT"
    const val ACTION_CAMERA = "com.dskapp.action.CAMERA"
    const val ACTION_VOICE = "com.dskapp.action.VOICE"
    const val ACTION_OPEN_CONVERSATION = "com.dskapp.action.OPEN_CONVERSATION"
    const val EXTRA_CONVERSATION_ID = "conversation_id"

    private val _pending = MutableStateFlow<LaunchAction?>(null)
    val pending: StateFlow<LaunchAction?> = _pending.asStateFlow()

    /** Returns true when [intent] carried a request we accepted. */
    fun offer(intent: Intent?): Boolean {
        val action = parse(intent) ?: return false
        _pending.value = action
        return true
    }

    fun consume(action: LaunchAction) {
        _pending.compareAndSet(action, null)
    }

    private fun parse(intent: Intent?): LaunchAction? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> parseShare(intent)?.let { LaunchAction.Share(it) }
            ACTION_NEW_CHAT -> LaunchAction.NewChat()
            ACTION_CAMERA -> LaunchAction.Camera()
            ACTION_VOICE -> LaunchAction.Voice()
            ACTION_OPEN_CONVERSATION -> intent.getLongExtra(EXTRA_CONVERSATION_ID, 0L)
                .takeIf { it > 0L }
                ?.let { LaunchAction.OpenConversation(it) }
            else -> null
        }
    }

    private fun parseShare(intent: Intent): SharedContent? {
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            )
            else -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                .orEmpty()
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
