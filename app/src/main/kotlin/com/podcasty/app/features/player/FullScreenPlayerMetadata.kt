/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import com.podcasty.app.R
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.PlaybackItem
import com.podcasty.app.domain.PlaybackQueueSnapshot
import com.podcasty.app.ui.components.EpisodeContextBottomSheetRequest
import com.podcasty.app.ui.components.EpisodeContextMenu
import com.podcasty.app.ui.components.MaterialSymbols
import com.podcasty.app.ui.components.PodcastyIconButton
import com.podcasty.app.ui.components.PodcastyIconButtonVariant
import com.podcasty.app.ui.components.PodcastyMarqueeText
import com.podcasty.app.ui.theme.LocalPodcastyColors

internal const val FullScreenPlayerMetadataSwipeTestTag = "full_screen_player_metadata_swipe_target"

@Composable
internal fun FullScreenPlayerMetadataTransition(
    item: PlaybackItem,
    displayedHorizontalSwipeOffset: Float,
    hazeState: HazeState?,
    compact: Boolean,
    isSaved: Boolean,
    onSaveToggle: (String, Boolean) -> Unit,
    onDownloadToggle: (String, Boolean) -> Unit,
    contextEpisode: Episode?,
    contextMenuExpanded: Boolean,
    onContextMenuOpen: () -> Unit,
    onContextMenuDismiss: () -> Unit,
    playbackQueue: PlaybackQueueSnapshot,
    onEpisodePlayNext: (String) -> Unit,
    onEpisodeAddToQueue: (String) -> Unit,
    onEpisodeGoToPodcast: (String) -> Unit,
    onEpisodeGoToHost: (String) -> Unit,
    onOpenEqualizer: () -> Unit = {},
    onEpisodeContextBottomSheet: (EpisodeContextBottomSheetRequest) -> Unit,
    onCloseFullscreenThen: ((() -> Unit) -> Unit),
    showMore: Boolean = true,
) {
    AnimatedContent(
        targetState = item,
        transitionSpec = {
            if (targetState.episodeId != initialState.episodeId) {
                (slideInHorizontally(tween(200, easing = FastOutSlowInEasing)) { it / 4 } + fadeIn(tween(200, easing = FastOutSlowInEasing))) togetherWith
                    (slideOutHorizontally(tween(180, easing = FastOutSlowInEasing)) { -it / 4 } + fadeOut(tween(160, easing = FastOutSlowInEasing)))
            } else {
                EnterTransition.None togetherWith ExitTransition.None
            }
        },
        label = "full-screen-player-metadata-crossfade",
    ) { animatedItem ->
        FullScreenPlayerMetadata(
            item = animatedItem,
            displayedHorizontalSwipeOffset = displayedHorizontalSwipeOffset,
            hazeState = hazeState,
            compact = compact,
            isSaved = isSaved,
            onSaveToggle = onSaveToggle,
            onDownloadToggle = onDownloadToggle,
            contextEpisode = contextEpisode,
            contextMenuExpanded = contextMenuExpanded,
            onContextMenuOpen = onContextMenuOpen,
            onContextMenuDismiss = onContextMenuDismiss,
            playbackQueue = playbackQueue,
            onEpisodePlayNext = onEpisodePlayNext,
            onEpisodeAddToQueue = onEpisodeAddToQueue,
            onEpisodeGoToPodcast = onEpisodeGoToPodcast,
            onEpisodeGoToHost = onEpisodeGoToHost,
            onOpenEqualizer = onOpenEqualizer,
            onEpisodeContextBottomSheet = onEpisodeContextBottomSheet,
            onCloseFullscreenThen = onCloseFullscreenThen,
            showMore = showMore,
        )
    }
}

