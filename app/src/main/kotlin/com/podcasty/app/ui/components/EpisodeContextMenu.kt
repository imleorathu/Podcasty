/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import com.podcasty.app.R
import com.podcasty.app.core.formatCompactDuration
import com.podcasty.app.core.formatFileSize
import com.podcasty.app.core.formatPlaybackTime
import com.podcasty.app.core.formatShortDate
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.PlaybackQueueSnapshot
import com.podcasty.app.domain.PlaybackStatus
import com.podcasty.app.domain.PodcastCollection
import com.podcasty.app.ui.theme.LocalPodcastyColors

data class EpisodeContextMenuActions(
    val removeFromQueue: Boolean = false,
    val playNext: Boolean = true,
    val addToQueue: Boolean = true,
    val episodeInfo: Boolean = true,
    val saveEpisode: Boolean = true,
    val downloadToggle: Boolean = true,
    val addToCollection: Boolean = true,
    val goToPodcast: Boolean = true,
    val goToHost: Boolean = true,
    val removeFromCollection: Boolean = false,
)

sealed interface EpisodeContextBottomSheetRequest {
    data class Info(val episode: Episode) : EpisodeContextBottomSheetRequest
    data class CollectionPicker(val episodeIds: List<String>, val addOnly: Boolean = false) : EpisodeContextBottomSheetRequest
    data class QueuePicker(val episodeIds: List<String>) : EpisodeContextBottomSheetRequest
    data class TagEditor(val episodes: List<Episode>) : EpisodeContextBottomSheetRequest
    data class ImportTranscript(val episode: Episode) : EpisodeContextBottomSheetRequest
    data class LinkAudioSource(val episode: Episode) : EpisodeContextBottomSheetRequest
    data class OpenYouTube(val episode: Episode) : EpisodeContextBottomSheetRequest
    data class FileDetails(val episode: Episode) : EpisodeContextBottomSheetRequest
    data class RenameFile(val episode: Episode) : EpisodeContextBottomSheetRequest
    data class MoveFile(val episode: Episode) : EpisodeContextBottomSheetRequest
    data class CopyFile(val episode: Episode) : EpisodeContextBottomSheetRequest
    data class DeleteFiles(val episodes: List<Episode>) : EpisodeContextBottomSheetRequest
    data class ShareFile(val episode: Episode) : EpisodeContextBottomSheetRequest
    data class ViewLyrics(val episode: Episode) : EpisodeContextBottomSheetRequest
    data class OpenContainingFolder(val episode: Episode) : EpisodeContextBottomSheetRequest
}

