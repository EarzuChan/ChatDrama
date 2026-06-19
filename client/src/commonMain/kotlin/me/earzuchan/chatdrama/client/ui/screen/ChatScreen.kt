package me.earzuchan.chatdrama.client.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import me.earzuchan.chatdrama.client.ui.view.FakeMessageItem
import me.earzuchan.chatdrama.client.viewmodel.ChatScreenViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun ChatScreen(title: String, onBack: () -> Unit) {
    val vm = koinViewModel<ChatScreenViewModel>(key = title) { parametersOf(title) }
    val messages by vm.messages.collectAsState()
    val input by vm.input.collectAsState()

    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(containerColor = MiuixTheme.colorScheme.surface, topBar = { SmallTopAppBar(title, scrollBehavior = scrollBehavior, navigationIcon = { IconButton(onBack) { Icon(MiuixIcons.Back, "返回") } }) }, bottomBar = {
        Row(Modifier.fillMaxWidth().padding(12.dp, 16.dp), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
            IconButton({}, backgroundColor = MiuixTheme.colorScheme.surfaceContainer) { Icon(MiuixIcons.Medium.Add, null) }
            TextField(input, { vm.setInput(it) }, Modifier.weight(1f, true))
            IconButton({}, backgroundColor = MiuixTheme.colorScheme.surfaceContainer) { Icon(MiuixIcons.Medium.Send, null) }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection), contentPadding = padding + PaddingValues(start = 12.dp, end = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { FakeMessageItem(it) }
        }
    }
}