@Composable
private fun FullScreenPlayerMetadata(
    item: PlaybackItem,
    displayedHorizontalSwipeOffset: Float,
    hazeState: HazeState?,
    compact: Boolean,
    isSaved: Boolean,
    onSaveToggle: (String, Boolean) -> Unit,
    onDownloadToggle: (String, Boolean) -> Unit,
    contextEpisode: Episode?,
    contextMenuExpanded: Boolean,
    onContextMenuOpen: () -> Unit,
    onContextMenuDismiss: () -> Unit,
    playbackQueue: PlaybackQueueSnapshot,
    onEpisodePlayNext: (String) -> Unit,
    onEpisodeAddToQueue: (String) -> Unit,
    onEpisodeGoToPodcast: (String) -> Unit,
    onEpisodeGoToHost: (String) -> Unit,
    onOpenEqualizer: () -> Unit = {},
    onEpisodeContextBottomSheet: (EpisodeContextBottomSheetRequest) -> Unit,
    onCloseFullscreenThen: ((() -> Unit) -> Unit),
    showMore: Boolean = true,
) {
    val colors = LocalPodcastyColors.current
    val hapticFeedback = LocalHapticFeedback.current
    val favoriteScale = remember(item.episodeId) { Animatable(1f) }
    var previousSaved by remember(item.episodeId) { mutableStateOf(isSaved) }
    LaunchedEffect(item.episodeId, isSaved) {
        val wasAdded = isSaved && !previousSaved
        previousSaved = isSaved
        if (wasAdded) {
            favoriteScale.snapTo(1f)
            favoriteScale.animateTo(1.14f, tween(120, easing = FastOutSlowInEasing))
            favoriteScale.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
        } else if (!isSaved) {
            favoriteScale.snapTo(1f)
        }
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier
                .weight(1f)
                .clipToBounds()
                .graphicsLayer { translationX = displayedHorizontalSwipeOffset }
                .semantics { testTag = FullScreenPlayerMetadataSwipeTestTag },
        ) {
            PodcastyMarqueeText(
                text = item.title,
                color = colors.onPrimary,
                style = if (compact) {
                    MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                } else {
                    MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                },
                fade = true,
            )
            PodcastyMarqueeText(
                text = item.podcastTitle,
                color = colors.foregroundSubtle,
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                fade = true,
            )
        }
        Spacer(Modifier.width(4.dp))
        if (!compact) {
            PodcastyIconButton(
                symbol = MaterialSymbols.Star,
                label = stringResource(R.string.player_save),
                onClick = {
                    if (!isSaved) hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                    onSaveToggle(item.episodeId, !isSaved)
                },
                modifier = Modifier
                    .excludeFullScreenSwipe()
                    .graphicsLayer { scaleX = favoriteScale.value; scaleY = favoriteScale.value },
                variant = PodcastyIconButtonVariant.Glass,
                tint = colors.onPrimary,
                glassColor = fullScreenSecondaryControlBackground(colors),
                hazeState = hazeState,
                showGlassBorder = false,
                circleSize = 36.dp,
                iconSize = 20.dp,
                filled = isSaved,
                suppressPressedIndication = true,
            )
        }
        if (showMore) {
            @Composable
            fun MoreButton(onClick: () -> Unit) = PodcastyIconButton(
                symbol = MaterialSymbols.MoreVert,
                label = stringResource(R.string.player_more),
                onClick = onClick,
                variant = PodcastyIconButtonVariant.Glass,
                tint = colors.onPrimary,
                glassColor = fullScreenSecondaryControlBackground(colors),
                hazeState = hazeState,
                showGlassBorder = false,
                circleSize = if (compact) 32.dp else 36.dp,
                iconSize = if (compact) 18.dp else 20.dp,
            )

            if (contextEpisode == null) {
                MoreButton(onOpenEqualizer)
            } else {
                EpisodeContextMenu(
                    episode = contextEpisode,
                    expanded = contextMenuExpanded,
                    onDismiss = onContextMenuDismiss,
                    hazeState = hazeState,
                    playbackQueue = playbackQueue,
                    onPlayNext = { onEpisodePlayNext(it.id) },
                    onAddToQueue = { onEpisodeAddToQueue(it.id) },
                    onSaveToggle = { ep, saved -> onSaveToggle(ep.id, saved) },
                    onDownloadToggle = { ep, downloaded -> onDownloadToggle(ep.id, downloaded) },
                    onGoToPodcast = { onEpisodeGoToPodcast(it.podcastId) },
                    onGoToHost = { onEpisodeGoToHost(it.hostId) },
                    onOpenEqualizer = onOpenEqualizer,
                    onBottomSheetRequested = onEpisodeContextBottomSheet,
                    onCloseFullscreenThen = onCloseFullscreenThen,
                ) { MoreButton(onContextMenuOpen) }
            }
        }
    }
}
