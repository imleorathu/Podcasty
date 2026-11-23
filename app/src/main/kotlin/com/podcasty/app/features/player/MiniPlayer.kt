/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.podcasty.app.R
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.PlaybackQueueSnapshot
import com.podcasty.app.domain.PlaybackState
import com.podcasty.app.domain.durationMsOrZero
import com.podcasty.app.domain.itemOrNull
import com.podcasty.app.domain.positionMsOrZero
import com.podcasty.app.ui.components.AnimatedPlayPauseSymbol
import com.podcasty.app.ui.components.AnimatedSkipSymbol
import com.podcasty.app.ui.components.EpisodeContextBottomSheetRequest
import com.podcasty.app.ui.components.EpisodeContextMenu
import com.podcasty.app.ui.components.MaterialSymbol
import com.podcasty.app.ui.components.MaterialSymbols
import com.podcasty.app.ui.components.PodcastyMarqueeText
import com.podcasty.app.ui.components.liquidGlassBackground
import com.podcasty.app.ui.components.rememberArtworkThumbnail
import com.podcasty.app.ui.theme.LocalPodcastyColors
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

val MiniPlayerHeight = 56.dp
val MiniPlayerNavigationGap = 8.dp
private val MiniPlayerPillRadius = 30.dp
private val MetadataSwipeMaximum = 40.dp
private val MetadataSwipeThreshold = 32.dp
private const val MetadataSwipeVelocityPxPerMs = 1.2f

