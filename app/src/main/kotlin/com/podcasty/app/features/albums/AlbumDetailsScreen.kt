/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.albums

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import com.podcasty.app.R
import com.podcasty.app.core.formatCompactDuration
import com.podcasty.app.domain.AlbumSummary
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.PlaybackQueueSnapshot
import com.podcasty.app.ui.components.ArtworkHeroBackdrop
import com.podcasty.app.ui.components.DetailHero
import com.podcasty.app.ui.components.EpisodeContextBottomSheetRequest
import com.podcasty.app.ui.components.EpisodeContextMenu
import com.podcasty.app.ui.components.EpisodeContextMenuActions
import com.podcasty.app.ui.components.HeroCard
import com.podcasty.app.ui.components.MaterialSymbols
import com.podcasty.app.ui.components.PodcastContextMenu
import com.podcasty.app.ui.components.PodcastEpisodeRow
import com.podcasty.app.ui.theme.LocalPodcastyColors

@Composable
fun AlbumDetailsScreen(
    album: AlbumSummary?,
    tracks: List<Episode>,
    isLoaded: Boolean,
    currentEpisodeId: String?,
    playbackQueue: PlaybackQueueSnapshot,
    hazeState: HazeState?,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onHeroColorChanged: (Color) -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onTrackClick: (String) -> Unit,
    onTrackPlayNext: (String) -> Unit,
    onTrackAddToQueue: (String) -> Unit,
    onTrackSaveToggle: (String, Boolean) -> Unit,
    onTrackDownloadToggle: (String, Boolean) -> Unit,
    onTrackGoToHost: (String) -> Unit,
    onAlbumPlayNext: (List<String>) -> Unit,
    onAlbumAddToQueue: (List<String>) -> Unit,
    onAlbumSaveAll: (List<String>) -> Unit,
    onEpisodeContextBottomSheet: (EpisodeContextBottomSheetRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!isLoaded && album == null) return
    if (album == null) {
        HeroCard(
            symbol = MaterialSymbols.Album,
            title = stringResource(R.string.album_details_empty_title),
            description = stringResource(R.string.album_details_empty_description),
            modifier = modifier.fillMaxSize().padding(contentPadding),
        )
        return
    }
    val colors = LocalPodcastyColors.current
    var contextEpisodeId by remember(album.albumId) { mutableStateOf<String?>(null) }
    var albumMenuExpanded by remember(album.albumId) { mutableStateOf(false) }
    val trackCountText = pluralStringResource(R.plurals.album_details_track_count, tracks.size, tracks.size)
    val totalDuration = formatCompactDuration(tracks.sumOf { it.durationMs }.takeIf { it > 0L } ?: album.totalDurationMs)
    val metadata = stringResource(R.string.podcast_details_metadata, trackCountText, totalDuration)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
    ) {
        item(key = "album-hero") {
            ArtworkHeroBackdrop(album.artworkAsset, Modifier.fillMaxWidth(), onHeroColorChanged) {
                DetailHero(
                    title = album.albumTitle,
                    subtitle = album.artistName,
                    metadata = metadata,
                    playLabel = stringResource(R.string.player_play),
                    shuffleLabel = stringResource(R.string.player_shuffle),
                    moreLabel = stringResource(R.string.podcast_row_more_options),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 24.dp,
                            top = contentPadding.calculateTopPadding(),
                            end = 24.dp,
                            bottom = 20.dp,
                        ),
                    artworkPath = album.artworkAsset,
                    artworkSize = 248.dp,
                    onPlayClick = onPlay,
                    onShuffleClick = onShuffle,
                    onMoreClick = { albumMenuExpanded = true },
                    moreAction = { moreButton ->
                        PodcastContextMenu(
                            episodes = tracks,
                            isSubscribed = true,
                            expanded = albumMenuExpanded,
                            onDismiss = { albumMenuExpanded = false },
                            hazeState = hazeState,
                            onPlay = onPlay,
                            onShuffle = onShuffle,
                            onToggleSubscribe = {},
                            onPlayNext = onAlbumPlayNext,
                            onAddToQueue = onAlbumAddToQueue,
                            onSaveAll = onAlbumSaveAll,
                            onAddToCollection = { ids ->
                                onEpisodeContextBottomSheet(EpisodeContextBottomSheetRequest.CollectionPicker(ids, addOnly = true))
                            },
                            onBulkEditTags = { localEps ->
                                onEpisodeContextBottomSheet(EpisodeContextBottomSheetRequest.TagEditor(localEps))
                            },
                            anchor = moreButton,
                        )
                    },
                )
            }
        }
        itemsIndexed(tracks, key = { _, ep -> ep.id }) { index, track ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .height(1.dp)
                    .background(colors.borderGlass),
            )
            EpisodeContextMenu(
                episode = track,
                expanded = contextEpisodeId == track.id,
                onDismiss = { if (contextEpisodeId == track.id) contextEpisodeId = null },
                actions = EpisodeContextMenuActions(goToPodcast = false),
                hazeState = hazeState,
                playbackQueue = playbackQueue,
                onPlayNext = { onTrackPlayNext(it.id) },
                onAddToQueue = { onTrackAddToQueue(it.id) },
                onSaveToggle = { ep, saved -> onTrackSaveToggle(ep.id, saved) },
                onDownloadToggle = { ep, downloaded -> onTrackDownloadToggle(ep.id, downloaded) },
                onGoToHost = { onTrackGoToHost(it.hostId) },
                onBottomSheetRequested = onEpisodeContextBottomSheet,
            ) {
                PodcastEpisodeRow(
                    number = track.trackNumber.takeIf { it > 0 } ?: (index + 1),
                    title = track.title,
                    subtitle = "${track.hostName} · ${formatCompactDuration(track.durationMs)}",
                    isCurrentPlaying = track.id == currentEpisodeId,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onTrackClick(track.id) },
                    onMoreClick = { contextEpisodeId = track.id },
                    onLongClick = { contextEpisodeId = track.id },
                )
            }
            if (index == tracks.lastIndex) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp)
                        .height(1.dp)
                        .background(colors.borderGlass),
                )
            }
        }
    }
}
