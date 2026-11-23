/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToLong
import com.podcasty.app.data.DefaultPlayerSecondaryControls
import com.podcasty.app.R
import com.podcasty.app.core.formatPlaybackTime
import com.podcasty.app.core.formatRemainingTime
import com.podcasty.app.core.formatSpeedLabel
import com.podcasty.app.core.formatTimerCountdown
import com.podcasty.app.domain.PlaybackQueueSnapshot
import com.podcasty.app.domain.RepeatMode
import com.podcasty.app.domain.SleepTimerPreset
import com.podcasty.app.domain.SleepTimerState
import com.podcasty.app.ui.components.AnimatedPlayPauseSymbol
import com.podcasty.app.ui.components.AnimatedSkipSymbol
import com.podcasty.app.ui.components.MaterialSymbol
import com.podcasty.app.ui.components.MaterialSymbols
import com.podcasty.app.ui.components.PodcastyTrackSlider
import com.podcasty.app.ui.components.sliderFilledTrackColor
import com.podcasty.app.ui.theme.LocalPodcastyColors
import com.podcasty.app.ui.theme.PodcastyColors

internal const val FullScreenPlayerControlsTestTag = "full_screen_player_controls"
internal const val QueueReorderTransitionDurationMs = 360
internal const val QueueReorderControlsFadeDurationMs = 300
internal const val QueueButtonSelectionTransitionDurationMs = 220
internal const val QueueStatusBadgeRevealDelayMs = QueueButtonSelectionTransitionDurationMs + 16
internal const val FullScreenQueueStatusBadgeTestTag = "full_screen_queue_status_badge"
internal const val FullScreenPlayerSpeedBadgeTestTag = "full_screen_player_speed_badge"
internal const val FullScreenPlayerElapsedTimeTestTag = "full_screen_player_elapsed_time"
internal const val FullScreenPlayerRemainingTimeTestTag = "full_screen_player_remaining_time"
private const val SeekConfirmationToleranceMs = 1_500L
private const val SeekConfirmationTimeoutMs = 650L

internal enum class FullScreenPlayerPanel { Transcript, Queue }

internal fun areFullScreenPlayerControlsVisible(isQueueReordering: Boolean): Boolean = !isQueueReordering

internal fun hasConfirmedSeekPosition(
    seekFraction: Float,
    playbackPositionMs: Long,
    durationMs: Long,
    explicitTargetMs: Long? = null,
): Boolean {
    if (durationMs <= 0L) return false
    val targetPositionMs = explicitTargetMs?.coerceIn(0L, durationMs)
        ?: (durationMs.toDouble() * seekFraction.coerceIn(0f, 1f)).roundToLong().coerceIn(0L, durationMs)
    val toleranceMs = minOf(SeekConfirmationToleranceMs, (durationMs / 4L).coerceAtLeast(250L))
    return kotlin.math.abs(playbackPositionMs - targetPositionMs) <= toleranceMs
}

