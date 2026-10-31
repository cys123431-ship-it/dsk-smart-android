package com.dskapp.ui.components

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dskapp.data.chat.Attachment
import com.dskapp.data.chat.AttachmentKind
import com.dskapp.ui.chat.ChatMessage
import kotlinx.coroutines.launch

/**
 * ChatGPT-style turn.
 *
 * The user's own message is a compact rounded bubble aligned to the end. The assistant's
 * answer is full width with no bubble, so long markdown (headings, tables, code) has room
 * to breathe - this is what makes the layout read like ChatGPT rather than a messenger.
 *
 * Copy / edit / regenerate actions live in a slim row under each turn so the bubble itself
 * stays clean; the token caption shares that row so it never steals vertical space.
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    onAttachmentClick: (Attachment) -> Unit = {},
    canEdit: Boolean = false,
    canRegenerate: Boolean = false,
    onEdit: (String) -> Unit = {},
    onRegenerate: () -> Unit = {},
    usageText: String? = null
) {
    if (message.isUser) {
        UserTurn(
            message = message,
            modifier = modifier,
            onAttachmentClick = onAttachmentClick,
            canEdit = canEdit,
            onEdit = onEdit
        )
    } else {
        AssistantTurn(
            message = message,
            modifier = modifier,
            canRegenerate = canRegenerate,
            onRegenerate = onRegenerate,
            usageText = usageText
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserTurn(
    message: ChatMessage,
    modifier: Modifier,
    onAttachmentClick: (Attachment) -> Unit,
    canEdit: Boolean,
    onEdit: (String) -> Unit
) {
    var editing by rememberSaveable(message.id) { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End
    ) {
        if (message.attachments.isNotEmpty()) {
            // Wraps onto more lines: a PDF can add several page images at once.
            FlowRow(
                modifier = Modifier.padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(6.dp)
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

        if (message.content.isNotBlank() || canEdit) {
            Row(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (message.content.isNotBlank()) {
                    CopyAction(message.content)
                }
                if (canEdit) {
                    ActionIcon(
                        icon = Icons.Outlined.Edit,
                        contentDescription = "수정",
                        onClick = { editing = true }
                    )
                }
            }
        }
    }

    if (editing) {
        EditDialog(
            message = message,
            onDismiss = { editing = false },
            onConfirm = { text ->
                onEdit(text)
                editing = false
            }
        )
    }
}

@Composable
private fun AssistantTurn(
    message: ChatMessage,
    modifier: Modifier,
    canRegenerate: Boolean,
    onRegenerate: () -> Unit,
    usageText: String?
) {
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

            // Actions are hidden while thinking and for empty answers, so a pending turn
            // never shows a copy/regenerate row the user cannot act on.
            if (!message.isThinking && message.content.isNotBlank()) {
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CopyAction(message.content)
                    if (canRegenerate) {
                        ActionIcon(
                            icon = Icons.Outlined.Refresh,
                            contentDescription = "다시 생성",
                            onClick = onRegenerate
                        )
                    }
                    if (usageText != null) {
                        Text(
                            text = usageText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Compact 32.dp icon button shared by every message action; keeps tap targets uniform. */
@Composable
private fun ActionIcon(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(32.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

/** Copy button that writes to the system clipboard using the Compose clipboard API. */
@Composable
private fun CopyAction(text: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    ActionIcon(
        icon = Icons.Outlined.ContentCopy,
        contentDescription = "복사",
        onClick = {
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("DSK", text)))
            }
        }
    )
}

@Composable
private fun EditDialog(
    message: ChatMessage,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by rememberSaveable(message.id) { mutableStateOf(message.content) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("메시지 수정") },
        text = {
            Column {
                Text(
                    text = "이 메시지 다음의 대화는 지워지고 새 답변을 받습니다.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    minLines = 3,
                    maxLines = 10
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                // Attachments survive the edit, so an empty body is acceptable in that case.
                enabled = text.isNotBlank() || message.attachments.isNotEmpty()
            ) {
                Text("보내기")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
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
