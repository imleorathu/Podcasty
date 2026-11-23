/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.podcasty.app.R
import com.podcasty.app.core.libraryAlphabeticalIndexLabel
import com.podcasty.app.ui.theme.LocalPodcastyColors

internal data class AlphabeticalIndexEntry(val label: String, val itemIndex: Int)

internal fun alphabeticalIndexEntries(
    values: List<String>,
    itemOffset: Int,
    itemsPerLazyItem: Int = 1,
): List<AlphabeticalIndexEntry> = values.mapIndexedNotNull { index, value ->
    libraryAlphabeticalIndexLabel(value)?.let { label ->
        AlphabeticalIndexEntry(label, itemOffset + index / itemsPerLazyItem)
    }
}.distinctBy(AlphabeticalIndexEntry::label)

@Composable
internal fun AlphabeticalIndex(
    entries: List<AlphabeticalIndexEntry>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return

    var size by remember { mutableStateOf(IntSize.Zero) }
    var labelsTop by remember { mutableFloatStateOf(0f) }
    var labelsHeight by remember { mutableIntStateOf(0) }
    var selectedEntry by remember(entries) { mutableStateOf<Int?>(null) }
    val colors = LocalPodcastyColors.current
    val hapticFeedback = LocalHapticFeedback.current
    val indexDescription = "${stringResource(R.string.alphabetical_index)}: ${entries.joinToString { it.label }}"
    LaunchedEffect(selectedEntry) { selectedEntry?.let { listState.scrollToItem(it) } }

    fun selectAt(y: Float, haptic: Boolean = false) {
        if (size.height == 0 || labelsHeight <= 0) return
        val entry = entries[((y - labelsTop) / labelsHeight * entries.size).toInt().coerceIn(0, entries.lastIndex)].itemIndex
        if (entry == selectedEntry) return
        selectedEntry = entry
        if (haptic) hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
    }

    Box(
        modifier = modifier
            .width(18.dp)
            .onSizeChanged { size = it }
            .semantics { contentDescription = indexDescription }
            .pointerInput(entries) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    selectAt(down.position.y)
                    verticalDrag(down.id) { change ->
                        selectAt(change.position.y, haptic = true)
                        change.consume()
                    }
                }
            },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(18.dp)
                .padding(vertical = 2.dp)
                .onGloballyPositioned { coordinates ->
                    labelsTop = coordinates.positionInParent().y
                    labelsHeight = coordinates.size.height
                },
        ) {
            entries.forEach { entry ->
                Text(
                    text = entry.label,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