@Composable
fun MiniPlayer(
    playbackState: PlaybackState,
    playbackPositionFlow: StateFlow<Long>? = null,
    playbackQueue: PlaybackQueueSnapshot = PlaybackQueueSnapshot(),
    hazeState: HazeState?,
    compact: Boolean = false,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onDismiss: () -> Unit,
    onOpenFullScreenPlayer: () -> Unit,
    onFullScreenPlayerDrag: (Float) -> Unit,
    onFullScreenPlayerDragEnd: (Boolean) -> Unit,
    contextEpisode: Episode? = null,
    onEpisodeContextBottomSheet: (EpisodeContextBottomSheetRequest) -> Unit = {},
    onEpisodeSaveToggle: (String, Boolean) -> Unit = { _, _ -> },
    onEpisodeDownloadToggle: (String, Boolean) -> Unit = { _, _ -> },
    onEpisodePlayNext: (String) -> Unit = {},
    onEpisodeAddToQueue: (String) -> Unit = {},
    onEpisodeGoToPodcast: (String) -> Unit = {},
    onEpisodeGoToHost: (String) -> Unit = {},
    stableGlassWidth: Dp? = null,
    modifier: Modifier = Modifier,
) {
    val item = playbackState.itemOrNull() ?: return
    val colors = LocalPodcastyColors.current
    val shape = RoundedCornerShape(MiniPlayerPillRadius)
    val isPlaying = playbackState is PlaybackState.Playing
    val isPreparing = playbackState is PlaybackState.Preparing
    val durationMs = playbackState.durationMsOrZero().takeIf { it > 0L } ?: item.durationMs
    val fallbackPositionMs = playbackState.positionMsOrZero()
    val canNavigatePrevious = playbackQueue.canNavigatePrevious()
    val canNavigateNext = playbackQueue.canNavigateNext()
    val latestCanNavigatePrevious by rememberUpdatedState(canNavigatePrevious)
    val latestCanNavigateNext by rememberUpdatedState(canNavigateNext)
    val latestOnPreviousClick by rememberUpdatedState(onPreviousClick)
    val latestOnNextClick by rememberUpdatedState(onNextClick)
    var isContextMenuExpanded by remember(item.episodeId) { mutableStateOf(false) }
    val latestOnContextMenuOpen by rememberUpdatedState { isContextMenuExpanded = true }
    val artwork = rememberArtworkThumbnail(item.artworkAsset)
    val density = LocalDensity.current
    val hapticFeedback = LocalHapticFeedback.current
    val configuration = LocalConfiguration.current
    val dragOffset = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    val dismissThresholdPx = with(density) { 36.dp.toPx() }
    val fullScreenTransitionDistancePx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val fullScreenCatchUpTravelPx = with(density) { 120.dp.toPx() }
    val maximumOpenPullPx = with(density) { MiniPlayerHeight.toPx() }
    val dismissTargetPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val metadataSwipeMaximumPx = with(density) { MetadataSwipeMaximum.toPx() }
    val metadataSwipeThresholdPx = with(density) { MetadataSwipeThreshold.toPx() }
    var metadataDragOffset by remember { mutableFloatStateOf(0f) }
    var isMetadataDragging by remember { mutableStateOf(false) }
    var metadataDragStartedAtMs by remember { mutableLongStateOf(0L) }
    val displayedMetadataDragOffset by animateFloatAsState(
        targetValue = if (isMetadataDragging) metadataDragOffset else 0f,
        animationSpec = spring(),
        label = "mini-player-metadata-swipe",
    )
    val displayedDragOffset = dragOffset.value
    val upwardPullProgress = (-displayedDragOffset / maximumOpenPullPx).coerceIn(0f, 1f)
    val miniPlayerAlpha = 1f - upwardPullProgress
    var fullScreenPullPx by remember { mutableFloatStateOf(0f) }
    val miniPlayerTopPxHolder = remember { FloatArray(1) }

    @Composable
    fun MiniPlayerSurface() {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(MiniPlayerHeight)
                .onGloballyPositioned { miniPlayerTopPxHolder[0] = it.positionInRoot().y }
                .pointerInput(
                    dismissThresholdPx,
                    fullScreenTransitionDistancePx,
                    fullScreenCatchUpTravelPx,
                    maximumOpenPullPx,
                    dismissTargetPx,
                    contextEpisode?.id,
                ) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fullScreenPullPx = 0f
                        var currentDragOffset = 0f
                        coroutineScope.launch { dragOffset.snapTo(0f) }
                        var totalDragX = 0f
                        var totalDragY = 0f
                        var isVerticalDrag = false
                        var isHorizontalDrag = false
                        var isOpeningFullscreen = false
                        var fullScreenCatchUpPx = 0f
                        var wasCancelled = false
                        var change = down
                        var pointerDown = true
                        var longPressHandled = false
                        val longPressJob = if (contextEpisode == null) null else coroutineScope.launch {
                            delay(viewConfiguration.longPressTimeoutMillis)
                            if (pointerDown && !isVerticalDrag && !isHorizontalDrag) {
                                longPressHandled = true
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                latestOnContextMenuOpen()
                            }
                        }

                        while (change.pressed) {
                            val event = awaitPointerEvent()
                            change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) {
                                longPressJob?.cancel()
                                wasCancelled = true
                                break
                            }
                            val delta = change.positionChange()
                            totalDragX += delta.x
                            totalDragY += delta.y
                            if (!isVerticalDrag && !isHorizontalDrag) {
                                when {
                                    abs(totalDragY) > viewConfiguration.touchSlop &&
                                        abs(totalDragY) > abs(totalDragX) -> {
                                        isVerticalDrag = true
                                        longPressJob?.cancel()
                                        isOpeningFullscreen = totalDragY < 0f
                                        if (isOpeningFullscreen) {
                                            val fingerStartY = miniPlayerTopPxHolder[0] + down.position.y
                                            fullScreenCatchUpPx = (fullScreenTransitionDistancePx - fingerStartY).coerceAtLeast(0f)
                                        }
                                        currentDragOffset = 0f
                                        coroutineScope.launch { dragOffset.snapTo(0f) }
                                    }
                                    abs(totalDragX) > viewConfiguration.touchSlop -> {
                                        isHorizontalDrag = true
                                        longPressJob?.cancel()
                                    }
                                }
                            }
                            if (isVerticalDrag) {
                                change.consume()
                                if (isOpeningFullscreen) {
                                    fullScreenPullPx = (fullScreenPullPx - delta.y).coerceIn(0f, fullScreenTransitionDistancePx)
                                    currentDragOffset = -fullScreenPullPx.coerceAtMost(maximumOpenPullPx)
                                    val target = currentDragOffset
                                    coroutineScope.launch { dragOffset.snapTo(target) }
                                    val catchUpProgress = (fullScreenPullPx / fullScreenCatchUpTravelPx).coerceIn(0f, 1f)
                                    val eased = 1f - (1f - catchUpProgress) * (1f - catchUpProgress)
                                    val panelDistance = fullScreenPullPx + fullScreenCatchUpPx * eased
                                    onFullScreenPlayerDrag((panelDistance / fullScreenTransitionDistancePx).coerceIn(0f, 1f))
                                } else {
                                    currentDragOffset = (currentDragOffset + delta.y).coerceIn(0f, dismissTargetPx)
                                    val target = currentDragOffset
                                    coroutineScope.launch { dragOffset.snapTo(target) }
                                }
                            }
                        }
                        pointerDown = false
                        longPressJob?.cancel()

                        when {
                            isVerticalDrag && wasCancelled -> {
                                onFullScreenPlayerDragEnd(false)
                                fullScreenPullPx = 0f
                                coroutineScope.launch { dragOffset.animateTo(0f, tween(180, easing = FastOutSlowInEasing)) }
                            }
                            isVerticalDrag -> {
                                when {
                                    fullScreenPullPx > viewConfiguration.touchSlop -> {
                                        onFullScreenPlayerDragEnd(true)
                                        fullScreenPullPx = 0f
                                        coroutineScope.launch { dragOffset.snapTo(0f) }
                                    }
                                    currentDragOffset >= dismissThresholdPx -> {
                                        coroutineScope.launch {
                                            dragOffset.animateTo(dismissTargetPx, tween(180, easing = FastOutSlowInEasing))
                                            onDismiss()
                                        }
                                    }
                                    else -> {
                                        onFullScreenPlayerDragEnd(false)
                                        fullScreenPullPx = 0f
                                        coroutineScope.launch { dragOffset.animateTo(0f, tween(180, easing = FastOutSlowInEasing)) }
                                    }
                                }
                            }
                            !longPressHandled && !wasCancelled && !isHorizontalDrag && !change.pressed &&
                                abs(totalDragX) <= viewConfiguration.touchSlop &&
                                abs(totalDragY) <= viewConfiguration.touchSlop -> onOpenFullScreenPlayer()
                        }
                    }
                }
                .semantics(mergeDescendants = true) {
                    onClick { onOpenFullScreenPlayer(); true }
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, displayedDragOffset.roundToInt()) }
                    .alpha(miniPlayerAlpha)
                    .clip(shape)
                    .border(1.dp, colors.borderGlass, shape),
            ) {
                Box(
                    modifier = (
                        if (stableGlassWidth == null) {
                            Modifier.fillMaxSize()
                        } else {
                            Modifier
                                .align(Alignment.CenterStart)
                                .requiredWidth(stableGlassWidth)
                                .fillMaxHeight()
                        }
                        ).liquidGlassBackground(hazeState, colors),
                )
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 16.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.glassElevated)
                            .border(1.dp, colors.borderGlass, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (artwork != null) {
                            Image(
                                bitmap = artwork,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            MaterialSymbol(
                                symbol = MaterialSymbols.Podcasts,
                                contentDescription = null,
                                tint = colors.textMuted,
                                size = 22.dp,
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 10.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clipToBounds()
                                .pointerInput(metadataSwipeMaximumPx, metadataSwipeThresholdPx) {
                                    detectHorizontalDragGestures(
                                        onDragStart = {
                                            metadataDragOffset = 0f
                                            metadataDragStartedAtMs = android.os.SystemClock.uptimeMillis()
                                            isMetadataDragging = true
                                        },
                                        onHorizontalDrag = { change, dragAmount ->
                                            change.consume()
                                            metadataDragOffset = (metadataDragOffset + dragAmount)
                                                .coerceIn(-metadataSwipeMaximumPx, metadataSwipeMaximumPx)
                                        },
                                        onDragCancel = {
                                            isMetadataDragging = false
                                            metadataDragOffset = 0f
                                        },
                                        onDragEnd = {
                                            val durationMs = (android.os.SystemClock.uptimeMillis() - metadataDragStartedAtMs).coerceAtLeast(1L)
                                            val completedOffset = metadataDragOffset
                                            val velocityPxPerMs = completedOffset / durationMs
                                            val shouldChange = completedOffset.absoluteValue >= metadataSwipeThresholdPx ||
                                                velocityPxPerMs.absoluteValue >= MetadataSwipeVelocityPxPerMs
                                            val direction = completedOffset.compareTo(0f)
                                            isMetadataDragging = false
                                            metadataDragOffset = 0f
                                            if (shouldChange && canDispatchQueueSwipe(direction, latestCanNavigatePrevious, latestCanNavigateNext)) {
                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                                                if (direction < 0) latestOnNextClick() else latestOnPreviousClick()
                                            }
                                        },
                                    )
                                },
                        ) {
                            Column(
                                modifier = Modifier.offset { IntOffset(displayedMetadataDragOffset.roundToInt(), 0) },
                            ) {
                                PodcastyMarqueeText(
                                    text = item.title,
                                    color = colors.textMain,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    fade = true,
                                    continuous = isPlaying,
                                )
                                PodcastyMarqueeText(
                                    text = item.podcastTitle,
                                    color = colors.textMuted,
                                    style = MaterialTheme.typography.bodySmall,
                                    fade = true,
                                    continuous = isPlaying,
                                )
                            }
                        }
                    }
                    AnimatedVisibility(
                        visible = !compact,
                        enter = fadeIn(animationSpec = tween(160)),
                        exit = fadeOut(animationSpec = tween(160)),
                    ) {
                        MiniPlayerControl(
                            symbol = MaterialSymbols.SkipPrevious,
                            label = stringResource(R.string.player_previous),
                            onClick = onPreviousClick,
                            horizontalOffset = 8.dp,
                            skipForward = false,
                            enabled = canNavigatePrevious,
                        )
                    }
                    MiniPlayerControl(
                        label = stringResource(if (isPlaying) R.string.player_pause else R.string.player_play),
                        onClick = onPlayPauseClick,
                        enabled = !isPreparing,
                        horizontalOffset = 4.dp,
                        isPlaying = isPlaying,
                    )
                    MiniPlayerControl(
                        symbol = MaterialSymbols.SkipNext,
                        label = stringResource(R.string.player_next),
                        onClick = onNextClick,
                        skipForward = true,
                        enabled = canNavigateNext,
                    )
                }
                // Subtle hairline playback progress along the bottom edge of the capsule (isolated recomposition scope)
                MiniPlayerProgressHairline(
                    playbackPositionFlow = playbackPositionFlow,
                    fallbackPositionMs = fallbackPositionMs,
                    durationMs = durationMs,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }
    }

    if (contextEpisode == null) {
        MiniPlayerSurface()
    } else {
        EpisodeContextMenu(
            episode = contextEpisode,
            expanded = isContextMenuExpanded,
            onDismiss = { isContextMenuExpanded = false },
            hazeState = hazeState,
            playbackQueue = playbackQueue,
            onPlayNext = { onEpisodePlayNext(it.id) },
            onAddToQueue = { onEpisodeAddToQueue(it.id) },
            onSaveToggle = { ep, saved -> onEpisodeSaveToggle(ep.id, saved) },
            onDownloadToggle = { ep, downloaded -> onEpisodeDownloadToggle(ep.id, downloaded) },
            onGoToPodcast = { onEpisodeGoToPodcast(it.podcastId) },
            onGoToHost = { onEpisodeGoToHost(it.hostId) },
            onBottomSheetRequested = onEpisodeContextBottomSheet,
        ) { MiniPlayerSurface() }
    }
}