@Composable
internal fun FullScreenPlayerControls(
    episodeId: String,
    seekState: PlayerSeekState = rememberPlayerSeekState(episodeId),
    currentPositionMs: Long,
    playbackPositionFlow: StateFlow<Long>? = null,
    durationMs: Long,
    displayedDurationMs: Long?,
    playbackSpeed: Float,
    skipBackwardSeconds: Int,
    skipForwardSeconds: Int,
    sleepTimer: SleepTimerState,
    showSpeedBadge: Boolean,
    isPreparing: Boolean,
    isPlaying: Boolean,
    canNavigatePrevious: Boolean,
    canNavigateNext: Boolean,
    volume: Float,
    compactControls: Boolean,
    queue: PlaybackQueueSnapshot,
    selectedPanel: FullScreenPlayerPanel?,
    isQueueReordering: Boolean,
    onPanelSelected: (FullScreenPlayerPanel?) -> Unit,
    onSeekRequested: (Long) -> Unit,
    onSeekConfirmed: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onPrevious: () -> Unit,
    onSkipBackward: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onNext: () -> Unit,
    onOpenSpeedSheet: () -> Unit,
    onOpenSleepTimerSheet: () -> Unit,
    onOpenMediaOutputSwitcher: () -> Unit,
    secondaryControls: List<String> = DefaultPlayerSecondaryControls,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPodcastyColors.current
    val volumeLabel = stringResource(R.string.player_volume)
    var restingHeightPx by remember { mutableIntStateOf(0) }
    var volumeInteracting by remember { mutableStateOf(false) }

    val volumeOffset by animateDpAsState(
        if (volumeInteracting) 4.dp else 0.dp,
        tween(220, easing = FastOutSlowInEasing),
        label = "full-screen-volume-icon-offset",
    )
    val volumeColor by animateColorAsState(
        sliderFilledTrackColor(colors, volumeInteracting),
        tween(220, easing = FastOutSlowInEasing),
        label = "full-screen-volume-icon-colour",
    )
    val controlsModifier = if (isQueueReordering && restingHeightPx > 0) {
        Modifier.fillMaxWidth().requiredHeight(with(LocalDensity.current) { restingHeightPx.toDp() })
    } else {
        modifier.fillMaxWidth()
    }

    AnimatedVisibility(
        visible = areFullScreenPlayerControlsVisible(isQueueReordering),
        modifier = controlsModifier
            .onSizeChanged { if (!isQueueReordering) restingHeightPx = it.height }
            .semantics { testTag = FullScreenPlayerControlsTestTag },
        enter = fadeIn(tween(QueueReorderControlsFadeDurationMs, easing = LinearOutSlowInEasing)) +
            slideInVertically(tween(QueueReorderTransitionDurationMs, easing = FastOutSlowInEasing)) { it },
        exit = fadeOut(tween(QueueReorderControlsFadeDurationMs, easing = LinearOutSlowInEasing)) +
            slideOutVertically(tween(QueueReorderTransitionDurationMs, easing = FastOutSlowInEasing)) { it },
    ) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(if (compactControls) 8.dp else 16.dp),
        ) {
            FullScreenSeekControls(
                episodeId = episodeId,
                seekState = seekState,
                currentPositionMs = currentPositionMs,
                playbackPositionFlow = playbackPositionFlow,
                durationMs = durationMs,
                displayedDurationMs = displayedDurationMs,
                playbackSpeed = playbackSpeed,
                showSpeedBadge = showSpeedBadge,
                isPreparing = isPreparing,
                onSeekRequested = onSeekRequested,
                onSeekConfirmed = onSeekConfirmed,
                onOpenSpeedSheet = onOpenSpeedSheet,
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
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    MaterialSymbol(
                        MaterialSymbols.VolumeDown,
                        null,
                        tint = volumeColor,
                        size = 20.dp,
                        filled = true,
                        modifier = Modifier.offset(x = -volumeOffset),
                    )
                    Spacer(Modifier.width(10.dp))
                    PodcastyTrackSlider(
                        value = volume,
                        onValueChange = onVolumeChange,
                        onInteractionChange = { volumeInteracting = it },
                        trackHeight = 7.dp,
                        modifier = Modifier.weight(1f).excludeFullScreenSwipe().semantics { contentDescription = volumeLabel },
                    )
                    Spacer(Modifier.width(10.dp))
                    MaterialSymbol(
                        MaterialSymbols.VolumeUp,
                        null,
                        tint = volumeColor,
                        size = 20.dp,
                        filled = true,
                        modifier = Modifier.offset(x = volumeOffset),
                    )
                }
                Spacer(Modifier.height(if (compactControls) 0.dp else 4.dp))
                FullScreenPanelButtons(
                    controls = secondaryControls,
                    selectedPanel = selectedPanel,
                    queue = queue,
                    sleepTimer = sleepTimer,
                    onPanelSelected = onPanelSelected,
                    onOpenSleepTimer = onOpenSleepTimerSheet,
                    output = {
                        FullScreenTransportButton(
                            symbol = MaterialSymbols.Airplay,
                            label = stringResource(R.string.player_cast),
                            onClick = onOpenMediaOutputSwitcher,
                            iconSize = 24.dp,
                            tint = colors.foregroundSubtle,
                            filled = false,
                        )
                    },
                )
            }
        }
    }
}

