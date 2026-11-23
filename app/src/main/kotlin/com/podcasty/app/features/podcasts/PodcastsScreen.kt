/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.podcasts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import com.podcasty.app.R
import com.podcasty.app.core.formatCompactDuration
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.PlaybackQueueSnapshot
import com.podcasty.app.domain.PlaybackStatus
import com.podcasty.app.domain.Podcast
import com.podcasty.app.domain.PodcastLayoutMode
import com.podcasty.app.domain.PodcastSortOption
import com.podcasty.app.ui.components.DiscGridItem
import com.podcasty.app.ui.components.EpisodeContextBottomSheetRequest
import com.podcasty.app.ui.components.EpisodeContextMenu
import com.podcasty.app.ui.components.EpisodeRow
import com.podcasty.app.ui.components.HeroCard
import com.podcasty.app.ui.components.LibraryPlaybackActions
import com.podcasty.app.ui.components.LibraryTextFilter
import com.podcasty.app.ui.components.LibraryVirtualList
import com.podcasty.app.ui.components.MaterialSymbols
import com.podcasty.app.ui.components.PodcastContextMenu
import com.podcasty.app.ui.components.PodcastRow
import com.podcasty.app.ui.components.PodcastyPillButton
import com.podcasty.app.ui.components.PodcastyPillButtonVariant
import com.podcasty.app.ui.components.discGridColumnCount
import com.podcasty.app.ui.components.discGridItems
import com.podcasty.app.ui.theme.LocalPodcastyColors

