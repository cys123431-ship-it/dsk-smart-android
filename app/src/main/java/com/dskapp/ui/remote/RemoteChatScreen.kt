package com.dskapp.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dskapp.data.remote.AcpPermissionRequest
import com.dskapp.data.remote.AcpToolCall
import com.dskapp.data.remote.AcpUsage

/**
 * Chat with the PC's goose desktop agent.
 *
 * Answer text streams in as `session/update` chunks, tool calls appear as cards while the
 * agent works, and a permission request blocks with an allow/deny dialog — approving is
 * never automatic, because ACP permission callbacks are not a sandbox.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteChatScreen(
    viewModel: RemoteChatViewModel,
    title: String,
    loadSessionId: String?,
    onBack: () -> Unit,
    onOpenSessions: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(loadSessionId) {
        if (loadSessionId == null) viewModel.startNew() else viewModel.requestLoad(loadSessionId, title)
    }
    LaunchedEffect(state.error) {
        val error = state.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(error)
        viewModel.consumeError()
    }
    // Follow the stream: item count alone changes even when a chunk appends to the last bubble.
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text?.length, state.tools.size) {
        val total = state.messages.size + state.tools.size
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    state.pendingPermission?.let { request ->
        PermissionDialog(
            request = request,
            onAllow = viewModel::allowPermission,
            onDeny = viewModel::denyPermission
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.sessionTitle.ifBlank { "PC goose 원격" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = statusLine(state),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    TextButton(onClick = onOpenSessions) { Text("대화 목록") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
        ) {
            state.usage?.let { usage ->
                LinearProgressIndicator(
                    progress = { usage.fraction },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "컨텍스트 ${usage.used} / ${usage.size} 토큰",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }

            if (state.connection == RemoteUiState.Connection.CONNECTING ||
                state.connection == RemoteUiState.Connection.IDLE
            ) {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                        Text(
                            text = state.statusText.ifBlank { "PC goose에 연결하는 중…" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.messages, key = { it.id }) { message ->
                    MessageBubble(message)
                }
                if (state.isTurnRunning && state.tools.isNotEmpty()) {
                    item(key = "tools") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.tools.forEach { ToolCard(it) }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("PC의 goose에게 지시…") },
                    maxLines = 5,
                    enabled = state.connection == RemoteUiState.Connection.READY,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
                )
                if (state.isTurnRunning) {
                    IconButton(onClick = viewModel::cancel) {
                        Icon(Icons.Filled.Stop, contentDescription = "중단")
                    }
                } else {
                    IconButton(
                        onClick = {
                            val text = draft.trim()
                            if (text.isNotEmpty()) {
                                viewModel.send(text)
                                draft = ""
                            }
                        },
                        enabled = state.connection == RemoteUiState.Connection.READY && draft.isNotBlank()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "보내기")
                    }
                }
            }
        }
    }
}

private fun statusLine(state: RemoteUiState): String = when (state.connection) {
    RemoteUiState.Connection.READY -> buildString {
        append(state.agentLabel.ifBlank { "goose" })
        val mode = state.modes.firstOrNull { it.id == state.currentMode }?.name ?: state.currentMode
        if (mode.isNotBlank()) append(" · ").append(mode)
        if (state.isTurnRunning) append(" · 작업 중…")
        if (state.statusText.isNotBlank()) append(" · ").append(state.statusText)
    }
    RemoteUiState.Connection.CONNECTING -> "연결 중…"
    RemoteUiState.Connection.FAILED -> "연결되지 않음"
    RemoteUiState.Connection.IDLE -> "대기 중"
}

@Composable
private fun MessageBubble(message: RemoteMessage) {
    val isUser = message.fromUser
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = when {
                isUser -> MaterialTheme.colorScheme.primaryContainer
                message.isThought -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (message.isThought) {
                    Text(
                        text = "생각",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = message.text.ifBlank { if (message.streaming) "…" else "" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = if (message.isThought) FontStyle.Italic else FontStyle.Normal
                )
            }
        }
    }
}

@Composable
private fun ToolCard(tool: AcpToolCall) {
    val running = !tool.isDone
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (running) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    text = if (tool.status == "failed") "✕" else "✓",
                    color = if (tool.status == "failed") MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            }
            Column(modifier = Modifier.padding(start = 10.dp)) {
                Text(
                    text = tool.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val detail = listOfNotNull(
                    tool.kind?.takeIf { it.isNotBlank() },
                    when (tool.status) {
                        "completed" -> "완료"
                        "failed" -> "실패"
                        "in_progress" -> "실행 중"
                        else -> "대기"
                    }
                ).joinToString(" · ")
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PermissionDialog(
    request: AcpPermissionRequest,
    onAllow: () -> Unit,
    onDeny: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* A turn is waiting on this answer; require an explicit choice. */ },
        title = { Text("PC에서 도구를 실행할까요?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(request.title, style = MaterialTheme.typography.bodyMedium)
                if (request.detail.isNotBlank()) {
                    Text(
                        text = request.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    request.options.take(3).forEach { (_, label) ->
                        AssistChip(onClick = {}, label = { Text(label) })
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onAllow) { Text("승인") } },
        dismissButton = { OutlinedButton(onClick = onDeny) { Text("거절") } }
    )
}
