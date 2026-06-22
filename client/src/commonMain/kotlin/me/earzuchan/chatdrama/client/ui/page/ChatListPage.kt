package me.earzuchan.chatdrama.client.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import me.earzuchan.chatdrama.client.viewmodel.ChatListPageViewModel
import org.koin.compose.viewmodel.koinViewModel
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Reply
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun ChatListPage(onOpenChat: (String) -> Unit, onOpenAiChat: () -> Unit, scrollConnection: NestedScrollConnection) {
    val vm = koinViewModel<ChatListPageViewModel>()
    val chats by vm.chats.collectAsState()

    LazyColumn(Modifier.fillMaxSize().overScrollVertical().nestedScroll(scrollConnection), contentPadding = PaddingValues(top=4.dp)) {
        item { // Temp Ai
            Row(Modifier.fillMaxWidth().clickable(onClick = onOpenAiChat).padding(28.dp, 14.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(MiuixTheme.colorScheme.primary), Alignment.Center) {
                    Icon(MiuixIcons.Reply, null, tint = MiuixTheme.colorScheme.onPrimary)
                }

                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("AI 聊天测试", Modifier.weight(1f, true), MiuixTheme.colorScheme.onSurface, style = MiuixTheme.textStyles.paragraph)
                        Text("LLM", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote1)
                    }

                    Text("仅仅是测试一下而已啦，祝玩得开心", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                }
            }
        }

        items(chats.entries.toList(), key = { it.key }) { chat ->
            Row(Modifier.fillMaxWidth().clickable { onOpenChat(chat.key) }.padding(28.dp, 14.dp), Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(MiuixTheme.colorScheme.primary), Alignment.Center)  {
                    Icon(MiuixIcons.Contacts, null, tint = MiuixTheme.colorScheme.onPrimary)
                }

                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(chat.key, Modifier.weight(1f, true), MiuixTheme.colorScheme.onSurface, style = MiuixTheme.textStyles.paragraph)

                        Text("早些时候", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote1)
                    }

                    Text(chat.value.lastOrNull()?.content.orEmpty(), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                }
            }
        }
    }
}
