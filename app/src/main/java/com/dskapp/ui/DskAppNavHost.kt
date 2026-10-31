package com.dskapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dskapp.di.ServiceLocator
import com.dskapp.ui.chat.ChatScreen
import com.dskapp.ui.chat.ChatScaffold
import com.dskapp.ui.chat.ChatViewModel
import com.dskapp.ui.chat.ConversationDrawer
import com.dskapp.ui.chat.ConversationListViewModel
import com.dskapp.ui.chat.SearchScreen
import com.dskapp.ui.chat.SearchViewModel
import com.dskapp.ui.settings.SettingsScreen
import com.dskapp.ui.settings.SettingsViewModel
import kotlinx.serialization.Serializable

@Serializable
private data object ChatRoute

@Serializable
private data object SettingsRoute

@Serializable
private data object SearchRoute

/**
 * App shell.
 *
 * A single chat destination keeps the selected conversation in local state, so switching
 * threads does not pile up back-stack entries. Window width decides the presentation:
 * permanent history pane on tablets (>= 840dp), modal drawer on phones - the same
 * responsive split ChatGPT uses.
 */
@Composable
fun DskAppNavHost() {
    val navController = rememberNavController()
    val context = LocalContext.current

    val chatRepository = ServiceLocator.chats(context)
    val settingsRepository = ServiceLocator.settings(context)
    val aiClient = ServiceLocator.aiClient(context)

    val listViewModel: ConversationListViewModel = viewModel(
        factory = ConversationListViewModel.factory(chatRepository)
    )
    val conversations by listViewModel.conversations.collectAsStateWithLifecycle()

    var selectedId by rememberSaveable { mutableLongStateOf(0L) }

    // Follow the newest thread until the user picks one explicitly.
    LaunchedEffect(conversations) {
        if (selectedId == 0L || conversations.none { it.id == selectedId }) {
            conversations.firstOrNull()?.let { selectedId = it.id }
        }
    }

    NavHost(navController = navController, startDestination = ChatRoute) {
        composable<ChatRoute> {
            if (selectedId == 0L) {
                PreparingScreen()
                return@composable
            }

            val chatViewModel: ChatViewModel = viewModel(
                key = "chat-$selectedId",
                factory = ChatViewModel.factory(
                    context = context,
                    conversationId = selectedId,
                    chatRepository = chatRepository,
                    settingsRepository = settingsRepository,
                    aiClient = aiClient
                )
            )

            val newChat: () -> Unit = {
                listViewModel.newConversation { id -> selectedId = id }
            }
            val openSettings: () -> Unit = { navController.navigate(SettingsRoute) }
            val openSearch: () -> Unit = { navController.navigate(SearchRoute) }

            val isTablet = LocalConfiguration.current.screenWidthDp >= TABLET_MIN_WIDTH_DP

            if (isTablet) {
                Row(modifier = Modifier.fillMaxSize()) {
                    ConversationDrawer(
                        conversations = conversations,
                        selectedId = selectedId,
                        onSelect = { selectedId = it },
                        onNewChat = newChat,
                        onDelete = listViewModel::delete,
                        onTogglePin = listViewModel::togglePin,
                        onOpenSettings = openSettings,
                        onOpenSearch = openSearch,
                        modifier = Modifier.width(300.dp)
                    )
                    TabletChatPane(
                        viewModel = chatViewModel,
                        onNewChat = newChat,
                        onOpenSearch = openSearch
                    )
                }
            } else {
                ChatScreen(
                    viewModel = chatViewModel,
                    conversations = conversations,
                    currentConversationId = selectedId,
                    onSelectConversation = { selectedId = it },
                    onNewChat = newChat,
                    onDeleteConversation = listViewModel::delete,
                    onTogglePin = listViewModel::togglePin,
                    onOpenSettings = openSettings,
                    onOpenSearch = openSearch
                )
            }
        }

        composable<SettingsRoute> {
            SettingsScreen(
                viewModel = viewModel(
                    factory = SettingsViewModel.factory(settingsRepository, aiClient)
                ),
                onBack = { navController.popBackStack() }
            )
        }

        composable<SearchRoute> {
            SearchScreen(
                viewModel = viewModel(factory = SearchViewModel.factory(chatRepository)),
                onOpenConversation = { id ->
                    selectedId = id
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() }
            )
        }
    }
}

/** Tablet right pane: no drawer button, history is always visible on the left. */
@Composable
private fun TabletChatPane(
    viewModel: ChatViewModel,
    onNewChat: () -> Unit,
    onOpenSearch: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    ChatScaffold(
        state = state,
        viewModel = viewModel,
        snackbarHostState = snackbarHostState,
        onOpenDrawer = null,
        onNewChat = onNewChat,
        onAttachmentClick = {},
        onPickImage = {},
        onPickCamera = {},
        onPickFile = {},
        isListening = false,
        onToggleVoice = {},
        onOpenSearch = onOpenSearch,
        modifier = Modifier.fillMaxSize()
    )
}

/** Shown for the instant before the first conversation row exists. */
@Composable
private fun PreparingScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "대화를 준비하는 중…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** Window width at which the history becomes a permanent pane (Material 3 breakpoint). */
private const val TABLET_MIN_WIDTH_DP = 840