@Composable
private fun MiniPlayerProgressHairline(
    playbackPositionFlow: StateFlow<Long>?,
    fallbackPositionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    val positionMs = if (playbackPositionFlow != null) {
        playbackPositionFlow.collectAsStateWithLifecycle().value
    } else {
        fallbackPositionMs
    }
    val progressFraction = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    if (progressFraction <= 0f) return
    val colors = LocalPodcastyColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 1.dp)
            .height(2.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(colors.borderGlass.copy(alpha = 0.25f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progressFraction)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(colors.primary.copy(alpha = 0.85f)),
        )
    }
}

@Composable
private fun MiniPlayerControl(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    horizontalOffset: Dp = 0.dp,
    isPlaying: Boolean? = null,
    skipForward: Boolean? = null,
    symbol: String? = null,
) {
    val colors = LocalPodcastyColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(48.dp)
            .offset(x = horizontalOffset)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
                interactionSource = interactionSource,
                indication = null,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (enabled || isPlaying != null) colors.textMain else colors.textMuted
        when {
            isPlaying != null -> AnimatedPlayPauseSymbol(
                isPlaying = isPlaying,
                isPreparing = !enabled,
                isPressed = isPressed,
                tint = tint,
                size = 24.dp,
                touchTargetSize = 48.dp,
            )
            skipForward != null -> AnimatedSkipSymbol(
                forward = skipForward,
                isPressed = isPressed,
                tint = tint,
                size = 24.dp,
                touchTargetSize = 48.dp,
            )
            else -> MaterialSymbol(
                symbol = requireNotNull(symbol),
                contentDescription = label,
                tint = tint,
                size = 24.dp,
                filled = true,
            )
        }
    }
}
