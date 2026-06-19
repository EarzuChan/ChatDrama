package me.earzuchan.chatdrama.client.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DisplayTempFakeChat(val title: String, val preview: String,val time : String="早些时候")

class ChatListPageViewModel : ViewModel() {
    private val _chats = MutableStateFlow(listOf(DisplayTempFakeChat("Teddy", "想你了"), DisplayTempFakeChat("Anna", "让我们回到那一天吧")))

    val chats = _chats.asStateFlow()
}
