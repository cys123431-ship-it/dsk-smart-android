package com.dskapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dskapp.data.export.MarkdownParser
import com.dskapp.data.export.MdBlock

/**
 * Renders the markdown the model returns.
 *
 * Deliberately lightweight: blocks are laid out with plain Compose text components instead
 * of a WebView, so rendering is instant, selectable and works offline. Inline emphasis is
 * handled by [inlineAnnotated] (bold, italic, inline code).
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false
) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> HeadingBlock(block)
                is MdBlock.Paragraph -> InlineText(block.text)
                is MdBlock.Quote -> QuoteBlock(block.text)
                is MdBlock.Bullet -> BulletBlock(block.items, block.ordered)
                is MdBlock.Code -> CodeBlock(block.code, block.language)
                is MdBlock.Table -> TableBlock(block)
                MdBlock.Rule -> HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }

        if (isError) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "오류가 발생했습니다.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun HeadingBlock(block: MdBlock.Heading) {
    val style = when (block.level) {
        1 -> MaterialTheme.typography.headlineSmall
        2 -> MaterialTheme.typography.titleLarge
        else -> MaterialTheme.typography.titleMedium
    }
    Text(
        text = MarkdownParser.stripInline(block.text),
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = if (block.level == 1) 10.dp else 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun InlineText(raw: String) {
    SelectionContainer {
        Text(
            text = inlineAnnotated(raw),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 3.dp)
        )
    }
}

@Composable
private fun QuoteBlock(raw: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier
                .width(3.dp)
                .height(36.dp)
        ) {}
        Spacer(Modifier.width(10.dp))
        Text(
            text = MarkdownParser.stripInline(raw),
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
@Composable
private fun BulletBlock(items: List<String>, ordered: Boolean) {
    Column(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        items.forEachIndexed { index, item ->
            Row {
                Text(
                    text = if (ordered) "${index + 1}." else "•",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(if (ordered) 24.dp else 16.dp)
                )
                Text(
                    text = inlineAnnotated(item),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}



/** Fenced code with a copy button, matching ChatGPT's code block affordance. */
@Composable
private fun CodeBlock(code: String, language: String) {
    val clipboard = LocalClipboardManager.current
    // Follows the app theme (also the manual dark mode), not only the system setting.
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val highlighted = remember(code, language, dark) { SyntaxHighlighter.highlight(code, language, dark) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = language.ifBlank { "code" }.lowercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(code)) },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "코드 복사",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 10.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = highlighted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/** Horizontally scrollable table; columns get a fixed width like a real data grid. */
@Composable
private fun TableBlock(block: MdBlock.Table) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 6.dp)
    ) {
        Column {
            Row(modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
                block.header.forEach { cell ->
                    Text(
                        text = MarkdownParser.stripInline(cell),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .width(140.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }
            block.rows.forEach { row ->
                Row {
                    row.forEach { cell ->
                        Text(
                            text = MarkdownParser.stripInline(cell),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .width(140.dp)
                                .padding(horizontal = 10.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }
    }
}

