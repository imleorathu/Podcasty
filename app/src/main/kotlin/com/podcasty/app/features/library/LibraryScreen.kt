/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import com.podcasty.app.R
import com.podcasty.app.core.formatCompactDuration
import com.podcasty.app.core.formatFileSize
import com.podcasty.app.core.formatShortDate
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.ListeningHistoryEntry
import com.podcasty.app.domain.PlaybackQueueSnapshot
import com.podcasty.app.ui.components.ActionList
import com.podcasty.app.ui.components.ActionListContainerStyle
import com.podcasty.app.ui.components.ActionListItem
import com.podcasty.app.ui.components.DiscGridItem
import com.podcasty.app.ui.components.EpisodeContextBottomSheetRequest
import com.podcasty.app.ui.components.EpisodeContextMenu
import com.podcasty.app.ui.components.EpisodeRow
import com.podcasty.app.ui.components.HeroCard
import com.podcasty.app.ui.components.LibraryPlaybackActions
import com.podcasty.app.ui.components.MaterialSymbol
import com.podcasty.app.ui.components.MaterialSymbols
import com.podcasty.app.ui.components.PodcastyCard
import com.podcasty.app.ui.components.PodcastyPillButton
import com.podcasty.app.ui.components.PodcastyPillButtonVariant
import com.podcasty.app.ui.components.TextHelper
import com.podcasty.app.ui.components.discGridColumnCount
import com.podcasty.app.ui.components.discGridItems
import com.podcasty.app.ui.theme.LocalPodcastyColors

