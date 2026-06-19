package me.earzuchan.chatdrama.client.ui.page

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.onClick
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import me.earzuchan.chatdrama.client.viewmodel.ChatListPageViewModel
import org.koin.compose.viewmodel.koinViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun ChatListPage(onOpenChat: (String) -> Unit,scrollConnection: NestedScrollConnection) {
    val vm = koinViewModel<ChatListPageViewModel>()
    val chats by vm.chats.collectAsState()

    LazyColumn(Modifier.fillMaxSize().overScrollVertical().nestedScroll(scrollConnection)) {
        items(chats, key = { it.title }) { chat ->
                Column(Modifier.fillMaxWidth().clickable{ onOpenChat(chat.title) }.padding(32.dp,16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically){
                        Text(chat.title, Modifier.weight(1f,true), MiuixTheme.colorScheme.onBackground, style = MiuixTheme.textStyles.title3)
                        Text(chat.time, color=MiuixTheme.colorScheme.onBackgroundVariant, style = MiuixTheme.textStyles.footnote1)
                    }
                    Text(chat.preview, style = MiuixTheme.textStyles.paragraph, color = MiuixTheme.colorScheme.onBackgroundVariant)
                }
        }
    }
}
