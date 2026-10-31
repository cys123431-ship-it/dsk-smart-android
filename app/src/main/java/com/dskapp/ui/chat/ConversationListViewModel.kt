package com.dskapp.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dskapp.data.chat.ChatRepository
import com.dskapp.data.chat.ConversationEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Drives the conversation history (drawer on phones, fixed pane on tablets). */
class ConversationListViewModel(
    private val repository: ChatRepository
) : ViewModel() {

    val conversations: StateFlow<List<ConversationEntity>> = repository.observeConversations()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    fun newConversation(onCreated: (Long) -> Unit) {
        viewModelScope.launch { onCreated(repository.createConversation()) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.deleteConversation(id) }
    }

    fun togglePin(conversation: ConversationEntity) {
        viewModelScope.launch { repository.setPinned(conversation.id, !conversation.isPinned) }
    }

    fun rename(id: Long, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch { repository.renameConversation(id, title) }
    }

    companion object {
        fun factory(repository: ChatRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { ConversationListViewModel(repository) }
        }
    }
}
