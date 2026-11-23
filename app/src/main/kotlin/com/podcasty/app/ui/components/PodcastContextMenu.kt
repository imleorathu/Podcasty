/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import com.podcasty.app.R
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.PodcastCollection

@Composable
fun PodcastContextMenu(
    episodes: List<Episode>,
    isSubscribed: Boolean,
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    onPlay: () -> Unit = {},
    onShuffle: () -> Unit = {},
    onToggleSubscribe: () -> Unit = {},
    onPlayNext: (List<String>) -> Unit = {},
    onAddToQueue: (List<String>) -> Unit = {},
    onSaveAll: (List<String>) -> Unit = {},
    onAddToCollection: (List<String>) -> Unit = {},
    onBulkEditTags: ((List<Episode>) -> Unit)? = null,
    anchor: @Composable () -> Unit,
) {
    val episodeIds = episodes.map { it.id }
    val localEpisodes = episodes.filter { it.isLocalAudioFile }
    AnchoredPopupMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier,
        width = 256.dp,
        offset = DpOffset(x = (-8).dp, y = 8.dp),
        hazeState = hazeState,
        anchor = anchor,
    ) {
        val entries = buildList {
            add(ContextActionMenuEntry.Action(stringResource(R.string.player_play), MaterialSymbols.PlayArrow, enabled = episodeIds.isNotEmpty()) { onDismiss(); onPlay() })
            add(ContextActionMenuEntry.Action(stringResource(R.string.player_shuffle), MaterialSymbols.Shuffle, enabled = episodeIds.isNotEmpty()) { onDismiss(); onShuffle() })
            add(ContextActionMenuEntry.Divider)
            add(
                ContextActionMenuEntry.Action(
                    stringResource(if (isSubscribed) R.string.podcast_unsubscribe else R.string.podcast_subscribe),
                    if (isSubscribed) MaterialSymbols.CheckCircle else MaterialSymbols.Add,
                ) { onDismiss(); onToggleSubscribe() },
            )
            if (episodeIds.isNotEmpty()) {
                add(ContextActionMenuEntry.Divider)
                add(ContextActionMenuEntry.Action(stringResource(R.string.episode_context_play_next), MaterialSymbols.QueuePlayNext) { onDismiss(); onPlayNext(episodeIds) })
                add(ContextActionMenuEntry.Action(stringResource(R.string.episode_context_add_to_queue), MaterialSymbols.AddToQueue) { onDismiss(); onAddToQueue(episodeIds) })
                add(ContextActionMenuEntry.Action(stringResource(R.string.episode_context_save_episode), MaterialSymbols.Star) { onDismiss(); onSaveAll(episodeIds) })
                add(ContextActionMenuEntry.Action(stringResource(R.string.episode_context_add_to_collection), MaterialSymbols.PlaylistAdd) { onDismiss(); onAddToCollection(episodeIds) })
                if (onBulkEditTags != null && localEpisodes.isNotEmpty()) {
                    add(
                        ContextActionMenuEntry.Action(
                            stringResource(R.string.episode_context_bulk_edit_tags),
                            MaterialSymbols.Edit,
                        ) { onDismiss(); onBulkEditTags(localEpisodes) },
                    )
                }
            }
        }
        ContextActionMenu(entries)
    }
}

@Composable
fun GroupContextMenu(
    episodeIds: List<String>,
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    onPlayNext: (List<String>) -> Unit = {},
    onAddToQueue: (List<String>) -> Unit = {},
    onAddToCollection: (List<String>) -> Unit = {},
    anchor: @Composable () -> Unit,
) {
    AnchoredPopupMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier,
        width = 248.dp,
        offset = DpOffset(x = (-8).dp, y = 8.dp),
        hazeState = hazeState,
        anchor = anchor,
    ) {
        ContextActionMenu(
            listOf(
                ContextActionMenuEntry.Action(stringResource(R.string.episode_context_play_next), MaterialSymbols.QueuePlayNext, enabled = episodeIds.isNotEmpty()) { onDismiss(); onPlayNext(episodeIds) },
                ContextActionMenuEntry.Action(stringResource(R.string.episode_context_add_to_queue), MaterialSymbols.AddToQueue, enabled = episodeIds.isNotEmpty()) { onDismiss(); onAddToQueue(episodeIds) },
                ContextActionMenuEntry.Divider,
                ContextActionMenuEntry.Action(stringResource(R.string.episode_context_add_to_collection), MaterialSymbols.PlaylistAdd, enabled = episodeIds.isNotEmpty()) { onDismiss(); onAddToCollection(episodeIds) },
            ),
        )
    }
}

@Composable
fun CollectionContextMenu(
    collection: PodcastCollection,
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    showReorder: Boolean = false,
    onPlayNext: (List<String>) -> Unit = {},
    onAddToQueue: (List<String>) -> Unit = {},
    onReorder: () -> Unit = {},
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    anchor: @Composable () -> Unit,
) {
    val ids = collection.episodeIds
    AnchoredPopupMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier,
        width = 248.dp,
        offset = DpOffset(x = (-8).dp, y = 8.dp),
        hazeState = hazeState,
        anchor = anchor,
    ) {
        val entries = buildList {
            add(ContextActionMenuEntry.Action(stringResource(R.string.episode_context_play_next), MaterialSymbols.QueuePlayNext, enabled = ids.isNotEmpty()) { onDismiss(); onPlayNext(ids) })
            add(ContextActionMenuEntry.Action(stringResource(R.string.episode_context_add_to_queue), MaterialSymbols.AddToQueue, enabled = ids.isNotEmpty()) { onDismiss(); onAddToQueue(ids) })
            if (!collection.isSystem) {
                add(ContextActionMenuEntry.Divider)
                if (showReorder && ids.size > 1) {
                    add(ContextActionMenuEntry.Action(stringResource(R.string.collection_reorder), MaterialSymbols.Reorder) { onDismiss(); onReorder() })
                }
                add(ContextActionMenuEntry.Action(stringResource(R.string.collection_edit), MaterialSymbols.Edit) { onDismiss(); onEdit() })
                add(ContextActionMenuEntry.Action(stringResource(R.string.collection_delete), MaterialSymbols.Delete, destructive = true) { onDismiss(); onDelete() })
            }
        }
        ContextActionMenu(entries)
    }
}
