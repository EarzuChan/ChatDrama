@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package androidx.compose.ui.text.font

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalFontFamilyResolver

@Composable
fun FontMamba(content: @Composable () -> Unit) {
    val resolver = remember { FontFamilyResolverImpl(SkiaFontLoader()) }

    CompositionLocalProvider(LocalFontFamilyResolver provides resolver, content)
}
