package me.earzuchan.chatdrama.client.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DisplayTempFakeMessage(val content: String, val fromMe: Boolean = true, val time: String? = null)

class ChatScreenViewModel(title: String) : ViewModel() {
    private val _messages = MutableStateFlow(
        listOf(
            DisplayTempFakeMessage("你是${title}吗"), DisplayTempFakeMessage("对的", false),
            DisplayTempFakeMessage("对了", false, "11:45"), DisplayTempFakeMessage("你知道永雏塔菲嘛", false),
            DisplayTempFakeMessage("知道的", time = "14:51"), DisplayTempFakeMessage("就是那个纯纯的骚鸡是吧"),
            DisplayTempFakeMessage("呜呜呜", false), DisplayTempFakeMessage("你怎么能这么说她", false),
            DisplayTempFakeMessage("就这么说", time = "19:19"), DisplayTempFakeMessage("怎么了"),
            DisplayTempFakeMessage("我想草捏"), DisplayTempFakeMessage("唉", false, "20:10"), DisplayTempFakeMessage("说点哥们不想的", false),
            DisplayTempFakeMessage("我看你是压抑了")
        )
    )
    val messages = _messages.asStateFlow()

    private val _input = MutableStateFlow("可爱捏")
    val input = _input.asStateFlow()
    fun setInput(inp: String) {
        _input.value = inp
    }

    fun sendInput() {
        val content = _input.value.trim()
        if (content.isEmpty()) return

        _messages.value += DisplayTempFakeMessage(content)
        _input.value = ""
    }
}
