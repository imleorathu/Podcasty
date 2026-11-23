/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.podcasty.app.ui.theme.LocalPodcastyColors

private val LibraryTextFilterHeight = 62.dp

@Composable
fun <T> LibraryVirtualList(
    items: List<T>,
    isLoaded: Boolean = true,
    key: (T) -> Any,
    contentType: Any,
    listState: LazyListState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    filterKey: String? = null,
    filterActive: Boolean = false,
    filterContent: (@Composable (showPlaceholderAndLeadingSymbol: Boolean) -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    alphabeticalIndexKey: ((T) -> String)? = null,
    alphabeticalIndexItemsPerLazyItem: Int = 1,
    emptyContent: @Composable () -> Unit,
    customItemsContent: (LazyListScope.() -> Unit)? = null,
    trailingItemsContent: (LazyListScope.() -> Unit)? = null,
    itemContent: @Composable (T) -> Unit,
) {
    var filterVisible by rememberSaveable(filterKey) { mutableStateOf(false) }
    var revealDistancePx by remember(filterKey) { mutableFloatStateOf(0f) }
    var hideDistancePx by remember(filterKey) { mutableFloatStateOf(0f) }
    val filterThresholdPx = with(LocalDensity.current) { 48.dp.toPx() }
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()

    LaunchedEffect(filterActive) {
        if (filterActive) {
            filterVisible = true
            hideDistancePx = 0f
        }
    }
    LaunchedEffect(isUserDragging, filterActive) {
        if (!isUserDragging) {
            if (!filterVisible && !filterActive) revealDistancePx = 0f
            if (filterVisible && !filterActive) hideDistancePx = 0f
        }
    }

    val filterScrollConnection = remember(listState, filterActive, filterContent, filterThresholdPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || filterContent == null) return Offset.Zero
                if (!filterVisible && listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 && available.y > 0f) {
                    revealDistancePx += available.y
                    if (revealDistancePx >= filterThresholdPx) {
                        filterVisible = true
                        hideDistancePx = 0f
                        revealDistancePx = 0f
                    }
                    return Offset(0f, available.y)
                } else if (!filterVisible && revealDistancePx > 0f && available.y < 0f) {
                    val consumedY = maxOf(available.y, -revealDistancePx)
                    revealDistancePx += consumedY
                    return Offset(0f, consumedY)
                } else if (filterVisible && !filterActive && available.y < 0f) {
                    hideDistancePx = (hideDistancePx - available.y).coerceAtMost(filterThresholdPx)
                    if (hideDistancePx >= filterThresholdPx) {
                        filterVisible = false
                    }
                    return Offset(0f, available.y)
                } else if (filterVisible && !filterActive && hideDistancePx > 0f && available.y > 0f) {
                    val consumedY = minOf(available.y, hideDistancePx)
                    hideDistancePx -= consumedY
                    return Offset(0f, consumedY)
                } else if (available.y < 0f) {
                    revealDistancePx = 0f
                }
                return Offset.Zero
            }
        }
    }

    val colors = LocalPodcastyColors.current
    val alphabeticalIndexEntries = remember(items, alphabeticalIndexKey, filterContent, leadingContent, alphabeticalIndexItemsPerLazyItem) {
        alphabeticalIndexKey?.let { indexKey ->
            alphabeticalIndexEntries(
                values = items.map(indexKey),
                itemOffset = (if (filterContent == null) 0 else 1) + (if (leadingContent == null) 0 else 1),
                itemsPerLazyItem = alphabeticalIndexItemsPerLazyItem,
            )
        }.orEmpty()
    }

    if (filterContent != null) {
        Box(modifier = modifier.fillMaxSize().nestedScroll(filterScrollConnection)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = contentPadding,
            ) {
                item(key = "library-list-text-filter", contentType = "library-list-text-filter") {
                    LibraryTextFilterSlot(
                        visible = filterVisible,
                        dismissalProgressPx = hideDistancePx,
                        previewHeightPx = revealDistancePx,
                        isUserDragging = isUserDragging,
                        content = filterContent,
                    )
                }
                if (items.isEmpty() && isLoaded) {
                    item(key = "library-list-empty-state", contentType = "library-list-empty-state") {
                        Box(modifier = Modifier.fillMaxWidth().height(320.dp)) { emptyContent() }
                    }
                } else {
                    if (leadingContent != null) {
                        item(contentType = "library-list-leading-content") { leadingContent() }
                    }
                    if (customItemsContent != null) {
                        customItemsContent()
                    } else {
                        itemsIndexed(
                            items = items,
                            key = { _, item -> key(item) },
                            contentType = { _, _ -> contentType },
                        ) { index, item ->
                            itemContent(item)
                            if (index < items.lastIndex) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp)
                                        .height(1.dp)
                                        .background(colors.borderGlass),
                                )
                            }
                        }
                    }
                    trailingItemsContent?.invoke(this)
                }
            }
            AlphabeticalIndex(
                entries = alphabeticalIndexEntries,
                listState = listState,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
        return
    }

    if (items.isEmpty()) {
        if (isLoaded) Box(modifier = modifier.fillMaxSize()) { emptyContent() }
        return
    }

    Box(modifier = modifier.fillMaxSize().nestedScroll(filterScrollConnection)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = contentPadding,
        ) {
            if (leadingContent != null) {
                item(contentType = "library-list-leading-content") { leadingContent() }
            }
            if (customItemsContent != null) {
                customItemsContent()
            } else {
                itemsIndexed(
                    items = items,
                    key = { _, item -> key(item) },
                    contentType = { _, _ -> contentType },
                ) { index, item ->
                    itemContent(item)
                    if (index < items.lastIndex) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                                .height(1.dp)
                                .background(colors.borderGlass),
                        )
                    }
                }
            }
        }
        AlphabeticalIndex(
            entries = alphabeticalIndexEntries,
            listState = listState,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

@Composable
private fun LibraryTextFilterSlot(
    visible: Boolean,
    dismissalProgressPx: Float,
    previewHeightPx: Float,
    isUserDragging: Boolean,
    content: @Composable (showPlaceholderAndLeadingSymbol: Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val previewHeight = with(density) { previewHeightPx.coerceIn(0f, LibraryTextFilterHeight.toPx()).toDp() }
    val dismissalProgress = with(density) { dismissalProgressPx.coerceIn(0f, LibraryTextFilterHeight.toPx()).toDp() }
    val targetHeight = if (visible) (LibraryTextFilterHeight - dismissalProgress).coerceAtLeast(0.dp) else previewHeight
    val usesSnap = isUserDragging && (previewHeightPx > 0f || (visible && dismissalProgressPx > 0f))
    val displayedHeight by animateDpAsState(
        targetValue = targetHeight,
        animationSpec = if (usesSnap) snap() else tween(180),
        label = "library-text-filter-height",
    )
    val isFullyOpen = visible && dismissalProgressPx == 0f && displayedHeight == LibraryTextFilterHeight
    Box(
        modifier = Modifier.fillMaxWidth().height(displayedHeight).clipToBounds(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) { content(isFullyOpen) }
    }
}