@Composable
fun EpisodeContextMenu(
    episode: Episode,
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    actions: EpisodeContextMenuActions = EpisodeContextMenuActions(),
    hazeState: HazeState? = null,
    playbackQueue: PlaybackQueueSnapshot = PlaybackQueueSnapshot(),
    onRemoveFromQueue: (Episode) -> Unit = {},
    onPlayNext: (Episode) -> Unit = {},
    onAddToQueue: (Episode) -> Unit = {},
    onSaveToggle: (Episode, Boolean) -> Unit = { _, _ -> },
    onDownloadToggle: (Episode, Boolean) -> Unit = { _, _ -> },
    onRemoveFromCollection: (Episode) -> Unit = {},
    onGoToPodcast: (Episode) -> Unit = {},
    onGoToHost: (Episode) -> Unit = {},
    onOpenEqualizer: (() -> Unit)? = null,
    onBottomSheetRequested: (EpisodeContextBottomSheetRequest) -> Unit = {},
    onCloseFullscreenThen: ((() -> Unit) -> Unit) = { action -> action() },
    anchor: @Composable () -> Unit,
) {
    AnchoredPopupMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier,
        width = 276.dp,
        offset = DpOffset(x = (-8).dp, y = 8.dp),
        hazeState = hazeState,
        anchor = anchor,
    ) {
        // Deferred inside menu lambda: avoids O(N) queue scan on every visible row during scrolling
        val hapticFeedback = LocalHapticFeedback.current
        val isCurrent = episode.id == playbackQueue.currentEpisodeId
        val showPlayNext = actions.playNext && !isCurrent
        val showAddToQueue = actions.addToQueue && !isCurrent && episode.id !in playbackQueue.activeEpisodeIds
        val entries = buildList {
            if (onOpenEqualizer != null) {
                add(
                    ContextActionMenuEntry.Action(
                        stringResource(R.string.player_equalizer_audio),
                        MaterialSymbols.GraphicEq,
                    ) { onDismiss(); onOpenEqualizer() },
                )
                add(ContextActionMenuEntry.Divider)
            }
            if (actions.removeFromQueue) {
                add(
                    ContextActionMenuEntry.Action(
                        stringResource(R.string.episode_context_remove_from_queue),
                        MaterialSymbols.RemoveFromQueue,
                        destructive = true,
                    ) { onDismiss(); onRemoveFromQueue(episode) },
                )
                add(ContextActionMenuEntry.Divider)
            }
            if (showPlayNext) {
                add(
                    ContextActionMenuEntry.Action(
                        stringResource(R.string.episode_context_play_next),
                        MaterialSymbols.QueuePlayNext,
                    ) { onDismiss(); onPlayNext(episode) },
                )
            }
            if (showAddToQueue) {
                add(
                    ContextActionMenuEntry.Action(
                        stringResource(R.string.episode_context_add_to_queue),
                        MaterialSymbols.AddToQueue,
                    ) { onDismiss(); onAddToQueue(episode) },
                )
            }
            if (actions.episodeInfo) {
                add(
                    ContextActionMenuEntry.Action(
                        stringResource(R.string.episode_context_episode_info),
                        MaterialSymbols.Info,
                    ) {
                        onDismiss()
                        onCloseFullscreenThen { onBottomSheetRequested(EpisodeContextBottomSheetRequest.Info(episode)) }
                    },
                )
            }
            if (showPlayNext || showAddToQueue || actions.episodeInfo) {
                add(ContextActionMenuEntry.Divider)
            }
            if (actions.saveEpisode) {
                add(
                    ContextActionMenuEntry.Action(
                        stringResource(
                            if (episode.isSaved) R.string.episode_context_unsave_episode else R.string.episode_context_save_episode,
                        ),
                        if (episode.isSaved) MaterialSymbols.StarMinus else MaterialSymbols.Star,
                    ) {
                        if (!episode.isSaved) hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                        onDismiss()
                        onSaveToggle(episode, !episode.isSaved)
                    },
                )
            }
            if (actions.downloadToggle) {
                add(
                    ContextActionMenuEntry.Action(
                        stringResource(if (episode.isDownloaded) R.string.downloads_remove else R.string.downloads_save),
                        if (episode.isDownloaded) MaterialSymbols.DownloadDone else MaterialSymbols.Download,
                    ) {
                        onDismiss()
                        onDownloadToggle(episode, !episode.isDownloaded)
                    },
                )
            }
            if (actions.addToCollection) {
                add(
                    ContextActionMenuEntry.Action(
                        stringResource(R.string.episode_context_add_to_collection),
                        MaterialSymbols.PlaylistAdd,
                    ) {
                        onDismiss()
                        onCloseFullscreenThen {
                            onBottomSheetRequested(EpisodeContextBottomSheetRequest.CollectionPicker(listOf(episode.id)))
                        }
                    },
                )
            }
            if (episode.isLocalAudioFile) {
                add(
                    ContextActionMenuEntry.Action(
                        stringResource(R.string.file_action_add_to_specific_queue),
                        MaterialSymbols.QueueMusic,
                    ) {
                        onDismiss()
                        onCloseFullscreenThen {
                            onBottomSheetRequested(EpisodeContextBottomSheetRequest.QueuePicker(listOf(episode.id)))
                        }
                    },
                )
                add(
                    ContextActionMenuEntry.Action(
                        stringResource(R.string.episode_context_edit_tags),
                        MaterialSymbols.Edit,
                    ) {
                        onDismiss()
                        onCloseFullscreenThen {
                            onBottomSheetRequested(EpisodeContextBottomSheetRequest.TagEditor(listOf(episode)))
                        }
                    },
                )
                add(
                    ContextActionMenuEntry.Action(
                        stringResource(R.string.file_action_view_lyrics),
                        MaterialSymbols.Lyrics,
                    ) {
                        onDismiss()
                        onCloseFullscreenThen {
                            onBottomSheetRequested(EpisodeContextBottomSheetRequest.ViewLyrics(episode))
                        }
                    },
                )
                add(
                    ContextActionMenuEntry.Action(
                        stringResource(R.string.file_action_open_folder),
                        MaterialSymbols.FolderOpen,
                    ) {
                        onDismiss()
                        onCloseFullscreenThen {
                            onBottomSheetRequested(EpisodeContextBottomSheetRequest.OpenContainingFolder(episode))
                        }
                    },
                )
                add(ContextActionMenuEntry.Divider)
                add(
                    ContextActionMenuEntry.Action(
                        stringResource(R.string.file_action_details),
                        MaterialSymbols.Info,
                    ) {
                        onDismiss()
                        onCloseFullscreenThen {
                            onBottomSheetRequested(EpisodeContextBottomSheetRequest.FileDetails(episode))
                        }
                    },
                )
                add(
                    ContextActionMenuEntry.Action(
                        stringResource(R.string.file_action_rename),
                        MaterialSymbols.Edit,
                    ) {
                        onDismiss()
                        onCloseFullscreenThen {
                            onBottomSheetRequested(EpisodeContextBottomSheetRequest.RenameFile(episode))
                        }
                    },
                )
                add(
                    ContextActionMenuEntry.Action(
                        stringResource(R.string.file_action_move),
                        MaterialSymbols.DriveFileMove,
                    ) {
                        onDismiss()
                        onCloseFullscreenThen {
                            onBottomSheetRequested(EpisodeContextBottomSheetRequest.MoveFile(episode))
                        }
                    },
                )
                add(
                    ContextActionMenuEntry.Action(
                        stringResource(R.string.file_action_copy),
                        MaterialSymbols.ContentCopy,
                    ) {
                        onDismiss()
                        onCloseFullscreenThen {
                            onBottomSheetRequested(EpisodeContextBottomSheetRequest.CopyFile(episode))
                        }
                    },
                )
                add(
                    ContextActionMenuEntry.Action(
                        stringResource(R.string.file_action_share),
                        MaterialSymbols.Share,
                    ) {
                        onDismiss()
                        onBottomSheetRequested(EpisodeContextBottomSheetRequest.ShareFile(episode))
                    },
                )
                add(
                    ContextActionMenuEntry.Action(
                        stringResource(R.string.file_action_delete),
                        MaterialSymbols.Delete,
                        destructive = true,
                    ) {
                        onDismiss()
                        onCloseFullscreenThen {
                            onBottomSheetRequested(EpisodeContextBottomSheetRequest.DeleteFiles(listOf(episode)))
                        }
                    },
                )
            }
            add(
                ContextActionMenuEntry.Action(
                    stringResource(R.string.episode_context_import_transcript),
                    MaterialSymbols.Chat,
                ) {
                    onDismiss()
                    onCloseFullscreenThen {
                        onBottomSheetRequested(EpisodeContextBottomSheetRequest.ImportTranscript(episode))
                    }
                },
            )
            if (episode.isYouTubeReference) {
                add(
                    ContextActionMenuEntry.Action(
                        stringResource(R.string.youtube_open_official),
                        MaterialSymbols.PlayCircle,
                    ) {
                        onDismiss()
                        onBottomSheetRequested(EpisodeContextBottomSheetRequest.OpenYouTube(episode))
                    },
                )
                add(
                    ContextActionMenuEntry.Action(
                        stringResource(R.string.youtube_link_audio_source),
                        MaterialSymbols.Podcasts,
                    ) {
                        onDismiss()
                        onCloseFullscreenThen {
                            onBottomSheetRequested(EpisodeContextBottomSheetRequest.LinkAudioSource(episode))
                        }
                    },
                )
            }
            if (actions.goToPodcast || actions.goToHost || actions.removeFromCollection) {
                add(ContextActionMenuEntry.Divider)
            }
            if (actions.goToPodcast) {
                add(
                    ContextActionMenuEntry.Action(
                        stringResource(R.string.episode_context_go_to_podcast),
                        MaterialSymbols.Podcasts,
                    ) {
                        onDismiss()
                        onCloseFullscreenThen { onGoToPodcast(episode) }
                    },
                )
            }
            if (actions.goToHost) {
                add(
                    ContextActionMenuEntry.Action(
                        stringResource(R.string.episode_context_go_to_host, episode.hostName),
                        MaterialSymbols.Person,
                    ) {
                        onDismiss()
                        onCloseFullscreenThen { onGoToHost(episode) }
                    },
                )
            }
            if (actions.removeFromCollection) {
                add(ContextActionMenuEntry.Divider)
                add(
                    ContextActionMenuEntry.Action(
                        stringResource(R.string.episode_context_remove_from_collection),
                        MaterialSymbols.PlaylistRemove,
                        destructive = true,
                    ) { onDismiss(); onRemoveFromCollection(episode) },
                )
            }
        }
        ContextActionMenu(entries)
    }
}

