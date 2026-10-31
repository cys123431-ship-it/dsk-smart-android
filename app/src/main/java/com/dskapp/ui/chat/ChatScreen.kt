package com.dskapp.ui.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dskapp.data.chat.Attachment
import com.dskapp.data.chat.ConversationEntity
import com.dskapp.data.export.ExportFormat
import com.dskapp.data.export.ExportService
import com.dskapp.ui.components.ChatComposer
import com.dskapp.ui.voice.VoiceInputController
import kotlinx.coroutines.launch
import java.io.File

/**
 * Full chat screen.
 *
 * Mirrors ChatGPT: a slim top bar with drawer + new-chat + export actions, the message list,
 * and the composer pinned above the keyboard. History is a modal drawer on phones and a
 * fixed left pane when [permanentDrawer] is true (wide screens such as an unfolded phone);
 * pickers, camera and voice input are shared by both layouts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    conversations: List<ConversationEntity>,
    currentConversationId: Long,
    onSelectConversation: (Long) -> Unit,
    onNewChat: () -> Unit,
    onDeleteConversation: (Long) -> Unit,
    onTogglePin: (ConversationEntity) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    permanentDrawer: Boolean = false
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    var cameraCapture by remember { mutableStateOf<CameraCapture?>(null) }
    var isListening by remember { mutableStateOf(false) }

    // Speech recognizer is created lazily and released with the screen.
    val voiceController = remember {
        VoiceInputController(
            context = context,
            onPartial = viewModel::onInputChange,
            onFinal = viewModel::onInputChange,
            onError = { message ->
                isListening = false
                scope.launch { snackbarHostState.showSnackbar(message) }
            },
            onStateChanged = { isListening = it }
        )
    }

    DisposableEffect(Unit) {
        onDispose { voiceController.release() }
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceController.start()
        } else {
            scope.launch { snackbarHostState.showSnackbar("음성 입력에는 마이크 권한이 필요합니다.") }
        }
    }

    val toggleVoice: () -> Unit = {
        if (isListening) {
            voiceController.stop()
        } else {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (granted) {
                voiceController.start()
            } else {
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    LaunchedEffect(state.banner) {
        state.banner?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeBanner()
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(viewModel::attachImage)
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::attachTextFile)
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        cameraCapture?.let { capture ->
            // The full-size original is deleted after import (or right away when cancelled).
            if (success) viewModel.attachImage(capture.uri, tempFile = capture.file) else capture.file.delete()
        }
        cameraCapture = null
    }

    val chat: @Composable (onOpenDrawer: (() -> Unit)?, modifier: Modifier) -> Unit = { onOpenDrawer, modifier ->
        ChatScaffold(
            state = state,
            viewModel = viewModel,
            snackbarHostState = snackbarHostState,
            onOpenDrawer = onOpenDrawer,
            onNewChat = onNewChat,
            onAttachmentClick = { attachment -> openAttachment(context, attachment) },
            onPickImage = { imagePicker.launch("image/*") },
            onPickFile = {
                filePicker.launch(arrayOf("text/*", "application/json", "application/xml"))
            },
            onPickCamera = {
                val capture = createCameraCapture(context)
                if (capture != null) {
                    cameraCapture = capture
                    cameraLauncher.launch(capture.uri)
                } else {
                    scope.launch { snackbarHostState.showSnackbar("카메라를 사용할 수 없습니다.") }
                }
            },
            isListening = isListening,
            onToggleVoice = toggleVoice,
            onOpenSearch = onOpenSearch,
            modifier = modifier
        )
    }

    if (permanentDrawer) {
        Row(modifier = Modifier.fillMaxSize()) {
            ConversationDrawer(
                conversations = conversations,
                selectedId = currentConversationId,
                onSelect = onSelectConversation,
                onNewChat = onNewChat,
                onDelete = onDeleteConversation,
                onTogglePin = onTogglePin,
                onOpenSettings = onOpenSettings,
                onOpenSearch = onOpenSearch,
                modifier = Modifier.width(300.dp)
            )
            chat(null, Modifier.weight(1f).fillMaxHeight())
        }
        return
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                ConversationDrawer(
                    conversations = conversations,
                    selectedId = currentConversationId,
                    onSelect = {
                        onSelectConversation(it)
                        scope.launch { drawerState.close() }
                    },
                    onNewChat = {
                        onNewChat()
                        scope.launch { drawerState.close() }
                    },
                    onDelete = onDeleteConversation,
                    onTogglePin = onTogglePin,
                    onOpenSettings = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    },
                    onOpenSearch = {
                        scope.launch { drawerState.close() }
                        onOpenSearch()
                    }
                )
            }
        }
    ) {
        chat({ scope.launch { drawerState.open() } }, Modifier)
    }
}

/** A camera target: the cache file and the content URI handed to the camera app. */
private class CameraCapture(val file: File, val uri: Uri)

