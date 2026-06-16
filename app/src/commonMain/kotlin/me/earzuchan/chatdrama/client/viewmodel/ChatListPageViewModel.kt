package me.earzuchan.chatdrama.client.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DisplayTempFakeChat(val title: String, val preview: String)

class ChatListPageViewModel : ViewModel() {
    private val _chats = MutableStateFlow(listOf(DisplayTempFakeChat("Teddy", "想你了"), DisplayTempFakeChat("Anna", "让我们回到那一天吧")))

    val chats: StateFlow<List<DisplayTempFakeChat>> = _chats.asStateFlow()
}
