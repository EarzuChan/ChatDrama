package me.earzuchan.chatdrama.client.viewmodel

import androidx.lifecycle.ViewModel
import me.earzuchan.chatdrama.client.navigation.MainRoute

class MainScreenViewModel : ViewModel() {
    fun selectTab(backStack: MutableList<MainRoute>, route: MainRoute) {
        if (backStack.lastOrNull() == route) return

        backStack.clear()
        backStack += route
    }
}
