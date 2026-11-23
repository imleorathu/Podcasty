/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.episodes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import com.podcasty.app.R
import com.podcasty.app.core.formatCompactDuration
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.EpisodeSortOption
import com.podcasty.app.domain.PlaybackQueueSnapshot
import com.podcasty.app.domain.PlaybackStatus
import com.podcasty.app.ui.components.EpisodeContextBottomSheetRequest
import com.podcasty.app.ui.components.EpisodeContextMenu
import com.podcasty.app.ui.components.EpisodeRow
import com.podcasty.app.ui.components.HeroCard
import com.podcasty.app.ui.components.LibraryPlaybackActions
import com.podcasty.app.ui.components.LibraryTextFilter
import com.podcasty.app.ui.components.LibraryVirtualList
import com.podcasty.app.ui.components.MaterialSymbols

@Composable
fun EpisodesScreen(
    uiState: EpisodesUiState,
    currentEpisodeId: String?,
    isPlaying: Boolean,
    playbackQueue: PlaybackQueueSnapshot,
    hazeState: HazeState?,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onFilterQueryChange: (String) -> Unit,
    onPlayAll: (Boolean) -> Unit,
    onEpisodeClick: (List<Episode>, String) -> Unit,
    onEpisodePlayNext: (Episode) -> Unit,
    onEpisodeAddToQueue: (Episode) -> Unit,
    onEpisodeSaveToggle: (Episode, Boolean) -> Unit,
    onEpisodeDownloadToggle: (Episode, Boolean) -> Unit,
    onEpisodeGoToPodcast: (Episode) -> Unit,
    onEpisodeGoToHost: (Episode) -> Unit,
    onEpisodeContextBottomSheet: (EpisodeContextBottomSheetRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    var contextEpisode by remember { mutableStateOf<Episode?>(null) }
    val alphaIndexKey: ((Episode) -> String)? = when (uiState.sortOption) {
        EpisodeSortOption.Title -> Episode::title
        EpisodeSortOption.Podcast -> Episode::podcastTitle
        else -> null
    }
    val listPadding = PaddingValues(
        top = contentPadding.calculateTopPadding(),
        bottom = contentPadding.calculateBottomPadding(),
    )

    LibraryVirtualList(
        items = uiState.episodes,
        isLoaded = uiState.isLoaded,
        key = Episode::id,
        contentType = "episode_row",
        listState = listState,
        contentPadding = listPadding,
        modifier = modifier,
        filterKey = "episodes-filter",
        filterActive = uiState.filterQuery.isNotBlank(),
        filterContent = { showDecorations ->
            LibraryTextFilter(
                value = uiState.filterQuery,
                onValueChange = onFilterQueryChange,
                showPlaceholderAndLeadingSymbol = showDecorations,
            )
        },
        leadingContent = if (uiState.episodes.isNotEmpty()) {
            {
                LibraryPlaybackActions(
                    playLabel = stringResource(R.string.player_play),
                    shuffleLabel = stringResource(R.string.player_shuffle),
                    onPlay = { onPlayAll(false) },
                    onShuffle = { onPlayAll(true) },
                    hazeState = hazeState,
                )
            }
        } else {
            null
        },
        alphabeticalIndexKey = alphaIndexKey,
        emptyContent = {
            Box(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                HeroCard(
                    symbol = MaterialSymbols.Headphones,
                    title = stringResource(
                        if (uiState.filterQuery.isNotBlank()) R.string.episodes_no_match_title
                        else R.string.episodes_empty_title,
                    ),
                    description = stringResource(
                        if (uiState.filterQuery.isNotBlank()) R.string.filter_no_match_description
                        else R.string.episodes_empty_description,
                    ),
                )
            }
        },
    ) { episode ->
        val statusLabel = when (episode.playbackStatus) {
            PlaybackStatus.Played -> stringResource(R.string.episode_status_played)
            PlaybackStatus.InProgress -> stringResource(R.string.episode_status_in_progress, formatCompactDuration(episode.remainingMs))
            PlaybackStatus.Unplayed -> formatCompactDuration(episode.durationMs)
        }
        EpisodeContextMenu(
            episode = episode,
            expanded = contextEpisode?.id == episode.id,
            onDismiss = { if (contextEpisode?.id == episode.id) contextEpisode = null },
            hazeState = hazeState,
            playbackQueue = playbackQueue,
            onPlayNext = onEpisodePlayNext,
            onAddToQueue = onEpisodeAddToQueue,
            onSaveToggle = onEpisodeSaveToggle,
            onDownloadToggle = onEpisodeDownloadToggle,
            onGoToPodcast = onEpisodeGoToPodcast,
            onGoToHost = onEpisodeGoToHost,
            onBottomSheetRequested = onEpisodeContextBottomSheet,
        ) {
            EpisodeRow(
                title = episode.title,
                subtitle = "${episode.podcastTitle} · $statusLabel",
                artworkPath = episode.artworkAsset,
                isCurrentPlaying = episode.id == currentEpisodeId,
                isPlaying = isPlaying,
                onClick = { onEpisodeClick(uiState.episodes, episode.id) },
                onMoreClick = { contextEpisode = episode },
                onLongClick = { contextEpisode = episode },
            )
        }
    }
}
