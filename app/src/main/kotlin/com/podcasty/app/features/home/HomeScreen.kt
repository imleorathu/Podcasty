/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import com.podcasty.app.R
import com.podcasty.app.core.formatCompactDuration
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.PlaybackQueueSnapshot
import com.podcasty.app.domain.Podcast
import com.podcasty.app.storage.LibraryScanState
import com.podcasty.app.ui.components.DiscCard
import com.podcasty.app.ui.components.EpisodeContextBottomSheetRequest
import com.podcasty.app.ui.components.EpisodeContextMenu
import com.podcasty.app.ui.components.HeroCard
import com.podcasty.app.ui.components.MaterialSymbol
import com.podcasty.app.ui.components.MaterialSymbols
import com.podcasty.app.ui.components.PodcastContextMenu
import com.podcasty.app.ui.components.PodcastyCard
import com.podcasty.app.ui.components.PodcastyPillButton
import com.podcasty.app.ui.components.PodcastyPillButtonVariant
import com.podcasty.app.ui.theme.LocalPodcastyColors

@Composable
fun HomeScreen(
    uiState: HomeUiState,
    playbackQueue: PlaybackQueueSnapshot,
    hazeState: HazeState?,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onEpisodeClick: (List<Episode>, String) -> Unit,
    onPodcastClick: (String) -> Unit,
    onPodcastPlay: (List<String>, Boolean) -> Unit,
    onPodcastToggleSubscribe: (String, Boolean) -> Unit,
    onEpisodePlayNext: (Episode) -> Unit,
    onEpisodeAddToQueue: (Episode) -> Unit,
    onEpisodeSaveToggle: (Episode, Boolean) -> Unit,
    onEpisodeDownloadToggle: (Episode, Boolean) -> Unit,
    onEpisodeGoToPodcast: (Episode) -> Unit,
    onEpisodeGoToHost: (Episode) -> Unit,
    onEpisodeContextBottomSheet: (EpisodeContextBottomSheetRequest) -> Unit,
    onPodcastPlayNext: (List<String>) -> Unit,
    onPodcastAddToQueue: (List<String>) -> Unit,
    onPodcastSaveAll: (List<String>) -> Unit,
    onSelectDefaultMusicFolder: (() -> Unit)? = null,
    onAddPodcastClick: (() -> Unit)? = null,
    scanState: LibraryScanState = LibraryScanState(),
    modifier: Modifier = Modifier,
) {
    val colors = LocalPodcastyColors.current
    val layoutDirection = LocalLayoutDirection.current
    val titleHorizontalPadding = contentPadding.calculateStartPadding(layoutDirection)
    val isEmpty = uiState.podcasts.isEmpty() &&
        uiState.recentlyPlayed.isEmpty() &&
        uiState.recentlyAdded.isEmpty() &&
        uiState.popularFavourites.isEmpty()

    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(36.dp),
    ) {
        if (scanState.isScanning) {
            item(key = "home-background-indexing-banner") {
                Box(modifier = Modifier.padding(horizontal = 24.dp)) {
                    PodcastyCard(contentPadding = PaddingValues(16.dp)) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = scanState.lastMessage ?: "Indexing audio files in background…",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.primary,
                            )
                            if (scanState.totalFiles > 0 && scanState.scannedFiles > 0) {
                                LinearProgressIndicator(
                                    progress = { scanState.progressFraction },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(999.dp)),
                                    color = colors.primary,
                                    trackColor = colors.borderGlass,
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(999.dp)),
                                    color = colors.primary,
                                    trackColor = colors.borderGlass,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (!uiState.isLoaded && isEmpty && !scanState.isScanning) return@LazyColumn
        if (isEmpty && !scanState.isScanning) {
            item {
                Box(modifier = Modifier.padding(horizontal = 24.dp)) {
                    HeroCard(
                        symbol = MaterialSymbols.Podcasts,
                        title = stringResource(R.string.home_empty_title),
                        description = stringResource(R.string.home_empty_description),
                        bottomContent = if (onSelectDefaultMusicFolder != null) {
                            {
                                Box(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
                                    PodcastyPillButton(
                                        label = stringResource(R.string.music_library_select_folder),
                                        onClick = onSelectDefaultMusicFolder,
                                        variant = PodcastyPillButtonVariant.Primary,
                                    )
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
            }
            return@LazyColumn
        }

        item(key = "home-podcasts") {
            if (uiState.podcasts.isNotEmpty()) {
                HomeEpisodeSection(
                    titleRes = R.string.home_podcasts,
                    episodes = uiState.podcasts,
                    showProgress = true,
                    singleRow = false,
                    playbackQueue = playbackQueue,
                    hazeState = hazeState,
                    titleHorizontalPadding = titleHorizontalPadding,
                    onEpisodeClick = onEpisodeClick,
                    onEpisodePlayNext = onEpisodePlayNext,
                    onEpisodeAddToQueue = onEpisodeAddToQueue,
                    onEpisodeSaveToggle = onEpisodeSaveToggle,
                    onEpisodeDownloadToggle = onEpisodeDownloadToggle,
                    onEpisodeGoToPodcast = onEpisodeGoToPodcast,
                    onEpisodeGoToHost = onEpisodeGoToHost,
                    onEpisodeContextBottomSheet = onEpisodeContextBottomSheet,
                )
            } else {
                HomePodcastsEmptySection(
                    titleHorizontalPadding = titleHorizontalPadding,
                    onAddPodcastClick = onAddPodcastClick,
                )
            }
        }
        if (uiState.recentlyPlayed.isNotEmpty()) {
            item(key = "home-recent-played") {
                HomeEpisodeSection(
                    titleRes = R.string.home_recently_played,
                    episodes = uiState.recentlyPlayed,
                    showProgress = true,
                    singleRow = false,
                    playbackQueue = playbackQueue,
                    hazeState = hazeState,
                    titleHorizontalPadding = titleHorizontalPadding,
                    onEpisodeClick = onEpisodeClick,
                    onEpisodePlayNext = onEpisodePlayNext,
                    onEpisodeAddToQueue = onEpisodeAddToQueue,
                    onEpisodeSaveToggle = onEpisodeSaveToggle,
                    onEpisodeDownloadToggle = onEpisodeDownloadToggle,
                    onEpisodeGoToPodcast = onEpisodeGoToPodcast,
                    onEpisodeGoToHost = onEpisodeGoToHost,
                    onEpisodeContextBottomSheet = onEpisodeContextBottomSheet,
                )
            }
        }
        if (uiState.recentlyAdded.isNotEmpty()) {
            item(key = "home-recent-added") {
                HomeEpisodeSection(
                    titleRes = R.string.home_recently_added,
                    episodes = uiState.recentlyAdded,
                    showProgress = false,
                    singleRow = false,
                    playbackQueue = playbackQueue,
                    hazeState = hazeState,
                    titleHorizontalPadding = titleHorizontalPadding,
                    onEpisodeClick = onEpisodeClick,
                    onEpisodePlayNext = onEpisodePlayNext,
                    onEpisodeAddToQueue = onEpisodeAddToQueue,
                    onEpisodeSaveToggle = onEpisodeSaveToggle,
                    onEpisodeDownloadToggle = onEpisodeDownloadToggle,
                    onEpisodeGoToPodcast = onEpisodeGoToPodcast,
                    onEpisodeGoToHost = onEpisodeGoToHost,
                    onEpisodeContextBottomSheet = onEpisodeContextBottomSheet,
                )
            }
        }
        if (uiState.popularFavourites.isNotEmpty()) {
            item(key = "home-popular-favourites") {
                HomeEpisodeSection(
                    titleRes = R.string.home_popular_favourites,
                    episodes = uiState.popularFavourites,
                    showProgress = false,
                    singleRow = false,
                    playbackQueue = playbackQueue,
                    hazeState = hazeState,
                    titleHorizontalPadding = titleHorizontalPadding,
                    onEpisodeClick = onEpisodeClick,
                    onEpisodePlayNext = onEpisodePlayNext,
                    onEpisodeAddToQueue = onEpisodeAddToQueue,
                    onEpisodeSaveToggle = onEpisodeSaveToggle,
                    onEpisodeDownloadToggle = onEpisodeDownloadToggle,
                    onEpisodeGoToPodcast = onEpisodeGoToPodcast,
                    onEpisodeGoToHost = onEpisodeGoToHost,
                    onEpisodeContextBottomSheet = onEpisodeContextBottomSheet,
                )
            }
        }
        if (uiState.optionalEpisodes.isNotEmpty()) {
            item(key = "home-optional-music-section") {
                HomeEpisodeSection(
                    titleRes = when (uiState.optionalCategory) {
                        "favorites" -> R.string.home_favorites
                        "most" -> R.string.home_most_played
                        "never" -> R.string.home_never_played
                        else -> R.string.home_longest_tracks
                    },
                    episodes = uiState.optionalEpisodes,
                    showProgress = false,
                    singleRow = false,
                    playbackQueue = playbackQueue,
                    hazeState = hazeState,
                    titleHorizontalPadding = titleHorizontalPadding,
                    onEpisodeClick = onEpisodeClick,
                    onEpisodePlayNext = onEpisodePlayNext,
                    onEpisodeAddToQueue = onEpisodeAddToQueue,
                    onEpisodeSaveToggle = onEpisodeSaveToggle,
                    onEpisodeDownloadToggle = onEpisodeDownloadToggle,
                    onEpisodeGoToPodcast = onEpisodeGoToPodcast,
                    onEpisodeGoToHost = onEpisodeGoToHost,
                    onEpisodeContextBottomSheet = onEpisodeContextBottomSheet,
                )
            }
        }
    }
}

@Composable
private fun HomeEpisodeSection(
    @StringRes titleRes: Int,
    episodes: List<Episode>,
    showProgress: Boolean,
    singleRow: Boolean,
    playbackQueue: PlaybackQueueSnapshot,
    hazeState: HazeState?,
    titleHorizontalPadding: Dp,
    onEpisodeClick: (List<Episode>, String) -> Unit,
    onEpisodePlayNext: (Episode) -> Unit,
    onEpisodeAddToQueue: (Episode) -> Unit,
    onEpisodeSaveToggle: (Episode, Boolean) -> Unit,
    onEpisodeDownloadToggle: (Episode, Boolean) -> Unit,
    onEpisodeGoToPodcast: (Episode) -> Unit,
    onEpisodeGoToHost: (Episode) -> Unit,
    onEpisodeContextBottomSheet: (EpisodeContextBottomSheetRequest) -> Unit,
) {
    if (episodes.isEmpty()) return
    val colors = LocalPodcastyColors.current
    var contextEpisode by remember { mutableStateOf<Episode?>(null) }
    val rows = if (singleRow) 1 else homeSectionRows(episodes.size)

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textMain,
            modifier = Modifier.padding(horizontal = titleHorizontalPadding, vertical = 4.dp),
        )
        LazyHorizontalGrid(
            rows = GridCells.Fixed(rows),
            modifier = Modifier
                .fillMaxWidth()
                .height(homeSectionHeight(rows)),
            contentPadding = PaddingValues(horizontal = titleHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(episodes.size, key = { index -> "${titleRes}_${episodes[index].id}" }) { index ->
                val episode = episodes[index]
                val subtitle = if (showProgress && episode.progressFraction > 0.02f && !episode.isPlayed) {
                    "${episode.podcastTitle} · ${formatCompactDuration(episode.remainingMs)} left"
                } else {
                    episode.podcastTitle
                }
                EpisodeContextMenu(
                    episode = episode,
                    expanded = contextEpisode?.id == episode.id,
                    onDismiss = { if (contextEpisode?.id == episode.id) contextEpisode = null },
                    modifier = Modifier.width(128.dp),
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
                    DiscCard(
                        title = episode.title,
                        subtitle = subtitle,
                        artworkPath = episode.artworkAsset,
                        progressFraction = if (showProgress && !episode.isPlayed) episode.progressFraction else null,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onEpisodeClick(episodes, episode.id) },
                        onLongClick = { contextEpisode = episode },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomePodcastSection(
    @StringRes titleRes: Int,
    podcasts: List<Podcast>,
    allEpisodes: List<Episode>,
    hazeState: HazeState?,
    titleHorizontalPadding: Dp,
    onPodcastClick: (String) -> Unit,
    onPodcastPlay: (List<String>, Boolean) -> Unit,
    onPodcastToggleSubscribe: (String, Boolean) -> Unit,
    onPodcastPlayNext: (List<String>) -> Unit,
    onPodcastAddToQueue: (List<String>) -> Unit,
    onPodcastSaveAll: (List<String>) -> Unit,
    onAddToCollection: (List<String>) -> Unit,
) {
    if (podcasts.isEmpty()) return
    val colors = LocalPodcastyColors.current
    var contextPodcast by remember { mutableStateOf<Podcast?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textMain,
            modifier = Modifier.padding(horizontal = titleHorizontalPadding, vertical = 4.dp),
        )
        LazyHorizontalGrid(
            rows = GridCells.Fixed(1),
            modifier = Modifier
                .fillMaxWidth()
                .height(homeSectionHeight(1)),
            contentPadding = PaddingValues(horizontal = titleHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(podcasts.size, key = { index -> "${titleRes}_${podcasts[index].id}" }) { index ->
                val podcast = podcasts[index]
                val showEpisodes = remember(allEpisodes, podcast.id) {
                    allEpisodes.filter { it.podcastId == podcast.id }.sortedByDescending { it.publishedAt }
                }
                PodcastContextMenu(
                    episodes = showEpisodes,
                    isSubscribed = podcast.isSubscribed,
                    expanded = contextPodcast?.id == podcast.id,
                    onDismiss = { if (contextPodcast?.id == podcast.id) contextPodcast = null },
                    modifier = Modifier.width(128.dp),
                    hazeState = hazeState,
                    onPlay = { onPodcastPlay(showEpisodes.map { it.id }, false) },
                    onShuffle = { onPodcastPlay(showEpisodes.map { it.id }, true) },
                    onToggleSubscribe = { onPodcastToggleSubscribe(podcast.id, !podcast.isSubscribed) },
                    onPlayNext = onPodcastPlayNext,
                    onAddToQueue = onPodcastAddToQueue,
                    onSaveAll = onPodcastSaveAll,
                    onAddToCollection = onAddToCollection,
                ) {
                    DiscCard(
                        title = podcast.title,
                        subtitle = podcast.hostName,
                        artworkPath = podcast.artworkAsset,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onPodcastClick(podcast.id) },
                        onLongClick = { contextPodcast = podcast },
                    )
                }
            }
        }
    }
}

internal fun homeSectionRows(count: Int): Int = if (count >= 4) 2 else 1

internal fun homeSectionHeight(rows: Int): Dp = if (rows == 1) 182.dp else 384.dp

@Composable
private fun HomePodcastsEmptySection(
    titleHorizontalPadding: Dp,
    onAddPodcastClick: (() -> Unit)?,
) {
    val colors = LocalPodcastyColors.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = stringResource(R.string.home_podcasts),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textMain,
            modifier = Modifier.padding(horizontal = titleHorizontalPadding, vertical = 4.dp),
        )
        Box(modifier = Modifier.padding(horizontal = titleHorizontalPadding)) {
            PodcastyCard(contentPadding = PaddingValues(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MaterialSymbol(
                        symbol = MaterialSymbols.Podcasts,
                        contentDescription = null,
                        size = 28.dp,
                        tint = colors.primary,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.home_podcasts_empty_title),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.textMain,
                        )
                        Text(
                            text = stringResource(R.string.home_podcasts_empty_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted,
                        )
                    }
                }
                if (onAddPodcastClick != null) {
                    Spacer(Modifier.height(14.dp))
                    PodcastyPillButton(
                        label = stringResource(R.string.home_podcasts_add_action),
                        onClick = onAddPodcastClick,
                        variant = PodcastyPillButtonVariant.Secondary,
                    )
                }
            }
        }
    }
}