@Composable
fun EpisodeContextBottomSheet(
    request: EpisodeContextBottomSheetRequest,
    collections: List<PodcastCollection>,
    onDismiss: () -> Unit,
    onPlayEpisode: (Episode) -> Unit = {},
    onToggleSave: (Episode, Boolean) -> Unit = { _, _ -> },
    onToggleDownload: (Episode, Boolean) -> Unit = { _, _ -> },
    onCollectionMembershipChange: (collectionId: String, episodeIds: List<String>, add: Boolean) -> Unit,
    onCreateCollectionRequested: (episodeIds: List<String>) -> Unit,
) {
    val colors = LocalPodcastyColors.current
    val titleRes = when (request) {
        is EpisodeContextBottomSheetRequest.Info -> R.string.episode_info_title
        is EpisodeContextBottomSheetRequest.CollectionPicker -> R.string.episode_context_collection_picker_title
        else -> return
    }
    PodcastyBottomSheet(
        title = { Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium, color = colors.textMain) },
        onDismiss = onDismiss,
    ) {
        when (request) {
            is EpisodeContextBottomSheetRequest.Info -> EpisodeInfoContent(
                episode = request.episode,
                onPlay = { onDismiss(); onPlayEpisode(request.episode) },
                onToggleSave = { onToggleSave(request.episode, !request.episode.isSaved) },
                onToggleDownload = { onToggleDownload(request.episode, !request.episode.isDownloaded) },
            )
            is EpisodeContextBottomSheetRequest.CollectionPicker -> CollectionPickerContent(
                episodeIds = request.episodeIds,
                addOnly = request.addOnly,
                collections = collections,
                onCollectionMembershipChange = onCollectionMembershipChange,
                onCreateCollectionRequested = onCreateCollectionRequested,
            )
            is EpisodeContextBottomSheetRequest.TagEditor,
            is EpisodeContextBottomSheetRequest.ImportTranscript,
            is EpisodeContextBottomSheetRequest.LinkAudioSource,
            is EpisodeContextBottomSheetRequest.OpenYouTube,
            -> Unit
        }
    }
}

