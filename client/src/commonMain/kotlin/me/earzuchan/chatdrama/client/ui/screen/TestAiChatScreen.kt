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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import me.earzuchan.chatdrama.client.data.model.TempAiMessage
import me.earzuchan.chatdrama.client.ui.component.MainMsgBarIconButton
import me.earzuchan.chatdrama.client.ui.component.MessageTextField
import me.earzuchan.chatdrama.client.ui.view.AiMessageItem
import me.earzuchan.chatdrama.client.utils.attachMsgBarBlur
import me.earzuchan.chatdrama.client.utils.attachTopBarBlur
import me.earzuchan.chatdrama.client.utils.rememberBlurBackdrop
import me.earzuchan.chatdrama.client.viewmodel.TestAiChatViewModel
import org.koin.compose.viewmodel.koinViewModel
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun TestAiChatScreen(onBack: () -> Unit) {
    val vm = koinViewModel<TestAiChatViewModel>()
    val messages by vm.messages.collectAsState()
    val input by vm.input.collectAsState()
    val isSending by vm.isSending.collectAsState()

    val listState = rememberLazyListState(messages.lastIndex.coerceAtLeast(0))
    val lastMessage = messages.lastOrNull()
    val lastThoughtLength = (lastMessage as? TempAiMessage.FromLlm)?.thought?.length ?: 0
    LaunchedEffect(messages.size, lastMessage?.content?.length, lastThoughtLength) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }

    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()

    Scaffold(
        containerColor = MiuixTheme.colorScheme.surface, topBar = { SmallTopAppBar("AI 聊天", Modifier.attachTopBarBlur(backdrop), Color.Transparent, scrollBehavior = scrollBehavior, navigationIcon = { IconButton(onBack) { Icon(MiuixIcons.Back, "返回") } }, actions = { IconButton({ vm.clearMessages() }) { Icon(MiuixIcons.Delete, "清空") } }) },
        bottomBar = {
            Row(Modifier.fillMaxWidth().attachMsgBarBlur(backdrop).padding(12.dp, 16.dp), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                MessageTextField(input, vm::setInput, Modifier.weight(1f, true), "消息")
                MainMsgBarIconButton(MiuixIcons.Demibold.Send, input.isNotBlank() && !isSending) { vm.sendInput() }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection).layerBackdrop(backdrop), listState, padding + PaddingValues(start = 12.dp, end = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { AiMessageItem(it) }
        }
    }
}