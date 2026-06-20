package me.earzuchan.chatdrama.client.viewmodel

import androidx.lifecycle.ViewModel
import me.earzuchan.chatdrama.client.data.store.TempFakeChatStore

class ChatListPageViewModel(chatStore: TempFakeChatStore) : ViewModel() {
    val chats = chatStore.chats
}
