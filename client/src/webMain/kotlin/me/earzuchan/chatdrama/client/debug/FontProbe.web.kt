package me.earzuchan.chatdrama.client.debug

import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Typeface

/**
 * Runtime probe for Compose Multiplatform Web/Skiko font behavior.
 *
 * Open browser DevTools console and look for lines prefixed with [FontProbe].
 * This does not change Compose rendering; it only asks Skia/CanvasKit what it can resolve.
 */
fun probeSkikoWebFonts() {
    runCatching {
        val mgr = FontMgr.default
        log("=== Skiko Web Font Probe start ===")
        log("FontMgr.default.familiesCount=${runCatching { mgr.familiesCount }.fold({ it.toString() }, { "ERROR: ${it.message}" })}")

        val count = runCatching { mgr.familiesCount }.getOrDefault(0)
        if (count > 0) {
            val names = (0 until minOf(count, 80)).mapNotNull { index ->
                runCatching { mgr.getFamilyName(index) }.getOrNull()
            }
            log("First ${names.size} FontMgr families: ${names.joinToString()}")
        } else {
            log("FontMgr has no enumerable families, or enumeration is unsupported in this backend.")
        }

        val candidates = listOf(
            "system-ui",
            "sans-serif",
            "Segoe UI",
            "Arial",
            "Microsoft YaHei",
            "DengXian",
            "SimSun",
            "SimHei",
            "KaiTi",
            "PingFang SC",
            "Noto Sans CJK SC",
            "Noto Sans SC",
            "Roboto",
        )

        candidates.forEach { name ->
            probeFamily(mgr, name)
        }

        probeFallback(mgr, '汉'.code, "zh")
        probeFallback(mgr, '字'.code, "zh")
        probeFallback(mgr, 'A'.code, "en")
        log("=== Skiko Web Font Probe end ===")
    }.onFailure { error ->
        log("Probe crashed: ${error::class.simpleName}: ${error.message}")
        error.stackTraceToString().lineSequence().take(8).forEach { log(it) }
    }
}

private fun probeFamily(mgr: FontMgr, name: String) {
    val legacy = runCatching { mgr.legacyMakeTypeface(name, FontStyle.NORMAL) }
    val match = runCatching { mgr.matchFamilyStyle(name, FontStyle.NORMAL) }

    log("family='$name'")
    log("  legacyMakeTypeface: ${legacy.fold({ it.describeTypeface() }, { "ERROR ${it::class.simpleName}: ${it.message}" })}")
    log("  matchFamilyStyle:   ${match.fold({ it.describeTypeface() }, { "ERROR ${it::class.simpleName}: ${it.message}" })}")

    val tf = legacy.getOrNull() ?: match.getOrNull()
    if (tf != null) {
        val hanGlyph = runCatching { tf.getUTF32Glyph('汉'.code).toInt() }.fold({ it.toString() }, { "ERROR ${it.message}" })
        val aGlyph = runCatching { tf.getUTF32Glyph('A'.code).toInt() }.fold({ it.toString() }, { "ERROR ${it.message}" })
        log("  glyphs: '汉'=$hanGlyph, 'A'=$aGlyph")
    }
}

private fun probeFallback(mgr: FontMgr, codePoint: Int, lang: String) {
    val ch = codePoint.toChar()
    val tf = runCatching {
        mgr.matchFamilyStyleCharacter(
            familyName = null,
            style = FontStyle.NORMAL,
            bcp47 = arrayOf(lang),
            character = codePoint,
        )
    }
    log("fallback char='$ch' lang='$lang': ${tf.fold({ it.describeTypeface() }, { "ERROR ${it::class.simpleName}: ${it.message}" })}")
}

private fun Typeface?.describeTypeface(): String = if (this == null) {
    "null"
} else {
    val family = runCatching { familyName }.getOrElse { "<familyName error: ${it.message}>" }
    val glyphs = runCatching { glyphsCount }.getOrElse { -1 }
    val id = runCatching { uniqueId }.getOrElse { -1 }
    val names = runCatching {
        familyNames.take(6).joinToString(prefix = "[", postfix = "]") { "${it.name}/${it.language}" }
    }.getOrElse { "<familyNames error: ${it.message}>" }
    "familyName='$family', glyphs=$glyphs, uniqueId=$id, names=$names"
}

private fun log(message: String) {
    println("[FontProbe] $message")
}
