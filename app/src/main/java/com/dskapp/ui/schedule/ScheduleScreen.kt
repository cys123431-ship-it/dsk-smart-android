package com.dskapp.ui.schedule

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dskapp.data.schedule.ScheduleCalc
import com.dskapp.data.schedule.ScheduleNotifier
import com.dskapp.data.schedule.ScheduledTask
import com.dskapp.ui.components.ModelMenuItems
import com.dskapp.ui.components.modelLabel
import kotlinx.coroutines.launch
import java.util.Locale

/** ISO day-of-week (1=월 .. 7=일) to the single Hangul character shown on chips. */
private val DAY_LABELS = listOf("월", "화", "수", "목", "금", "토", "일")

/** Cap for the model menu; the server may list 100+ models that must stay scrollable. */
private val MODEL_MENU_MAX_HEIGHT = 300.dp

private fun dayLabel(isoDay: Int): String = DAY_LABELS[isoDay - 1]

/**
 * Lists scheduled tasks and lets the user create/edit them.
 *
 * Permission handling lives here rather than in the ViewModel because both
 * requesting and reporting the outcome are pure UI concerns.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    viewModel: ScheduleViewModel,
    onBack: () -> Unit,
    onOpenConversation: (Long) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val models by viewModel.knownModels.collectAsStateWithLifecycle()
    val unavailableModels by viewModel.unavailableModels.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var editorTarget by remember { mutableStateOf<ScheduledTask?>(null) }
    var editorVisible by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ScheduledTask?>(null) }

    // Set when the user declines notifications, so the snackbar explains the fallback.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    "알림이 꺼져 있어 결과를 알림으로 받을 수 없어요. 결과는 '⏰' 대화에 저장됩니다.",
                )
            }
        }
    }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.consumeMessage()
    }

    /** Saves through the ViewModel, then prompts for notifications on first use. */
    fun persist(task: ScheduledTask) {
        viewModel.save(task)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !ScheduleNotifier.canNotify(context)) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("예약 작업") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("추가") },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = {
                    editorTarget = null
                    editorVisible = true
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "helper") {
                ScheduleHelperCard(
                    needsExactAlarm = viewModel.needsExactAlarm,
                    onOpenBatterySettings = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                        Unit
                    },
                    onRequestExactAlarm = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                        Uri.parse("package:" + context.packageName),
                                    ),
                                )
                            }
                        }
                        Unit
                    },
                )
            }
            if (tasks.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = "아직 예약 작업이 없어요. 예: 매일 아침 8시에 '오늘의 영어 표현 3개 알려줘'",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(tasks, key = { it.id }) { task ->
                    TaskCard(
                        task = task,
                        nextRun = viewModel.nextRunText(task),
                        onToggle = { enabled -> viewModel.setEnabled(task.id, enabled) },
                        onRunNow = { viewModel.runNow(task.id) },
                        onEdit = {
                            editorTarget = task
                            editorVisible = true
                        },
                        onOpenResult = { task.conversationId?.let(onOpenConversation) },
                        onDelete = { deleteTarget = task },
                    )
                }
            }
        }
    }

    if (editorVisible) {
        TaskEditorDialog(
            initial = editorTarget,
            models = models,
            unavailableModels = unavailableModels,
            defaultModel = viewModel.defaultModel,
            onDismiss = { editorVisible = false },
            onSave = { task ->
                editorVisible = false
                persist(task)
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("예약 작업 삭제") },
            text = { Text("‘${target.name}’ 예약 작업을 지울까요?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target.id)
                    deleteTarget = null
                }) { Text("지우기") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("취소") }
            },
        )
    }
}

/** Explains the scheduling behavior and offers shortcuts into the relevant system settings. */
@Composable
private fun ScheduleHelperCard(
    needsExactAlarm: Boolean,
    onOpenBatterySettings: () -> Unit,
    onRequestExactAlarm: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "정한 시각(한국 시간)에 AI에게 요청을 보내고 결과를 알림으로 받아요. 인터넷이 꺼져 있으면 연결되면 다시 시도합니다.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "삼성 폰은 'DSK 스마트'를 '제한 없음'으로 두면 시간이 정확해요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpenBatterySettings) { Text("배터리 설정 열기") }
                if (needsExactAlarm) {
                    TextButton(onClick = onRequestExactAlarm) { Text("정확한 알람 허용") }
                }
            }
        }
    }
}

/** One task summary with inline actions; kept private to avoid leaking list layout outward. */
@Composable
private fun TaskCard(
    task: ScheduledTask,
    nextRun: String,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onEdit: () -> Unit,
    onOpenResult: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = task.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Switch(checked = task.enabled, onCheckedChange = onToggle)
            }
            Text(
                text = ScheduleCalc.describe(task) + " · " + nextRun,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = task.prompt,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val lastError = task.lastError
            if (lastError != null) {
                Text(
                    text = "마지막 실행 실패: $lastError",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (task.lastRunAt != null) {
                Text(
                    text = "마지막 실행 성공",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRunNow) { Text("지금 실행") }
                TextButton(onClick = onEdit) { Text("수정") }
                if (task.conversationId != null) {
                    TextButton(onClick = onOpenResult) { Text("결과 보기") }
                }
                TextButton(onClick = onDelete) { Text("삭제") }
            }
        }
    }
}

