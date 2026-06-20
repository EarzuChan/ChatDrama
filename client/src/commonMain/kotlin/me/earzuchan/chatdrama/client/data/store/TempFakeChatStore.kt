package me.earzuchan.chatdrama.client.data.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.earzuchan.chatdrama.client.data.model.DisplayTempFakeMessage

class TempFakeChatStore {
    private val _chats: MutableStateFlow<Map<String, List<DisplayTempFakeMessage>>> = MutableStateFlow(
        linkedMapOf(
            "Teddy" to initialMessages("Teddy") + DisplayTempFakeMessage("想你了", false),
            "Anna" to initialMessages("Anna") + DisplayTempFakeMessage("让我们回到那一天吧", false)
        )
    )
    val chats: StateFlow<Map<String, List<DisplayTempFakeMessage>>> = _chats.asStateFlow()

    fun addMessage(title: String, message: DisplayTempFakeMessage) {
        val chats = _chats.value
        val messages = chats[title] ?: initialMessages(title)

        _chats.value = chats + (title to messages + message)
    }

    private fun initialMessages(title: String): List<DisplayTempFakeMessage> = listOf(
        DisplayTempFakeMessage("你是${title}吗"),
        DisplayTempFakeMessage("对的", false),
        DisplayTempFakeMessage("对了", false, "11:45"),
        DisplayTempFakeMessage("你知道永雏塔菲嘛", false),
        DisplayTempFakeMessage("知道的", time = "14:51"),
        DisplayTempFakeMessage("就是那个纯纯的骚鸡是吧"),
        DisplayTempFakeMessage("呜呜呜", false),
        DisplayTempFakeMessage("你怎么能这么说她", false),
        DisplayTempFakeMessage("就这么说", time = "19:19"),
        DisplayTempFakeMessage("怎么了"),
        DisplayTempFakeMessage("我想草捏"),
        DisplayTempFakeMessage("唉", false, "20:10"),
        DisplayTempFakeMessage("说点哥们不想的", false),
        DisplayTempFakeMessage("我看你是压抑了")
    )
}
