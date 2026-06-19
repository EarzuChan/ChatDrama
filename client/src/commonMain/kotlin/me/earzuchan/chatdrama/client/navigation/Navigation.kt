package me.earzuchan.chatdrama.client.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface RootRoute : NavKey {
    @Serializable
    data object Main : RootRoute

    @Serializable
    data class Chat(val id: String) : RootRoute
}

@Serializable
sealed interface MainRoute : NavKey {
    val index: Int
    val title: String

    @Serializable
    data object ChatList : MainRoute {
        override val index = 0
        override val title = "聊天"
    }

    @Serializable
    data object My : MainRoute {
        override val index = 1
        override val title = "我的"
    }

    companion object {
        val routes: List<MainRoute> = listOf(ChatList, My)

        fun of(index: Int) = routes[index.coerceIn(routes.indices)]
    }
}