@Composable
internal fun FullScreenSeekControls(
    episodeId: String,
    seekState: PlayerSeekState,
    currentPositionMs: Long,
    playbackPositionFlow: StateFlow<Long>? = null,
    durationMs: Long,
    displayedDurationMs: Long?,
    playbackSpeed: Float,
    showSpeedBadge: Boolean,
    isPreparing: Boolean,
    onSeekRequested: (Long) -> Unit,
    onSeekConfirmed: () -> Unit,
    onOpenSpeedSheet: () -> Unit,
) {
    val livePositionMs = if (playbackPositionFlow != null) {
        playbackPositionFlow.collectAsStateWithLifecycle().value
    } else {
        currentPositionMs
    }
    val colors = LocalPodcastyColors.current
    val seekLabel = stringResource(R.string.player_seek)
    val pendingSeekFraction = seekState.pendingFraction.value
    val awaitingSeekConfirmation = seekState.awaitingConfirmation.value
    val isScrubbing = seekState.isScrubbing.value
    val pendingGeneration = seekState.pendingGeneration.value
    var seekInteracting by remember(episodeId) { mutableStateOf(false) }

    val totalMs = (displayedDurationMs?.takeIf { it > 0L } ?: durationMs).coerceAtLeast(0L)

    // Confirm seek when Media3 reaches the target position (never while the user is actively dragging)
    LaunchedEffect(livePositionMs, totalMs, awaitingSeekConfirmation, isScrubbing, pendingGeneration) {
        val fraction = seekState.pendingFraction.value
        if (!isScrubbing && awaitingSeekConfirmation && fraction != null &&
            hasConfirmedSeekPosition(
                seekFraction = fraction,
                playbackPositionMs = livePositionMs,
                durationMs = totalMs,
                explicitTargetMs = seekState.pendingTargetMs.value,
            )
        ) {
            seekState.confirmSeek()
            onSeekConfirmed()
        }
    }

    // Safety confirmation timeout so paused playback or >1.5s keyframe-snapped codecs never lock the bar
    LaunchedEffect(pendingGeneration, awaitingSeekConfirmation, isScrubbing) {
        if (!isScrubbing && awaitingSeekConfirmation && pendingGeneration > 0L) {
            delay(SeekConfirmationTimeoutMs)
            if (!seekState.isScrubbing.value && seekState.pendingGeneration.value == pendingGeneration) {
                seekState.confirmSeek()
                onSeekConfirmed()
            }
        }
    }

    val seekOffset by animateDpAsState(
        if (seekInteracting) 4.dp else 0.dp,
        tween(180, easing = FastOutSlowInEasing),
        label = "full-screen-seek-supporting-offset",
    )
    val seekLabelColor by animateColorAsState(
        if (seekInteracting) colors.onPrimary else colors.foregroundSubtle,
        tween(180, easing = FastOutSlowInEasing),
        label = "full-screen-seek-time-label-colour",
    )
    val seekScaleX by animateFloatAsState(
        if (seekInteracting) 1.03f else 1f,
        tween(180, easing = FastOutSlowInEasing),
        label = "full-screen-seek-supporting-scale-x",
    )
    val seekScaleY by animateFloatAsState(
        if (seekInteracting) 1.10f else 1f,
        tween(180, easing = FastOutSlowInEasing),
        label = "full-screen-seek-supporting-scale-y",
    )

    val safeLivePositionMs = if (totalMs > 0L) livePositionMs.coerceIn(0L, totalMs) else 0L
    val pendingTargetMs = seekState.pendingTargetMs.value
    val sliderFraction = when {
        totalMs <= 0L -> 0f
        pendingSeekFraction != null -> pendingSeekFraction.coerceIn(0f, 1f)
        else -> (safeLivePositionMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
    }
    val effectivePosMs = when {
        totalMs <= 0L -> 0L
        !isScrubbing && awaitingSeekConfirmation && pendingTargetMs != null -> pendingTargetMs.coerceIn(0L, totalMs)
        pendingSeekFraction != null -> (totalMs.toDouble() * pendingSeekFraction.coerceIn(0f, 1f)).roundToLong().coerceIn(0L, totalMs)
        else -> safeLivePositionMs
    }
    val elapsedSec = effectivePosMs / 1_000L
    val totalSec = totalMs / 1_000L
    val elapsedText = remember(elapsedSec) { formatPlaybackTime(elapsedSec * 1_000L) }
    val remainingText = remember(elapsedSec, totalSec) { formatRemainingTime(elapsedSec * 1_000L, totalSec * 1_000L) }

    Column {
        PodcastyTrackSlider(
            value = sliderFraction,
            onValueChange = { fraction -> seekState.updateScrub(fraction) },
            onValueChangeFinished = {
                val targetMs = seekState.commitScrub(totalMs)
                if (targetMs != null) {
                    onSeekRequested(targetMs)
                }
            },
            onValueChangeCancelled = { seekState.cancelScrub() },
            enabled = totalMs > 0L && !isPreparing,
            onInteractionChange = { interacting -> seekInteracting = interacting },
            trackHeight = 7.dp,
            modifier = Modifier.excludeFullScreenSwipe().semantics { contentDescription = seekLabel },
        )
        Box(Modifier.fillMaxWidth()) {
            Text(
                text = elapsedText,
                color = seekLabelColor,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = -seekOffset, y = (-12).dp + seekOffset)
                    .semantics { testTag = FullScreenPlayerElapsedTimeTestTag },
            )
            // Centered Airmedy-style badge pill repurposed as an interactive Playback Speed control
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = (-12).dp + seekOffset)
                    .alpha(if (showSpeedBadge) 1f else 0f)
                    .then(
                        if (showSpeedBadge) {
                            Modifier.excludeFullScreenSwipe().clickable(
                                role = Role.Button,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onOpenSpeedSheet,
                            ).semantics {
                                testTag = FullScreenPlayerSpeedBadgeTestTag
                            }
                        } else {
                            Modifier.clearAndSetSemantics {}
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier
                        .graphicsLayer { scaleX = seekScaleX; scaleY = seekScaleY }
                        .clip(RoundedCornerShape(6.dp))
                        .background(fullScreenSecondaryControlBackground(colors))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    MaterialSymbol(MaterialSymbols.Speed, null, size = 12.dp, tint = colors.foregroundSubtle)
                    Text(
                        text = formatSpeedLabel(playbackSpeed),
                        color = colors.foregroundSubtle,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    )
                }
            }
            Text(
                text = remainingText,
                color = seekLabelColor,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = seekOffset, y = (-12).dp + seekOffset)
                    .semantics { testTag = FullScreenPlayerRemainingTimeTestTag },
            )
        }
    }
}

