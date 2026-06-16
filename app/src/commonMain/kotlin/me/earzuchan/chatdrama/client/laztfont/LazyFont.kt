package me.earzuchan.chatdrama.client.laztfont

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

// TIPS：从 Miuix 导出

interface LazyTextController {
    fun fontFamilyForCodepoint(cp: Int): FontFamily?
    fun requestText(text: String)

    val revision: Int
}

val LocalLazyTextController = staticCompositionLocalOf<LazyTextController?> { null }

@Composable
fun LazyText(
    text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, fontSize: TextUnit = TextUnit.Unspecified, fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null, fontFamily: FontFamily? = null, letterSpacing: TextUnit = TextUnit.Unspecified, textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null, lineHeight: TextUnit = TextUnit.Unspecified, overflow: TextOverflow = TextOverflow.Clip, softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE, minLines: Int = 1, onTextLayout: ((TextLayoutResult) -> Unit)? = null, style: TextStyle = MiuixTheme.textStyles.main
) {
    val controller = LocalLazyTextController.current

    if (controller == null) {
        // println("暂没有控制器")
        Text(
            text, modifier, color, fontSize = fontSize, fontStyle = fontStyle, fontWeight = fontWeight, fontFamily = fontFamily, letterSpacing = letterSpacing, textDecoration = textDecoration,
            textAlign = textAlign, lineHeight = lineHeight, overflow = overflow, softWrap = softWrap, maxLines = maxLines, minLines = minLines, onTextLayout = onTextLayout, style = style
        )
        return
    }

    // println("有控制器力")
    LaunchedEffect(text) { controller.requestText(text) }
    val annotated = remember(text, controller.revision) { buildSegmented(text, controller) }

    Text(
        annotated, modifier, color, fontSize = fontSize, fontStyle = fontStyle, fontWeight = fontWeight, fontFamily = fontFamily, letterSpacing = letterSpacing, textDecoration = textDecoration,
        textAlign = textAlign, lineHeight = lineHeight, overflow = overflow, softWrap = softWrap, maxLines = maxLines, minLines = minLines, onTextLayout = onTextLayout ?: {}, style = style
    )
}

private fun buildSegmented(text: String, controller: LazyTextController): AnnotatedString = buildAnnotatedString {
    if (text.isEmpty()) return@buildAnnotatedString
    var segStart = 0
    var segFamily: FontFamily? = null
    var firstChar = true
    var i = 0

    while (i < text.length) {
        val cp = text.codePointAtCompat(i)
        val charLen = if (cp >= 0x10000) 2 else 1
        val f = controller.fontFamilyForCodepoint(cp)

        if (firstChar) {
            segFamily = f
            firstChar = false
        } else if (f != segFamily) {
            emitSegment(text, segStart, i, segFamily)
            segStart = i
            segFamily = f
        }

        i += charLen
    }

    emitSegment(text, segStart, text.length, segFamily)
}

private fun AnnotatedString.Builder.emitSegment(text: String, start: Int, end: Int, family: FontFamily?) {
    if (start >= end) return

    val slice = text.substring(start, end)
    if (family != null) withStyle(SpanStyle(fontFamily = family)) { append(slice) } else append(slice)
}

internal fun String.codePointAtCompat(index: Int): Int {
    val high = this[index]

    if (high.isHighSurrogate() && index + 1 < length) {
        val low = this[index + 1]
        if (low.isLowSurrogate()) return ((high.code - 0xD800) shl 10) + (low.code - 0xDC00) + 0x10000
    }

    return high.code
}

@Composable
fun LazyTextField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, insideMargin: DpSize = TextFieldDefaults.InsideMargin, colors: TextFieldColors = TextFieldDefaults.textFieldColors(),
    cornerRadius: Dp = TextFieldDefaults.CornerRadius, label: String = "", useLabelAsPlaceholder: Boolean = false, enabled: Boolean = true, readOnly: Boolean = false, textStyle: TextStyle = MiuixTheme.textStyles.main,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default, leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null, singleLine: Boolean = false, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE, minLines: Int = 1, visualTransformation: VisualTransformation = VisualTransformation.None
) {
    val controller = LocalLazyTextController.current
    if (controller == null) {
        TextField(
            value, onValueChange, modifier, insideMargin, colors, cornerRadius, label, useLabelAsPlaceholder, enabled, readOnly,
            textStyle, keyboardOptions, keyboardActions, leadingIcon, trailingIcon, singleLine, maxLines, minLines, visualTransformation
        )
        return
    }

    LaunchedEffect(value) { controller.requestText(value) }
    val effectiveTransformation = remember(controller.revision, visualTransformation) { lazyFontVisualTransformation(controller, visualTransformation) }

    TextField(
        value, onValueChange, modifier, insideMargin, colors, cornerRadius, label, useLabelAsPlaceholder, enabled, readOnly, textStyle,
        keyboardOptions, keyboardActions, leadingIcon, trailingIcon, singleLine, maxLines, minLines, effectiveTransformation
    )
}