@Composable
fun PodcastsScreen(
    uiState: PodcastsUiState,
    currentEpisodeId: String?,
    isPlaying: Boolean,
    playbackQueue: PlaybackQueueSnapshot,
    hazeState: HazeState?,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onFilterQueryChange: (String) -> Unit,
    onPodcastClick: (String) -> Unit,
    onAddPodcastClick: (() -> Unit)? = null,
    onPlayAll: (Boolean) -> Unit,
    onPodcastPlay: (List<String>, Boolean) -> Unit,
    onPodcastToggleSubscribe: (String, Boolean) -> Unit,
    onPodcastPlayNext: (List<String>) -> Unit,
    onPodcastAddToQueue: (List<String>) -> Unit,
    onPodcastSaveAll: (List<String>) -> Unit,
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
    val colors = LocalPodcastyColors.current
    var contextPodcastId by remember { mutableStateOf<String?>(null) }
    var contextEpisode by remember { mutableStateOf<Episode?>(null) }
    val alphaKey: ((Podcast) -> String)? = when (uiState.sortOption) {
        PodcastSortOption.Title -> Podcast::title
        PodcastSortOption.Host -> Podcast::hostName
        PodcastSortOption.DateAdded -> null
    }
    val listPadding = PaddingValues(
        top = contentPadding.calculateTopPadding(),
        bottom = contentPadding.calculateBottomPadding(),
    )

    BoxWithConstraints(modifier = modifier) {
        val columns = discGridColumnCount(maxWidth - 48.dp)
        val isGrid = uiState.layoutMode == PodcastLayoutMode.Grid

        LibraryVirtualList(
            items = uiState.podcasts,
            isLoaded = uiState.isLoaded,
            key = Podcast::id,
            contentType = if (isGrid) "podcast_grid_row" else "podcast_row",
            listState = listState,
            contentPadding = listPadding,
            filterKey = "podcasts-filter",
            filterActive = uiState.filterQuery.isNotBlank(),
            filterContent = { showDecorations ->
                LibraryTextFilter(
                    value = uiState.filterQuery,
                    onValueChange = onFilterQueryChange,
                    showPlaceholderAndLeadingSymbol = showDecorations,
                )
            },
            leadingContent = if (uiState.podcasts.isNotEmpty() || uiState.podcastEpisodes.isNotEmpty()) {
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
            alphabeticalIndexKey = alphaKey,
            alphabeticalIndexItemsPerLazyItem = if (isGrid) columns else 1,
            emptyContent = {
                Box(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                    HeroCard(
                        symbol = MaterialSymbols.Podcasts,
                        title = stringResource(
                            if (uiState.filterQuery.isNotBlank()) R.string.podcasts_no_match_title
                            else R.string.podcasts_empty_title,
                        ),
                        description = stringResource(
                            if (uiState.filterQuery.isNotBlank()) R.string.filter_no_match_description
                            else R.string.podcasts_empty_description,
                        ),
                        bottomContent = if (uiState.filterQuery.isBlank() && onAddPodcastClick != null) {
                            {
                                Box(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
                                    PodcastyPillButton(
                                        label = stringResource(R.string.home_podcasts_add_action),
                                        onClick = onAddPodcastClick,
                                        variant = PodcastyPillButtonVariant.Primary,
                                    )
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
            },
            customItemsContent = if (isGrid) {
                {
                    discGridItems(
                        items = uiState.podcasts.map { podcast ->
                            val epCount = uiState.episodesByPodcast[podcast.id].orEmpty().size
                            val epLabel = if (epCount == 1) "1 episode" else "$epCount episodes"
                            DiscGridItem(
                                id = podcast.id,
                                title = podcast.title,
                                subtitle = "${podcast.hostName} · $epLabel",
                                artworkPath = podcast.artworkAsset,
                            )
                        },
                        columns = columns,
                        horizontalContentPadding = 24.dp,
                        onClick = onPodcastClick,
                        onLongClick = { contextPodcastId = it },
                        itemWrapper = { item, itemModifier, content ->
                            val podcast = uiState.podcasts.firstOrNull { it.id == item.id }
                            if (podcast == null) {
                                Box(itemModifier) { content() }
                            } else {
                                val episodes = uiState.episodesByPodcast[podcast.id].orEmpty()
                                PodcastContextMenu(
                                    episodes = episodes,
                                    isSubscribed = podcast.isSubscribed,
                                    expanded = contextPodcastId == podcast.id,
                                    onDismiss = { if (contextPodcastId == podcast.id) contextPodcastId = null },
                                    modifier = itemModifier,
                                    hazeState = hazeState,
                                    onPlay = { onPodcastPlay(episodes.map { it.id }, false) },
                                    onShuffle = { onPodcastPlay(episodes.map { it.id }, true) },
                                    onToggleSubscribe = { onPodcastToggleSubscribe(podcast.id, !podcast.isSubscribed) },
                                    onPlayNext = onPodcastPlayNext,
                                    onAddToQueue = onPodcastAddToQueue,
                                    onSaveAll = onPodcastSaveAll,
                                    onAddToCollection = { ids ->
                                        onEpisodeContextBottomSheet(EpisodeContextBottomSheetRequest.CollectionPicker(ids, addOnly = true))
                                    },
                                    anchor = content,
                                )
                            }
                        },
                    )
                }
            } else {
                null
            },
            trailingItemsContent = if (uiState.podcastEpisodes.isNotEmpty()) {
                {
                    item(key = "podcasts-episodes-header", contentType = "podcasts-episodes-header") {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.podcasts_episodes_section),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.textMain,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        )
                    }
                    itemsIndexed(
                        items = uiState.podcastEpisodes,
                        key = { _, ep -> "pod_ep_${ep.id}" },
                        contentType = { _, _ -> "podcast_episode_row" },
                    ) { index, episode ->
                        val statusSuffix = when (episode.playbackStatus) {
                            PlaybackStatus.Played -> stringResource(R.string.episode_status_played)
                            PlaybackStatus.InProgress -> stringResource(
                                R.string.episode_status_in_progress,
                                formatCompactDuration(episode.remainingMs),
                            )
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
                                subtitle = "${episode.podcastTitle} · $statusSuffix",
                                artworkPath = episode.artworkAsset,
                                isCurrentPlaying = episode.id == currentEpisodeId,
                                isPlaying = isPlaying,
                                onClick = { onEpisodeClick(uiState.podcastEpisodes, episode.id) },
                                onMoreClick = { contextEpisode = episode },
                                onLongClick = { contextEpisode = episode },
                            )
                        }
                        if (index < uiState.podcastEpisodes.lastIndex) {
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
            } else {
                null
            },
        ) { podcast ->
            val episodes = uiState.episodesByPodcast[podcast.id].orEmpty()
            val epLabel = pluralStringResource(R.plurals.podcast_details_episode_count, episodes.size, episodes.size)
            PodcastContextMenu(
                episodes = episodes,
                isSubscribed = podcast.isSubscribed,
                expanded = contextPodcastId == podcast.id,
                onDismiss = { if (contextPodcastId == podcast.id) contextPodcastId = null },
                hazeState = hazeState,
                onPlay = { onPodcastPlay(episodes.map { it.id }, false) },
                onShuffle = { onPodcastPlay(episodes.map { it.id }, true) },
                onToggleSubscribe = { onPodcastToggleSubscribe(podcast.id, !podcast.isSubscribed) },
                onPlayNext = onPodcastPlayNext,
                onAddToQueue = onPodcastAddToQueue,
                onSaveAll = onPodcastSaveAll,
                onAddToCollection = { ids ->
                    onEpisodeContextBottomSheet(EpisodeContextBottomSheetRequest.CollectionPicker(ids, addOnly = true))
                },
            ) {
                PodcastRow(
                    title = podcast.title,
                    host = "${podcast.hostName} · $epLabel",
                    artworkPath = podcast.artworkAsset,
                    onClick = { onPodcastClick(podcast.id) },
                    onLongClick = { contextPodcastId = podcast.id },
                )
            }
        }
    }
}
