/*
 * Podcasty — Android Podcast & Music Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.podcasty.app.R
import com.podcasty.app.asr.LiveCaptionSessionStatus
import com.podcasty.app.asr.LiveCaptionStatus
import com.podcasty.app.asr.LiveCaptionUiState
import com.podcasty.app.asr.SupportedAsrLanguages
import com.podcasty.app.domain.TranscriptLine
import com.podcasty.app.domain.displayNameForTranscriptLanguage
import com.podcasty.app.transcript.CaptionSyncEngine
import com.podcasty.app.transcript.TranscriptCue
import com.podcasty.app.transcript.TranscriptDocument
import com.podcasty.app.transcript.TranscriptParser
import com.podcasty.app.ui.components.PodcastyPillButton
import com.podcasty.app.ui.components.PodcastyPillButtonVariant
import com.podcasty.app.ui.theme.LocalPodcastyColors
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TranscriptMotionDurationMs = 320
private const val TranscriptPlaybackTickMs = 250

internal fun parseEpisodeTranscript(content: String): List<TranscriptLine> =
    TranscriptParser.parse(episodeId = "inline", content = content).cues.map { cue ->
        TranscriptLine(
            timestampSeconds = if (cue.startMs >= 0L) cue.startMs / 1_000f else null,
            speaker = cue.speaker,
            text = cue.text,
        )
    }

internal fun syncedTranscriptBlurRadius(distance: Int) = when (distance) {
    0 -> 0.dp
    1 -> 0.35.dp
    2 -> 1.25.dp
    else -> 2.dp
}

/**
 * Unified display row adapter that renders either normal music lyrics OR live podcast AI captions
 * without mixing the underlying data sources.
 */
private data class DisplayCaptionRow(
    val key: String,
    val cue: TranscriptCue,
    val translation: String? = cue.translation,
    val isPartial: Boolean = false,
)

private val QuickLyricsFontScales = listOf(0.85f, 1.0f, 1.15f, 1.30f)