/** Creates a cache file + content URI for the camera intent. */
private fun createCameraCapture(context: Context): CameraCapture? = runCatching {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    file.createNewFile()
    CameraCapture(file, FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
}.getOrNull()

/**
 * Opens a saved attachment in whatever app handles it. The file lives in app-private
 * storage, so it is exposed through the same FileProvider used for exports.
 */
private fun openAttachment(context: Context, attachment: Attachment) {
    val file = File(attachment.localPath)
    if (!file.exists()) return

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, attachment.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

/** Bar + list + composer, shared by the phone and tablet layouts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatScaffold(
    state: ChatUiState,
    viewModel: ChatViewModel,
    snackbarHostState: SnackbarHostState,
    onOpenDrawer: (() -> Unit)?,
    onNewChat: () -> Unit,
    onAttachmentClick: (Attachment) -> Unit,
    onPickImage: () -> Unit,
    onPickCamera: () -> Unit,
    onPickFile: () -> Unit,
    onOpenSearch: () -> Unit,
    isListening: Boolean = false,
    onToggleVoice: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportService = remember { ExportService(context) }
    var showExportSheet by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.title.ifBlank { "새 대화" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (onOpenDrawer != null) {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(
                                Icons.AutoMirrored.Filled.MenuOpen,
                                contentDescription = "대화 목록"
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "검색")
                    }
                    IconButton(onClick = onNewChat) {
                        Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = "새 채팅")
                    }
                    IconButton(
                        onClick = { showExportSheet = true },
                        enabled = state.messages.any { it.isExportable }
                    ) {
                        Icon(Icons.Filled.IosShare, contentDescription = "내보내기")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            ChatComposer(
                value = state.input,
                onValueChange = viewModel::onInputChange,
                onSend = viewModel::send,
                onStop = viewModel::stopGenerating,
                isSending = state.isSending,
                pendingAttachments = state.pendingAttachments,
                onRemoveAttachment = viewModel::removePendingAttachment,
                onPickImage = onPickImage,
                onPickCamera = onPickCamera,
                onPickFile = onPickFile,
                isImporting = state.isImporting,
                canSend = state.canSend,
                isListening = isListening,
                onToggleVoice = onToggleVoice
            )
        }
    ) { padding ->
        ChatContent(
            state = state,
            onSuggestionClick = viewModel::onSuggestionClick,
            onAttachmentClick = onAttachmentClick,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }

    val lastAssistant = state.messages.lastOrNull { it.isExportable }

    if (showExportSheet && lastAssistant != null) {
        ExportSheet(
            onDismiss = { showExportSheet = false },
            onExport = { format ->
                showExportSheet = false
                scope.launch {
                    val result = exportService.export(
                        markdown = lastAssistant.content,
                        title = state.title.ifBlank { "DSK 문서" },
                        format = format
                    )
                    result.fold(
                        onSuccess = { exported ->
                            snackbarHostState.showSnackbar("${exported.file.name} 생성 완료")
                            runCatching { exportService.share(exported) }
                        },
                        onFailure = {
                            snackbarHostState.showSnackbar("내보내기 실패: ${it.message}")
                        }
                    )
                }
            }
        )
    }
}

