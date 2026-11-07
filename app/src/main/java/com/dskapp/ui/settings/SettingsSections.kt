package com.dskapp.ui.settings

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.dskapp.agent.AgentBridge
import com.dskapp.agent.AgentLevel
import com.dskapp.data.commands.CommandApplier
import kotlin.math.roundToInt
import com.dskapp.data.commands.PromptCommand
import kotlinx.coroutines.launch

// These composables are dropped into an existing vertically scrolling Column, so each one only
// emits a handful of direct children (title, body, actions) and never owns a scroll container.

/** Editable list of the user's own prompt shortcuts ("commands"). */
@Composable
fun CommandsSection(
    commands: List<PromptCommand>,
    onAdd: (label: String, prompt: String) -> Unit,
    onUpdate: (PromptCommand) -> Unit,
    onDelete: (id: String) -> Unit,
    onMove: (id: String, up: Boolean) -> Unit,
    onReset: () -> Unit
) {
    // null = no dialog, "" = add dialog, otherwise the id of the command being edited.
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }

    Text("나만의 명령어", style = MaterialTheme.typography.titleMedium)
    Text(
        "입력창 위 버튼으로 한 번에 쓰는 요청입니다. 명령 안에 ${CommandApplier.PLACEHOLDER} 을 넣으면 " +
            "그 자리에 입력한 글이 들어갑니다.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    commands.forEachIndexed { index, command ->
        CommandRow(
            command = command,
            isFirst = index == 0,
            isLast = index == commands.lastIndex,
            onOpenEdit = { editingId = command.id },
            onMove = onMove,
            onDelete = { deleteId = command.id }
        )
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(onClick = { editingId = "" }) { Text("명령어 추가") }
        TextButton(onClick = { confirmReset = true }) { Text("기본값으로") }
    }

    if (editingId != null) {
        val isNew = editingId.isNullOrEmpty()
        val initial = commands.firstOrNull { it.id == editingId }
        CommandDialog(
            initial = if (isNew) null else initial,
            onDismiss = { editingId = null },
            onSave = { label, prompt ->
                if (isNew || initial == null) {
                    onAdd(label, prompt)
                } else {
                    onUpdate(initial.copy(label = label, prompt = prompt))
                }
                editingId = null
            }
        )
    }

    val deleteTarget = deleteId?.let { id -> commands.firstOrNull { it.id == id } }
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text("명령어 삭제") },
            text = { Text("‘${deleteTarget.label}’ 명령어를 지울까요?") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(deleteTarget.id)
                    deleteId = null
                }) { Text("지우기") }
            },
            dismissButton = {
                TextButton(onClick = { deleteId = null }) { Text("취소") }
            }
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("기본값으로") },
            text = { Text("기본 명령어로 되돌릴까요? 직접 만든 명령어는 지워집니다.") },
            confirmButton = {
                TextButton(onClick = {
                    onReset()
                    confirmReset = false
                }) { Text("되돌리기") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("취소") }
            }
        )
    }
}

@Composable
private fun CommandRow(
    command: PromptCommand,
    isFirst: Boolean,
    isLast: Boolean,
    onOpenEdit: () -> Unit,
    onMove: (String, Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onOpenEdit,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = command.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = command.prompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            SmallIconButton(
                icon = Icons.Filled.KeyboardArrowUp,
                contentDescription = "위로",
                enabled = !isFirst,
                onClick = { onMove(command.id, true) }
            )
            SmallIconButton(
                icon = Icons.Filled.KeyboardArrowDown,
                contentDescription = "아래로",
                enabled = !isLast,
                onClick = { onMove(command.id, false) }
            )
            SmallIconButton(
                icon = Icons.Outlined.Edit,
                contentDescription = "수정",
                enabled = true,
                onClick = onOpenEdit
            )
            SmallIconButton(
                icon = Icons.Outlined.Delete,
                contentDescription = "삭제",
                enabled = true,
                onClick = onDelete
            )
        }
    }
}

