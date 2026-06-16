package me.earzuchan.chatdrama.client.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.earzuchan.chatdrama.client.laztfont.LazyText
import me.earzuchan.chatdrama.client.viewmodel.ChatListPageViewModel
import org.koin.compose.viewmodel.koinViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
fun ChatListPage(onOpenChat: (String) -> Unit, modifier: Modifier = Modifier, vm: ChatListPageViewModel = koinViewModel()) {
    val chats by vm.chats.collectAsState()

    LazyColumn(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(chats, key = { it.title }) { chat ->
            Card(Modifier.fillMaxWidth(), onClick = { onOpenChat(chat.title) }, pressFeedbackType = PressFeedbackType.Sink, showIndication = true) {
                Column(Modifier.padding(16.dp)) {
                    LazyText(chat.title, style = MiuixTheme.textStyles.title2)
                    Spacer(Modifier.height(8.dp))
                    LazyText(chat.preview)
                }
            }
        }
    }
}
