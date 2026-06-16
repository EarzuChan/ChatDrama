package me.earzuchan.chatdrama.client.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import me.earzuchan.chatdrama.client.laztfont.LazyText
import me.earzuchan.chatdrama.client.navigation.MainRoute
import me.earzuchan.chatdrama.client.ui.page.ChatListPage
import me.earzuchan.chatdrama.client.ui.page.MyPage
import me.earzuchan.chatdrama.client.viewmodel.MainScreenViewModel
import org.koin.compose.viewmodel.koinViewModel
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDefaults
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.ContactsBook
import top.yukonga.miuix.kmp.icon.extended.ContactsCircle
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Reply
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MainScreen(onOpenChat: (String) -> Unit, vm: MainScreenViewModel = koinViewModel()) {
    val backStack = remember { mutableStateListOf<MainRoute>(MainRoute.ChatList) }
    val selected = backStack.last()
    val pagerState = rememberPagerState(selected.index) { MainRoute.routes.size }

    LaunchedEffect(pagerState) { snapshotFlow { pagerState.settledPage }.collect { page -> vm.selectTab(backStack, MainRoute.of(page)) } }
    LaunchedEffect(selected) { if (pagerState.settledPage != selected.index) pagerState.animateScrollToPage(selected.index) }

    Scaffold(topBar = { TopAppBar(selected.title) }, bottomBar = { MainNavigationBar(selected) { vm.selectTab(backStack, it) } }) { padding ->
        HorizontalPager(pagerState, Modifier.fillMaxSize().padding(padding), key = { MainRoute.of(it) }) { page ->
            when (MainRoute.of(page)) {
                MainRoute.ChatList -> ChatListPage(onOpenChat, Modifier.fillMaxSize())

                MainRoute.My -> MyPage(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun MainNavigationBar(selected: MainRoute, onSelect: (MainRoute) -> Unit) = NavigationBar(showDivider = true, mode = NavigationBarDisplayMode.IconAndText) {
    (selected == MainRoute.ChatList).let { LazyNavigationBarItem(it, { onSelect(MainRoute.ChatList) }, if (it) MiuixIcons.Messages else MiuixIcons.Reply, MainRoute.ChatList.title) }

    (selected == MainRoute.My).let { LazyNavigationBarItem(it, { onSelect(MainRoute.My) }, if (it) MiuixIcons.ContactsCircle else MiuixIcons.ContactsBook, MainRoute.My.title) }
}

// CHECK：考虑把这个组件移走到正规复用场地
@Composable
private fun RowScope.LazyNavigationBarItem(selected: Boolean, onClick: () -> Unit, icon: ImageVector, label: String, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val onSurfaceContainerColor = MiuixTheme.colorScheme.onSurfaceContainer

    val tint = when {
        isPressed && selected -> onSurfaceContainerColor.copy(alpha = NavigationBarDefaults.SelectedPressedAlpha)
        isPressed -> onSurfaceContainerColor.copy(alpha = NavigationBarDefaults.UnselectedPressedAlpha)
        selected -> onSurfaceContainerColor
        else -> onSurfaceContainerColor.copy(alpha = NavigationBarDefaults.UnselectedAlpha)
    }

    Column(modifier.height(NavigationBarDefaults.ItemHeight).weight(1f).selectable(selected, interactionSource, null, onClick = onClick, role = Role.Tab), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(icon, null, Modifier.padding(top = NavigationBarDefaults.IconTopPadding).size(NavigationBarDefaults.IconSize), colorFilter = ColorFilter.tint(tint))

        LazyText(label, Modifier.padding(bottom = NavigationBarDefaults.BottomPadding), tint, NavigationBarDefaults.LabelFontSize, textAlign = TextAlign.Center, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}
