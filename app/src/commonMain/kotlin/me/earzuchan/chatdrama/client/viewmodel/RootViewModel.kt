package me.earzuchan.chatdrama.client.viewmodel

import androidx.lifecycle.ViewModel
import me.earzuchan.chatdrama.client.navigation.RootRoute

class RootViewModel : ViewModel() {
    fun openChat(backStack: MutableList<RootRoute>, chatId: String) {
        val route = RootRoute.Chat(chatId)
        if (backStack.lastOrNull() != route) backStack += route
    }
}