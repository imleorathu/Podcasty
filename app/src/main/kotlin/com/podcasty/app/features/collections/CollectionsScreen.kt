/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.podcasty.app.R
import com.podcasty.app.core.formatCompactDuration
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.PlaybackQueueSnapshot
import com.podcasty.app.domain.PodcastCollection
import com.podcasty.app.domain.PodcastRepository
import com.podcasty.app.ui.components.ArtworkHeroBackdrop
import com.podcasty.app.ui.components.CollectionArtwork
import com.podcasty.app.ui.components.CollectionContextMenu
import com.podcasty.app.ui.components.CollectionRow
import com.podcasty.app.ui.components.DetailHero
import com.podcasty.app.ui.components.EpisodeContextBottomSheetRequest
import com.podcasty.app.ui.components.EpisodeContextMenu
import com.podcasty.app.ui.components.EpisodeContextMenuActions
import com.podcasty.app.ui.components.EpisodeRow
import com.podcasty.app.ui.components.HeroCard
import com.podcasty.app.ui.components.MaterialSymbol
import com.podcasty.app.ui.components.MaterialSymbols
import com.podcasty.app.ui.components.PodcastyBottomSheet
import com.podcasty.app.ui.components.PodcastyDialog
import com.podcasty.app.ui.components.PodcastyPillButtonVariant
import com.podcasty.app.ui.components.PodcastyTextField
import com.podcasty.app.ui.components.PodcastyTextFieldSize
import com.podcasty.app.ui.components.liquidGlassBackground
import com.podcasty.app.ui.theme.LocalPodcastyColors

data class CollectionItemModel(
    val collection: PodcastCollection,
    val episodes: List<Episode>,
    val artworkPaths: List<String>,
)

data class CollectionsUiState(
    val isLoaded: Boolean = false,
    val items: List<CollectionItemModel> = emptyList(),
)

class CollectionsViewModel(private val repository: PodcastRepository) : ViewModel() {
    private val _createdCollectionIds = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val createdCollectionIds: SharedFlow<String> = _createdCollectionIds.asSharedFlow()

    val uiState: StateFlow<CollectionsUiState> = combine(
        repository.collections.distinctUntilChanged(),
        repository.episodes.distinctUntilChanged(),
    ) { collections, episodes ->
        val byId = episodes.associateBy { it.id }
        val models = collections.map { col ->
            val resolved = col.episodeIds.mapNotNull { byId[it] }
            CollectionItemModel(
                collection = col,
                episodes = resolved,
                artworkPaths = resolved.mapNotNull { it.artworkAsset }.distinct(),
            )
        }
        CollectionsUiState(isLoaded = true, items = models)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), CollectionsUiState())

    fun createCollection(name: String, initialEpisodeIds: List<String> = emptyList()) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val id = repository.createCollection(trimmed, initialEpisodeIds)
            _createdCollectionIds.tryEmit(id)
        }
    }

    fun renameCollection(collectionId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { repository.renameCollection(collectionId, trimmed) }
    }

    fun deleteCollection(collectionId: String) {
        viewModelScope.launch { repository.deleteCollection(collectionId) }
    }

    fun changeMembership(collectionId: String, episodeIds: List<String>, add: Boolean) {
        viewModelScope.launch { repository.setCollectionMembership(collectionId, episodeIds, add) }
    }

    fun reorderEpisodes(collectionId: String, orderedIds: List<String>) {
        viewModelScope.launch { repository.reorderCollectionEpisodes(collectionId, orderedIds) }
    }

    class Factory(private val repository: PodcastRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CollectionsViewModel(repository) as T
    }
}

