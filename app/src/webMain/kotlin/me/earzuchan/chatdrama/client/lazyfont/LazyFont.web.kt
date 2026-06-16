package me.earzuchan.chatdrama.client.lazyfont

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import me.earzuchan.chatdrama.client.laztfont.LazyTextController
import me.earzuchan.chatdrama.client.laztfont.codePointAtCompat
import kotlin.coroutines.resume
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.ExperimentalWasmJsInterop
import androidx.compose.ui.text.font.Font as ComposeFont

// TIPS：从 Miuix 导出

internal data class FontFaceDeclare(val family: String, val weight: FontWeight, val style: FontStyle, val ranges: List<IntRange>, val url: String)

internal fun parseCssFontFaces(css: String, baseUrl: String? = null): List<FontFaceDeclare> {
    val out = mutableListOf<FontFaceDeclare>()
    val blockRegex = Regex("""@font-face\s*\{([^}]*)\}""", RegexOption.IGNORE_CASE)

    for (match in blockRegex.findAll(css)) {
        val body = match.groupValues[1]
        val family = pickKey(body, "font-family")?.trim()?.trim('"', '\'') ?: continue
        val weight = parseWeight(pickKey(body, "font-weight"))
        val style = parseStyle(pickKey(body, "font-style"))
        val ranges = parseUnicodeRange(pickKey(body, "unicode-range"))
        val url = pickFontUrl(pickKey(body, "src"), baseUrl) ?: continue
        out += FontFaceDeclare(family, weight, style, ranges, url)
    }

    return out
}

private fun pickKey(block: String, key: String): String? {
    val r = Regex("""(?:^|[\s;])$key\s*:\s*([^;]+?)\s*(?:;|$)""", RegexOption.IGNORE_CASE)
    return r.find(block)?.groupValues?.get(1)
}

private fun parseWeight(raw: String?): FontWeight {
    if (raw == null) return FontWeight.Normal
    val tokens = raw.trim().lowercase().split(Regex("\\s+"))

    if (tokens.size >= 2 && tokens[0].toIntOrNull() != null && tokens[1].toIntOrNull() != null) return FontWeight.Normal
    val first = tokens.firstOrNull() ?: return FontWeight.Normal

    return when (first) {
        "normal" -> FontWeight.Normal
        "bold" -> FontWeight.Bold
        else -> first.toIntOrNull()?.let { FontWeight(it.coerceIn(1, 1000)) } ?: FontWeight.Normal
    }
}

private fun parseStyle(raw: String?): FontStyle = if (raw?.trim()?.lowercase() == "italic") FontStyle.Italic else FontStyle.Normal

private fun parseUnicodeRange(raw: String?): List<IntRange> {
    if (raw.isNullOrBlank()) return listOf(0..0xFFFF)
    val out = mutableListOf<IntRange>()

    for (part in raw.split(',')) {
        val seg = part.trim()
        if (!seg.startsWith("U+", ignoreCase = true)) continue
        val body = seg.removePrefix("U+").removePrefix("u+")

        when {
            body.contains('-') -> {
                val pieces = body.split('-', limit = 2)
                val l = pieces[0].toIntOrNull(16) ?: continue
                val h = pieces[1].toIntOrNull(16) ?: continue
                if (l <= h) out += l..h
            }

            body.contains('?') -> {
                val lo = body.replace('?', '0').toIntOrNull(16) ?: continue
                val hi = body.replace('?', 'F').toIntOrNull(16) ?: continue
                if (lo <= hi) out += lo..hi
            }

            else -> {
                val v = body.toIntOrNull(16) ?: continue
                out += v..v
            }
        }
    }

    return if (out.isEmpty()) listOf(0..0xFFFF) else out
}

private fun pickFontUrl(srcRaw: String?, baseUrl: String?): String? {
    if (srcRaw == null) return null

    val entryRegex = Regex(
        """url\(\s*['"]?([^'")]+)['"]?\s*\)\s*(?:format\(\s*['"]?([^'")]+)['"]?\s*\))?""",
        RegexOption.IGNORE_CASE,
    )

    for (m in entryRegex.findAll(srcRaw)) {
        val rawUrl = m.groupValues[1].trim()
        val format = m.groupValues.getOrNull(2)?.trim()?.lowercase().orEmpty()
        val url = resolveUrl(rawUrl, baseUrl)
        val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        val isTtfOrOtf = format == "truetype" || format == "opentype" || ext == "ttf" || ext == "otf"
        if (isTtfOrOtf) return url
    }

    return null
}

private fun resolveUrl(href: String, baseUrl: String?): String {
    if (baseUrl.isNullOrEmpty()) return href

    if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("//") || href.startsWith("data:")) return href

    val protoEnd = baseUrl.indexOf("://").let { if (it >= 0) it + 3 else 0 }
    val pathStart = baseUrl.indexOf('/', protoEnd).let { if (it >= 0) it else baseUrl.length }
    val schemeHost = baseUrl.substring(0, pathStart)

    return if (href.startsWith('/')) schemeHost + href else {
        val basePath = baseUrl.substringBeforeLast('/', schemeHost)
        "$basePath/$href"
    }
}