/** Compact 32.dp icon button; the description is read out by TalkBack. */
@Composable
private fun SmallIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(32.dp)) {
        Icon(imageVector = icon, contentDescription = contentDescription, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun CommandDialog(
    initial: PromptCommand?,
    onDismiss: () -> Unit,
    onSave: (label: String, prompt: String) -> Unit
) {
    var label by rememberSaveable { mutableStateOf(initial?.label ?: "") }
    var prompt by rememberSaveable { mutableStateOf(initial?.prompt ?: "") }

    val canSave = label.isNotBlank() && prompt.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "명령어 추가" else "명령어 수정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { next ->
                        // Enforce max length by dropping over-long input instead of truncating mid-edit.
                        if (next.length <= 20) label = next
                    },
                    label = { Text("버튼 이름") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("요청 내용") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(label.trim(), prompt.trim()) },
                enabled = canSave
            ) { Text("저장") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

/** Toggle for letting the assistant fetch link contents included in user messages. */
@Composable
fun LinkSection(
    autoReadLinks: Boolean,
    onToggle: (Boolean) -> Unit,
    webSearch: Boolean,
    searchEngine: String,
    onToggleSearch: (Boolean) -> Unit
) {
    Text("링크 읽기 · 웹 검색", style = MaterialTheme.typography.titleMedium)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("메시지 속 링크 자동으로 읽기", style = MaterialTheme.typography.bodyMedium)
            Text(
                "보낸 메시지에 웹 주소가 있으면 페이지 본문을 가져와 함께 보냅니다 (최대 3개).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = autoReadLinks, onCheckedChange = onToggle)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("웹 검색 ($searchEngine)", style = MaterialTheme.typography.bodyMedium)
            Text(
                "최신 정보가 필요한 질문이면 AI가 검색해서 출처와 함께 답합니다. " +
                    "검색한 질문은 토큰이 수천~2만 개 더 듭니다. 예약 작업에도 적용됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = webSearch, onCheckedChange = onToggleSearch)
    }
}

/** Recent error log: view, copy, share, or clear. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ErrorLogSection(
    entryCount: Int,
    lastEntryTime: String?,
    readLog: () -> String,
    onClear: () -> Unit,
    onShare: (String) -> Unit
) {
    var showLog by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val hasEntries = entryCount > 0

    Text("오류 기록", style = MaterialTheme.typography.titleMedium)
    Text(
        text = if (entryCount == 0) {
            "기록된 오류가 없습니다."
        } else {
            "오류 ${entryCount}건 · 마지막 ${lastEntryTime ?: "-"}"
        },
        style = MaterialTheme.typography.bodyMedium
    )
    Text(
        "문제가 생기면 기록을 복사해 개발자에게 보내 주세요. API 키는 기록되지 않습니다.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // Wraps on narrow phones instead of pushing "지우기" off screen.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        OutlinedButton(onClick = { showLog = true }, enabled = hasEntries) { Text("보기") }
        OutlinedButton(
            onClick = {
                // Wrap launch so the lambda returns Unit, matching the () -> Unit contract.
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("DSK 오류 기록", readLog())))
                }
                Toast.makeText(context, "복사했습니다.", Toast.LENGTH_SHORT).show()
            },
            enabled = hasEntries
        ) { Text("복사") }
        OutlinedButton(onClick = { onShare(readLog()) }, enabled = hasEntries) { Text("보내기") }
        TextButton(onClick = { confirmClear = true }, enabled = hasEntries) { Text("지우기") }
    }

    if (showLog) {
        // Snapshot the text once when the dialog opens so it reflects the moment the user asked.
        val log = remember { readLog() }
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text("오류 기록") },
            text = {
                SelectionContainer {
                    Text(
                        text = log,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLog = false }) { Text("닫기") }
            }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("오류 기록 지우기") },
            text = { Text("오류 기록을 모두 지울까요?") },
            confirmButton = {
                TextButton(onClick = {
                    onClear()
                    confirmClear = false
                }) { Text("지우기") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("취소") }
            }
        )
    }
}

/** Phone agent ("스마트폰 유즈") switches: accessibility status, level, steps, screenshots, allow-list. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentSection(
    level: AgentLevel,
    maxSteps: Int,
    screenshots: Boolean,
    allowedText: String,
    blockedText: String,
    onLevel: (AgentLevel) -> Unit,
    onMaxSteps: (Int) -> Unit,
    onScreenshots: (Boolean) -> Unit,
    onSaveAllowed: (String) -> Unit,
    onSaveBlocked: (String) -> Unit
) {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val enabled = remember(refresh) { AgentBridge.isEnabled }
    var steps by remember(maxSteps) { mutableStateOf(maxSteps.toFloat()) }
    var allowed by rememberSaveable(allowedText) { mutableStateOf(allowedText) }
    var blocked by rememberSaveable(blockedText) { mutableStateOf(blockedText) }

    Text("폰 조작 (스마트폰 유즈)", style = MaterialTheme.typography.titleMedium)
    Text(
        text = if (enabled) "켜짐 · 채팅의 + 메뉴 > 폰 조작 모드로 요청하세요." else "꺼짐 · 접근성 설정에서 'DSK 스마트 폰 조작'을 켜야 합니다.",
        style = MaterialTheme.typography.bodyMedium,
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    )
    Text(
        "보내기·결제·삭제 같은 동작은 실행 전에 확인을 받고, 은행·결제 앱과 비밀번호 칸은 조작하지 않습니다. " +
            "화면 위 '중지' 버튼으로 언제든 멈출 수 있어요. 접근성 버튼을 누르면 지금 화면을 요약합니다.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { runCatching { context.startActivity(AgentBridge.accessibilitySettingsIntent()) } }) {
            Text("접근성 설정 열기")
        }
        TextButton(onClick = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                )
            }
        }) { Text("앱 정보 (제한된 설정 허용)") }
    }
    Text(
        "Android 13 이상에서 접근성을 켤 수 없다고 나오면: 앱 정보 > 오른쪽 위 ⋮ > '제한된 설정 허용'을 누른 뒤 다시 켜 주세요.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Text("허용 수준", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AgentLevel.entries.forEach { option ->
            FilterChip(selected = level == option, onClick = { onLevel(option) }, label = { Text(option.label) })
        }
    }
    Text(
        when (level) {
            AgentLevel.READ -> "화면을 읽고 설명만 합니다."
            AgentLevel.NAVIGATE -> "앱 열기·스크롤·뒤로 가기까지 합니다. 누르거나 입력하지 않습니다."
            AgentLevel.CONTROL -> "누르기·입력까지 합니다. 되돌릴 수 없는 동작은 확인을 받습니다."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Text("최대 단계 ${steps.roundToInt()}번", style = MaterialTheme.typography.bodySmall)
    Slider(
        value = steps,
        onValueChange = { steps = it },
        onValueChangeFinished = { onMaxSteps(steps.roundToInt()) },
        valueRange = 5f..40f,
        steps = 6
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("그림뿐인 화면은 스크린샷도 보내기", style = MaterialTheme.typography.bodyMedium)
            Text(
                "지도·게임처럼 글자가 거의 없는 화면에서만 사용 (이미지를 읽는 모델 필요, Android 11+)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = screenshots, onCheckedChange = onScreenshots)
    }

    OutlinedTextField(
        value = allowed,
        onValueChange = { allowed = it },
        label = { Text("예외로 허용할 앱 패키지 (줄마다 하나)") },
        placeholder = { Text("com.example.app") },
        minLines = 2,
        maxLines = 4,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedButton(onClick = { onSaveAllowed(allowed) }) { Text("허용 목록 저장") }

    OutlinedTextField(
        value = blocked,
        onValueChange = { blocked = it },
        label = { Text("항상 차단할 앱 패키지 (줄마다 하나)") },
        placeholder = { Text("com.example.app") },
        supportingText = {
            Text("기본 차단(은행·결제·인증 키워드)에 더해 이 앱들은 절대 조작하지 않습니다.")
        },
        minLines = 2,
        maxLines = 4,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedButton(onClick = { onSaveBlocked(blocked) }) { Text("차단 목록 저장") }
}