@Composable
fun LazyTextField(
    value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, modifier: Modifier = Modifier, insideMargin: DpSize = TextFieldDefaults.InsideMargin,
    colors: TextFieldColors = TextFieldDefaults.textFieldColors(), cornerRadius: Dp = TextFieldDefaults.CornerRadius, label: String = "", useLabelAsPlaceholder: Boolean = false,
    enabled: Boolean = true, readOnly: Boolean = false, textStyle: TextStyle = MiuixTheme.textStyles.main, keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default, leadingIcon: @Composable (() -> Unit)? = null, trailingIcon: @Composable (() -> Unit)? = null,
    singleLine: Boolean = false, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE, minLines: Int = 1, visualTransformation: VisualTransformation = VisualTransformation.None
) {
    val controller = LocalLazyTextController.current

    if (controller == null) {
        TextField(
            value, onValueChange, modifier, insideMargin, colors, cornerRadius, label, useLabelAsPlaceholder, enabled, readOnly,
            textStyle, keyboardOptions, keyboardActions, leadingIcon, trailingIcon, singleLine, maxLines, minLines, visualTransformation
        )
        return
    }

    LaunchedEffect(value.text) { controller.requestText(value.text) }
    val effectiveTransformation = remember(controller.revision, visualTransformation) { lazyFontVisualTransformation(controller, visualTransformation) }

    TextField(
        value, onValueChange, modifier, insideMargin, colors, cornerRadius, label, useLabelAsPlaceholder, enabled, readOnly, textStyle,
        keyboardOptions, keyboardActions, leadingIcon, trailingIcon, singleLine, maxLines, minLines, effectiveTransformation
    )
}

@Composable
fun LazyTextField(
    state: TextFieldState, modifier: Modifier = Modifier, insideMargin: DpSize = TextFieldDefaults.InsideMargin,
    colors: TextFieldColors = TextFieldDefaults.textFieldColors(), cornerRadius: Dp = TextFieldDefaults.CornerRadius,
    label: String = "", useLabelAsPlaceholder: Boolean = false, enabled: Boolean = true, readOnly: Boolean = false,
    inputTransformation: InputTransformation? = null, textStyle: TextStyle = MiuixTheme.textStyles.main,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, onKeyboardAction: KeyboardActionHandler? = null,
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.Default, leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null, outputTransformation: OutputTransformation? = null
) {
    val controller = LocalLazyTextController.current
    if (controller == null) {
        TextField(
            state, modifier, insideMargin, colors, cornerRadius, label, useLabelAsPlaceholder, enabled, readOnly, inputTransformation, textStyle,
            keyboardOptions, onKeyboardAction, lineLimits, leadingIcon, trailingIcon, outputTransformation = outputTransformation,
        )
        return
    }

    val fontFamilyResolver = LocalFontFamilyResolver.current
    val registeredRevision = remember { mutableIntStateOf(0) }
    val preloaded = remember { mutableSetOf<FontFamily>() }
    LaunchedEffect(state) { snapshotFlow { state.text.toString() }.collect { controller.requestText(it) } }
    LaunchedEffect(state, fontFamilyResolver) {
        snapshotFlow { controller.revision to state.text.toString() }.collect { (_, text) ->
            var registeredNew = false
            distinctLoadedFamilies(text, controller).forEach { family ->
                if (preloaded.add(family)) {
                    runCatching { fontFamilyResolver.preload(family) }
                    registeredNew = true
                }
            }
            if (registeredNew) registeredRevision.intValue++
        }
    }

    val effectiveTextStyle = remember(textStyle, registeredRevision.intValue) { if (registeredRevision.intValue % 2 == 0) textStyle else textStyle.copy(letterSpacing = textStyle.letterSpacing.reshapeNudge()) }
    TextField(
        state, modifier, insideMargin, colors, cornerRadius, label, useLabelAsPlaceholder, enabled, readOnly, inputTransformation,
        effectiveTextStyle, keyboardOptions, onKeyboardAction, lineLimits, leadingIcon, trailingIcon, outputTransformation = outputTransformation,
    )
}

