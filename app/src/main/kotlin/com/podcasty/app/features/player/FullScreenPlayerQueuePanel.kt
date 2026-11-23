/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.podcasty.app.R
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.PlaybackQueueSnapshot
import com.podcasty.app.domain.RepeatMode
import com.podcasty.app.ui.components.EpisodeContextBottomSheetRequest
import com.podcasty.app.ui.components.EpisodeContextMenu
import com.podcasty.app.ui.components.EpisodeContextMenuActions
import com.podcasty.app.ui.components.MaterialSymbol
import com.podcasty.app.ui.components.MaterialSymbols
import com.podcasty.app.ui.components.PodcastyPlayingIndicator
import com.podcasty.app.ui.components.rememberArtworkThumbnail
import com.podcasty.app.ui.components.sliderFilledTrackColor
import com.podcasty.app.ui.theme.LocalPodcastyColors
import com.podcasty.app.ui.theme.PodcastyColors

private val QueueDragHandleWidth = 72.dp
private val QueuePanelRowHorizontalPadding = 20.dp
private const val QueuePanelHeaderTestTag = "full_screen_queue_panel_header"
private const val QueuePanelRowTestTag = "full_screen_queue_row"

@Composable
internal fun FullScreenQueuePanel(
    queue: PlaybackQueueSnapshot,
    episodes: List<Episode>,
    currentEpisodeId: String,
    isPlaying: Boolean,
    onEpisodeSelected: (String) -> Unit,
    onEpisodeRemoved: (String) -> Unit = {},
    onEpisodePlayNext: (String) -> Unit = {},
    onReorder: (List<String>) -> Unit,
    onReorderDragStateChange: (Boolean) -> Unit = {},
    onShuffleChange: (Boolean) -> Unit,
    onRepeatModeChange: (RepeatMode) -> Unit,
    onOpenQueueManager: () -> Unit = {},
    onSaveToggle: (String, Boolean) -> Unit = { _, _ -> },
    onDownloadToggle: (String, Boolean) -> Unit = { _, _ -> },
    onEpisodeGoToPodcast: (String) -> Unit = {},
    onEpisodeGoToHost: (String) -> Unit = {},
    onEpisodeContextBottomSheet: (EpisodeContextBottomSheetRequest) -> Unit = {},
    onCloseFullscreenThen: ((() -> Unit) -> Unit) = { action -> action() },
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPodcastyColors.current
    val listState = rememberLazyListState()
    val episodesById = remember(episodes) { episodes.associateBy(Episode::id) }
    var orderedIds by remember(queue.activeEpisodeIds) { mutableStateOf(queue.activeEpisodeIds) }
    val latestOrderedIds = rememberUpdatedState(orderedIds)
    val latestOnReorder = rememberUpdatedState(onReorder)
    var contextEpisodeId by remember { mutableStateOf<String?>(null) }
    val haptics = LocalHapticFeedback.current
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        if (from.index != to.index) {
            orderedIds = moveQueueEpisode(orderedIds, from.index, to.index)
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        }
    }
    val repeatLabel = stringResource(
        when (queue.repeatMode) {
            RepeatMode.Off -> R.string.player_repeat_off
            RepeatMode.All -> R.string.player_repeat_all
            RepeatMode.One -> R.string.player_repeat_one
        },
    )

    var initiallyPositioned by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (initiallyPositioned) return@LaunchedEffect
        initiallyPositioned = true
        val targetIndex = orderedIds.indexOf(currentEpisodeId)
        if (targetIndex >= 0) listState.scrollToItem(targetIndex)
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = QueuePanelRowHorizontalPadding)
                .semantics { testTag = QueuePanelHeaderTestTag },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = queue.activeQueueName.ifBlank { stringResource(R.string.player_queue) },
                color = colors.onPrimary,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.onPrimary.copy(alpha = 0.14f))
                    .clickable(onClick = onOpenQueueManager)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    text = "Queues",
                    color = colors.onPrimary,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }
            Spacer(Modifier.weight(1f))
            PlayerModeButton(
                symbol = MaterialSymbols.Shuffle,
                label = stringResource(if (queue.shuffle) R.string.player_shuffle_on else R.string.player_shuffle),
                active = queue.shuffle,
            ) {
                onShuffleChange(!queue.shuffle)
            }
            Spacer(Modifier.width(8.dp))
            PlayerModeButton(
                symbol = if (queue.repeatMode == RepeatMode.One) MaterialSymbols.RepeatOne else MaterialSymbols.Repeat,
                label = repeatLabel,
                active = queue.repeatMode != RepeatMode.Off,
            ) {
                onRepeatModeChange(queue.repeatMode.next())
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().semantics { testTag = "full_screen_queue_list" },
        ) {
            items(orderedIds, key = { it }, contentType = { "queue-episode" }) { episodeId ->
                ReorderableItem(reorderableState, key = episodeId) { isDragging ->
                    val episode = episodesById[episodeId]
                    val dragHandleMod = queueDragHandleModifier(
                        haptics = haptics,
                        onReorderDragStateChange = onReorderDragStateChange,
                        onReorder = { commitQueueReorder(latestOrderedIds, latestOnReorder) },
                    )
                    if (episode == null) {
                        FullScreenQueueEpisodeRow(
                            episode = null,
                            episodeId = episodeId,
                            isCurrent = episodeId == currentEpisodeId,
                            isPlaying = isPlaying,
                            isDragged = isDragging,
                            modifier = Modifier,
                            dragHandleModifier = dragHandleMod,
                            onClick = { onEpisodeSelected(episodeId) },
                        )
                    } else {
                        EpisodeContextMenu(
                            episode = episode,
                            expanded = contextEpisodeId == episodeId,
                            onDismiss = { contextEpisodeId = null },
                            actions = EpisodeContextMenuActions(
                                removeFromQueue = episodeId != currentEpisodeId,
                                addToQueue = false,
                            ),
                            hazeState = hazeState,
                            playbackQueue = queue,
                            onRemoveFromQueue = { onEpisodeRemoved(it.id) },
                            onPlayNext = { onEpisodePlayNext(it.id) },
                            onSaveToggle = { ep, saved -> onSaveToggle(ep.id, saved) },
                            onDownloadToggle = { ep, downloaded -> onDownloadToggle(ep.id, downloaded) },
                            onGoToPodcast = { onEpisodeGoToPodcast(it.podcastId) },
                            onGoToHost = { onEpisodeGoToHost(it.hostId) },
                            onBottomSheetRequested = { request ->
                                onCloseFullscreenThen { onEpisodeContextBottomSheet(request) }
                            },
                            onCloseFullscreenThen = onCloseFullscreenThen,
                        ) {
                            FullScreenQueueEpisodeRow(
                                episode = episode,
                                episodeId = episodeId,
                                isCurrent = episodeId == currentEpisodeId,
                                isPlaying = isPlaying,
                                isDragged = isDragging,
                                modifier = Modifier,
                                dragHandleModifier = dragHandleMod,
                                onClick = { onEpisodeSelected(episodeId) },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                    contextEpisodeId = episodeId
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun ReorderableCollectionItemScope.queueDragHandleModifier(
    haptics: HapticFeedback,
    onReorderDragStateChange: (Boolean) -> Unit,
    onReorder: () -> Unit,
) = Modifier.longPressDraggableHandle(
    onDragStarted = {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        onReorderDragStateChange(true)
    },
    onDragStopped = {
        onReorderDragStateChange(false)
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        onReorder()
    },
)

@Composable
internal fun PlayerModeButton(symbol: String, label: String, active: Boolean, onClick: () -> Unit) {
    val colors = LocalPodcastyColors.current
    val backgroundColor by animateColorAsState(
        targetValue = if (active) {
            sliderFilledTrackColor(colors, isInteracting = false)
        } else {
            fullScreenSecondaryControlBackground(colors)
        },
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "queue-mode-background",
    )
    val iconColor by animateColorAsState(
        if (active) colors.playerBackdrop.copy(alpha = 0.72f) else colors.onPrimary,
        tween(220, easing = FastOutSlowInEasing),
        label = "queue-mode-icon",
    )
    Box(
        Modifier
            .width(72.dp)
            .height(48.dp)
            .semantics { contentDescription = label; selected = active }
            .clickable(
                onClick = onClick,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(72.dp)
                .height(36.dp)
                .clip(CircleShape)
                .background(backgroundColor)
                .border(1.dp, colors.borderGlass, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            MaterialSymbol(symbol = symbol, contentDescription = null, tint = iconColor, size = 22.dp)
        }
    }
}

@Composable
private fun FullScreenQueueEpisodeRow(
    episode: Episode?,
    episodeId: String,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isDragged: Boolean,
    modifier: Modifier,
    dragHandleModifier: Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = LocalPodcastyColors.current
    val artwork = rememberArtworkThumbnail(episode?.artworkAsset)
    val title = episode?.title ?: stringResource(R.string.player_queue_unknown_episode)
    val subtitle = episode?.podcastTitle.orEmpty()
    val dragHandleLabel = stringResource(R.string.player_queue_drag_handle)
    val currentLabel = stringResource(R.string.player_queue_current)
    val dragHandleWidthPx = with(LocalDensity.current) { QueueDragHandleWidth.toPx() }
    val latestOnClick = rememberUpdatedState(onClick)
    val latestOnLongClick = rememberUpdatedState(onLongClick)
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val hoverBackground by animateColorAsState(
        targetValue = if (isHovered) {
            fullScreenSecondaryControlBackground(colors)
        } else {
            colors.sliderInactive.copy(alpha = 0f)
        },
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "queue-row-hover-background",
    )

    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .then(
                if (isDragged) {
                    Modifier
                        .background(queueDraggedRowBackground(colors))
                        .border(1.dp, colors.borderGlass)
                } else {
                    Modifier.background(hoverBackground)
                },
            )
            .semantics { if (isCurrent) contentDescription = currentLabel }
            .semantics { testTag = "$QueuePanelRowTestTag-$episodeId" }
            .hoverable(interactionSource)
            .pointerInput(dragHandleWidthPx) {
                detectTapGestures(
                    onTap = { latestOnClick.value() },
                    onLongPress = { position ->
                        if (position.x < size.width - dragHandleWidthPx) {
                            latestOnLongClick.value?.invoke()
                        }
                    },
                )
            }
            .semantics {
                role = Role.Button
                onClick { onClick(); true }
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = QueuePanelRowHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.glassElevated)
                    .border(1.dp, colors.borderGlass, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (artwork != null) {
                    Image(artwork, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    MaterialSymbol(MaterialSymbols.Podcasts, tint = colors.textMuted, size = 20.dp)
                }
                if (isCurrent) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(colors.playerBackdrop.copy(alpha = 0.64f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        PodcastyPlayingIndicator(isPlaying = isPlaying)
                    }
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = title,
                    color = colors.onPrimary,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    color = colors.foregroundSubtle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier
                    .size(QueueDragHandleWidth)
                    .then(dragHandleModifier)
                    .semantics { contentDescription = dragHandleLabel },
                contentAlignment = Alignment.CenterEnd,
            ) {
                MaterialSymbol(MaterialSymbols.Menu, tint = colors.sliderInactive, size = 24.dp)
            }
        }
    }
}

private fun queueDraggedRowBackground(colors: PodcastyColors) =
    colors.sliderInactive.copy(alpha = 0.20f)

internal fun moveQueueEpisode(episodeIds: List<String>, fromIndex: Int, toIndex: Int): List<String> =
    episodeIds.toMutableList().apply {
        if (fromIndex in indices && toIndex in indices && fromIndex != toIndex) {
            add(toIndex, removeAt(fromIndex))
        }
    }

internal fun commitQueueReorder(
    latestOrderedIds: State<List<String>>,
    latestOnReorder: State<(List<String>) -> Unit>,
) = latestOnReorder.value(latestOrderedIds.value)
