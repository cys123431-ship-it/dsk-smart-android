package com.dskapp.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Converts inline markdown to an [AnnotatedString]: **bold**, *italic*, `code`,
 * ~~strike~~ and [links](url) (links are shown underlined but not clickable, since the
 * model's URLs are often placeholders).
 *
 * Implemented as a small scanner rather than regex-replace so nested emphasis and the
 * "no matches inside code spans" rule behave predictably.
 */
internal fun inlineAnnotated(raw: String): AnnotatedString = buildAnnotatedString {
    var index = 0

    while (index < raw.length) {
        val rest = raw.substring(index)
        when {
            // Inline code first: its content is literal.
            rest.startsWith("`") -> {
                val end = raw.indexOf('`', index + 1)
                if (end > index) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                        append(raw.substring(index + 1, end))
                    }
                    index = end + 1
                } else {
                    append('`')
                    index++
                }
            }

            rest.startsWith("**") || rest.startsWith("__") -> {
                val marker = rest.substring(0, 2)
                val end = raw.indexOf(marker, index + 2)
                if (end > index) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(raw.substring(index + 2, end))
                    }
                    index = end + 2
                } else {
                    append(raw[index])
                    index++
                }
            }

            rest.startsWith("~~") -> {
                val end = raw.indexOf("~~", index + 2)
                if (end > index) {
                    withStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)) {
                        append(raw.substring(index + 2, end))
                    }
                    index = end + 2
                } else {
                    append(raw[index])
                    index++
                }
            }

            rest.startsWith("*") || rest.startsWith("_") -> {
                val marker = rest[0]
                val end = raw.indexOf(marker, index + 1)
                if (end > index) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(raw.substring(index + 1, end))
                    }
                    index = end + 1
                } else {
                    append(raw[index])
                    index++
                }
            }

            rest.startsWith("[") -> {
                val closeBracket = raw.indexOf(']', index)
                val openParen = if (closeBracket > 0) raw.indexOf('(', closeBracket) else -1
                val closeParen = if (openParen > 0) raw.indexOf(')', openParen) else -1
                if (closeBracket > 0 && openParen == closeBracket + 1 && closeParen > openParen) {
                    withStyle(
                        SpanStyle(
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                            fontWeight = FontWeight.Medium
                        )
                    ) {
                        append(raw.substring(index + 1, closeBracket))
                    }
                    index = closeParen + 1
                } else {
                    append(raw[index])
                    index++
                }
            }

            else -> {
                append(raw[index])
                index++
            }
        }
    }
}