@Composable
internal fun FullScreenPlaybackButtons(
    isPreparing: Boolean,
    isPlaying: Boolean,
    skipBackwardSeconds: Int,
    skipForwardSeconds: Int,
    canNavigatePrevious: Boolean,
    canNavigateNext: Boolean,
    onPrevious: () -> Unit,
    onSkipBackward: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onNext: () -> Unit,
) {
    val colors = LocalPodcastyColors.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FullScreenTransportButton(
            symbol = MaterialSymbols.SkipPrevious,
            label = stringResource(R.string.player_previous),
            onClick = onPrevious,
            enabled = canNavigatePrevious,
            iconSize = 30.dp,
            buttonSize = 52.dp,
            skipForward = false,
        )
        FullScreenTransportButton(
            symbol = MaterialSymbols.Replay10,
            label = stringResource(R.string.player_rewind, skipBackwardSeconds),
            onClick = onSkipBackward,
            enabled = !isPreparing,
            iconSize = 28.dp,
            buttonSize = 52.dp,
            tint = colors.onPrimary,
            filled = false,
        )
        FullScreenTransportButton(
            label = stringResource(if (isPlaying) R.string.player_pause else R.string.player_play),
            onClick = onPlayPause,
            enabled = !isPreparing,
            iconSize = 48.dp,
            buttonSize = 64.dp,
            isPlaying = isPlaying,
        )
        FullScreenTransportButton(
            symbol = MaterialSymbols.Forward30,
            label = stringResource(R.string.player_forward, skipForwardSeconds),
            onClick = onSkipForward,
            enabled = !isPreparing,
            iconSize = 28.dp,
            buttonSize = 52.dp,
            tint = colors.onPrimary,
            filled = false,
        )
        FullScreenTransportButton(
            symbol = MaterialSymbols.SkipNext,
            label = stringResource(R.string.player_next),
            onClick = onNext,
            enabled = canNavigateNext,
            iconSize = 30.dp,
            buttonSize = 52.dp,
            skipForward = true,
        )
    }
}

