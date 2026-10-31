package com.dskapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dskapp.data.chat.Attachment
import com.dskapp.data.chat.AttachmentKind
import com.dskapp.ui.chat.ChatMessage

/**
 * ChatGPT-style turn.
 *
 * The user's own message is a compact rounded bubble aligned to the end. The assistant's
 * answer is full width with no bubble, so long markdown (headings, tables, code) has room
 * to breathe - this is what makes the layout read like ChatGPT rather than a messenger.
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    onAttachmentClick: (Attachment) -> Unit = {}
) {
    if (message.isUser) {
        UserTurn(message, modifier, onAttachmentClick)
    } else {
        AssistantTurn(message, modifier)
    }
}

@Composable
private fun UserTurn(
    message: ChatMessage,
    modifier: Modifier,
    onAttachmentClick: (Attachment) -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End
    ) {
        if (message.attachments.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                message.attachments.forEach { attachment ->
                    AttachmentChip(
                        attachment = attachment,
                        onClick = { onAttachmentClick(attachment) }
                    )
                }
            }
        }

        if (message.content.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantTurn(message: ChatMessage, modifier: Modifier) {
    Row(modifier = modifier.fillMaxWidth()) {
        // Small brand avatar, like ChatGPT's logo column.
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(16.dp)
            )
        }

        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            when {
                message.isThinking -> Text(
                    text = "생각하는 중…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                message.content.isBlank() -> Unit

                else -> MarkdownText(
                    markdown = message.content,
                    isError = message.isError
                )
            }
        }
    }
}

/** Attachment preview chip shown above a user message (and in the composer). */
@Composable
fun AttachmentChip(
    attachment: Attachment,
    onClick: () -> Unit = {}
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (attachment.kind == AttachmentKind.IMAGE) "🖼" else "📄",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = attachment.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp)
            )
        }
    }
}