@Composable
fun CollectionsScreen(
    uiState: CollectionsUiState,
    hazeState: HazeState?,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onCollectionClick: (String) -> Unit,
    onPlayNext: (List<String>) -> Unit,
    onAddToQueue: (List<String>) -> Unit,
    onRenameCollection: (String, String) -> Unit,
    onDeleteCollection: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPodcastyColors.current
    var contextItem by remember { mutableStateOf<CollectionItemModel?>(null) }
    var editingItem by remember { mutableStateOf<CollectionItemModel?>(null) }
    var deletingItem by remember { mutableStateOf<CollectionItemModel?>(null) }

    if (uiState.isLoaded && uiState.items.isEmpty()) {
        Box(modifier = modifier.fillMaxSize().padding(contentPadding)) {
            HeroCard(
                symbol = MaterialSymbols.QueueMusic,
                title = stringResource(R.string.collections_empty_title),
                description = stringResource(R.string.collections_empty_description),
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
        itemsIndexed(uiState.items, key = { _, item -> item.collection.id }) { index, item ->
            val countText = pluralStringResource(
                R.plurals.podcast_details_episode_count,
                item.episodes.size,
                item.episodes.size,
            )
            CollectionContextMenu(
                collection = item.collection,
                expanded = contextItem?.collection?.id == item.collection.id,
                onDismiss = { if (contextItem?.collection?.id == item.collection.id) contextItem = null },
                hazeState = hazeState,
                onPlayNext = onPlayNext,
                onAddToQueue = onAddToQueue,
                onEdit = { editingItem = item },
                onDelete = { deletingItem = item },
            ) {
                CollectionRow(
                    collectionId = item.collection.id,
                    name = item.collection.name,
                    subtitle = countText,
                    artworkPaths = item.artworkPaths,
                    onClick = { onCollectionClick(item.collection.id) },
                    onLongClick = { contextItem = item },
                )
            }
            if (index < uiState.items.lastIndex) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .height(1.dp)
                        .background(colors.borderGlass),
                )
            }
        }
    }

    editingItem?.let { item ->
        CollectionEditorBottomSheet(
            title = stringResource(R.string.collection_edit_title),
            actionLabel = stringResource(R.string.save),
            initialName = item.collection.name,
            onDismiss = { editingItem = null },
            onSave = { newName ->
                onRenameCollection(item.collection.id, newName)
                editingItem = null
            },
        )
    }
    deletingItem?.let { item ->
        PodcastyDialog(
            title = stringResource(R.string.collection_delete_confirm_title),
            description = stringResource(R.string.collection_delete_confirm_description),
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = { deletingItem = null },
            confirmLabel = stringResource(R.string.collection_delete),
            onConfirm = {
                onDeleteCollection(item.collection.id)
                deletingItem = null
            },
            confirmVariant = PodcastyPillButtonVariant.Destructive,
        )
    }
}

