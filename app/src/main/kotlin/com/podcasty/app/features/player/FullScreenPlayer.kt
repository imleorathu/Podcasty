/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.player

import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.podcasty.app.R
import com.podcasty.app.asr.LiveCaptionUiState
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.PlaybackQueueSnapshot
import com.podcasty.app.domain.PlaybackState
import com.podcasty.app.domain.RepeatMode
import com.podcasty.app.domain.SleepTimerPreset
import com.podcasty.app.domain.SleepTimerRequest
import com.podcasty.app.domain.SleepTimerState
import com.podcasty.app.domain.durationMsOrZero
import com.podcasty.app.domain.itemOrNull
import com.podcasty.app.domain.playbackSpeedOrDefault
import com.podcasty.app.domain.positionMsOrZero
import com.podcasty.app.transcript.TranscriptDocument
import com.podcasty.app.ui.components.EpisodeContextBottomSheetRequest
import kotlin.math.roundToInt

@Composable
fun FullScreenPlayer(
    visible: Boolean,
    dragProgress: Float,
    isDragging: Boolean,
    openingFromMiniPlayerSwipe: Boolean,
    playbackState: PlaybackState,
    playbackPositionFlow: StateFlow<Long>? = null,
    currentEpisode: Episode? = null,
    queue: PlaybackQueueSnapshot = PlaybackQueueSnapshot(),
    queueEpisodes: List<Episode> = emptyList(),
    sleepTimer: SleepTimerState = SleepTimerState(),
    skipBackwardSeconds: Int = 15,
    skipForwardSeconds: Int = 30,
    livingArtworkBackground: Boolean = true,
    secondaryControls: List<String> = com.podcasty.app.data.DefaultPlayerSecondaryControls,
    showSpeedBadge: Boolean = true,
    keepScreenOnForTranscript: Boolean = false,
    autoSyncLyrics: Boolean = true,
    autoScrollLyrics: Boolean = true,
    captionFontScale: Float = 1.0f,
    sleepFadeOutSeconds: Int = 30,
    activeTranscriptDocument: TranscriptDocument? = null,
    availableTranscriptDocuments: List<TranscriptDocument> = emptyList(),
    isExtractingTranscript: Boolean = false,
    liveCaptionState: LiveCaptionUiState = LiveCaptionUiState(),
    onSelectAsrLanguage: (String) -> Unit = {},
    onRetryLiveCaptions: () -> Unit = {},
    onOpenCaptionSettings: () -> Unit = {},
    volume: Float,
    onSeek: (Long) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onPrevious: () -> Unit,
    onSkipBackward: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onNext: () -> Unit,
    onPlaybackSpeedSelected: (Float) -> Unit,
    onSleepTimerConfigured: (SleepTimerRequest) -> Unit,
    onSleepFadeOutChanged: (Int) -> Unit = {},
    onSelectTranscriptLanguage: (String) -> Unit = {},
    onImportTranscriptForEpisode: (Episode) -> Unit = {},
    onOpenYouTubeForEpisode: (Episode) -> Unit = {},
    onLinkAudioForEpisode: (Episode) -> Unit = {},
    onQueueEpisodeSelected: (String) -> Unit = {},
    onQueueReordered: (List<String>) -> Unit = {},
    onQueueEpisodeRemoved: (String) -> Unit = {},
    onShuffleChange: (Boolean) -> Unit = {},
    onRepeatModeChange: (RepeatMode) -> Unit = {},
    onOpenQueueManager: () -> Unit = {},
    isSaved: Boolean = false,
    onSaveToggle: (String, Boolean) -> Unit = { _, _ -> },
    onDownloadToggle: (String, Boolean) -> Unit = { _, _ -> },
    onEpisodePlayNext: (String) -> Unit = {},
    onEpisodeAddToQueue: (String) -> Unit = {},
    onEpisodeGoToPodcast: (String) -> Unit = {},
    onEpisodeGoToHost: (String) -> Unit = {},
    onOpenEqualizer: () -> Unit = {},
    onEpisodeContextBottomSheet: (EpisodeContextBottomSheetRequest) -> Unit = {},
    onCloseFullscreenThen: ((() -> Unit) -> Unit) = { action -> action() },
    onOpenMediaOutputSwitcher: () -> Unit,
    onDismiss: () -> Unit,
    onDismissAnimationFinished: () -> Unit = {},
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
) {
    val item = playbackState.itemOrNull() ?: return
    val fullScreenHazeState = rememberHazeState()
    val glassHazeState = fullScreenHazeState.takeIf { hazeState != null }
    val coroutineScope = rememberCoroutineScope()
    val dragOffset = remember { Animatable(0f) }
    val expansionProgress = remember { Animatable(0f) }
    val closeThresholdPx = with(LocalDensity.current) { 96.dp.toPx() }
    val playerDescription = stringResource(R.string.full_screen_player)
    var hasBeenVisible by remember { mutableStateOf(visible) }

    LaunchedEffect(isDragging, dragProgress, visible, openingFromMiniPlayerSwipe) {
        if (isDragging) {
            expansionProgress.snapTo(dragProgress)
        } else {
            if (visible) {
                hasBeenVisible = true
                dragOffset.snapTo(0f)
            }
            expansionProgress.animateTo(
                targetValue = if (visible) 1f else 0f,
                animationSpec = tween(
                    durationMillis = if (visible && openingFromMiniPlayerSwipe) 760 else if (visible) 520 else 400,
                    easing = FastOutSlowInEasing,
                ),
            )
            if (!visible) {
                dragOffset.snapTo(0f)
                if (hasBeenVisible) {
                    hasBeenVisible = false
                    onDismissAnimationFinished()
                }
            }
        }
    }

    if (expansionProgress.value <= 0f) return
    val artwork = rememberFullscreenArtwork(item.artworkAsset)
    val contextEpisode = currentEpisode?.takeIf { it.id == item.episodeId }
        ?: queueEpisodes.firstOrNull { it.id == item.episodeId }
    val currentPositionMs = playbackState.positionMsOrZero()
    val durationMs = playbackState.durationMsOrZero()
    val displayedDurationMs = durationMs.takeIf { it > 0L } ?: contextEpisode?.durationMs ?: item.durationMs
    val playbackSpeed = playbackState.playbackSpeedOrDefault()
    val isPreparing = playbackState is PlaybackState.Preparing
    val isPlaying = playbackState is PlaybackState.Playing
    var selectedPanel by rememberSaveable { mutableStateOf<FullScreenPlayerPanel?>(null) }
    val canNavigatePrevious = queue.canNavigatePrevious()
    val canNavigateNext = queue.canNavigateNext()
    var isEpisodeContextMenuExpanded by remember(item.episodeId) { mutableStateOf(false) }
    var speedSheetVisible by remember { mutableStateOf(false) }
    var sleepTimerSheetVisible by remember { mutableStateOf(false) }
    val window = LocalActivity.current?.window
    val isPanelOpen = selectedPanel != null
    var isQueueReordering by remember { mutableStateOf(false) }

    LaunchedEffect(selectedPanel, visible) {
        if (!visible || selectedPanel != FullScreenPlayerPanel.Queue) {
            isQueueReordering = false
        }
    }

    val artworkScale by animateFloatAsState(
        targetValue = if (playbackState is PlaybackState.Paused && !isPanelOpen) 0.75f else 1f,
        animationSpec = tween(if (isPanelOpen) 320 else 500, easing = FastOutSlowInEasing),
        label = "full-screen-artwork-scale",
    )
    val artworkTransformOrigin by animateFloatAsState(
        targetValue = if (isPanelOpen) 0f else 0.5f,
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "full-screen-artwork-transform-origin",
    )
    var transcriptSeekPositionMs by rememberSaveable(item.episodeId) { mutableStateOf<Long?>(null) }
    var transcriptSeekRequestId by rememberSaveable(item.episodeId) { mutableLongStateOf(0L) }
    val seekState = rememberPlayerSeekState(item.episodeId)

    val horizontalSwipeState = remember { FullScreenPlayerSwipeState() }
    val displayedHorizontalSwipeOffset by animateFloatAsState(
        targetValue = if (horizontalSwipeState.isDragging) horizontalSwipeState.dragOffset else 0f,
        animationSpec = spring(),
        label = "full-screen-player-swipe",
    )

    val layoutState = rememberSaveableStateHolder()
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val insets = WindowInsets.safeDrawing
        val safeWidth = maxWidth - with(density) {
            (insets.getLeft(density, LayoutDirection.Ltr) + insets.getRight(density, LayoutDirection.Ltr)).toDp()
        }
        val safeHeight = maxHeight - with(density) {
            (insets.getTop(density) + insets.getBottom(density)).toDp()
        }
        val layoutMode = resolvePlayerLayout(safeWidth.value, safeHeight.value)
        val displayedPanel = selectedPanel ?: FullScreenPlayerPanel.Transcript.takeIf { layoutMode == PlayerLayoutMode.Wide }
        val canCommitReorder = rememberUpdatedState(visible && displayedPanel == FullScreenPlayerPanel.Queue)

        LaunchedEffect(layoutMode, maxWidth, maxHeight) {
            isEpisodeContextMenuExpanded = false
            isQueueReordering = false
            horizontalSwipeState.reset()
            if (!seekState.isScrubbing.value && !seekState.awaitingConfirmation.value) {
                seekState.pendingFraction.value = null
            }
            dragOffset.snapTo(0f)
        }

        val keepScreenOn = keepScreenOnForTranscript && visible && isPlaying && displayedPanel == FullScreenPlayerPanel.Transcript
        DisposableEffect(window, keepScreenOn) {
            if (keepScreenOn) window?.addFlags(FLAG_KEEP_SCREEN_ON)
            else window?.clearFlags(FLAG_KEEP_SCREEN_ON)
            onDispose { window?.clearFlags(FLAG_KEEP_SCREEN_ON) }
        }

        val panelHeightPx = with(density) { maxHeight.toPx() }
        val panelOffsetPx = panelHeightPx * (1f - expansionProgress.value)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, (panelOffsetPx + dragOffset.value).roundToInt()) }
                .alpha((expansionProgress.value * 8f).coerceIn(0f, 1f))
                .semantics { contentDescription = playerDescription }
                .pointerInput(closeThresholdPx, layoutMode) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            if (dragAmount > 0f || dragOffset.value > 0f) {
                                coroutineScope.launch {
                                    dragOffset.snapTo((dragOffset.value + dragAmount).coerceAtLeast(0f))
                                }
                            }
                        },
                        onDragCancel = { coroutineScope.launch { dragOffset.animateTo(0f, spring()) } },
                        onDragEnd = {
                            coroutineScope.launch {
                                if (dragOffset.value >= closeThresholdPx) onDismiss()
                                else dragOffset.animateTo(0f, spring())
                            }
                        },
                    )
                },
        ) {
            FullScreenPlayerBackground(
                artwork = artwork,
                livingArtworkBackground = livingArtworkBackground,
                isPlaying = isPlaying,
                showArtwork = layoutMode == PlayerLayoutMode.Square && selectedPanel == null,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (glassHazeState == null) Modifier else Modifier.hazeSource(glassHazeState)),
            )

            val metadataContent: @Composable (Boolean) -> Unit = { compact ->
                FullScreenPlayerMetadataTransition(
                    item = item,
                    displayedHorizontalSwipeOffset = displayedHorizontalSwipeOffset,
                    hazeState = glassHazeState,
                    compact = compact,
                    showMore = layoutMode != PlayerLayoutMode.Square,
                    isSaved = isSaved,
                    onSaveToggle = onSaveToggle,
                    onDownloadToggle = onDownloadToggle,
                    contextEpisode = contextEpisode,
                    contextMenuExpanded = isEpisodeContextMenuExpanded,
                    onContextMenuOpen = { isEpisodeContextMenuExpanded = true },
                    onContextMenuDismiss = { isEpisodeContextMenuExpanded = false },
                    playbackQueue = queue,
                    onEpisodePlayNext = onEpisodePlayNext,
                    onEpisodeAddToQueue = onEpisodeAddToQueue,
                    onEpisodeGoToPodcast = onEpisodeGoToPodcast,
                    onEpisodeGoToHost = onEpisodeGoToHost,
                    onOpenEqualizer = onOpenEqualizer,
                    onEpisodeContextBottomSheet = onEpisodeContextBottomSheet,
                    onCloseFullscreenThen = onCloseFullscreenThen,
                )
            }

            val panelContent: @Composable (FullScreenPlayerPanel?, Dp) -> Unit = { panel, queuePanelWidth ->
                if (panel == FullScreenPlayerPanel.Queue) {
                    FullScreenQueuePanel(
                        queue = queue,
                        episodes = queueEpisodes,
                        currentEpisodeId = item.episodeId,
                        isPlaying = isPlaying,
                        onEpisodeSelected = onQueueEpisodeSelected,
                        onEpisodeRemoved = onQueueEpisodeRemoved,
                        onEpisodePlayNext = onEpisodePlayNext,
                        onReorder = { ids -> if (canCommitReorder.value) onQueueReordered(ids) },
                        onReorderDragStateChange = { isQueueReordering = it },
                        onShuffleChange = onShuffleChange,
                        onRepeatModeChange = onRepeatModeChange,
                        onOpenQueueManager = onOpenQueueManager,
                        onSaveToggle = onSaveToggle,
                        onDownloadToggle = onDownloadToggle,
                        onEpisodeGoToPodcast = onEpisodeGoToPodcast,
                        onEpisodeGoToHost = onEpisodeGoToHost,
                        onEpisodeContextBottomSheet = onEpisodeContextBottomSheet,
                        onCloseFullscreenThen = onCloseFullscreenThen,
                        hazeState = glassHazeState,
                        modifier = Modifier.requiredWidth(queuePanelWidth).fillMaxSize(),
                    )
                } else {
                    key(item.episodeId, contextEpisode?.transcriptLrc, activeTranscriptDocument?.language) {
                        val liveTranscriptPosMs = if (playbackPositionFlow != null) {
                            playbackPositionFlow.collectAsStateWithLifecycle().value
                        } else {
                            currentPositionMs
                        }
                        FullScreenPlayerTranscriptPanel(
                            episodeId = item.episodeId,
                            transcript = contextEpisode?.transcriptLrc,
                            useLargeText = layoutMode == PlayerLayoutMode.Wide && safeHeight >= 480.dp,
                            textScale = if (isLargeWidePlayer(safeWidth.value, safeHeight.value)) 1.5f else 1f,
                            captionFontScale = captionFontScale,
                            autoSyncEnabled = autoSyncLyrics,
                            autoScrollEnabled = autoScrollLyrics,
                            activeDocument = activeTranscriptDocument,
                            availableDocuments = availableTranscriptDocuments,
                            isYouTubeReference = contextEpisode?.isYouTubeReference == true,
                            hasLinkedAudio = contextEpisode?.effectivePlaybackUri != null ||
                                contextEpisode?.isYouTubeReference == true ||
                                !item.sourceUri.isNullOrBlank(),
                            isExtractingTranscript = isExtractingTranscript,
                            liveCaptionState = liveCaptionState,
                            onSelectAsrLanguage = onSelectAsrLanguage,
                            onRetryLiveCaptions = onRetryLiveCaptions,
                            onOpenCaptionSettings = { onCloseFullscreenThen(onOpenCaptionSettings) },
                            currentPositionMs = liveTranscriptPosMs,
                            isPlaying = isPlaying,
                            pendingSeekPositionMs = transcriptSeekPositionMs,
                            seekRequestId = transcriptSeekRequestId,
                            onSeek = onSeek,
                            onSelectLanguage = onSelectTranscriptLanguage,
                            onImportTranscriptClick = {
                                if (contextEpisode != null) onImportTranscriptForEpisode(contextEpisode)
                                else onImportTranscriptForEpisode(
                                    Episode(
                                        id = item.episodeId,
                                        podcastId = item.podcastId,
                                        podcastTitle = item.podcastTitle,
                                        hostId = item.hostId,
                                        hostName = item.hostName,
                                        categoryId = "",
                                        categoryName = "",
                                        episodeNumber = 1,
                                        title = item.title,
                                        summary = "",
                                        artworkAsset = item.artworkAsset,
                                        audioAsset = item.audioAsset,
                                        durationMs = item.durationMs,
                                        playbackPositionMs = 0L,
                                        isPlayed = false,
                                        isSaved = false,
                                        isDownloaded = false,
                                        fileSizeBytes = 0L,
                                        playCount = 0,
                                        publishedAt = 0L,
                                        lastPlayedAt = null,
                                        transcriptLrc = null,
                                    ),
                                )
                            },
                            onOpenYouTubeClick = contextEpisode?.takeIf { it.isYouTubeReference }?.let { ep ->
                                { onOpenYouTubeForEpisode(ep) }
                            },
                            onLinkAudioClick = contextEpisode?.takeIf { it.isYouTubeReference }?.let { ep ->
                                { onCloseFullscreenThen { onLinkAudioForEpisode(ep) } }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            AdaptivePlayerContent(
                mode = layoutMode,
                wide = {
                    layoutState.SaveableStateProvider("wide") {
                        WidePlayerLayout(
                            selectedPanel = requireNotNull(displayedPanel),
                            horizontalSwipeState = horizontalSwipeState,
                            displayedHorizontalSwipeOffset = displayedHorizontalSwipeOffset,
                            canNavigatePrevious = canNavigatePrevious,
                            canNavigateNext = canNavigateNext,
                            onPrevious = onPrevious,
                            onNext = onNext,
                            swipeEnabled = visible,
                            artwork = { artworkMod ->
                                FullScreenPlayerArtwork(artwork, artworkMod, livingArtworkBackground)
                            },
                            metadata = { metadataContent(false) },
                            controls = {
                                FullScreenPlayerControls(
                                    secondaryControls = secondaryControls,
                                    episodeId = item.episodeId,
                                    seekState = seekState,
                                    currentPositionMs = currentPositionMs,
                                    playbackPositionFlow = playbackPositionFlow,
                                    durationMs = durationMs,
                                    displayedDurationMs = displayedDurationMs,
                                    playbackSpeed = playbackSpeed,
                                    skipBackwardSeconds = skipBackwardSeconds,
                                    skipForwardSeconds = skipForwardSeconds,
                                    sleepTimer = sleepTimer,
                                    showSpeedBadge = showSpeedBadge,
                                    isPreparing = isPreparing,
                                    isPlaying = isPlaying,
                                    canNavigatePrevious = canNavigatePrevious,
                                    canNavigateNext = canNavigateNext,
                                    volume = volume,
                                    compactControls = usesCompactPlayerControls(safeWidth.value, safeHeight.value),
                                    queue = queue,
                                    selectedPanel = displayedPanel,
                                    isQueueReordering = false,
                                    onPanelSelected = { if (it != null) selectedPanel = it },
                                    onSeekRequested = { posMs ->
                                        transcriptSeekPositionMs = posMs
                                        transcriptSeekRequestId += 1
                                        onSeek(posMs)
                                    },
                                    onSeekConfirmed = { transcriptSeekPositionMs = null },
                                    onVolumeChange = onVolumeChange,
                                    onPrevious = onPrevious,
                                    onSkipBackward = onSkipBackward,
                                    onPlayPause = onPlayPause,
                                    onSkipForward = onSkipForward,
                                    onNext = onNext,
                                    onOpenSpeedSheet = { speedSheetVisible = true },
                                    onOpenSleepTimerSheet = { sleepTimerSheetVisible = true },
                                    onOpenMediaOutputSwitcher = onOpenMediaOutputSwitcher,
                                )
                            },
                            panelContent = { width -> panelContent(displayedPanel, width) },
                        )
                    }
                },
                square = {
                    layoutState.SaveableStateProvider("square") {
                        val minTouchSize = with(density) { LocalViewConfiguration.current.minimumTouchTargetSize.toSize() }
                        val swipeExclusions = remember(minTouchSize) { FullScreenSwipeExclusions(minTouchSize) }
                        FullScreenPlayerSwipeTarget(
                            testTag = "square_player_swipe_target",
                            swipeState = horizontalSwipeState,
                            displayedOffset = displayedHorizontalSwipeOffset,
                            onPrevious = onPrevious,
                            onNext = onNext,
                            canNavigatePrevious = canNavigatePrevious,
                            canNavigateNext = canNavigateNext,
                            enabled = visible && selectedPanel == null,
                            exclusions = swipeExclusions,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            SquarePlayerLayout(
                                selectedPanel = selectedPanel,
                                metadata = { metadataContent(false) },
                                controls = {
                                    FullScreenSeekControls(
                                        episodeId = item.episodeId,
                                        seekState = seekState,
                                        currentPositionMs = currentPositionMs,
                                        playbackPositionFlow = playbackPositionFlow,
                                        durationMs = durationMs,
                                        displayedDurationMs = displayedDurationMs,
                                        playbackSpeed = playbackSpeed,
                                        showSpeedBadge = showSpeedBadge,
                                        isPreparing = isPreparing,
                                        onSeekRequested = { posMs ->
                                            transcriptSeekPositionMs = posMs
                                            transcriptSeekRequestId += 1
                                            onSeek(posMs)
                                        },
                                        onSeekConfirmed = { transcriptSeekPositionMs = null },
                                        onOpenSpeedSheet = { speedSheetVisible = true },
                                    )
                                    FullScreenPlaybackButtons(
                                        isPreparing = isPreparing,
                                        isPlaying = isPlaying,
                                        skipBackwardSeconds = skipBackwardSeconds,
                                        skipForwardSeconds = skipForwardSeconds,
                                        canNavigatePrevious = canNavigatePrevious,
                                        canNavigateNext = canNavigateNext,
                                        onPrevious = onPrevious,
                                        onSkipBackward = onSkipBackward,
                                        onPlayPause = onPlayPause,
                                        onSkipForward = onSkipForward,
                                        onNext = onNext,
                                    )
                                },
                                panelContent = { width -> panelContent(selectedPanel, width) },
                                footer = {
                                    FullScreenPanelButtons(
                                        controls = secondaryControls,
                                        selectedPanel = selectedPanel,
                                        queue = queue,
                                        sleepTimer = sleepTimer,
                                        onPanelSelected = { selectedPanel = it },
                                        onOpenSleepTimer = { sleepTimerSheetVisible = true },
                                    )
                                },
                            )
                        }
                    }
                },
                phone = {
                    layoutState.SaveableStateProvider("phone") {
                        PhonePlayerLayout(
                            isPanelOpen = isPanelOpen,
                            isQueueReordering = isQueueReordering,
                            artworkScale = artworkScale,
                            artworkTransformOrigin = artworkTransformOrigin,
                            horizontalSwipeState = horizontalSwipeState,
                            displayedHorizontalSwipeOffset = displayedHorizontalSwipeOffset,
                            canNavigatePrevious = canNavigatePrevious,
                            canNavigateNext = canNavigateNext,
                            onPrevious = onPrevious,
                            onNext = onNext,
                            onClosePanel = { selectedPanel = null },
                            artwork = { artworkMod ->
                                FullScreenPlayerArtwork(artwork, artworkMod, livingArtworkBackground)
                            },
                            metadata = metadataContent,
                            panelContent = { queuePanelWidth ->
                                AnimatedContent(
                                    targetState = selectedPanel,
                                    transitionSpec = {
                                        (fadeIn(tween(200, easing = FastOutSlowInEasing)) +
                                            slideInVertically(tween(200, easing = FastOutSlowInEasing)) { 12 }) togetherWith
                                            (fadeOut(tween(120, easing = FastOutSlowInEasing)) +
                                                slideOutVertically(tween(120, easing = FastOutSlowInEasing)) { -10 })
                                    },
                                    label = "full-screen-player-panel-content",
                                ) { panel ->
                                    panelContent(panel, queuePanelWidth)
                                }
                            },
                            controls = { controlsMod ->
                                FullScreenPlayerControls(
                                    secondaryControls = secondaryControls,
                                    episodeId = item.episodeId,
                                    seekState = seekState,
                                    currentPositionMs = currentPositionMs,
                                    playbackPositionFlow = playbackPositionFlow,
                                    durationMs = durationMs,
                                    displayedDurationMs = displayedDurationMs,
                                    playbackSpeed = playbackSpeed,
                                    skipBackwardSeconds = skipBackwardSeconds,
                                    skipForwardSeconds = skipForwardSeconds,
                                    sleepTimer = sleepTimer,
                                    showSpeedBadge = showSpeedBadge,
                                    isPreparing = isPreparing,
                                    isPlaying = isPlaying,
                                    canNavigatePrevious = canNavigatePrevious,
                                    canNavigateNext = canNavigateNext,
                                    volume = volume,
                                    compactControls = usesCompactPlayerControls(safeWidth.value, safeHeight.value),
                                    queue = queue,
                                    selectedPanel = selectedPanel,
                                    isQueueReordering = isQueueReordering,
                                    onPanelSelected = { selectedPanel = it },
                                    onSeekRequested = { posMs ->
                                        transcriptSeekPositionMs = posMs
                                        transcriptSeekRequestId += 1
                                        onSeek(posMs)
                                    },
                                    onSeekConfirmed = { transcriptSeekPositionMs = null },
                                    onVolumeChange = onVolumeChange,
                                    onPrevious = onPrevious,
                                    onSkipBackward = onSkipBackward,
                                    onPlayPause = onPlayPause,
                                    onSkipForward = onSkipForward,
                                    onNext = onNext,
                                    onOpenSpeedSheet = { speedSheetVisible = true },
                                    onOpenSleepTimerSheet = { sleepTimerSheetVisible = true },
                                    onOpenMediaOutputSwitcher = onOpenMediaOutputSwitcher,
                                    modifier = controlsMod,
                                )
                            },
                        )
                    }
                },
            )
        }
    }

    if (speedSheetVisible) {
        PlaybackSpeedBottomSheet(
            currentSpeed = playbackSpeed,
            onSpeedSelected = onPlaybackSpeedSelected,
            onDismiss = { speedSheetVisible = false },
        )
    }
    if (sleepTimerSheetVisible) {
        SleepTimerBottomSheet(
            sleepTimer = sleepTimer,
            defaultFadeOutSeconds = sleepFadeOutSeconds,
            onConfigureTimer = onSleepTimerConfigured,
            onFadeOutChanged = onSleepFadeOutChanged,
            onDismiss = { sleepTimerSheetVisible = false },
        )
    }
}
