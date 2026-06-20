package me.earzuchan.chatdrama.client.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import me.earzuchan.chatdrama.client.ui.component.MessageTextField
import me.earzuchan.chatdrama.client.ui.view.FakeMessageItem
import me.earzuchan.chatdrama.client.utils.attachBarBlur
import me.earzuchan.chatdrama.client.utils.rememberBlurBackdrop
import me.earzuchan.chatdrama.client.viewmodel.ChatScreenViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun ChatScreen(title: String, onBack: () -> Unit) {
    val vm = koinViewModel<ChatScreenViewModel>(key = title) { parametersOf(title) }
    val messages by vm.messages.collectAsState(emptyList())
    val input by vm.input.collectAsState()

    val listState = rememberLazyListState(messages.lastIndex.coerceAtLeast(0)) // 确保默认最后
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }

    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()

    Scaffold(containerColor = MiuixTheme.colorScheme.surface, topBar = { SmallTopAppBar(title, Modifier.attachBarBlur(backdrop), Color.Transparent, scrollBehavior = scrollBehavior, navigationIcon = { IconButton(onBack) { Icon(MiuixIcons.Back, "返回") } }) }, bottomBar = {
        Row(Modifier.fillMaxWidth().attachBarBlur(backdrop).padding(12.dp, 16.dp), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
            BarIconButton(MiuixIcons.Demibold.Add)
            MessageTextField(input, { vm.setInput(it) }, Modifier.weight(1f, true), "消息")
            BarIconMainButton(MiuixIcons.Demibold.Send, input.isNotBlank()) { vm.sendInput() }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection).layerBackdrop(backdrop), listState, padding + PaddingValues(start = 12.dp, end = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { FakeMessageItem(it) }
        }
    }
}

@Composable
private fun BarIconButton(icon: ImageVector, onClick: () -> Unit = {}) = IconButton(onClick, backgroundColor = MiuixTheme.colorScheme.onSurface.copy(0.16f)) { Icon(icon, null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary) }

@Composable
private fun BarIconMainButton(icon: ImageVector, active: Boolean = false, onClick: () -> Unit = {}) {
    val background = if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface.copy(0.16f)
    val foreground = if (active) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.disabledOnSurface

    IconButton(onClick, enabled = active, backgroundColor = background) { Icon(icon, null, tint = foreground) }
}
