package me.earzuchan.chatdrama.client.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import me.earzuchan.chatdrama.client.utils.platform
import me.earzuchan.chatdrama.client.viewmodel.MyPageViewModel
import org.koin.compose.viewmodel.koinViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun MyPage(scrollConnection: NestedScrollConnection) {
    val vm = koinViewModel<MyPageViewModel>()

    val switchEnabled by vm.switchState.collectAsState()

    LazyColumn(Modifier.fillMaxSize().overScrollVertical().nestedScroll(scrollConnection), contentPadding = PaddingValues(top=4.dp, start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Card { BasicComponent(title = "恩情", summary = "当前平台：$platform") } }

        item{ Card { SwitchPreference(switchEnabled, { vm.setSwitchState(it) }, "南下") } }
    }
}