/**
 * Editor for both new and existing tasks. A null [initial] means creation, which is
 * signalled to the ViewModel by passing an empty id.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TaskEditorDialog(
    initial: ScheduledTask?,
    models: List<String>,
    unavailableModels: Set<String>,
    defaultModel: String,
    onDismiss: () -> Unit,
    onSave: (ScheduledTask) -> Unit,
) {
    val startHour = initial?.hour ?: 8
    val startMinute = initial?.minute ?: 0

    var name by remember { mutableStateOf(initial?.name ?: "") }
    var prompt by remember { mutableStateOf(initial?.prompt ?: "") }
    var hour by remember { mutableStateOf(startHour) }
    var minute by remember { mutableStateOf(startMinute) }
    var days by remember {
        mutableStateOf(initial?.days ?: ScheduledTask.ALL_DAYS)
    }
    var model by remember { mutableStateOf(initial?.model) }
    var usePhone by remember { mutableStateOf(initial?.usePhoneAgent ?: false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var modelMenuOpen by remember { mutableStateOf(false) }

    val canSave = name.isNotBlank() && prompt.isNotBlank() && days.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "예약 작업 추가" else "예약 작업 수정") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("이름") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("AI에게 보낼 요청") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "링크를 넣으면 그 페이지를 읽고 답해요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "시각", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.size(12.dp))
                    OutlinedButton(onClick = { showTimePicker = true }) {
                        Text(String.format(Locale.KOREA, "%02d:%02d", hour, minute))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(text = "요일", style = MaterialTheme.typography.bodyMedium)
                // Seven chips do not fit one dialog line on a phone; let them wrap.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..7).forEach { iso ->
                        FilterChip(
                            selected = iso in days,
                            onClick = {
                                days = if (iso in days) days - iso else days + iso
                            },
                            label = { Text(dayLabel(iso)) },
                        )
                    }
                }
                Row {
                    TextButton(onClick = { days = ScheduledTask.ALL_DAYS }) { Text("매일") }
                    TextButton(onClick = { days = setOf(1, 2, 3, 4, 5) }) { Text("평일") }
                    TextButton(onClick = { days = setOf(6, 7) }) { Text("주말") }
                }
                Spacer(Modifier.height(8.dp))
                Column {
                    Text(text = "모델", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(
                        onClick = { modelMenuOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(model ?: ("기본 모델 (" + defaultModel.substringAfterLast('/') + ")"))
                    }
                    DropdownMenu(
                        expanded = modelMenuOpen,
                        onDismissRequest = { modelMenuOpen = false },
                        // Bounded height: 100+ models would otherwise grow the menu past the
                        // dialog. Past the cap the menu scrolls.
                        modifier = Modifier.heightIn(max = MODEL_MENU_MAX_HEIGHT),
                    ) {
                        ModelMenuItems(
                            models = models,
                            selected = model,
                            onSelect = { candidate ->
                                model = candidate
                                modelMenuOpen = false
                            },
                            defaultLabel = "기본 모델 (" + defaultModel.substringAfterLast('/') + ")",
                            // The button shows the short name, so the menu does too.
                            labelFor = { candidate ->
                                modelLabel(candidate, candidate.substringAfterLast('/'), unavailableModels)
                            },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("📱 폰 조작으로 실행", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "앱 열기·스크롤·읽기만 합니다 (누르기·입력 없음). 그 시각에 화면이 잠겨 있으면 실행하지 못해요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = usePhone, onCheckedChange = { usePhone = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val base = initial
                    val result = if (base == null) {
                        ScheduledTask(
                            id = "",
                            name = name.trim(),
                            prompt = prompt.trim(),
                            hour = hour,
                            minute = minute,
                            days = days,
                            model = model,
                            usePhoneAgent = usePhone,
                        )
                    } else {
                        base.copy(
                            name = name.trim(),
                            prompt = prompt.trim(),
                            hour = hour,
                            minute = minute,
                            days = days,
                            model = model,
                            usePhoneAgent = usePhone,
                        )
                    }
                    onSave(result)
                },
                enabled = canSave,
            ) { Text("저장") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        },
    )

    if (showTimePicker) {
        val timeState = rememberTimePickerState(
            initialHour = hour,
            initialMinute = minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("시각 선택") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TimePicker(state = timeState)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    hour = timeState.hour
                    minute = timeState.minute
                    showTimePicker = false
                }) { Text("확인") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("취소") }
            },
        )
    }
}
