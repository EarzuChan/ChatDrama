package me.earzuchan.chatdrama.client.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.earzuchan.chatdrama.client.utils.platform
import me.earzuchan.chatdrama.client.viewmodel.MyPageViewModel
import org.koin.compose.viewmodel.koinViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MyPage(modifier: Modifier = Modifier) {
    val vm = koinViewModel<MyPageViewModel>()
    val switchEnabled by vm.switchState.collectAsState()

    Column(modifier.padding(16.dp), Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("恩情", style = MiuixTheme.textStyles.title2)
                Spacer(Modifier.height(8.dp))
                Text("当前平台：$platform")
            }
        }

        Card { SwitchPreference(switchEnabled, { vm.setSwitchState(it) }, "南下") }
    }
}