@Composable
fun LibraryScreen(
    recentEpisodes: List<Episode>,
    playbackQueue: PlaybackQueueSnapshot,
    hazeState: HazeState?,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onSearchSelected: () -> Unit,
    onAddPodcastSelected: () -> Unit,
    onPodcastsSelected: () -> Unit,
    onAlbumsSelected: () -> Unit,
    onEpisodesSelected: () -> Unit,
    onHostsSelected: () -> Unit,
    onCategoriesSelected: () -> Unit,
    onCollectionsSelected: () -> Unit,
    onSavedEpisodesSelected: () -> Unit,
    onDownloadsSelected: () -> Unit,
    onHistorySelected: () -> Unit,
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
    var contextEpisode by remember { mutableStateOf<Episode?>(null) }
    val layoutDirection = LocalLayoutDirection.current

    BoxWithConstraints(modifier = modifier) {
        val columns = discGridColumnCount(
            maxWidth - contentPadding.calculateLeftPadding(layoutDirection) -
                contentPadding.calculateRightPadding(layoutDirection),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            item(key = "library-menu") {
                ActionList(
                    items = listOf(
                        ActionListItem(R.string.library_search, leadingSymbol = MaterialSymbols.Search, leadingIconTint = colors.primary, onClick = onSearchSelected),
                        ActionListItem(R.string.library_add_podcast, leadingSymbol = MaterialSymbols.Add, leadingIconTint = colors.primary, onClick = onAddPodcastSelected),
                        ActionListItem(R.string.library_podcasts, leadingSymbol = MaterialSymbols.Podcasts, leadingIconTint = colors.primary, onClick = onPodcastsSelected),
                        ActionListItem(R.string.library_albums, leadingSymbol = MaterialSymbols.Album, leadingIconTint = colors.primary, onClick = onAlbumsSelected),
                        ActionListItem(R.string.library_episodes, leadingSymbol = MaterialSymbols.Headphones, leadingIconTint = colors.primary, onClick = onEpisodesSelected),
                        ActionListItem(R.string.library_hosts, leadingSymbol = MaterialSymbols.People, leadingIconTint = colors.primary, onClick = onHostsSelected),
                        ActionListItem(R.string.library_categories, leadingSymbol = MaterialSymbols.Label, leadingIconTint = colors.primary, onClick = onCategoriesSelected),
                        ActionListItem(R.string.library_collections, leadingSymbol = MaterialSymbols.QueueMusic, leadingIconTint = colors.primary, onClick = onCollectionsSelected),
                        ActionListItem(R.string.library_saved_episodes, leadingSymbol = MaterialSymbols.Star, leadingIconTint = colors.primary, onClick = onSavedEpisodesSelected),
                        ActionListItem(R.string.library_downloads, leadingSymbol = MaterialSymbols.Download, leadingIconTint = colors.primary, onClick = onDownloadsSelected),
                        ActionListItem(R.string.library_history, leadingSymbol = MaterialSymbols.History, leadingIconTint = colors.primary, onClick = onHistorySelected),
                    ),
                    containerStyle = ActionListContainerStyle.Plain,
                    horizontalContentPadding = 0.dp,
                    showTrailingDivider = true,
                )
            }
            if (recentEpisodes.isNotEmpty()) {
                item(key = "library-recently-added-header") {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.library_recently_added),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textMain,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                discGridItems(
                    columns = columns,
                    items = recentEpisodes.map { ep ->
                        DiscGridItem(
                            id = ep.id,
                            title = ep.title,
                            subtitle = ep.podcastTitle,
                            artworkPath = ep.artworkAsset,
                            fallbackSymbol = MaterialSymbols.Podcasts,
                        )
                    },
                    onClick = { id -> onEpisodeClick(recentEpisodes, id) },
                    onLongClick = { id -> contextEpisode = recentEpisodes.firstOrNull { it.id == id } },
                    itemWrapper = { item, itemModifier, content ->
                        val episode = recentEpisodes.firstOrNull { it.id == item.id }
                        if (episode == null) {
                            Box(itemModifier) { content() }
                        } else {
                            EpisodeContextMenu(
                                episode = episode,
                                expanded = contextEpisode?.id == episode.id,
                                onDismiss = { if (contextEpisode?.id == episode.id) contextEpisode = null },
                                modifier = itemModifier,
                                hazeState = hazeState,
                                playbackQueue = playbackQueue,
                                onPlayNext = onEpisodePlayNext,
                                onAddToQueue = onEpisodeAddToQueue,
                                onSaveToggle = onEpisodeSaveToggle,
                                onDownloadToggle = onEpisodeDownloadToggle,
                                onGoToPodcast = onEpisodeGoToPodcast,
                                onGoToHost = onEpisodeGoToHost,
                                onBottomSheetRequested = onEpisodeContextBottomSheet,
                                anchor = content,
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun SavedEpisodesScreen(
    episodes: List<Episode>,
    currentEpisodeId: String?,
    isPlaying: Boolean,
    playbackQueue: PlaybackQueueSnapshot,
    hazeState: HazeState?,
    listState: LazyListState,
    contentPadding: PaddingValues,
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
    val colors = LocalPodcastyColors.current
    var contextEpisode by remember { mutableStateOf<Episode?>(null) }

    if (episodes.isEmpty()) {
        Box(modifier = modifier.fillMaxSize().padding(contentPadding)) {
            HeroCard(
                symbol = MaterialSymbols.Star,
                title = stringResource(R.string.saved_episodes_empty_title),
                description = stringResource(R.string.saved_episodes_empty_description),
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding(),
        ),
    ) {
        item(key = "saved-actions") {
            LibraryPlaybackActions(
                playLabel = stringResource(R.string.player_play),
                shuffleLabel = stringResource(R.string.player_shuffle),
                onPlay = { onPlayAll(false) },
                onShuffle = { onPlayAll(true) },
                hazeState = hazeState,
            )
        }
        itemsIndexed(episodes, key = { _, ep -> ep.id }) { index, ep ->
            EpisodeContextMenu(
                episode = ep,
                expanded = contextEpisode?.id == ep.id,
                onDismiss = { if (contextEpisode?.id == ep.id) contextEpisode = null },
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
                    title = ep.title,
                    subtitle = "${ep.podcastTitle} · ${formatCompactDuration(ep.durationMs)}",
                    artworkPath = ep.artworkAsset,
                    isCurrentPlaying = ep.id == currentEpisodeId,
                    isPlaying = isPlaying,
                    onClick = { onEpisodeClick(episodes, ep.id) },
                    onMoreClick = { contextEpisode = ep },
                    onLongClick = { contextEpisode = ep },
                )
            }
            if (index < episodes.lastIndex) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(1.dp).background(colors.borderGlass))
            }
        }
    }
}

@Composable
fun DownloadsScreen(
    downloadedEpisodes: List<Episode>,
    currentEpisodeId: String?,
    isPlaying: Boolean,
    playbackQueue: PlaybackQueueSnapshot,
    hazeState: HazeState?,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onPlayAll: (Boolean) -> Unit,
    onEpisodeClick: (List<Episode>, String) -> Unit,
    onEpisodePlayNext: (Episode) -> Unit,
    onEpisodeAddToQueue: (Episode) -> Unit,
    onEpisodeSaveToggle: (Episode, Boolean) -> Unit,
    onEpisodeDownloadToggle: (Episode, Boolean) -> Unit,
    onEpisodeGoToPodcast: (Episode) -> Unit,
    onEpisodeGoToHost: (Episode) -> Unit,
    onEpisodeContextBottomSheet: (EpisodeContextBottomSheetRequest) -> Unit,
    onRestoreDemoDownloads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPodcastyColors.current
    var contextEpisode by remember { mutableStateOf<Episode?>(null) }
    val totalBytes = remember(downloadedEpisodes) { downloadedEpisodes.sumOf { it.fileSizeBytes } }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding(),
        ),
    ) {
        item(key = "downloads-summary") {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PodcastyCard(contentPadding = PaddingValues(18.dp)) {
                    Text(
                        text = stringResource(R.string.downloads_storage_used),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textMuted,
                    )
                    Text(
                        text = "${formatFileSize(totalBytes)} · ${downloadedEpisodes.size} episodes",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textMain,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                TextHelper(text = stringResource(R.string.downloads_info_helper))
            }
        }
        if (downloadedEpisodes.isEmpty()) {
            item(key = "downloads-empty") {
                Box(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                    HeroCard(
                        symbol = MaterialSymbols.Download,
                        title = stringResource(R.string.downloads_empty_title),
                        description = stringResource(R.string.downloads_empty_description),
                    )
                }
            }
        } else {
            item(key = "downloads-actions") {
                LibraryPlaybackActions(
                    playLabel = stringResource(R.string.player_play),
                    shuffleLabel = stringResource(R.string.player_shuffle),
                    onPlay = { onPlayAll(false) },
                    onShuffle = { onPlayAll(true) },
                    hazeState = hazeState,
                )
            }
            itemsIndexed(downloadedEpisodes, key = { _, ep -> ep.id }) { index, ep ->
                EpisodeContextMenu(
                    episode = ep,
                    expanded = contextEpisode?.id == ep.id,
                    onDismiss = { if (contextEpisode?.id == ep.id) contextEpisode = null },
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
                        title = ep.title,
                        subtitle = "${ep.podcastTitle} · ${formatFileSize(ep.fileSizeBytes)}",
                        artworkPath = ep.artworkAsset,
                        isCurrentPlaying = ep.id == currentEpisodeId,
                        isPlaying = isPlaying,
                        onClick = { onEpisodeClick(downloadedEpisodes, ep.id) },
                        onMoreClick = { contextEpisode = ep },
                        onLongClick = { contextEpisode = ep },
                    )
                }
                if (index < downloadedEpisodes.lastIndex) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(1.dp).background(colors.borderGlass))
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(
    entries: List<ListeningHistoryEntry>,
    episodesById: Map<String, Episode>,
    currentEpisodeId: String?,
    isPlaying: Boolean,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onPlayEpisode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPodcastyColors.current
    if (entries.isEmpty()) {
        Box(modifier = modifier.fillMaxSize().padding(contentPadding)) {
            HeroCard(
                symbol = MaterialSymbols.History,
                title = stringResource(R.string.history_empty_title),
                description = stringResource(R.string.history_empty_description),
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding(),
        ),
    ) {
        itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
            val durationMin = (entry.listenedSeconds / 60).coerceAtLeast(1)
            val status = when {
                entry.completed -> stringResource(R.string.insight_completed)
                entry.skipped -> stringResource(R.string.insight_skipped)
                else -> "${durationMin} min"
            }
            EpisodeRow(
                title = entry.episodeTitle,
                subtitle = "${entry.podcastTitle} · $status · ${formatShortDate(entry.playedAt)}",
                artworkPath = entry.artworkAsset ?: episodesById[entry.episodeId]?.artworkAsset,
                isCurrentPlaying = entry.episodeId == currentEpisodeId,
                isPlaying = isPlaying,
                onClick = { onPlayEpisode(entry.episodeId) },
                trailingContent = {
                    MaterialSymbol(
                        symbol = if (entry.completed) MaterialSymbols.CheckCircle else MaterialSymbols.PlayCircle,
                        contentDescription = null,
                        size = 20.dp,
                        tint = if (entry.completed) colors.success else colors.textMuted,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
            )
            if (index < entries.lastIndex) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(1.dp).background(colors.borderGlass))
            }
        }
    }
}
