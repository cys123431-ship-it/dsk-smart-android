package com.dskapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Fixed height for the settings model box, so it can live inside the scrolling screen. */
val MODEL_LIST_BOX_HEIGHT = 232.dp

/** Approximate height of one row; only used to place the initial scroll position. */
private val MODEL_ROW_HEIGHT = 44.dp

/** List key of the "use the default model" row; model ids never contain this. */
private const val DEFAULT_ROW_KEY = "default"

/**
 * The whole model list in a scrollable box, every entry selectable.
 *
 * Used by Settings. [defaultLabel] adds a first entry meaning "no override, use the default
 * model". The list opens scrolled to the current choice, so a model 90 rows down still shows
 * what is selected when the box appears.
 */
@Composable
fun ScrollableModelList(
    models: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = MODEL_LIST_BOX_HEIGHT,
    defaultLabel: String? = null,
    labelFor: (String) -> String = { it }
) {
    val selectedIndex = remember(models, selected, defaultLabel) {
        val modelIndex = selected?.let { current -> models.indexOf(current) } ?: -1
        when {
            modelIndex >= 0 -> if (defaultLabel != null) modelIndex + 1 else modelIndex
            defaultLabel != null -> 0
            else -> -1
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(selectedIndex, height) {
        if (selectedIndex > 0) {
            // A little context above the choice instead of pinning it to the top edge.
            val visibleRows = (height / MODEL_ROW_HEIGHT).toInt().coerceAtLeast(1)
            listState.scrollToItem((selectedIndex - visibleRows / 2).coerceAtLeast(0))
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = height),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (defaultLabel != null) {
            item(key = DEFAULT_ROW_KEY) {
                ModelRow(
                    label = defaultLabel,
                    isSelected = selected == null,
                    onClick = { onSelect(null) }
                )
            }
        }
        items(models, key = { it }) { model ->
            ModelRow(
                label = labelFor(model),
                isSelected = model == selected,
                onClick = { onSelect(model) }
            )
        }
    }
}

/**
 * The same rows for a DropdownMenu. The caller wraps these in a height limited menu
 * (`Modifier.heightIn(max = ...)`), otherwise 100+ models grow the menu past the screen.
 */
@Composable
fun ModelMenuItems(
    models: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    defaultLabel: String? = null,
    labelFor: (String) -> String = { it }
) {
    if (defaultLabel != null) {
        ModelRow(
            label = defaultLabel,
            isSelected = selected == null,
            onClick = { onSelect(null) }
        )
        HorizontalDivider()
    }
    models.forEach { model ->
        ModelRow(
            label = labelFor(model),
            isSelected = model == selected,
            onClick = { onSelect(model) }
        )
    }
}

@Composable
private fun ModelRow(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (isSelected) {
                Spacer(Modifier.size(8.dp))
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "선택됨",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
