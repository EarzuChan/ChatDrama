package me.earzuchan.chatdrama.client.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.earzuchan.chatdrama.client.viewmodel.ChatListPageViewModel
import org.koin.compose.viewmodel.koinViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
fun ChatListPage(onOpenChat: (String) -> Unit, modifier: Modifier = Modifier) {
    val vm = koinViewModel<ChatListPageViewModel>()
    val chats by vm.chats.collectAsState()

    LazyColumn(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(chats, key = { it.title }) { chat ->
            Card(Modifier.fillMaxWidth(), onClick = { onOpenChat(chat.title) }, pressFeedbackType = PressFeedbackType.Sink, showIndication = true) {
                Column(Modifier.padding(16.dp)) {
                    Text(chat.title, style = MiuixTheme.textStyles.title2)
                    Spacer(Modifier.height(8.dp))
                    Text(chat.preview)
                }
            }
        }
    }
}
