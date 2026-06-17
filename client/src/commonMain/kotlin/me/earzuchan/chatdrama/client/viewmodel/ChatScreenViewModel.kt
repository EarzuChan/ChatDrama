package me.earzuchan.chatdrama.client.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DisplayTempFakeMessage(val id: String, val content: String, val fromMe: Boolean)

class ChatScreenViewModel(title: String) : ViewModel() {
    private val _messages = MutableStateFlow(listOf(DisplayTempFakeMessage("1", "这里是$title", true), DisplayTempFakeMessage("2", "气泡偷懒了还没做", false)))

    val messages = _messages.asStateFlow()
}
