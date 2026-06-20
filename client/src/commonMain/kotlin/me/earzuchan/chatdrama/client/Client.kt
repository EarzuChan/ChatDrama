package me.earzuchan.chatdrama.client

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import me.earzuchan.chatdrama.client.di.clientModule
import me.earzuchan.chatdrama.client.navigation.BindRootBrowserNavigation
import me.earzuchan.chatdrama.client.navigation.RootRoute
import me.earzuchan.chatdrama.client.ui.ClientTheme
import me.earzuchan.chatdrama.client.ui.screen.ChatScreen
import me.earzuchan.chatdrama.client.ui.screen.MainScreen
import me.earzuchan.chatdrama.client.viewmodel.RootViewModel
import org.koin.compose.KoinApplication
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.dsl.koinConfiguration
import top.yukonga.miuix.kmp.theme.MiuixTheme

@OptIn(KoinExperimentalAPI::class)
@Composable
@Preview
fun Client() = KoinApplication(koinConfiguration { modules(clientModule) }) { ClientTheme { Root() } }

@Composable
private fun Root(vm: RootViewModel = koinViewModel()) {
    val backStack = remember { mutableStateListOf<RootRoute>(RootRoute.Main) }

    BindRootBrowserNavigation(backStack)

    NavDisplay(backStack, Modifier.safeContentPadding().fillMaxSize().background(MiuixTheme.colorScheme.background), onBack = { if (backStack.size > 1) backStack.removeLastOrNull() }) { route ->
        NavEntry(route) {
            when (route) {
                RootRoute.Main -> MainScreen { vm.openChat(backStack, it) }

                is RootRoute.Chat -> ChatScreen(route.id) { backStack.removeLastOrNull() }
            }
        }
    }
}