@Composable
internal fun FullScreenPlayerTranscriptPanel(
    episodeId: String,
    transcript: String?,
    currentPositionMs: Long,
    isPlaying: Boolean,
    pendingSeekPositionMs: Long?,
    seekRequestId: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    playbackSpeed: Float = 1.0f,
    useLargeText: Boolean = false,
    textScale: Float = 1f,
    captionFontScale: Float = 1f,
    autoSyncEnabled: Boolean = true,
    autoScrollEnabled: Boolean = true,
    showTranslation: Boolean = true,
    onToggleTranslation: ((Boolean) -> Unit)? = null,
    onFontScaleChange: ((Float) -> Unit)? = null,
    activeDocument: TranscriptDocument? = null,
    availableDocuments: List<TranscriptDocument> = emptyList(),
    isYouTubeReference: Boolean = false,
    hasLinkedAudio: Boolean = true,
    isExtractingTranscript: Boolean = false,
    liveCaptionState: LiveCaptionUiState = LiveCaptionUiState(),
    onSelectAsrLanguage: (String) -> Unit = {},
    onRetryLiveCaptions: () -> Unit = {},
    onOpenCaptionSettings: () -> Unit = {},
    onSelectLanguage: (String) -> Unit = {},
    onImportTranscriptClick: () -> Unit = {},
    onOpenYouTubeClick: (() -> Unit)? = null,
    onLinkAudioClick: (() -> Unit)? = null,
) {
    val colors = LocalPodcastyColors.current
    val effectiveScale = (textScale * captionFontScale).coerceIn(0.75f, 2.0f)

    // Strictly separate data sources:
    // - RSS / online podcast streams -> live AI captions (or imported transcript if attached)
    // - Normal music / local tracks -> existing lyrics only (never replaced by ASR)
    val isLivePodcastMode = liveCaptionState.isPodcastCaptionMode && liveCaptionState.episodeId == episodeId
    val staticCues = remember(episodeId, activeDocument, transcript) {
        when {
            activeDocument != null && activeDocument.cues.isNotEmpty() -> activeDocument.cues
            !transcript.isNullOrBlank() -> TranscriptParser.parse(episodeId, transcript).cues
            else -> emptyList()
        }
    }

    val secondaryDoc = remember(activeDocument, availableDocuments) {
        if (activeDocument == null || availableDocuments.size < 2) null
        else availableDocuments.firstOrNull {
            !it.language.equals(activeDocument.language, ignoreCase = true) && it.cues.isNotEmpty()
        }
    }

    val hasAnyTranslation = remember(staticCues, secondaryDoc) {
        staticCues.any { !it.translation.isNullOrBlank() } || secondaryDoc != null
    }

    val displayRows: List<DisplayCaptionRow> = remember(
        isLivePodcastMode,
        liveCaptionState.finalizedSegments,
        liveCaptionState.activePartial,
        staticCues,
        secondaryDoc,
        showTranslation,
    ) {
        if (isLivePodcastMode && staticCues.isEmpty()) {
            liveCaptionState.allDisplaySegments.mapIndexed { idx, seg ->
                DisplayCaptionRow(
                    key = "${seg.id}_${seg.status.name}_$idx",
                    cue = TranscriptCue(
                        id = idx.toLong(),
                        startMs = seg.startMs,
                        endMs = seg.endMs,
                        text = seg.text,
                        speaker = if (seg.status == LiveCaptionStatus.PARTIAL) "LIVE" else null,
                    ),
                    isPartial = seg.status == LiveCaptionStatus.PARTIAL,
                )
            }
        } else {
            val secTimedCues = secondaryDoc?.cues?.filter { it.startMs >= 0L }?.sortedBy { it.startMs }.orEmpty()
            staticCues.mapIndexed { idx, cue ->
                val pairedTranslation = when {
                    !showTranslation -> null
                    !cue.translation.isNullOrBlank() -> cue.translation
                    secTimedCues.isNotEmpty() && cue.startMs >= 0L -> {
                        val matchIdx = CaptionSyncEngine.findActiveCueIndex(secTimedCues, cue.startMs)
                        val candidate = secTimedCues.getOrNull(matchIdx)
                        if (candidate != null && abs(candidate.startMs - cue.startMs) <= 1_500L &&
                            !candidate.text.equals(cue.text, ignoreCase = true)
                        ) {
                            candidate.text
                        } else {
                            null
                        }
                    }
                    else -> null
                }
                DisplayCaptionRow(
                    key = "${cue.id}_$idx",
                    cue = cue,
                    translation = pairedTranslation,
                    isPartial = false,
                )
            }
        }
    }

    val syncedRows = remember(displayRows) { displayRows.filter { it.cue.startMs >= 0L } }
    var resyncTrigger by remember(episodeId) { mutableLongStateOf(0L) }
    var isManuallyBrowsing by remember(episodeId) { mutableStateOf(false) }

    Column(
        modifier = modifier.padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Subtle Airmedy-native live podcast caption status & language switcher (only during RSS podcast playback)
        if (isLivePodcastMode && staticCues.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .excludeFullScreenSwipe(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LiveCaptionStatusPill(
                    status = liveCaptionState.status,
                    isSpeechDetected = liveCaptionState.isSpeechDetected,
                )
                SupportedAsrLanguages.forEach { lang ->
                    val selected = lang.code.equals(liveCaptionState.languageCode, ignoreCase = true)
                    CaptionChip(
                        label = lang.shortLabel,
                        selected = selected,
                        onClick = { onSelectAsrLanguage(lang.code) },
                    )
                }
                if (liveCaptionState.status == LiveCaptionSessionStatus.Error ||
                    liveCaptionState.status == LiveCaptionSessionStatus.ConfigurationRequired
                ) {
                    CaptionChip(
                        label = stringResource(R.string.live_captions_retry),
                        selected = false,
                        onClick = onRetryLiveCaptions,
                    )
                }
            }
        }

        // Language selector, quick font-size, translation toggle, and recenter chips
        val showToolbar = !isLivePodcastMode && displayRows.isNotEmpty() && (
            availableDocuments.size > 1 ||
                onFontScaleChange != null ||
                (hasAnyTranslation && onToggleTranslation != null) ||
                isManuallyBrowsing
            )
        if (showToolbar) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .excludeFullScreenSwipe(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isManuallyBrowsing) {
                    CaptionChip(
                        label = stringResource(R.string.lyrics_resync),
                        selected = true,
                        onClick = { resyncTrigger += 1L },
                    )
                }
                if (onFontScaleChange != null) {
                    val currentIdx = QuickLyricsFontScales.indices.minByOrNull {
                        abs(QuickLyricsFontScales[it] - captionFontScale)
                    } ?: 1
                    CaptionChip(
                        label = "A−",
                        selected = false,
                        onClick = {
                            val next = QuickLyricsFontScales[(currentIdx - 1).coerceAtLeast(0)]
                            if (next != captionFontScale) onFontScaleChange(next)
                        },
                    )
                    CaptionChip(
                        label = "A+",
                        selected = false,
                        onClick = {
                            val next = QuickLyricsFontScales[(currentIdx + 1).coerceAtMost(QuickLyricsFontScales.lastIndex)]
                            if (next != captionFontScale) onFontScaleChange(next)
                        },
                    )
                }
                if (hasAnyTranslation && onToggleTranslation != null) {
                    CaptionChip(
                        label = stringResource(R.string.lyrics_translation_chip),
                        selected = showTranslation,
                        onClick = { onToggleTranslation(!showTranslation) },
                    )
                }
                if (availableDocuments.size > 1) {
                    availableDocuments.forEach { doc ->
                        val selected = doc.language.equals(activeDocument?.language, ignoreCase = true)
                        CaptionChip(
                            label = displayNameForTranscriptLanguage(doc.language),
                            selected = selected,
                            onClick = { onSelectLanguage(doc.language) },
                        )
                    }
                }
            }
        }

        // Non-intrusive inline error notice if recognition fails mid-episode while preserving previous finalized lines
        if (isLivePodcastMode && !liveCaptionState.errorMessage.isNullOrBlank() && displayRows.isNotEmpty()) {
            Text(
                text = liveCaptionState.errorMessage,
                color = colors.onPrimary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.primary.copy(alpha = 0.25f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (displayRows.isEmpty()) {
                if (isLivePodcastMode) {
                    LiveCaptionsStatusState(
                        state = liveCaptionState,
                        onRetry = onRetryLiveCaptions,
                        onConfigure = onOpenCaptionSettings,
                        onImportTranscriptClick = onImportTranscriptClick,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    TranscriptEmptyState(
                        isYouTubeReference = isYouTubeReference,
                        hasLinkedAudio = hasLinkedAudio,
                        isExtractingTranscript = isExtractingTranscript,
                        onImportTranscriptClick = onImportTranscriptClick,
                        onOpenYouTubeClick = onOpenYouTubeClick,
                        onLinkAudioClick = onLinkAudioClick,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                val typography = MaterialTheme.typography
                MaterialTheme(
                    typography = typography.copy(
                        titleLarge = typography.titleLarge.copy(
                            fontSize = (if (useLargeText) 26.sp else typography.titleLarge.fontSize) * effectiveScale,
                            lineHeight = (if (useLargeText) 36.sp else typography.titleLarge.lineHeight) * effectiveScale,
                        ),
                        bodyLarge = typography.bodyLarge.copy(
                            fontSize = (if (useLargeText) 18.sp else typography.bodyLarge.fontSize) * effectiveScale,
                            lineHeight = (if (useLargeText) 28.sp else typography.bodyLarge.lineHeight) * effectiveScale,
                        ),
                    ),
                ) {
                    if (syncedRows.isNotEmpty() && autoSyncEnabled) {
                        SyncedTranscriptList(
                            episodeId = episodeId,
                            rows = syncedRows,
                            currentPositionMs = currentPositionMs,
                            playbackSpeed = playbackSpeed,
                            isPlaying = isPlaying,
                            autoScrollEnabled = autoScrollEnabled,
                            pendingSeekPositionMs = pendingSeekPositionMs,
                            seekRequestId = seekRequestId,
                            resyncTrigger = resyncTrigger,
                            onBrowsingChanged = { isManuallyBrowsing = it },
                            canSeek = hasLinkedAudio,
                            overrideActiveIndex = if (isLivePodcastMode && staticCues.isEmpty() && liveCaptionState.activeSegmentIndex >= 0) {
                                liveCaptionState.activeSegmentIndex.coerceIn(0, syncedRows.lastIndex)
                            } else {
                                null
                            },
                            onSeek = onSeek,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        PlainTranscriptList(displayRows, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveCaptionStatusPill(
    status: LiveCaptionSessionStatus,
    isSpeechDetected: Boolean,
) {
    val colors = LocalPodcastyColors.current
    val label = when (status) {
        LiveCaptionSessionStatus.Initializing -> "Initializing AI…"
        LiveCaptionSessionStatus.Listening -> if (isSpeechDetected) "Live AI · Speaking" else "Live AI"
        LiveCaptionSessionStatus.Paused -> "Live AI · Paused"
        LiveCaptionSessionStatus.Reconnecting -> "Reconnecting…"
        LiveCaptionSessionStatus.ConfigurationRequired -> "Setup needed"
        LiveCaptionSessionStatus.Error -> "Caption error"
        LiveCaptionSessionStatus.Idle -> "Live AI"
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.onPrimary.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status == LiveCaptionSessionStatus.Initializing || status == LiveCaptionSessionStatus.Reconnecting) {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                strokeWidth = 1.5.dp,
                color = colors.onPrimary,
            )
        } else {
            val dotColor = when (status) {
                LiveCaptionSessionStatus.Listening -> if (isSpeechDetected) colors.success else colors.onPrimary.copy(alpha = 0.7f)
                LiveCaptionSessionStatus.Error, LiveCaptionSessionStatus.ConfigurationRequired -> colors.primary
                else -> colors.foregroundSubtle
            }
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
        Text(
            text = label,
            color = colors.onPrimary,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CaptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalPodcastyColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) colors.onPrimary.copy(alpha = 0.26f)
                else colors.sliderInactive.copy(alpha = 0.12f),
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            color = if (selected) colors.onPrimary else colors.foregroundSubtle,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LiveCaptionsStatusState(
    state: LiveCaptionUiState,
    onRetry: () -> Unit,
    onConfigure: () -> Unit,
    onImportTranscriptClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = LocalPodcastyColors.current
    Box(modifier = modifier.padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.status == LiveCaptionSessionStatus.Initializing ||
                state.status == LiveCaptionSessionStatus.Reconnecting ||
                state.status == LiveCaptionSessionStatus.Listening
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.5.dp,
                    color = colors.onPrimary,
                )
            }
            val message = when {
                !state.errorMessage.isNullOrBlank() -> state.errorMessage
                !state.statusMessage.isNullOrBlank() -> state.statusMessage
                state.status == LiveCaptionSessionStatus.Listening ->
                    "Listening for speech (${state.languageCode})… Live captions will appear automatically as the speaker talks."
                else -> "Live AI captions are ready for this podcast."
            }
            Text(
                text = message,
                color = colors.foregroundSubtle,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            if (state.status == LiveCaptionSessionStatus.ConfigurationRequired ||
                state.status == LiveCaptionSessionStatus.Error
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PodcastyPillButton(
                        label = stringResource(R.string.live_captions_configure_settings),
                        onClick = onConfigure,
                        variant = PodcastyPillButtonVariant.Primary,
                        modifier = Modifier.weight(1f),
                    )
                    PodcastyPillButton(
                        label = stringResource(R.string.live_captions_retry),
                        onClick = onRetry,
                        variant = PodcastyPillButtonVariant.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            PodcastyPillButton(
                label = stringResource(R.string.transcript_import_action),
                onClick = onImportTranscriptClick,
                variant = PodcastyPillButtonVariant.Secondary,
            )
        }
    }
}

@Composable
private fun rememberTranscriptDisplayPosition(
    episodeId: String,
    currentPositionMs: Long,
    pendingSeekPositionMs: Long?,
    isPlaying: Boolean,
    playbackSpeed: Float,
): Long {
    val speed = playbackSpeed.coerceIn(0.5f, 2.5f)
    val maxAdvanceMs = (TranscriptPlaybackTickMs * speed).toLong().coerceAtLeast(100L)
    val clock = remember(episodeId) { Animatable(currentPositionMs.toFloat()) }
    LaunchedEffect(episodeId, currentPositionMs, pendingSeekPositionMs, isPlaying, speed) {
        val pos = (pendingSeekPositionMs ?: currentPositionMs).toFloat()
        clock.snapTo(pos)
        if (isPlaying && pendingSeekPositionMs == null) {
            clock.animateTo(pos + maxAdvanceMs, tween(TranscriptPlaybackTickMs, easing = LinearEasing))
        }
    }
    val clockMs = clock.value.toLong()
    return pendingSeekPositionMs ?: if (isPlaying && abs(clockMs - currentPositionMs) <= maxAdvanceMs + 50L) {
        clockMs
    } else {
        currentPositionMs
    }
}

@Composable
private fun SyncedTranscriptList(
    episodeId: String,
    rows: List<DisplayCaptionRow>,
    currentPositionMs: Long,
    playbackSpeed: Float,
    isPlaying: Boolean,
    autoScrollEnabled: Boolean,
    pendingSeekPositionMs: Long?,
    seekRequestId: Long,
    resyncTrigger: Long,
    onBrowsingChanged: (Boolean) -> Unit,
    canSeek: Boolean,
    overrideActiveIndex: Int?,
    onSeek: (Long) -> Unit,
    modifier: Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val listState = rememberLazyListState()
    val cues = remember(rows) { rows.map { it.cue } }
    val rowHeights = remember(rows) { mutableStateMapOf<Int, Int>() }
    val trailingHeights = remember(rows) { mutableStateMapOf<Int, Int>() }
    val rowBottomPaddingPx = with(LocalDensity.current) { 10.dp.roundToPx() }
    var hasPositionedInitial by rememberSaveable(episodeId) { mutableStateOf(false) }
    var previousActiveIndex by rememberSaveable(episodeId) { mutableStateOf<Int?>(null) }
    var isBrowsing by rememberSaveable(episodeId) { mutableStateOf(false) }
    var selectedLineIndex by rememberSaveable(episodeId) { mutableStateOf<Int?>(null) }
    var isFollowingSelected by rememberSaveable(episodeId) { mutableStateOf(false) }
    var returnedToForeground by rememberSaveable(episodeId) { mutableStateOf(false) }
    var previousPositionMs by rememberSaveable(episodeId) { mutableLongStateOf(currentPositionMs) }
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    val displayedPositionMs = rememberTranscriptDisplayPosition(
        episodeId = episodeId,
        currentPositionMs = currentPositionMs,
        pendingSeekPositionMs = pendingSeekPositionMs,
        isPlaying = isPlaying,
        playbackSpeed = playbackSpeed,
    )

    val activeIndex = remember(cues, displayedPositionMs, overrideActiveIndex) {
        overrideActiveIndex ?: CaptionSyncEngine.findActiveCueIndex(cues, displayedPositionMs)
    }

    LaunchedEffect(isBrowsing) {
        onBrowsingChanged(isBrowsing)
    }

    DisposableEffect(lifecycleOwner, episodeId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                returnedToForeground = true
                isBrowsing = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(episodeId) {
        isBrowsing = false
        selectedLineIndex = null
        hasPositionedInitial = false
        previousActiveIndex = null
        listState.scrollToItem(0)
    }
    LaunchedEffect(episodeId, currentPositionMs) {
        if (previousPositionMs > 1_000L && currentPositionMs <= 1_000L) {
            isBrowsing = false
            selectedLineIndex = null
            listState.scrollToItem(0)
        }
        previousPositionMs = currentPositionMs
    }
    LaunchedEffect(isUserDragging, isFollowingSelected) {
        if (isUserDragging && !isFollowingSelected) isBrowsing = true
    }

    suspend fun previousLineOffset(targetIndex: Int): Int {
        val prev = (targetIndex - 1).coerceAtLeast(0)
        if (prev >= targetIndex) return 0
        val (h, trailing) = snapshotFlow { rowHeights[prev] to trailingHeights[prev] }
            .first { (rowH, tH) -> rowH != null && tH != null }
        return ((h ?: 0) - (trailing ?: 0) - rowBottomPaddingPx).coerceAtLeast(0)
    }

    suspend fun scrollToActive(targetIndex: Int, animate: Boolean) {
        val prev = (targetIndex - 1).coerceAtLeast(0)
        val visibleTarget = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == prev }
        if (animate && visibleTarget != null) {
            val offset = previousLineOffset(targetIndex)
            listState.animateScrollBy(
                (visibleTarget.offset + offset).toFloat(),
                animationSpec = tween(TranscriptMotionDurationMs, easing = FastOutSlowInEasing),
            )
        } else {
            listState.scrollToItem(prev)
            listState.scrollToItem(prev, previousLineOffset(targetIndex))
        }
    }

    LaunchedEffect(seekRequestId, resyncTrigger) {
        if (seekRequestId > 0L || resyncTrigger > 0L) {
            isBrowsing = false
            selectedLineIndex = null
            scrollToActive(activeIndex.coerceAtLeast(0), animate = true)
            hasPositionedInitial = true
            previousActiveIndex = activeIndex.coerceAtLeast(0)
        }
    }

    LaunchedEffect(selectedLineIndex) {
        val sel = selectedLineIndex ?: return@LaunchedEffect
        isFollowingSelected = true
        try {
            scrollToActive(sel, animate = true)
            hasPositionedInitial = true
            previousActiveIndex = sel
        } finally {
            isFollowingSelected = false
            selectedLineIndex = null
        }
    }

    LaunchedEffect(rows.size, activeIndex, isBrowsing, selectedLineIndex, returnedToForeground, autoScrollEnabled) {
        if (!autoScrollEnabled || isBrowsing || selectedLineIndex != null) return@LaunchedEffect
        val safeTarget = activeIndex.coerceAtLeast(0)
        if (!hasPositionedInitial) {
            scrollToActive(safeTarget, animate = false)
            hasPositionedInitial = true
            returnedToForeground = false
        } else {
            val prevVisible = previousActiveIndex?.let { idx ->
                listState.layoutInfo.visibleItemsInfo.any { it.index == idx }
            } ?: false
            if (prevVisible || returnedToForeground || safeTarget == rows.lastIndex) {
                scrollToActive(safeTarget, animate = !returnedToForeground)
                returnedToForeground = false
            }
        }
        previousActiveIndex = safeTarget
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = 24.dp),
        modifier = modifier.testTag("synced_transcript_list"),
    ) {
        itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
            val distance = if (activeIndex < 0) index + 1 else abs(index - activeIndex)
            SyncedTranscriptRow(
                cue = row.cue,
                translation = row.translation,
                isPartial = row.isPartial,
                distance = distance,
                focusMode = !isBrowsing,
                onClick = {
                    if (canSeek && !row.isPartial) {
                        isBrowsing = false
                        selectedLineIndex = index
                        onSeek(row.cue.startMs)
                    }
                },
                onRowHeightChanged = { rowHeights[index] = it },
                onTrailingLineHeightChanged = { trailingHeights[index] = it },
            )
        }
    }
}

@Composable
private fun SyncedTranscriptRow(
    cue: TranscriptCue,
    translation: String?,
    isPartial: Boolean,
    distance: Int,
    focusMode: Boolean,
    onClick: () -> Unit,
    onRowHeightChanged: (Int) -> Unit,
    onTrailingLineHeightChanged: (Int) -> Unit,
) {
    val colors = LocalPodcastyColors.current
    val latestOnClick = rememberUpdatedState(onClick)
    val targetOpacity = if (!focusMode) {
        if (isPartial) 0.78f else 1f
    } else {
        when (distance) {
            0 -> if (isPartial) 0.82f else 1f
            1 -> 0.30f
            2 -> 0.18f
            else -> 0.12f
        }
    }
    val opacity by animateFloatAsState(
        targetOpacity,
        tween(TranscriptMotionDurationMs, easing = FastOutSlowInEasing),
        label = "synced-transcript-opacity",
    )
    val animatedBlur by animateDpAsState(
        if (focusMode) syncedTranscriptBlurRadius(distance) else 0.dp,
        tween(TranscriptMotionDurationMs, easing = FastOutSlowInEasing),
        label = "synced-transcript-blur",
    )
    val scale by animateFloatAsState(
        if (focusMode && distance == 0) 1.03f else 1f,
        tween(TranscriptMotionDurationMs, easing = FastOutSlowInEasing),
        label = "synced-transcript-scale",
    )
    val tapSlopPx = with(LocalDensity.current) { 20.dp.toPx() }
    val activeOffsetPx = with(LocalDensity.current) { 3.dp.toPx() }
    val translationY by animateFloatAsState(
        if (focusMode && distance == 0) -activeOffsetPx else 0f,
        tween(TranscriptMotionDurationMs, easing = FastOutSlowInEasing),
        label = "synced-transcript-offset",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 10.dp, end = 16.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.translationY = translationY
                transformOrigin = TransformOrigin(0f, 0.5f)
                clip = false
            }
            .blur(animatedBlur, edgeTreatment = BlurredEdgeTreatment.Unbounded)
            .onSizeChanged { onRowHeightChanged(it.height) }
            .pointerInput(cue.startMs, isPartial) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = down.position
                    var maxDrag = 0f
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        maxDrag = maxOf(maxDrag, (change.position - start).getDistance())
                        pressed = change.pressed
                    }
                    if (maxDrag <= tapSlopPx) latestOnClick.value()
                }
            }
            .semantics {
                role = Role.Button
                onClick { latestOnClick.value(); true }
            },
    ) {
        cue.speaker?.let { speaker ->
            Text(
                text = speaker.uppercase(),
                color = colors.foregroundSubtle.copy(alpha = (opacity * 0.9f).coerceIn(0.1f, 1f)),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        Text(
            text = cue.text,
            color = colors.onPrimary.copy(alpha = opacity),
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = if (isPartial) FontWeight.SemiBold else FontWeight.Bold,
                fontStyle = if (isPartial) FontStyle.Italic else FontStyle.Normal,
            ),
            onTextLayout = { layout ->
                val last = layout.lineCount - 1
                if (last >= 0) {
                    onTrailingLineHeightChanged((layout.getLineBottom(last) - layout.getLineTop(last)).roundToInt())
                }
            },
        )
        if (!translation.isNullOrBlank()) {
            Text(
                text = translation,
                color = colors.onPrimary.copy(alpha = (opacity * 0.78f).coerceIn(0.10f, 0.86f)),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun PlainTranscriptList(rows: List<DisplayCaptionRow>, modifier: Modifier) {
    val colors = LocalPodcastyColors.current
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        itemsIndexed(rows, key = { index, row -> "${row.key}_$index" }) { _, row ->
            val cue = row.cue
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                cue.speaker?.let { speaker ->
                    Text(
                        text = speaker.uppercase(),
                        color = colors.foregroundSubtle,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                Text(
                    text = cue.text,
                    color = colors.onPrimary,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (!row.translation.isNullOrBlank()) {
                    Text(
                        text = row.translation,
                        color = colors.foregroundSubtle,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TranscriptEmptyState(
    isYouTubeReference: Boolean,
    hasLinkedAudio: Boolean,
    isExtractingTranscript: Boolean,
    onImportTranscriptClick: () -> Unit,
    onOpenYouTubeClick: (() -> Unit)?,
    onLinkAudioClick: (() -> Unit)?,
    modifier: Modifier,
) {
    val colors = LocalPodcastyColors.current
    Box(modifier = modifier.padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (isExtractingTranscript) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.5.dp,
                    color = colors.onPrimary,
                )
            }
            val message = when {
                isExtractingTranscript -> "Extracting & embedding synchronized YouTube captions…"
                isYouTubeReference -> stringResource(R.string.youtube_transcript_unavailable)
                else -> stringResource(R.string.player_transcript_not_available)
            }
            Text(
                text = message,
                color = colors.foregroundSubtle,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            if (!isExtractingTranscript) {
                PodcastyPillButton(
                    label = stringResource(R.string.transcript_import_action),
                    onClick = onImportTranscriptClick,
                    variant = PodcastyPillButtonVariant.Secondary,
                )
                if (isYouTubeReference && onOpenYouTubeClick != null) {
                    PodcastyPillButton(
                        label = stringResource(R.string.youtube_open_official),
                        onClick = onOpenYouTubeClick,
                        variant = PodcastyPillButtonVariant.Primary,
                    )
                }
                if (isYouTubeReference && !hasLinkedAudio && onLinkAudioClick != null) {
                    PodcastyPillButton(
                        label = stringResource(R.string.youtube_link_audio_source),
                        onClick = onLinkAudioClick,
                        variant = PodcastyPillButtonVariant.Secondary,
                    )
                }
            }
        }
    }
}
