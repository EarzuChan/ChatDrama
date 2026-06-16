package me.earzuchan.chatdrama.client

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.earzuchan.chatdrama.client.laztfont.LazyText
import me.earzuchan.chatdrama.client.ui.ClientTheme
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Scaffold

@Composable
@Preview
fun Client() = ClientTheme {
    Scaffold {
        Column(Modifier.safeContentPadding().padding(64.dp).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            var showContent by remember { mutableStateOf(false) }
            val greeting = remember { Greeting.greet }

            Button({ showContent = !showContent }) { LazyText("点这一块") }

            AnimatedVisibility(showContent) { Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { LazyText(greeting) } }
        }
    }
}