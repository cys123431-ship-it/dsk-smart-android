package com.dskapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dskapp.data.chat.Attachment

private const val MAX_INPUT_LINES = 6

/**
 * ChatGPT-style composer pinned to the bottom of the chat.
 *
 * Layout: a pill-shaped container holding pending attachment chips, a multi-line text field
 * that grows up to [MAX_INPUT_LINES], a "+" attach menu on the left and a send/stop circle
 * button on the right.
 */
@Composable
fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isSending: Boolean,
    pendingAttachments: List<Attachment>,
    onRemoveAttachment: (Attachment) -> Unit,
    onPickImage: () -> Unit,
    onPickCamera: () -> Unit,
    onPickFile: () -> Unit,
    isImporting: Boolean,
    canSend: Boolean,
    isListening: Boolean,
    onToggleVoice: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                // Navigation bar or keyboard, whichever is taller; the Surface colour still
                // fills the area behind them.
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            PendingAttachments(pendingAttachments, onRemoveAttachment)
            ComposerRow(
                value = value,
                onValueChange = onValueChange,
                onSend = onSend,
                onStop = onStop,
                isSending = isSending,
                onPickImage = onPickImage,
                onPickCamera = onPickCamera,
                onPickFile = onPickFile,
                isImporting = isImporting,
                canSend = canSend,
                isListening = isListening,
                onToggleVoice = onToggleVoice
            )

            Text(
                text = "AI가 실수할 수 있습니다. 중요한 내용은 확인하세요.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun PendingAttachments(
    attachments: List<Attachment>,
    onRemove: (Attachment) -> Unit
) {
    if (attachments.isEmpty()) return

    // Scrolls sideways: a PDF can add several page images at once.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        attachments.forEach { attachment ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = attachment.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                    IconButton(
                        onClick = { onRemove(attachment) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "첨부 삭제",
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerRow(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isSending: Boolean,
    onPickImage: () -> Unit,
    onPickCamera: () -> Unit,
    onPickFile: () -> Unit,
    isImporting: Boolean,
    canSend: Boolean,
    isListening: Boolean,
    onToggleVoice: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    enabled = !isImporting,
                    modifier = Modifier.size(44.dp)
                ) {
                    if (isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "첨부",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("사진 선택") },
                        onClick = {
                            menuExpanded = false
                            onPickImage()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("카메라로 촬영") },
                        onClick = {
                            menuExpanded = false
                            onPickCamera()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("파일 (PDF·Word·PPT·텍스트)") },
                        onClick = {
                            menuExpanded = false
                            onPickFile()
                        }
                    )
                }
            }

            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp, max = 160.dp),
                placeholder = {
                    Text(
                        text = "무엇이든 물어보세요",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                textStyle = MaterialTheme.typography.bodyLarge,
                maxLines = MAX_INPUT_LINES,
                shape = RoundedCornerShape(22.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )

            Spacer(Modifier.width(2.dp))

            // Mic sits left of send, as in ChatGPT; turns red while capturing.
            IconButton(
                onClick = onToggleVoice,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = if (isListening) Icons.Filled.Mic else Icons.Filled.MicNone,
                    contentDescription = if (isListening) "음성 입력 중지" else "음성 입력",
                    tint = if (isListening) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            val active = canSend || isSending
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = { if (isSending) onStop() else onSend() },
                    enabled = active,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = if (isSending) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (isSending) "중지" else "전송",
                        tint = if (active) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