class LazyWebFontFamily internal constructor(private val decls: List<FontFaceDeclare>, private val scope: CoroutineScope) : LazyTextController {
    private val loadedFonts = mutableStateListOf<ComposeFont>()
    private val loadedDecls = mutableSetOf<FontFaceDeclare>()
    private val familyByUrl = mutableStateMapOf<String, FontFamily>()
    private val inFlightUrls = mutableSetOf<String>()
    private val failedUrls = mutableSetOf<String>()
    private val processedTexts = mutableSetOf<String>()

    override val revision: Int get() = loadedFonts.size

    override fun fontFamilyForCodepoint(cp: Int): FontFamily? {
        val decl = findCoveringDeclare(cp) ?: return null
        return familyByUrl[decl.url]
    }

    override fun requestText(text: String) {
        if (text.isEmpty() || !processedTexts.add(text) || decls.isEmpty()) return
        val toFetch = LinkedHashMap<String, FontFaceDeclare>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAtCompat(i)
            i += if (cp >= 0x10000) 2 else 1
            if (isCovered(cp)) continue
            val decl = findCoveringDeclare(cp) ?: continue
            if (decl.url in inFlightUrls || decl.url in failedUrls) continue
            if (decl.url !in toFetch) toFetch[decl.url] = decl
        }
        for ((url, decl) in toFetch) {
            inFlightUrls.add(url)
            scope.launch {
                val bytes = fetchBytesOrNull(url)
                inFlightUrls.remove(url)
                if (bytes == null) {
                    failedUrls.add(url)
                    return@launch
                }

                val font = runCatching { Font(identity = url, getData = { bytes }, weight = decl.weight, style = decl.style) }.getOrNull()

                if (font == null) {
                    failedUrls.add(url)
                    consoleWarn("[lazyfont] Skia rejected font bytes for $url")
                } else {
                    loadedFonts.add(font)
                    loadedDecls.add(decl)
                    familyByUrl[url] = FontFamily(font)
                }
            }
        }
    }

    private fun isCovered(cp: Int): Boolean {
        for (decl in loadedDecls) for (range in decl.ranges) if (cp in range) return true

        return false
    }

    private fun findCoveringDeclare(cp: Int): FontFaceDeclare? {
        for (decl in decls) for (range in decl.ranges) if (cp in range) return decl

        return null
    }
}

suspend fun loadLazyWebFontFamily(cssUrl: String, scope: CoroutineScope): LazyWebFontFamily? {
    val css = fetchTextOrNull(cssUrl) ?: return null

    val declares = parseCssFontFaces(css, baseUrl = cssUrl)
    if (declares.isEmpty()) return null else println("有啊一个字体")

    return LazyWebFontFamily(declares, scope)
}

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """
        function fetchTextJs(url, onOk, onErr) {
            fetch(url, { credentials: 'omit' })
                .then(function (r) {
                    if (!r.ok) { onErr('http ' + r.status); return null; }
                    return r.text();
                })
                .then(function (t) { if (t !== null) onOk(t); })
                .catch(function (e) { onErr(String(e)); });
        }
    """,
)
private external fun fetchTextJs(url: String, onOk: (String) -> Unit, onErr: (String) -> Unit)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """
        function fetchBytesAsBase64Js(url, onOk, onErr) {
            fetch(url, { credentials: 'omit' })
                .then(function (r) {
                    if (!r.ok) { onErr('http ' + r.status); return null; }
                    return r.arrayBuffer();
                })
                .then(function (buf) {
                    if (buf === null) return;
                    var bytes = new Uint8Array(buf);
                    var chunks = [];
                    var step = 0x8000;
                    for (var i = 0; i < bytes.length; i += step) {
                        chunks.push(String.fromCharCode.apply(null, bytes.subarray(i, i + step)));
                    }
                    onOk(btoa(chunks.join('')));
                })
                .catch(function (e) { onErr(String(e)); });
        }
    """,
)
private external fun fetchBytesAsBase64Js(url: String, onOk: (String) -> Unit, onErr: (String) -> Unit)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """
        function consoleWarnJs(message) {
            if (typeof console !== 'undefined' && console.warn) console.warn(message);
        }
    """,
)
external fun consoleWarn(message: String)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """
        function queryParamJs(name) {
            try {
                var p = new URLSearchParams(window.location.search);
                return p.get(name) || '';
            } catch (e) {
                return '';
            }
        }
    """,
)
external fun queryParam(name: String): String

internal suspend fun fetchTextOrNull(url: String): String? = suspendCancellableCoroutine { cont ->
    fetchTextJs(
        url,
        { ok -> if (cont.isActive) cont.resume(ok) },
        { err ->
            if (cont.isActive) {
                consoleWarn("[lazyfont] fetchText failed: $url -> $err")
                cont.resume(null)
            }
        },
    )
}

@OptIn(ExperimentalEncodingApi::class)
internal suspend fun fetchBytesOrNull(url: String): ByteArray? = suspendCancellableCoroutine { cont ->
    fetchBytesAsBase64Js(
        url,
        { b64 ->
            if (cont.isActive) cont.resume(runCatching { Base64.decode(b64) }.getOrNull())
        },
        { err ->
            if (cont.isActive) {
                consoleWarn("[lazyfont] fetchBytes failed: $url -> $err")
                cont.resume(null)
            }
        },
    )
}