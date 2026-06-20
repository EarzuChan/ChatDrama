package me.earzuchan.chatdrama.client.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import me.earzuchan.chatdrama.client.ui.component.MessageTextField
import me.earzuchan.chatdrama.client.ui.view.FakeMessageItem
import me.earzuchan.chatdrama.client.utils.attachTopBarBlur
import me.earzuchan.chatdrama.client.utils.rememberBlurBackdrop
import me.earzuchan.chatdrama.client.viewmodel.ChatScreenViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.isDarkTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun ChatScreen(title: String, onBack: () -> Unit) {
    val vm = koinViewModel<ChatScreenViewModel>(key = title) { parametersOf(title) }
    val messages by vm.messages.collectAsState(emptyList())
    val input by vm.input.collectAsState()

    val listState = rememberLazyListState(messages.lastIndex.coerceAtLeast(0)) // 确保默认最后
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }

    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()

    Scaffold(containerColor = MiuixTheme.colorScheme.surface, topBar = { SmallTopAppBar(title, Modifier.attachTopBarBlur(backdrop), Color.Transparent, scrollBehavior = scrollBehavior, navigationIcon = { IconButton(onBack) { Icon(MiuixIcons.Back, "返回") } }) }, bottomBar = {
        Row(Modifier.fillMaxWidth().attachMsgBarBlur(backdrop).padding(12.dp, 16.dp), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
            BarMsgIconButton(MiuixIcons.Demibold.Add)
            MessageTextField(input, { vm.setInput(it) }, Modifier.weight(1f, true), "消息")
            MainMsgBarIconButton(MiuixIcons.Demibold.Send, input.isNotBlank()) { vm.sendInput() }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection).layerBackdrop(backdrop), listState, padding + PaddingValues(start = 12.dp, end = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { FakeMessageItem(it) }
        }
    }
}

@Composable
private fun BaseMsgBarIconButton(icon: ImageVector, bg: Color, fore: Color, enabled: Boolean = true, onClick: () -> Unit) = IconButton(onClick, enabled = enabled, backgroundColor = bg) { Icon(icon, null, tint = fore) }

@Composable
private fun BarMsgIconButton(icon: ImageVector, onClick: () -> Unit = {}) {
    val background = if (isDarkTheme) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.06f)

    val foreground = (if (isDarkTheme) Color.White else Color.Black).copy(alpha = 0.8f)

    BaseMsgBarIconButton(icon, background, foreground, onClick = onClick)
}

@Composable
private fun MainMsgBarIconButton(icon: ImageVector, active: Boolean = false, onClick: () -> Unit = {}) {
    val background = when {
        active -> MiuixTheme.colorScheme.primary
        isDarkTheme -> Color.White.copy(0.14f)
        else -> Color.Black.copy(0.06f)
    }

    val foreground = when {
        active -> MiuixTheme.colorScheme.onPrimary
        isDarkTheme -> Color.White.copy(0.3f)
        else -> Color.Black.copy(0.3f)
    }

    BaseMsgBarIconButton(icon, background, foreground, active, onClick)
}

@Composable
fun Modifier.attachMsgBarBlur(backdrop: Backdrop) = textureBlur(
    backdrop, RectangleShape, 66f, colors = BlurDefaults.blurColors(
        if (isDarkTheme) listOf(BlendColorEntry(Color(0x75737373), BlurBlendMode.ColorBurn),BlendColorEntry(Color(0x8A000000), BlurBlendMode.SrcOver),BlendColorEntry(Color(0x0AFFFFFF), BlurBlendMode.SrcOver))
        else listOf(BlendColorEntry(Color(0xA66B6B6B), BlurBlendMode.ColorDodge), BlendColorEntry(Color(0xCCF5F5F5), BlurBlendMode.SrcOver))
    )
)