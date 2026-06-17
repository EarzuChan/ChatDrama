@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER", "SEALED_INHERITOR_IN_DIFFERENT_MODULE", "SEALED_INHERITOR_IN_DIFFERENT_PACKAGE")

package androidx.compose.ui.text.font

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.platform.PlatformFont
import org.jetbrains.skiko.OS
import org.jetbrains.skiko.hostOs

@Composable
fun FontMamba(content: @Composable () -> Unit) {
    val resolver = remember { FontFamilyResolverImpl(SkiaFontLoader(), MambaThunderousInterceptor) }

    CompositionLocalProvider(LocalFontFamilyResolver provides resolver, content)
}

@OptIn(ExperimentalTextApi::class)
private object MambaThunderousInterceptor : PlatformResolveInterceptor {
    private val defaultFontFamily: FontFamily = FontFamily(platformChineseFontNames().map { MambaOptionalSystemFont(identity = it) })

    override fun interceptFontFamily(fontFamily: FontFamily?): FontFamily {
        val theOne =if (fontFamily == null || fontFamily === FontFamily.Default) defaultFontFamily else fontFamily

        println("啊一个：$theOne")

        return theOne
    }
}

@OptIn(ExperimentalTextApi::class)
private class MambaOptionalSystemFont(override val identity: String, override val weight: FontWeight = FontWeight.Normal, override val style: FontStyle = FontStyle.Normal, override val variationSettings: FontVariation.Settings = FontVariation.Settings(weight, style)) : PlatformFont() {
    override val loadingStrategy: FontLoadingStrategy = FontLoadingStrategy.OptionalLocal

    override fun toString(): String = "MambaOptionalSystemFont(identity='$identity', weight=$weight, style=$style)"
}

private fun platformChineseFontNames(): List<String> = when (hostOs) {
    OS.Windows -> listOf(
        "Microsoft YaHei UI",
        "Microsoft YaHei",
        "SimHei",
        "SimSun",
        "Noto Sans CJK SC",
        "Segoe UI",
        "Arial",
    )

    OS.MacOS, OS.Ios -> listOf(
        "PingFang SC",
        "Hiragino Sans GB",
        "Heiti SC",
        ".AppleSystemUIFont",
        "Helvetica Neue",
        "Helvetica",
    )

    OS.Android -> listOf(
        "Noto Sans CJK SC",
        "Noto Sans SC",
        "Roboto",
        "Noto Sans",
    )

    OS.Linux -> listOf(
        "Noto Sans CJK SC",
        "Noto Sans SC",
        "WenQuanYi Micro Hei",
        "Noto Sans",
        "DejaVu Sans",
        "Arial",
    )

    else -> listOf(
        "Noto Sans CJK SC",
        "Noto Sans SC",
        "Microsoft YaHei",
        "PingFang SC",
        "WenQuanYi Micro Hei",
        "Arial",
    )
}
