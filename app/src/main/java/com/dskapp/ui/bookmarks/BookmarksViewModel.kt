package com.dskapp.ui.bookmarks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dskapp.data.chat.BookmarkItem
import com.dskapp.data.chat.ChatRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Exposes the bookmark feed as UI state and performs optimistic removal by clearing
 * the flag on the source message.
 */
class BookmarksViewModel(
    private val repository: ChatRepository,
) : ViewModel() {

    val items: StateFlow<List<BookmarkItem>> = repository.observeBookmarks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun remove(messageId: Long) {
        viewModelScope.launch {
            repository.setBookmarked(messageId, false)
        }
    }

    companion object {
        fun factory(repository: ChatRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    BookmarksViewModel(repository) as T
            }
    }
}
