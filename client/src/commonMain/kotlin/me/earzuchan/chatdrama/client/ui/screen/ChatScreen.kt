package me.earzuchan.chatdrama.client.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.earzuchan.chatdrama.client.viewmodel.ChatScreenViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ChatScreen(title: String, onBack: () -> Unit) {
    val vm = koinViewModel<ChatScreenViewModel>(key = title) { parametersOf(title) }
    val messages by vm.messages.collectAsState()

    Scaffold(containerColor = MiuixTheme.colorScheme.surface, topBar = { TopAppBar(title, navigationIcon = { IconButton(onBack) { Icon(MiuixIcons.Back, "返回") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(messages, key = { it.id }) { message ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(if (message.fromMe) "我" else "对方", color = MiuixTheme.colorScheme.primary)
                        Spacer(Modifier.height(6.dp))
                        Text(message.content)
                    }
                }
            }
        }
    }
}
