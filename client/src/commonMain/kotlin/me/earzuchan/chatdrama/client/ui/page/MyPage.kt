package me.earzuchan.chatdrama.client.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import me.earzuchan.chatdrama.client.ui.view.TextPreference
import me.earzuchan.chatdrama.client.utils.platform
import me.earzuchan.chatdrama.client.viewmodel.MyPageUiState
import me.earzuchan.chatdrama.client.viewmodel.MyPageViewModel
import me.earzuchan.chatdrama.framework.llm.LlmProvider
import org.koin.compose.viewmodel.koinViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun MyPage(scrollConnection: NestedScrollConnection) {
    val vm = koinViewModel<MyPageViewModel>()

    val switchEnabled by vm.switchState.collectAsState()
    val uiState by vm.uiState.collectAsState()

    val providers = LlmProvider.entries

    LazyColumn(Modifier.fillMaxSize().overScrollVertical().nestedScroll(scrollConnection), contentPadding = PaddingValues(top=4.dp, start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Card { BasicComponent(title = "恩情", summary = "当前平台：$platform") } }

        item{ Card { SwitchPreference(switchEnabled, { vm.setSwitchState(it) }, "南下") } }

        (uiState as? MyPageUiState.Success)?.let { succeed -> // 仅当加载好才显示
            item {
                val llmSettings = succeed.llmSettings

                SmallTitle("LLM 配置", insideMargin = PaddingValues(16.dp, 8.dp))
                Card {
                    Column {
                        OverlaySpinnerPreference(providers.map { DropdownItem(it.displayName) }, providers.indexOf(llmSettings.provider).coerceAtLeast(0), "API类型", onSelectedIndexChange = { index -> vm.setLlmProvider(providers[index]) })

                        TextPreference(llmSettings.apiKey, vm::setLlmApiKey, "API Key")

                        TextPreference(llmSettings.endpoint, vm::setLlmEndpoint, "端点（不填则使用默认）")

                        TextPreference(llmSettings.model, vm::setLlmModel, "模型")

                        SwitchPreference(llmSettings.preferReasoning, vm::setPreferReasoning, "希望启用思考")
                    }
                }
            }
        }
    }
}

private val LlmProvider.displayName: String
    get() = when (this) {
        LlmProvider.OpenAiLegacy -> "OpenAI 经典API"
        LlmProvider.OpenAiResponses -> "OpenAI 新API"
        LlmProvider.Claude -> "Claude"
        LlmProvider.Gemini -> "Gemini"
    }