@Composable
internal fun FullScreenPanelButtons(
    selectedPanel: FullScreenPlayerPanel?,
    queue: PlaybackQueueSnapshot,
    sleepTimer: SleepTimerState,
    onPanelSelected: (FullScreenPlayerPanel?) -> Unit,
    onOpenSleepTimer: () -> Unit,
    controls: List<String> = DefaultPlayerSecondaryControls,
    output: (@Composable () -> Unit)? = null,
) {
    val colors = LocalPodcastyColors.current
    var queueBadgeVisible by remember { mutableStateOf(true) }
    LaunchedEffect(selectedPanel) {
        if (selectedPanel == FullScreenPlayerPanel.Queue) {
            queueBadgeVisible = false
        } else {
            delay(QueueStatusBadgeRevealDelayMs.toLong())
            queueBadgeVisible = true
        }
    }

    val transcriptSelected = selectedPanel == FullScreenPlayerPanel.Transcript
    val queueSelected = selectedPanel == FullScreenPlayerPanel.Queue
    val transcriptBackground by animateColorAsState(
        if (transcriptSelected) sliderFilledTrackColor(colors, false) else colors.foregroundSubtle.copy(alpha = 0f),
        tween(QueueButtonSelectionTransitionDurationMs, easing = FastOutSlowInEasing),
        label = "full-screen-transcript-button-background",
    )
    val queueBackground by animateColorAsState(
        if (queueSelected) sliderFilledTrackColor(colors, false) else colors.foregroundSubtle.copy(alpha = 0f),
        tween(QueueButtonSelectionTransitionDurationMs, easing = FastOutSlowInEasing),
        label = "full-screen-queue-button-background",
    )
    val sleepBackground by animateColorAsState(
        if (sleepTimer.isActive) sliderFilledTrackColor(colors, false) else colors.foregroundSubtle.copy(alpha = 0f),
        tween(QueueButtonSelectionTransitionDurationMs, easing = FastOutSlowInEasing),
        label = "full-screen-sleep-button-background",
    )
    val transcriptIcon by animateColorAsState(
        if (transcriptSelected) colors.playerBackdrop.copy(alpha = 0.72f) else colors.foregroundSubtle,
        tween(QueueButtonSelectionTransitionDurationMs, easing = FastOutSlowInEasing),
        label = "full-screen-transcript-button-icon",
    )
    val queueIcon by animateColorAsState(
        if (queueSelected) colors.playerBackdrop.copy(alpha = 0.72f) else colors.foregroundSubtle,
        tween(QueueButtonSelectionTransitionDurationMs, easing = FastOutSlowInEasing),
        label = "full-screen-queue-button-icon",
    )
    val sleepIcon by animateColorAsState(
        if (sleepTimer.isActive) colors.playerBackdrop.copy(alpha = 0.72f) else colors.foregroundSubtle,
        tween(QueueButtonSelectionTransitionDurationMs, easing = FastOutSlowInEasing),
        label = "full-screen-sleep-button-icon",
    )

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        controls.forEach { action ->
            when (action) {
                "lyrics" -> FullScreenControlSlot {
            FullScreenTransportButton(
                symbol = MaterialSymbols.Chat,
                label = stringResource(R.string.player_transcript),
                onClick = { onPanelSelected(if (transcriptSelected) null else FullScreenPlayerPanel.Transcript) },
                iconSize = 24.dp,
                tint = transcriptIcon,
                containerColor = transcriptBackground,
                filled = false,
                selected = transcriptSelected,
            )
        }
                "sleep" -> FullScreenControlSlot {
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                FullScreenTransportButton(
                    symbol = MaterialSymbols.Bedtime,
                    label = stringResource(R.string.player_sleep_timer),
                    onClick = onOpenSleepTimer,
                    iconSize = 23.dp,
                    tint = sleepIcon,
                    containerColor = sleepBackground,
                    filled = sleepTimer.isActive,
                    selected = sleepTimer.isActive,
                )
                if (sleepTimer.isActive) {
                    val badgeText = when {
                        sleepTimer.preset == SleepTimerPreset.EndOfEpisode -> "End"
                        sleepTimer.preset == SleepTimerPreset.AfterTracks && sleepTimer.remainingTracks != null ->
                            "${sleepTimer.remainingTracks}T"
                        sleepTimer.remainingSeconds != null -> formatTimerCountdown(sleepTimer.remainingSeconds)
                        else -> null
                    }
                    badgeText?.let { text ->
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 2.dp, end = 0.dp)
                                .background(colors.primary, RoundedCornerShape(8.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        ) {
                            Text(
                                text = text,
                                color = colors.onPrimary,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                            )
                        }
                    }
                }
            }
        }
                "output" -> if (output != null) {
            FullScreenControlSlot { output() }
        }
                "queue" -> FullScreenControlSlot {
            Box(Modifier.size(64.dp)) {
                FullScreenTransportButton(
                    symbol = MaterialSymbols.QueueMusic,
                    label = stringResource(R.string.player_queue),
                    onClick = { onPanelSelected(if (queueSelected) null else FullScreenPlayerPanel.Queue) },
                    iconSize = 24.dp,
                    tint = queueIcon,
                    containerColor = queueBackground,
                    filled = false,
                    selected = queueSelected,
                )
                if (!queueSelected && queueBadgeVisible) {
                    queueStatusBadgeSymbol(queue)?.let { QueueStatusBadge(it) }
                }
            }
        }
            }
        }

    }
}

