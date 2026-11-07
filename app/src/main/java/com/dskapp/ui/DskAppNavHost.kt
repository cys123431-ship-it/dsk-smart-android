package com.dskapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dskapp.data.schedule.ScheduleRepository
import com.dskapp.di.ServiceLocator
import com.dskapp.ui.bookmarks.BookmarksScreen
import com.dskapp.ui.bookmarks.BookmarksViewModel
import com.dskapp.ui.schedule.ScheduleScreen
import com.dskapp.ui.schedule.ScheduleViewModel
import com.dskapp.ui.chat.ChatScreen
import com.dskapp.ui.chat.ChatViewModel
import com.dskapp.ui.chat.ConversationListViewModel
import com.dskapp.ui.chat.SearchScreen
import com.dskapp.ui.chat.SearchViewModel
import com.dskapp.ui.settings.SettingsScreen
import com.dskapp.ui.settings.SettingsViewModel
import com.dskapp.ui.remote.RemoteChatScreen
import com.dskapp.ui.remote.RemoteChatViewModel
import com.dskapp.ui.remote.RemoteSessionsScreen
import com.dskapp.ui.remote.RemoteSessionsViewModel
import androidx.navigation.toRoute
import com.dskapp.ui.chat.StartAction
import com.dskapp.ui.share.LaunchAction
import com.dskapp.ui.share.LaunchInbox
import kotlinx.serialization.Serializable

@Serializable
private data object ChatRoute

@Serializable
private data object SettingsRoute

@Serializable
private data object SearchRoute

@Serializable
private data object BookmarksRoute

@Serializable
private data object ScheduleRoute

@Serializable
private data object RemoteSessionsRoute

/** [sessionId] empty starts a new PC session; otherwise that goose chat is loaded. */
@Serializable
private data class RemoteChatRoute(val sessionId: String = "", val title: String = "")

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

    // A launch request (share, launcher shortcut) waiting for the thread created for it.
    var launchTarget by remember { mutableStateOf<Pair<Long, LaunchAction>?>(null) }

    // Follow the newest thread until the user picks one explicitly.
    LaunchedEffect(conversations) {
        // A thread just created for a launch may not be in a stale list yet; keep it selected.
        if (launchTarget?.first == selectedId) return@LaunchedEffect
        if (selectedId == 0L || conversations.none { it.id == selectedId }) {
            conversations.firstOrNull()?.let { selectedId = it.id }
        }
    }

    // One ViewModelStore for the open thread only, cleared when another thread is selected.
    // Keying viewModel() by id in the shared back-stack entry kept every thread ever opened
    // (and its DB observers) alive until the app was killed. It lives outside the NavHost so
    // a trip to Settings/Search keeps the draft, pending photos and a running answer.
    val chatOwner = remember(selectedId) { ConversationViewModelOwner() }
    DisposableEffect(chatOwner) {
        onDispose { chatOwner.viewModelStore.clear() }
    }

    // Shares and launcher shortcuts open in a fresh thread; a notification opens its thread.
    val pendingLaunch by LaunchInbox.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pendingLaunch) {
        val action = pendingLaunch ?: return@LaunchedEffect
        LaunchInbox.consume(action)
        navController.popBackStack(ChatRoute, inclusive = false)
        if (action is LaunchAction.OpenConversation) {
            selectedId = action.conversationId
        } else {
            listViewModel.newConversation { id ->
                launchTarget = id to action
                selectedId = id
            }
        }
    }

    NavHost(navController = navController, startDestination = ChatRoute) {
        composable<ChatRoute> {
            if (selectedId == 0L) {
                PreparingScreen()
                return@composable
            }

            val chatViewModel: ChatViewModel = viewModel(
                viewModelStoreOwner = chatOwner,
                factory = ChatViewModel.factory(
                    context = context,
                    conversationId = selectedId,
                    chatRepository = chatRepository,
                    settingsRepository = settingsRepository,
                    aiClient = aiClient
                )
            )

            val target = launchTarget?.takeIf { it.first == selectedId }
            val startAction = when (target?.second) {
                is LaunchAction.Camera -> StartAction.CAMERA
                is LaunchAction.Voice -> StartAction.VOICE
                else -> null
            }
            if (target != null && startAction == null) {
                LaunchedEffect(target) {
                    (target.second as? LaunchAction.Share)?.let {
                        chatViewModel.acceptShare(it.content.text, it.content.uris)
                    }
                    launchTarget = null
                }
            }

            ChatScreen(
                viewModel = chatViewModel,
                conversations = conversations,
                currentConversationId = selectedId,
                onSelectConversation = { selectedId = it },
                onNewChat = { listViewModel.newConversation { id -> selectedId = id } },
                onDeleteConversation = listViewModel::delete,
                onTogglePin = listViewModel::togglePin,
                onOpenSettings = { navController.navigate(SettingsRoute) },
                onOpenSearch = { navController.navigate(SearchRoute) },
                permanentDrawer = LocalConfiguration.current.screenWidthDp >= TABLET_MIN_WIDTH_DP,
                startAction = startAction,
                onStartActionHandled = { launchTarget = null },
                onOpenBookmarks = { navController.navigate(BookmarksRoute) },
                onOpenSchedule = { navController.navigate(ScheduleRoute) }
            )
        }

        composable<SettingsRoute> {
            SettingsScreen(
                viewModel = viewModel(
                    factory = SettingsViewModel.factory(
                        context,
                        settingsRepository,
                        chatRepository,
                        aiClient,
                        ServiceLocator.commands(context)
                    )
                ),
                onBack = { navController.popBackStack() },
                onOpenSchedule = { navController.navigate(ScheduleRoute) },
                onOpenRemoteSessions = { navController.navigate(RemoteSessionsRoute) }
            )
        }

        composable<RemoteSessionsRoute> {
            RemoteSessionsScreen(
                viewModel = viewModel(factory = RemoteSessionsViewModel.factory(settingsRepository, aiClient)),
                onOpenSession = { session ->
                    navController.navigate(RemoteChatRoute(session.sessionId, session.title))
                },
                onOpenNewSession = { navController.navigate(RemoteChatRoute()) },
                onBack = { navController.popBackStack() }
            )
        }

        composable<RemoteChatRoute> { entry ->
            val route = entry.toRoute<RemoteChatRoute>()
            RemoteChatScreen(
                viewModel = viewModel(factory = RemoteChatViewModel.factory(settingsRepository, aiClient)),
                title = route.title,
                loadSessionId = route.sessionId.ifBlank { null },
                onBack = { navController.popBackStack() },
                onOpenSessions = { navController.navigate(RemoteSessionsRoute) }
            )
        }

        composable<BookmarksRoute> {
            BookmarksScreen(
                viewModel = viewModel(factory = BookmarksViewModel.factory(chatRepository)),
                onOpenConversation = { id ->
                    selectedId = id
                    navController.popBackStack(ChatRoute, inclusive = false)
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable<ScheduleRoute> {
            ScheduleScreen(
                viewModel = viewModel(
                    factory = ScheduleViewModel.factory(
                        context,
                        ScheduleRepository.get(context),
                        settingsRepository
                    )
                ),
                onBack = { navController.popBackStack() },
                onOpenConversation = { id ->
                    selectedId = id
                    navController.popBackStack(ChatRoute, inclusive = false)
                }
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

/** Owns the ChatViewModel of the currently open thread only. */
private class ConversationViewModelOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
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

