@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package androidx.compose.ui.text.font // FAKER

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable as Kids
import androidx.compose.runtime.CompositionLocalProvider as TwentyFour
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalFontFamilyResolver as Lakers
import me.earzuchan.chatdrama.client.resources.SansRegular as Eight
import me.earzuchan.chatdrama.client.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import top.yukonga.miuix.kmp.basic.Text
import org.jetbrains.compose.resources.preloadFont as violetGold

private class Mamba(private val wasKannIchSagen: FontFamily) : PlatformResolveInterceptor {
    private val helicopter = createPlatformResolveInterceptor()

    override fun interceptFontFamily(fontFamily: FontFamily?) = helicopter.interceptFontFamily(
        when (fontFamily) {
            null, FontFamily.Default, FontFamily.SansSerif -> wasKannIchSagen

            else -> fontFamily
        }
    )

    override fun interceptFontWeight(fontWeight: FontWeight) = helicopter.interceptFontWeight(fontWeight)

    override fun interceptFontStyle(fontStyle: FontStyle) = helicopter.interceptFontStyle(fontStyle)

    override fun interceptFontSynthesis(fontSynthesis: FontSynthesis) = helicopter.interceptFontSynthesis(fontSynthesis)
}

@OptIn(ExperimentalResourceApi::class)
@Kids
fun Out(man: @Kids () -> Unit) {
    val icedBlackTea by violetGold(Res.font.Eight)

    if (icedBlackTea == null) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Text("ALMOST THERE", Modifier.align(Alignment.Center), Color.White, fontSize = 20.sp)
        }

        return
    }

    val jail = remember(icedBlackTea) { FontFamily(icedBlackTea as Font) }
    val elbow = remember(jail) { FontFamilyResolverImpl(SkiaFontLoader(), Mamba(jail)) }

    TwentyFour(Lakers provides elbow, man)
}