@Composable
internal fun RowScope.FullScreenControlSlot(content: @Composable () -> Unit) {
    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) { content() }
}

internal fun queueStatusBadgeSymbol(queue: PlaybackQueueSnapshot): String? = when {
    queue.shuffle -> MaterialSymbols.Shuffle
    queue.repeatMode == RepeatMode.One -> MaterialSymbols.RepeatOne
    queue.repeatMode == RepeatMode.All -> MaterialSymbols.Repeat
    else -> null
}

internal fun fullScreenSecondaryControlBackground(colors: PodcastyColors): Color =
    colors.sliderInactive.copy(alpha = 0.06f)

@Composable
internal fun BoxScope.QueueStatusBadge(symbol: String) {
    val colors = LocalPodcastyColors.current
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .padding(2.dp)
            .size(20.dp)
            .semantics { testTag = FullScreenQueueStatusBadgeTestTag }
            .background(fullScreenSecondaryControlBackground(colors), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        MaterialSymbol(symbol, null, tint = colors.onPrimary, size = 13.dp)
    }
}

@Composable
internal fun FullScreenTransportButton(
    symbol: String? = null,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    iconSize: Dp = 32.dp,
    buttonSize: Dp = 64.dp,
    tint: Color? = null,
    containerColor: Color? = null,
    filled: Boolean = true,
    isPlaying: Boolean? = null,
    skipForward: Boolean? = null,
    selected: Boolean? = null,
) {
    val colors = LocalPodcastyColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    Box(
        Modifier
            .excludeFullScreenSwipe()
            .size(buttonSize)
            .then(
                if (containerColor == null) Modifier
                else Modifier.padding(8.dp).background(containerColor, CircleShape),
            )
            .semantics {
                contentDescription = label
                selected?.let { this.selected = it }
            }
            .clickable(
                enabled = enabled,
                onClick = onClick,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = null,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val iconTint = tint ?: if (enabled || isPlaying != null) colors.onPrimary else colors.foregroundSubtle
        when {
            isPlaying != null -> AnimatedPlayPauseSymbol(isPlaying, !enabled, isPressed, iconTint, iconSize, buttonSize)
            skipForward != null -> AnimatedSkipSymbol(skipForward, isPressed, iconTint, iconSize, buttonSize)
            else -> MaterialSymbol(requireNotNull(symbol), null, tint = iconTint, size = iconSize, filled = filled)
        }
    }
}