@Composable
fun CollectionDetailsScreen(
    model: CollectionItemModel?,
    currentEpisodeId: String?,
    isPlaying: Boolean,
    playbackQueue: PlaybackQueueSnapshot,
    hazeState: HazeState?,
    contentPadding: PaddingValues,
    onHeroColorChanged: (Color) -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onEpisodeClick: (String) -> Unit,
    onEpisodePlayNext: (String) -> Unit,
    onEpisodeAddToQueue: (String) -> Unit,
    onEpisodeSaveToggle: (String, Boolean) -> Unit,
    onEpisodeDownloadToggle: (String, Boolean) -> Unit,
    onEpisodeGoToPodcast: (String) -> Unit,
    onEpisodeGoToHost: (String) -> Unit,
    onRemoveFromCollection: (String) -> Unit,
    onReorderEpisodes: (List<String>) -> Unit,
    onCollectionPlayNext: (List<String>) -> Unit,
    onCollectionAddToQueue: (List<String>) -> Unit,
    onCollectionRename: (String, String) -> Unit,
    onCollectionDelete: (String) -> Unit,
    onEpisodeContextBottomSheet: (EpisodeContextBottomSheetRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (model == null) {
        HeroCard(
            symbol = MaterialSymbols.QueueMusic,
            title = stringResource(R.string.collection_details_empty_title),
            description = stringResource(R.string.collection_details_empty_description),
            modifier = modifier.fillMaxSize().padding(contentPadding),
        )
        return
    }
    val collection = model.collection
    val colors = LocalPodcastyColors.current
    var contextEpisodeId by remember(collection.id) { mutableStateOf<String?>(null) }
    var menuExpanded by remember(collection.id) { mutableStateOf(false) }
    var showEditor by remember(collection.id) { mutableStateOf(false) }
    var showDeleteConfirm by remember(collection.id) { mutableStateOf(false) }
    var isReordering by remember(collection.id) { mutableStateOf(false) }
    var orderedIds by remember(collection.id) { mutableStateOf(model.episodes.map { it.id }) }
    val latestOrderedIds = rememberUpdatedState(orderedIds)
    val latestOnReorder = rememberUpdatedState(onReorderEpisodes)
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIdx = from.index - 1
        val toIdx = to.index - 1
        if (fromIdx in orderedIds.indices && toIdx in orderedIds.indices && fromIdx != toIdx) {
            orderedIds = orderedIds.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        }
    }

    LaunchedEffect(model.episodes, isReordering) {
        if (!isReordering) orderedIds = model.episodes.map { it.id }
    }

    val episodesById = remember(model.episodes) { model.episodes.associateBy { it.id } }
    val countText = pluralStringResource(R.plurals.podcast_details_episode_count, model.episodes.size, model.episodes.size)
    val totalDuration = formatCompactDuration(model.episodes.sumOf { it.durationMs })

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
    ) {
        item(key = "collection-hero") {
            ArtworkHeroBackdrop(model.artworkPaths.firstOrNull(), Modifier.fillMaxWidth(), onHeroColorChanged) {
                DetailHero(
                    title = collection.name,
                    metadata = stringResource(R.string.podcast_details_metadata, countText, totalDuration),
                    playLabel = stringResource(R.string.player_play),
                    shuffleLabel = stringResource(R.string.player_shuffle),
                    moreLabel = stringResource(if (isReordering) R.string.collection_reorder_done else R.string.podcast_row_more_options),
                    moreSymbol = if (isReordering) MaterialSymbols.Check else MaterialSymbols.MoreVert,
                    modifier = Modifier.fillMaxWidth().padding(
                        start = 24.dp,
                        top = contentPadding.calculateTopPadding(),
                        end = 24.dp,
                        bottom = 20.dp,
                    ),
                    artworkSize = 248.dp,
                    artworkContent = {
                        CollectionArtwork(collection.id, model.artworkPaths, size = 248.dp)
                    },
                    onPlayClick = onPlay,
                    onShuffleClick = onShuffle,
                    onMoreClick = { if (isReordering) isReordering = false else menuExpanded = true },
                    moreAction = { action ->
                        if (isReordering) {
                            action()
                        } else {
                            CollectionContextMenu(
                                collection = collection,
                                expanded = menuExpanded,
                                onDismiss = { menuExpanded = false },
                                hazeState = hazeState,
                                showReorder = true,
                                onPlayNext = onCollectionPlayNext,
                                onAddToQueue = onCollectionAddToQueue,
                                onReorder = { isReordering = true },
                                onEdit = { showEditor = true },
                                onDelete = { showDeleteConfirm = true },
                                anchor = action,
                            )
                        }
                    },
                )
            }
        }
        items(orderedIds, key = { it }) { epId ->
            val episode = episodesById[epId] ?: return@items
            Box(Modifier.fillMaxWidth().padding(horizontal = 22.dp).height(1.dp).background(colors.borderGlass))
            if (isReordering) {
                ReorderableItem(reorderableState, key = epId) { isDragging ->
                    EpisodeRow(
                        title = episode.title,
                        subtitle = "${episode.podcastTitle} · ${formatCompactDuration(episode.durationMs)}",
                        artworkPath = episode.artworkAsset,
                        modifier = Modifier.fillMaxWidth().then(
                            if (isDragging) {
                                Modifier.liquidGlassBackground(hazeState, colors, 30.dp, colors.glassElevated)
                                    .border(1.dp, colors.borderGlass)
                            } else {
                                Modifier
                            },
                        ),
                        trailingContent = {
                            CollectionDragHandle(
                                isDragged = isDragging,
                                modifier = collectionDragHandleModifier {
                                    latestOnReorder.value(latestOrderedIds.value)
                                },
                            )
                        },
                    )
                }
            } else {
                EpisodeContextMenu(
                    episode = episode,
                    expanded = contextEpisodeId == episode.id,
                    onDismiss = { if (contextEpisodeId == episode.id) contextEpisodeId = null },
                    actions = EpisodeContextMenuActions(removeFromCollection = !collection.isSystem),
                    hazeState = hazeState,
                    playbackQueue = playbackQueue,
                    onPlayNext = { onEpisodePlayNext(it.id) },
                    onAddToQueue = { onEpisodeAddToQueue(it.id) },
                    onSaveToggle = { ep, saved -> onEpisodeSaveToggle(ep.id, saved) },
                    onDownloadToggle = { ep, downloaded -> onEpisodeDownloadToggle(ep.id, downloaded) },
                    onGoToPodcast = { onEpisodeGoToPodcast(it.podcastId) },
                    onGoToHost = { onEpisodeGoToHost(it.hostId) },
                    onRemoveFromCollection = { onRemoveFromCollection(it.id) },
                    onBottomSheetRequested = onEpisodeContextBottomSheet,
                ) {
                    EpisodeRow(
                        title = episode.title,
                        subtitle = "${episode.podcastTitle} · ${formatCompactDuration(episode.durationMs)}",
                        artworkPath = episode.artworkAsset,
                        isCurrentPlaying = episode.id == currentEpisodeId,
                        isPlaying = isPlaying,
                        onClick = { onEpisodeClick(episode.id) },
                        onMoreClick = { contextEpisodeId = episode.id },
                        onLongClick = { contextEpisodeId = episode.id },
                    )
                }
            }
            if (epId == orderedIds.lastOrNull()) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 22.dp).height(1.dp).background(colors.borderGlass))
            }
        }
    }

    if (showEditor) {
        CollectionEditorBottomSheet(
            title = stringResource(R.string.collection_edit_title),
            actionLabel = stringResource(R.string.save),
            initialName = collection.name,
            onDismiss = { showEditor = false },
            onSave = { newName ->
                onCollectionRename(collection.id, newName)
                showEditor = false
            },
        )
    }
    if (showDeleteConfirm) {
        PodcastyDialog(
            title = stringResource(R.string.collection_delete_confirm_title),
            description = stringResource(R.string.collection_delete_confirm_description),
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = { showDeleteConfirm = false },
            confirmLabel = stringResource(R.string.collection_delete),
            onConfirm = {
                onCollectionDelete(collection.id)
                showDeleteConfirm = false
            },
            confirmVariant = PodcastyPillButtonVariant.Destructive,
        )
    }
}

