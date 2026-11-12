package com.dskapp.ui.settings

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dskapp.data.settings.SettingsRepository
import com.dskapp.ui.components.MODEL_LIST_BOX_HEIGHT
import com.dskapp.ui.components.ScrollableModelList
import com.dskapp.ui.components.modelLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Settings: endpoint, custom instructions, appearance, usage/cost, backup and the security
 * note. All sections live on one scrollable page because they are short and rarely revisited.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenSchedule: () -> Unit = {},
    onOpenRemoteSessions: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val commands by viewModel.commands.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Wi-Fi / Tailscale can change while the app was in the background, so re-read them
    // each time this screen opens (the ViewModel only reads once, at creation).
    LaunchedEffect(Unit) { runCatching { viewModel.refreshNetwork() } }

    // Results (save, backup, prices...) appear as a snackbar so they are visible from
    // whichever section of this long page the user is in.
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        // Null means the user backed out of the picker; do nothing.
        if (uri != null) viewModel.exportBackup(uri)
    }
    val openBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importBackup(uri)
    }

    // Timestamp computed at launch time so repeated taps produce distinct file names.
    val exportName = remember {
        {
            "DSK_백업_" +
                SimpleDateFormat("yyyyMMdd_HHmm", Locale.KOREA).format(Date()) + ".zip"
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("설정") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(text = "AI 연결", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "OpenAI 호환 서버라면 어디든 사용할 수 있습니다. 기본값은 River AI입니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = viewModel::onBaseUrlChange,
                label = { Text("서버 주소 (Base URL)") },
                placeholder = { Text("https://api.river.ai/v1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = state.apiKey,
                onValueChange = viewModel::onApiKeyChange,
                label = { Text("API 키") },
                placeholder = {
                    Text(if (state.hasStoredKey) "저장된 키 사용 중 (변경 시 입력)" else "rv_...")
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )

            // Directly under the connection fields: the full model list in its own scroll box.
            // The box has a fixed height, so it scrolls inside this page instead of growing it.
            ModelPicker(
                models = state.availableModels,
                selected = state.model,
                onSelect = viewModel::onModelChange,
                onLoadModels = viewModel::loadModels,
                isLoading = state.isLoadingModels,
                unavailable = state.unavailableModels
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = viewModel::loadModels,
                    enabled = !state.isLoadingModels,
                    modifier = Modifier.weight(1f)
                ) {
                    if (state.isLoadingModels) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("모델 목록")
                    }
                }
                OutlinedButton(
                    onClick = viewModel::testConnection,
                    enabled = !state.isTesting,
                    modifier = Modifier.weight(1f)
                ) {
                    if (state.isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("연결 테스트")
                    }
                }
            }

            Button(
                onClick = viewModel::save,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("저장")
            }

            // Free-text entry for a model id that the server does not list.
            OutlinedTextField(
                value = state.model,
                onValueChange = viewModel::onModelChange,
                label = { Text("모델 (직접 입력)") },
                placeholder = { Text("deepseek-ai/DeepSeek-V4.1-Flash") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(text = "맞춤 지시문", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "모든 대화에 항상 적용할 요청입니다. 예: 항상 존댓말로, 코드는 Kotlin으로.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = state.customInstructions,
                onValueChange = viewModel::onCustomInstructionsChange,
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(onClick = viewModel::saveCustomInstructions) {
                Text("지시문 저장")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            CommandsSection(
                commands = commands,
                onAdd = viewModel::addCommand,
                onUpdate = viewModel::updateCommand,
                onDelete = viewModel::deleteCommand,
                onMove = viewModel::moveCommand,
                onReset = viewModel::resetCommands
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            LinkSection(
                autoReadLinks = state.autoReadLinks,
                onToggle = viewModel::setAutoReadLinks,
                webSearch = state.webSearch,
                searchEngine = state.searchEngine,
                onToggleSearch = viewModel::setWebSearch
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(text = "예약 작업", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "매일 정한 시각(한국 시간)에 AI에게 요청하고 결과를 알림으로 받아요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = onOpenSchedule) { Text("예약 작업 관리") }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            RemoteSection(
                url = state.remoteUrl,
                secret = state.remoteSecret,
                cwd = state.remoteCwd,
                mode = state.remoteMode,
                hasSecret = state.hasRemoteSecret,
                isTesting = state.isRemoteTesting,
                isDiscovering = state.isDiscovering,
                knownPcs = state.knownPcs,
                networkSummary = state.networkSummary,
                status = state.remoteStatus,
                onUrl = viewModel::onRemoteUrlChange,
                onSecret = viewModel::onRemoteSecretChange,
                onCwd = viewModel::onRemoteCwdChange,
                onMode = viewModel::onRemoteModeChange,
                onSave = viewModel::saveRemoteSettings,
                onTest = viewModel::testRemote,
                onAutoConnect = viewModel::autoConnect,
                onUseKnownPc = viewModel::useKnownPc,
                onForgetKnownPc = viewModel::forgetKnownPc,
                onOpenSessions = onOpenRemoteSessions,
                onScanQr = { viewModel.onQrScannerVisibilityChange(true) }
            )

            if (state.isScanningQr) {
                QrScanner(
                    onResult = viewModel::applyPcQr,
                    onCancel = { viewModel.onQrScannerVisibilityChange(false) }
                )
            }

            state.qrMessage?.let { message ->
                AlertDialog(
                    onDismissRequest = viewModel::dismissQrMessage,
                    confirmButton = {
                        TextButton(onClick = viewModel::dismissQrMessage) { Text("확인") }
                    },
                    title = { Text("PC 와 연결하지 못했습니다") },
                    text = { Text(message) }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            AgentSection(
                level = state.agentLevel,
                maxSteps = state.agentMaxSteps,
                screenshots = state.agentScreenshots,
                allowedText = state.agentAllowedText,
                blockedText = state.agentBlockedText,
                onLevel = viewModel::setAgentLevel,
                onMaxSteps = viewModel::setAgentMaxSteps,
                onScreenshots = viewModel::setAgentScreenshots,
                onSaveAllowed = viewModel::saveAgentAllowed,
                onSaveBlocked = viewModel::saveAgentBlocked
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(text = "앱 잠금", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("열 때 지문·얼굴·화면 잠금으로 확인", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "1분 넘게 다른 앱에 있다가 돌아오면 다시 확인합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = state.appLock, onCheckedChange = viewModel::setAppLock)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(text = "화면", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "테마",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.themeMode == SettingsRepository.ThemeMode.SYSTEM,
                    onClick = { viewModel.setThemeMode(SettingsRepository.ThemeMode.SYSTEM) },
                    label = { Text("시스템") }
                )
                FilterChip(
                    selected = state.themeMode == SettingsRepository.ThemeMode.LIGHT,
                    onClick = { viewModel.setThemeMode(SettingsRepository.ThemeMode.LIGHT) },
                    label = { Text("밝게") }
                )
                FilterChip(
                    selected = state.themeMode == SettingsRepository.ThemeMode.DARK,
                    onClick = { viewModel.setThemeMode(SettingsRepository.ThemeMode.DARK) },
                    label = { Text("어둡게") }
                )
            }
            Text(
                text = "글자 크기 ${(state.fontScale * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = state.fontScale,
                onValueChange = viewModel::onFontScaleChange,
                onValueChangeFinished = viewModel::saveFontScale,
                valueRange = SettingsRepository.MIN_FONT_SCALE..SettingsRepository.MAX_FONT_SCALE
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(text = "사용량", style = MaterialTheme.typography.titleMedium)
            val usage = state.usage
            if (usage == null) {
                Text(
                    text = "아직 사용 기록이 없습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "이번 달 (${usage.month})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "입력 ${"%,d".format(usage.promptTokens)} 토큰 · " +
                        "출력 ${"%,d".format(usage.completionTokens)} 토큰",
                    style = MaterialTheme.typography.bodySmall
                )
                val cost = state.estimatedCostUsd
                if (cost != null) {
                    Text(
                        text = "예상 비용 약 $${"%.4f".format(cost)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        text = "단가를 입력하면 예상 비용을 보여 줍니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.priceInput,
                    onValueChange = viewModel::onPriceInputChange,
                    label = { Text("입력 단가 (USD/100만)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = state.priceOutput,
                    onValueChange = viewModel::onPriceOutputChange,
                    label = { Text("출력 단가 (USD/100만)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
            }
            OutlinedButton(onClick = viewModel::savePrices) {
                Text("단가 저장")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(text = "백업", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "대화와 첨부 파일을 .zip 하나로 저장합니다. 불러오면 기존 대화는 " +
                    "그대로 두고 추가됩니다. API 키는 포함되지 않습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { createBackup.launch(exportName()) },
                    enabled = !state.isBackupRunning
                ) {
                    Text("대화 백업")
                }
                OutlinedButton(
                    onClick = {
                        openBackup.launch(
                            arrayOf(
                                "application/zip",
                                "application/x-zip-compressed",
                                "application/octet-stream"
                            )
                        )
                    },
                    enabled = !state.isBackupRunning
                ) {
                    Text("백업 불러오기")
                }
                if (state.isBackupRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            ErrorLogSection(
                entryCount = state.errorCount,
                lastEntryTime = state.lastErrorTime,
                readLog = viewModel::readErrorLog,
                onClear = viewModel::clearErrorLog,
                onShare = { log -> shareText(context, "DSK 스마트 오류 기록", log) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(text = "보안 안내", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "API 키는 이 기기의 암호화 저장소(Android Keystore)에만 보관되며 " +
                    "클라우드 백업에 포함되지 않습니다. 앱은 키를 AI 서버 외의 곳으로 전송하지 않습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 640.dp)
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Hands plain text to the share sheet (mail, messenger, notes...). */
private fun shareText(context: Context, subject: String, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, subject)) }
}

/**
 * "사용 가능한 모델": every model the server listed, inside a fixed-height box that scrolls
 * on its own. An OpenAI-compatible endpoint can return 100+ ids, so the list must never be
 * cut off (the old version showed the first 12 and dropped the rest) and must not push the
 * rest of the settings page away. The surrounding screen scrolls; this box scrolls inside it.
 */
@Composable
private fun ModelPicker(
    models: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onLoadModels: () -> Unit,
    isLoading: Boolean,
    unavailable: Set<String> = emptySet()
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "사용 가능한 모델",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (models.isNotEmpty()) {
            Text(
                text = "${models.size}개 · 스크롤",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (models.isEmpty()) {
        Text(
            text = "위의 '모델 목록'을 누르면 서버에서 모델 목록을 가져옵니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = onLoadModels, enabled = !isLoading) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Text("모델 목록 불러오기")
            }
        }
        return
    }

    ScrollableModelList(
        models = models,
        selected = selected,
        onSelect = { model -> model?.let(onSelect) },
        height = MODEL_LIST_BOX_HEIGHT,
        labelFor = { modelLabel(it, it, unavailable) }
    )
}