@Composable
fun LazyInputField(
    query: String, onQueryChange: (String) -> Unit, onSearch: (String) -> Unit, expanded: Boolean, onExpandedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, label: String = "",
    enabled: Boolean = true, textStyle: TextStyle? = null, leadingIcon: @Composable (() -> Unit)? = null, trailingIcon: @Composable (() -> Unit)? = null, interactionSource: MutableInteractionSource? = null
) {
    val controller = LocalLazyTextController.current
    if (controller == null) {
        InputField(query, onQueryChange, onSearch, expanded, onExpandedChange, modifier, label, enabled, textStyle, leadingIcon, trailingIcon, interactionSource)
        return
    }

    val fontFamilyResolver = LocalFontFamilyResolver.current
    val registeredRevision = remember { mutableIntStateOf(0) }
    val preloaded = remember { mutableSetOf<FontFamily>() }
    LaunchedEffect(query) { controller.requestText(query) }
    LaunchedEffect(query, fontFamilyResolver) {
        snapshotFlow { controller.revision }.collect {
            var registeredNew = false
            distinctLoadedFamilies(query, controller).forEach { family ->
                if (preloaded.add(family)) {
                    runCatching { fontFamilyResolver.preload(family) }
                    registeredNew = true
                }
            }
            if (registeredNew) registeredRevision.intValue++
        }
    }
    val effectiveTextStyle = remember(textStyle, registeredRevision.intValue) { if (registeredRevision.intValue % 2 == 0) textStyle else (textStyle ?: TextStyle.Default).copy(letterSpacing = (textStyle?.letterSpacing ?: TextUnit.Unspecified).reshapeNudge()) }

    InputField(query, onQueryChange, onSearch, expanded, onExpandedChange, modifier, label, enabled, effectiveTextStyle, leadingIcon, trailingIcon, interactionSource)
}

private fun lazyFontVisualTransformation(controller: LazyTextController, inner: VisualTransformation) = VisualTransformation { original ->
    val transformed = inner.filter(original)
    val styled = applyLazyFontSpans(transformed.text, controller)
    TransformedText(styled, transformed.offsetMapping)
}

private fun TextUnit.reshapeNudge(): TextUnit = when {
    isUnspecified -> 0.01.sp
    type == TextUnitType.Em -> TextUnit(value + 0.0005f, TextUnitType.Em)
    else -> TextUnit(value + 0.01f, TextUnitType.Sp)
}

private fun distinctLoadedFamilies(text: String, controller: LazyTextController): List<FontFamily> {
    if (text.isEmpty()) return emptyList()
    val families = LinkedHashSet<FontFamily>()

    var i = 0
    while (i < text.length) {
        val cp = text.codePointAtCompat(i)
        i += if (cp >= 0x10000) 2 else 1
        controller.fontFamilyForCodepoint(cp)?.let { families.add(it) }
    }

    return families.toList()
}

private fun applyLazyFontSpans(text: AnnotatedString, controller: LazyTextController): AnnotatedString {
    val raw = text.text
    if (raw.isEmpty()) return text

    return buildAnnotatedString {
        append(text)
        var runStart = 0
        var runFamily: FontFamily? = null
        var firstChar = true
        var i = 0

        while (i < raw.length) {
            val cp = raw.codePointAtCompat(i)
            val charLen = if (cp >= 0x10000) 2 else 1
            val family = controller.fontFamilyForCodepoint(cp)

            if (firstChar) {
                runFamily = family
                firstChar = false
            } else if (family !== runFamily) {
                if (runFamily != null) addStyle(SpanStyle(fontFamily = runFamily), runStart, i)
                runStart = i
                runFamily = family
            }

            i += charLen
        }
        if (!firstChar && runFamily != null) addStyle(SpanStyle(fontFamily = runFamily), runStart, raw.length)
    }
}