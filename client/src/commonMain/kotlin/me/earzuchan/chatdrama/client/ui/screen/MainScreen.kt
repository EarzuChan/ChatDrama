package me.earzuchan.chatdrama.client.ui.screen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import me.earzuchan.chatdrama.client.navigation.MainRoute
import me.earzuchan.chatdrama.client.ui.page.ChatListPage
import me.earzuchan.chatdrama.client.ui.page.MyPage
import me.earzuchan.chatdrama.client.viewmodel.MainScreenViewModel
import org.koin.compose.viewmodel.koinViewModel
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ContactsBook
import top.yukonga.miuix.kmp.icon.extended.ContactsCircle
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Reply

@Composable
fun MainScreen(onOpenChat: (String) -> Unit, onOpenAiChat: () -> Unit) {
    val vm = koinViewModel<MainScreenViewModel>()

    val backStack = remember { mutableStateListOf<MainRoute>(MainRoute.ChatList) }
    val selected = backStack.last()
    val pagerState = rememberPagerState(selected.index) { MainRoute.routes.size }

    LaunchedEffect(pagerState) { snapshotFlow { pagerState.settledPage }.collect { page -> vm.selectTab(backStack, MainRoute.of(page)) } }
    LaunchedEffect(selected) { if (pagerState.settledPage != selected.index) pagerState.animateScrollToPage(selected.index) }

    val scrollBehavior = MiuixScrollBehavior()
    val scrollConnection = scrollBehavior.nestedScrollConnection

    Scaffold(topBar = { TopAppBar(selected.title, scrollBehavior = scrollBehavior) }, bottomBar = { MainNavigationBar(selected) { vm.selectTab(backStack, it) } }) { padding ->
        HorizontalPager(pagerState, Modifier.fillMaxSize().padding(padding), key = { MainRoute.of(it) }) { page ->
            when (MainRoute.of(page)) {
                MainRoute.ChatList -> ChatListPage(onOpenChat, onOpenAiChat, scrollConnection)

                MainRoute.My -> MyPage(scrollConnection)
            }
        }
    }
}

@Composable
private fun MainNavigationBar(selected: MainRoute, onSelect: (MainRoute) -> Unit) = NavigationBar(showDivider = true, mode = NavigationBarDisplayMode.IconAndText) {
    (selected == MainRoute.ChatList).let { NavigationBarItem(it, { onSelect(MainRoute.ChatList) }, if (it) MiuixIcons.Messages else MiuixIcons.Reply, MainRoute.ChatList.title) }

    (selected == MainRoute.My).let { NavigationBarItem(it, { onSelect(MainRoute.My) }, if (it) MiuixIcons.ContactsCircle else MiuixIcons.ContactsBook, MainRoute.My.title) }
}
