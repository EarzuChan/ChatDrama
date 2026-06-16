package me.earzuchan.chatdrama.client.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.earzuchan.chatdrama.client.laztfont.LazyText
import me.earzuchan.chatdrama.client.viewmodel.MyPageViewModel
import org.koin.compose.viewmodel.koinViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MyPage(modifier: Modifier = Modifier, vm: MyPageViewModel = koinViewModel()) {
    Column(modifier.padding(16.dp), Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                LazyText("恩情", style = MiuixTheme.textStyles.title2)
                Spacer(Modifier.height(8.dp))
                LazyText("南下")
            }
        }
    }
}