@Composable
private fun CollectionDragHandle(isDragged: Boolean, modifier: Modifier) {
    val colors = LocalPodcastyColors.current
    Box(Modifier.size(48.dp).then(modifier), contentAlignment = Alignment.Center) {
        MaterialSymbol(
            MaterialSymbols.Menu,
            stringResource(R.string.collection_reorder_drag_handle),
            size = 24.dp,
            tint = if (isDragged) colors.textMain else colors.textMuted,
        )
    }
}

private fun ReorderableCollectionItemScope.collectionDragHandleModifier(onStop: () -> Unit) =
    Modifier.longPressDraggableHandle(onDragStopped = onStop)

@Composable
fun CreateCollectionBottomSheet(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    CollectionEditorBottomSheet(
        title = stringResource(R.string.collection_create_title),
        actionLabel = stringResource(R.string.create),
        initialName = "",
        onDismiss = onDismiss,
        onSave = onCreate,
    )
}

@Composable
private fun CollectionEditorBottomSheet(
    title: String,
    actionLabel: String,
    initialName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val colors = LocalPodcastyColors.current
    var name by remember(initialName) { mutableStateOf(initialName) }
    val valid = name.trim().isNotEmpty()
    PodcastyBottomSheet(
        title = { Text(title, style = MaterialTheme.typography.titleMedium, color = colors.textMain) },
        onDismiss = onDismiss,
        leadingAction = { dismiss ->
            SheetCircleIconButton(MaterialSymbols.Close, stringResource(R.string.cancel), dismiss)
        },
        trailingAction = {
            SheetCircleIconButton(
                symbol = MaterialSymbols.Check,
                label = actionLabel,
                onClick = { if (valid) onSave(name.trim()) },
                enabled = valid,
                primary = true,
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.glassElevated)
                    .border(1.dp, colors.borderGlass, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                MaterialSymbol(MaterialSymbols.QueueMusic, null, size = 40.dp, tint = colors.primary)
            }
            PodcastyTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                placeholder = stringResource(R.string.collection_name),
                size = PodcastyTextFieldSize.Medium,
                onDone = { if (valid) onSave(name.trim()) },
            )
        }
    }
}

@Composable
private fun SheetCircleIconButton(
    symbol: String,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    val colors = LocalPodcastyColors.current
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (primary) if (enabled) colors.primary else colors.buttonSecondary else colors.glassElevated)
            .border(1.dp, if (primary && enabled) colors.primary else colors.borderGlass, RoundedCornerShape(24.dp))
            .semantics { contentDescription = label }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MaterialSymbol(
            symbol,
            null,
            size = 22.dp,
            tint = if (primary && enabled) colors.onPrimary else if (enabled) colors.textMain else colors.textMuted,
        )
    }
}
