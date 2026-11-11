package com.dskapp.ui.remote

/**
 * How streamed chunks turn into chat bubbles. Pure list-in, list-out, so the rules can be
 * tested without a view model or a socket.
 *
 * goose streams an answer as many small chunks, and a turn can switch between thinking and
 * answering (and call tools in between). The rules:
 *
 *  - chunks of the same kind keep growing the bubble that is still open;
 *  - a switch between thinking and answering closes the open bubble and starts a new one, so
 *    hidden reasoning never ends up glued to the answer;
 *  - the blank lines models like to send first are dropped while a bubble is still empty;
 *  - when a turn (or a replayed history) ends, open bubbles are closed, trimmed, and the ones
 *    that never received text are removed.
 */
internal object RemoteTranscript {

    /** Adds one streamed [text] chunk; [thought] marks reasoning (`agent_thought_chunk`). */
    fun append(
        messages: List<RemoteMessage>,
        text: String,
        thought: Boolean,
        newId: () -> Long
    ): List<RemoteMessage> {
        if (text.isEmpty()) return messages
        val index = messages.indexOfLast { !it.fromUser && it.streaming }
        if (index < 0) {
            val first = text.trimStart()
            // Whitespace alone is not worth a bubble; the next real chunk opens it.
            if (first.isEmpty()) return messages
            return messages + RemoteMessage(newId(), fromUser = false, text = first, isThought = thought, streaming = true)
        }
        val current = messages[index]
        return when {
            // Nothing visible yet: the bubble simply becomes whatever kind arrives first.
            current.text.isBlank() ->
                messages.replacedAt(index, current.copy(text = (current.text + text).trimStart(), isThought = thought))
            current.isThought == thought ->
                messages.replacedAt(index, current.copy(text = current.text + text))
            else -> {
                val opened = text.trimStart()
                if (opened.isEmpty()) {
                    messages
                } else {
                    messages.replacedAt(index, current.copy(text = current.text.trimEnd(), streaming = false)) +
                        RemoteMessage(newId(), fromUser = false, text = opened, isThought = thought, streaming = true)
                }
            }
        }
    }

    /**
     * Starts a user turn: closes whatever was open, adds the user's message and, when
     * [placeholder] is set, an empty open bubble that shows "…" until the first chunk.
     */
    fun userTurn(
        messages: List<RemoteMessage>,
        text: String,
        placeholder: Boolean,
        newId: () -> Long
    ): List<RemoteMessage> {
        val closed = finish(messages) + RemoteMessage(newId(), fromUser = true, text = text)
        return if (placeholder) {
            closed + RemoteMessage(newId(), fromUser = false, text = "", streaming = true)
        } else {
            closed
        }
    }

    /** Closes every open bubble; assistant bubbles are trimmed and empty ones dropped. */
    fun finish(messages: List<RemoteMessage>): List<RemoteMessage> =
        messages.mapNotNull { message ->
            when {
                message.fromUser -> message
                !message.streaming -> message.takeIf { it.text.isNotBlank() }
                else -> message.copy(text = message.text.trim(), streaming = false).takeIf { it.text.isNotEmpty() }
            }
        }

    private fun List<RemoteMessage>.replacedAt(index: Int, message: RemoteMessage): List<RemoteMessage> =
        toMutableList().also { it[index] = message }
}
