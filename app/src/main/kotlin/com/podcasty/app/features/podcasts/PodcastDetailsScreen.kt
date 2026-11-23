/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.podcasts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import com.podcasty.app.R
import com.podcasty.app.core.formatCompactDuration
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.PlaybackQueueSnapshot
import com.podcasty.app.domain.PlaybackStatus
import com.podcasty.app.domain.Podcast
import com.podcasty.app.ui.components.ArtworkHeroBackdrop
import com.podcasty.app.ui.components.DetailHero
import com.podcasty.app.ui.components.EpisodeContextBottomSheetRequest
import com.podcasty.app.ui.components.EpisodeContextMenu
import com.podcasty.app.ui.components.EpisodeContextMenuActions
import com.podcasty.app.ui.components.HeroCard
import com.podcasty.app.ui.components.MaterialSymbol
import com.podcasty.app.ui.components.MaterialSymbols
import com.podcasty.app.ui.components.PodcastContextMenu
import com.podcasty.app.ui.components.PodcastEpisodeRow
import com.podcasty.app.ui.theme.LocalPodcastyColors

@Composable
fun PodcastDetailsScreen(
    podcast: Podcast?,
    episodes: List<Episode>,
    currentEpisodeId: String?,
    playbackQueue: PlaybackQueueSnapshot,
    hazeState: HazeState?,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onHeroColorChanged: (Color) -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onToggleSubscribe: (String, Boolean) -> Unit,
    onEpisodeClick: (String) -> Unit,
    onEpisodePlayNext: (String) -> Unit,
    onEpisodeAddToQueue: (String) -> Unit,
    onEpisodeSaveToggle: (String, Boolean) -> Unit,
    onEpisodeDownloadToggle: (String, Boolean) -> Unit,
    onEpisodeGoToHost: (String) -> Unit,
    onPodcastPlayNext: (List<String>) -> Unit,
    onPodcastAddToQueue: (List<String>) -> Unit,
    onPodcastSaveAll: (List<String>) -> Unit,
    onEpisodeContextBottomSheet: (EpisodeContextBottomSheetRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (podcast == null) {
        HeroCard(
            symbol = MaterialSymbols.Podcasts,
            title = stringResource(R.string.podcast_details_empty_title),
            description = stringResource(R.string.podcast_details_empty_description),
            modifier = modifier.fillMaxSize().padding(contentPadding),
        )
        return
    }
    val colors = LocalPodcastyColors.current
    var contextEpisodeId by remember(podcast.id) { mutableStateOf<String?>(null) }
    var podcastMenuExpanded by remember(podcast.id) { mutableStateOf(false) }
    val episodeCount = pluralStringResource(R.plurals.podcast_details_episode_count, episodes.size, episodes.size)
    val totalDuration = formatCompactDuration(episodes.sumOf { it.durationMs })
    val metadata = stringResource(R.string.podcast_details_metadata, episodeCount, totalDuration)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
    ) {
        item(key = "podcast-hero") {
            ArtworkHeroBackdrop(podcast.artworkAsset, Modifier.fillMaxWidth(), onHeroColorChanged) {
                DetailHero(
                    title = podcast.title,
                    subtitle = "${podcast.hostName} · ${podcast.categoryName}",
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
                    artworkPath = podcast.artworkAsset,
                    artworkSize = 248.dp,
                    onPlayClick = onPlay,
                    onShuffleClick = onShuffle,
                    onMoreClick = { podcastMenuExpanded = true },
                    secondaryAction = {
                        Row(
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (podcast.isSubscribed) colors.buttonSecondary else colors.primary)
                                .clickable(role = Role.Button) {
                                    onToggleSubscribe(podcast.id, !podcast.isSubscribed)
                                }
                                .padding(horizontal = 18.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MaterialSymbol(
                                symbol = if (podcast.isSubscribed) MaterialSymbols.Check else MaterialSymbols.Add,
                                contentDescription = null,
                                size = 16.dp,
                                tint = if (podcast.isSubscribed) colors.textMain else colors.onPrimary,
                            )
                            Text(
                                text = stringResource(if (podcast.isSubscribed) R.string.podcast_subscribed else R.string.podcast_subscribe),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (podcast.isSubscribed) colors.textMain else colors.onPrimary,
                            )
                        }
                    },
                    moreAction = { moreButton ->
                        PodcastContextMenu(
                            episodes = episodes,
                            isSubscribed = podcast.isSubscribed,
                            expanded = podcastMenuExpanded,
                            onDismiss = { podcastMenuExpanded = false },
                            hazeState = hazeState,
                            onPlay = onPlay,
                            onShuffle = onShuffle,
                            onToggleSubscribe = { onToggleSubscribe(podcast.id, !podcast.isSubscribed) },
                            onPlayNext = onPodcastPlayNext,
                            onAddToQueue = onPodcastAddToQueue,
                            onSaveAll = onPodcastSaveAll,
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
        if (podcast.description.isNotBlank()) {
            item(key = "podcast-description") {
                Text(
                    text = podcast.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                )
            }
        }
        itemsIndexed(episodes, key = { _, ep -> ep.id }) { index, episode ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .height(1.dp)
                    .background(colors.borderGlass),
            )
            val statusSuffix = when (episode.playbackStatus) {
                PlaybackStatus.Played -> stringResource(R.string.episode_status_played)
                PlaybackStatus.InProgress -> stringResource(R.string.episode_status_in_progress, formatCompactDuration(episode.remainingMs))
                PlaybackStatus.Unplayed -> formatCompactDuration(episode.durationMs)
            }
            EpisodeContextMenu(
                episode = episode,
                expanded = contextEpisodeId == episode.id,
                onDismiss = { if (contextEpisodeId == episode.id) contextEpisodeId = null },
                actions = EpisodeContextMenuActions(goToPodcast = false),
                hazeState = hazeState,
                playbackQueue = playbackQueue,
                onPlayNext = { onEpisodePlayNext(it.id) },
                onAddToQueue = { onEpisodeAddToQueue(it.id) },
                onSaveToggle = { ep, saved -> onEpisodeSaveToggle(ep.id, saved) },
                onDownloadToggle = { ep, downloaded -> onEpisodeDownloadToggle(ep.id, downloaded) },
                onGoToHost = { onEpisodeGoToHost(it.hostId) },
                onBottomSheetRequested = onEpisodeContextBottomSheet,
            ) {
                PodcastEpisodeRow(
                    number = episode.episodeNumber.takeIf { it > 0 } ?: (index + 1),
                    title = episode.title,
                    subtitle = "${episode.hostName} · $statusSuffix",
                    isCurrentPlaying = episode.id == currentEpisodeId,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onEpisodeClick(episode.id) },
                    onMoreClick = { contextEpisodeId = episode.id },
                    onLongClick = { contextEpisodeId = episode.id },
                )
            }
            if (index == episodes.lastIndex) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp)
                        .height(1.dp)
                        .background(colors.borderGlass),
                )
            }
        }
        if (podcast.publisher.isNotBlank()) {
            item(key = "podcast-publisher") {
                Text(
                    text = podcast.publisher,
                    color = colors.textMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 12.dp),
                )
            }
        }
    }
}
