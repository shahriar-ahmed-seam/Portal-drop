package app.portaldrop

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import org.commonmark.node.BlockQuote
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal class MessageMarkup(private val accent: Int, private val codeBackground: Int) {
    private val parser = Parser.builder().build()

    fun render(source: String): CharSequence {
        if (isJson(source)) {
            return SpannableStringBuilder().apply { code { append(source) } }
        }
        if (source.none { it in MARKDOWN_CHARACTERS }) return source
        val output = SpannableStringBuilder()
        output.renderChildren(parser.parse(source))
        while (output.endsWith('\n')) output.delete(output.length - 1, output.length)
        return output.takeUnless { it.isBlank() } ?: source
    }

    private fun SpannableStringBuilder.renderChildren(node: Node) {
        var child = node.firstChild
        while (child != null) {
            render(child)
            child = child.next
        }
    }

    private fun SpannableStringBuilder.render(node: Node) {
        when (node) {
            is Text -> append(node.literal)
            is SoftLineBreak, is HardLineBreak -> append('\n')
            is Code -> code { append(node.literal) }
            is FencedCodeBlock -> {
                val language = node.info?.substringBefore(' ')?.trim().orEmpty()
                blockCode(node.literal, language)
            }
            is IndentedCodeBlock -> blockCode(node.literal, "")
            is StrongEmphasis -> styles(StyleSpan(Typeface.BOLD)) { renderChildren(node) }
            is Emphasis -> styles(StyleSpan(Typeface.ITALIC)) { renderChildren(node) }
            is Heading -> {
                styles(StyleSpan(Typeface.BOLD), RelativeSizeSpan(if (node.level <= 2) 1.25f else 1.1f)) {
                    renderChildren(node)
                }
                append("\n\n")
            }
            is Link -> styles(UnderlineSpan(), ForegroundColorSpan(accent)) { renderChildren(node) }
            is BlockQuote -> {
                styles(ForegroundColorSpan(accent)) {
                    append("│ ")
                    renderChildren(node)
                }
            }
            is Paragraph -> {
                renderChildren(node)
                if (node.parent is ListItem) append('\n') else append("\n\n")
            }
            is OrderedList -> {
                var counter = node.markerStartNumber ?: 1
                var item = node.firstChild
                while (item != null) {
                    append(counter.toString()).append(". ")
                    renderChildren(item)
                    counter++
                    item = item.next
                }
            }
            is ListItem -> {
                append("• ")
                renderChildren(node)
            }
            is ThematicBreak -> append("──────\n\n")
            is HtmlInline -> append(node.literal)
            is HtmlBlock -> append(node.literal).append("\n\n")
            else -> renderChildren(node)
        }
    }

    private fun SpannableStringBuilder.blockCode(literal: String, language: String) {
        if (language.isNotEmpty()) {
            styles(ForegroundColorSpan(accent), RelativeSizeSpan(0.8f)) { append(language) }
            append('\n')
        }
        code { append(literal.removeSuffix("\n")) }
        append("\n\n")
    }

    private inline fun SpannableStringBuilder.code(appendText: () -> Unit) =
        styles(TypefaceSpan("monospace"), BackgroundColorSpan(codeBackground), appendText = appendText)

    private inline fun SpannableStringBuilder.styles(vararg spans: Any, appendText: () -> Unit) {
        val start = length
        appendText()
        if (start < length) {
            spans.forEach { setSpan(it, start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
    }

    private fun isJson(value: String): Boolean {
        val trimmed = value.trim()
        if (!(trimmed.startsWith("{") && trimmed.endsWith("}") ||
                trimmed.startsWith("[") && trimmed.endsWith("]"))) return false
        return try {
            val tokener = JSONTokener(trimmed)
            val parsed = tokener.nextValue()
            (parsed is JSONObject || parsed is JSONArray) && tokener.nextClean() == '\u0000'
        } catch (_: org.json.JSONException) {
            false
        }
    }

    private companion object {
        val MARKDOWN_CHARACTERS = setOf('\n', '#', '*', '_', '`', '~', '[', '<', '>', '-')
    }
}
