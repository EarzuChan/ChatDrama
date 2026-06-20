package me.earzuchan.chatdrama.client.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import me.earzuchan.chatdrama.client.data.model.DisplayTempFakeMessage
import me.earzuchan.chatdrama.client.data.store.TempFakeChatStore

class ChatScreenViewModel(private val title: String, private val chatStore: TempFakeChatStore) : ViewModel() {
    val messages = chatStore.chats.map { it[title].orEmpty() }

    private val _input = MutableStateFlow("可爱捏")
    val input = _input.asStateFlow()
    fun setInput(inp: String) {
        _input.value = inp
    }

    fun sendInput() {
        val content = _input.value.trim()
        if (content.isEmpty()) return

        chatStore.addMessage(title, DisplayTempFakeMessage(content))
        _input.value = ""
    }
}