@Composable
private fun EpisodeInfoContent(
    episode: Episode,
    onPlay: () -> Unit,
    onToggleSave: () -> Unit,
    onToggleDownload: () -> Unit,
) {
    val colors = LocalPodcastyColors.current
    val artwork = rememberArtworkThumbnail(episode.artworkAsset, targetPx = 480)
    val density = LocalDensity.current
    val topSafeInset = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val statusText = when (episode.playbackStatus) {
        PlaybackStatus.Played -> stringResource(R.string.episode_status_played)
        PlaybackStatus.InProgress -> stringResource(R.string.episode_status_in_progress, formatCompactDuration(episode.remainingMs))
        PlaybackStatus.Unplayed -> stringResource(R.string.episode_status_new)
    }
    val details = listOf(
        R.string.episode_info_podcast to episode.podcastTitle,
        R.string.episode_info_host to episode.hostName,
        R.string.episode_info_category to episode.categoryName,
        R.string.episode_info_episode_number to "#${episode.episodeNumber}",
        R.string.episode_info_published to formatShortDate(episode.publishedAt),
        R.string.episode_info_duration to formatPlaybackTime(episode.durationMs),
        R.string.episode_info_status to statusText,
        R.string.episode_info_plays to episode.playCount.toString(),
        R.string.episode_info_audio to episode.formatSummary,
        R.string.episode_info_file_size to formatFileSize(episode.fileSizeBytes),
    )

    Box(modifier = Modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp, max = 560.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, top = 8.dp, end = 24.dp, bottom = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(216.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(colors.glassElevated)
                            .border(1.dp, colors.borderGlass, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (artwork != null) Image(artwork, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                        else MaterialSymbol(MaterialSymbols.Podcasts, null, size = 52.dp, tint = colors.textMuted)
                    }
                    Text(
                        episode.title,
                        modifier = Modifier.padding(top = 18.dp),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = colors.textMain,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${episode.podcastTitle} · ${episode.hostName}",
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textMuted,
                        textAlign = TextAlign.Center,
                    )
                    Row(
                        modifier = Modifier.padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PodcastyPillButton(
                            label = stringResource(R.string.player_play),
                            onClick = onPlay,
                            variant = PodcastyPillButtonVariant.Primary,
                            modifier = Modifier.weight(1f),
                        )
                        PodcastyIconButton(
                            symbol = MaterialSymbols.Star,
                            label = stringResource(R.string.player_save),
                            onClick = onToggleSave,
                            variant = PodcastyIconButtonVariant.Glass,
                            tint = if (episode.isSaved) colors.primary else colors.textMain,
                            filled = episode.isSaved,
                        )
                        PodcastyIconButton(
                            symbol = if (episode.isDownloaded) MaterialSymbols.DownloadDone else MaterialSymbols.Download,
                            label = stringResource(if (episode.isDownloaded) R.string.downloads_remove else R.string.downloads_save),
                            onClick = onToggleDownload,
                            variant = PodcastyIconButtonVariant.Glass,
                            tint = if (episode.isDownloaded) colors.success else colors.textMain,
                        )
                    }
                }
            }
            if (episode.summary.isNotBlank()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                        Text(
                            stringResource(R.string.episode_info_summary),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = colors.textMuted,
                        )
                        Text(
                            episode.summary,
                            modifier = Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textMain,
                        )
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.episode_info_details),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = colors.textMuted,
                )
            }
            items(details, key = { it.first }) { (labelRes, value) ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(stringResource(labelRes), Modifier.weight(0.40f), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                    Text(
                        value,
                        Modifier.weight(0.60f),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textMain,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

@Composable
private fun CollectionPickerContent(
    episodeIds: List<String>,
    addOnly: Boolean,
    collections: List<PodcastCollection>,
    onCollectionMembershipChange: (collectionId: String, episodeIds: List<String>, add: Boolean) -> Unit,
    onCreateCollectionRequested: (episodeIds: List<String>) -> Unit,
) {
    val colors = LocalPodcastyColors.current
    val editable = remember(collections) { collections.filter { !it.isSystem } }
    val singleEpisode = episodeIds.distinct().singleOrNull()
    var locallyAddedIds by remember(episodeIds, addOnly) { mutableStateOf<Set<String>>(emptySet()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable { onCreateCollectionRequested(episodeIds) }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                MaterialSymbol(MaterialSymbols.Add, null, size = 18.dp, tint = colors.primary)
            }
            Text(stringResource(R.string.collection_create), modifier = Modifier.padding(start = 12.dp), color = colors.textMain)
        }
        if (editable.isEmpty()) {
            Text(
                stringResource(R.string.episode_context_no_collections),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                color = colors.textMuted,
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(editable, key = PodcastCollection::id) { collection ->
                    val checked = if (addOnly) {
                        collection.id in locallyAddedIds
                    } else if (singleEpisode != null) {
                        singleEpisode in collection.episodeIds
                    } else {
                        episodeIds.isNotEmpty() && episodeIds.all { it in collection.episodeIds }
                    }
                    val add = addOnly || !checked
                    val targetIds = when {
                        addOnly -> if (checked) emptyList() else episodeIds
                        singleEpisode != null -> listOf(singleEpisode)
                        add -> episodeIds.filter { it !in collection.episodeIds }
                        else -> episodeIds
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clickable {
                                if (targetIds.isNotEmpty()) {
                                    if (addOnly) locallyAddedIds += collection.id
                                    onCollectionMembershipChange(collection.id, targetIds, add)
                                }
                            }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CollectionPickerCheckbox(checked = checked, modifier = Modifier.size(24.dp))
                        Text(collection.name, modifier = Modifier.padding(start = 12.dp), color = colors.textMain)
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionPickerCheckbox(checked: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalPodcastyColors.current
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (checked) colors.primary else colors.glassElevated)
            .border(1.dp, if (checked) colors.primary else colors.borderGlass, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) MaterialSymbol(MaterialSymbols.Check, null, size = 14.dp, tint = colors.onPrimary)
    }
}
