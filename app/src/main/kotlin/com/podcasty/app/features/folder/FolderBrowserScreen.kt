/*
 * Podcasty — Android Podcast & Music Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.folder

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import com.podcasty.app.core.formatCompactDuration
import com.podcasty.app.core.formatPlaybackTime
import com.podcasty.app.domain.AlbumSummary
import com.podcasty.app.domain.ArtistSummary
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.FolderSortOption
import com.podcasty.app.domain.FolderViewMode
import com.podcasty.app.domain.LinearFolderSummary
import com.podcasty.app.domain.MusicLibraryCategory
import com.podcasty.app.domain.PlaybackQueueSnapshot
import com.podcasty.app.domain.PodcastCollection
import com.podcasty.app.domain.SongSortOption
import com.podcasty.app.domain.SortOrder
import com.podcasty.app.storage.LibraryScanState
import com.podcasty.app.ui.components.AnchoredPopupMenu
import com.podcasty.app.ui.components.ContextActionMenu
import com.podcasty.app.ui.components.ContextActionMenuEntry
import com.podcasty.app.ui.components.EpisodeContextBottomSheetRequest
import com.podcasty.app.ui.components.EpisodeContextMenu
import com.podcasty.app.ui.components.EpisodeRow
import com.podcasty.app.ui.components.HeroCard
import com.podcasty.app.ui.components.LibraryPlaybackActions
import com.podcasty.app.ui.components.LibraryTextFilter
import com.podcasty.app.ui.components.MaterialSymbol
import com.podcasty.app.ui.components.MaterialSymbols
import com.podcasty.app.ui.components.PodcastyCard
import com.podcasty.app.ui.components.PodcastyIconButton
import com.podcasty.app.ui.components.PodcastyPillButton
import com.podcasty.app.ui.components.PodcastyPillButtonVariant
import com.podcasty.app.ui.components.rememberArtworkThumbnail
import com.podcasty.app.ui.theme.LocalPodcastyColors

@Composable
fun FolderBrowserScreen(
    uiState: MusicBrowserUiState,
    defaultFolderName: String?,
    scanState: LibraryScanState,
    currentEpisodeId: String?,
    isPlaying: Boolean,
    playbackQueue: PlaybackQueueSnapshot,
    hazeState: HazeState?,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onSelectCategory: (MusicLibraryCategory) -> Unit,
    onSetFolderViewMode: (FolderViewMode) -> Unit,
    onSetFolderSortOption: (FolderSortOption) -> Unit,
    onSetSongSortOption: (SongSortOption) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onOpenStorageRoot: (String) -> Unit,
    onToggleFavoriteFolder: () -> Unit = {},
    onOpenFavoriteFolder: (com.podcasty.app.data.FavoriteMusicFolder) -> Unit = {},
    onOpenSubfolder: (String) -> Unit,
    onNavigateUpFolder: () -> Unit,
    onOpenLinearFolder: (LinearFolderSummary) -> Unit,
    onExcludeFolder: (LinearFolderSummary) -> Unit,
    onOpenExcludedFolders: () -> Unit,
    onSelectAlbum: (String?) -> Unit,
    onSelectArtist: (String?) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onCreatePlaylist: () -> Unit,
    onRenamePlaylist: (PodcastCollection) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onSelectDefaultMusicFolder: () -> Unit,
    onRescanFolders: () -> Unit,
    onRefreshSingleFolder: (String) -> Unit,
    onPlayEpisodes: (List<String>, Boolean) -> Unit,
    onPlayAllFolderRecursive: (Boolean) -> Unit,
    onPlayAllFolderDirect: (Boolean) -> Unit,
    onPlayLinearFolderDirect: (String, String, Boolean) -> Unit,
    onPlayAllSortedSongs: (Boolean) -> Unit,
    onAddFolderTracksToQueue: (String, String) -> Unit,
    onOpenQueueManager: () -> Unit,
    onToggleSelectSong: (String) -> Unit,
    onSelectAllVisible: (List<Episode>) -> Unit,
    onClearSelection: () -> Unit,
    onLoadMoreSongs: () -> Unit,
    onLoadMoreAlbums: () -> Unit,
    onLoadMoreArtists: () -> Unit,
    onLoadMoreFolderSongs: () -> Unit,
    onEpisodeClick: (List<Episode>, String) -> Unit,
    onEpisodePlayNext: (Episode) -> Unit,
    onEpisodeAddToQueue: (Episode) -> Unit,
    onEpisodesPlayNext: (List<String>) -> Unit,
    onEpisodesAddToQueue: (List<String>) -> Unit,
    onEpisodeSaveToggle: (Episode, Boolean) -> Unit,
    onEpisodeDownloadToggle: (Episode, Boolean) -> Unit,
    onEpisodeGoToPodcast: (Episode) -> Unit,
    onEpisodeGoToHost: (Episode) -> Unit,
    onEpisodeContextBottomSheet: (EpisodeContextBottomSheetRequest) -> Unit,
    isFullScreenPlayerVisible: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPodcastyColors.current
    var contextEpisode by remember { mutableStateOf<Episode?>(null) }
    var contextLinearFolderKey by remember { mutableStateOf<String?>(null) }

    val canNavigateBackInside = uiState.isMultiSelectActive ||
        (uiState.selectedCategory == MusicLibraryCategory.Albums && uiState.selectedAlbum != null) ||
        (uiState.selectedCategory == MusicLibraryCategory.Artists && uiState.selectedArtist != null) ||
        (uiState.selectedCategory == MusicLibraryCategory.Folders && (uiState.selectedLinearFolder != null || uiState.currentSubpath.isNotEmpty()))

    BackHandler(enabled = canNavigateBackInside && !isFullScreenPlayerVisible) {
        when {
            uiState.isMultiSelectActive -> onClearSelection()
            uiState.selectedCategory == MusicLibraryCategory.Albums && uiState.selectedAlbum != null -> onSelectAlbum(null)
            uiState.selectedCategory == MusicLibraryCategory.Artists && uiState.selectedArtist != null -> onSelectArtist(null)
            else -> onNavigateUpFolder()
        }
    }

    // Auto-load next page when user nears the bottom of a paginated list
    val isNearBottom by remember(listState) {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 12 && lastVisible >= total - 8
        }
    }
    LaunchedEffect(isNearBottom, uiState.selectedCategory) {
        if (!isNearBottom) return@LaunchedEffect
        when (uiState.selectedCategory) {
            MusicLibraryCategory.Songs -> onLoadMoreSongs()
            MusicLibraryCategory.Albums -> if (uiState.selectedAlbum == null) onLoadMoreAlbums()
            MusicLibraryCategory.Artists -> if (uiState.selectedArtist == null) onLoadMoreArtists()
            MusicLibraryCategory.Folders -> onLoadMoreFolderSongs()
            MusicLibraryCategory.Playlists -> Unit
        }
    }

    val activeRootLabel = uiState.storageRoots.firstOrNull { it.treeUri == uiState.selectedTreeUri }?.displayName
        ?: defaultFolderName
        ?: "Music"
    val activeStorageLabel = uiState.storageRoots.firstOrNull { it.treeUri == uiState.selectedTreeUri }?.storageType?.label
        ?: "Internal Storage"

    val hasAnyFoldersOrTracks = uiState.storageRoots.isNotEmpty() || uiState.totalLocalMusicCount > 0 || scanState.isScanning

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding(),
        ),
    ) {
        // 1. Single-Row Horizontal Library Category Selector (Folders | Albums | Artists | Songs | Playlists)
        item(key = "music-category-bar") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MusicLibraryCategory.entries.forEach { category ->
                    val selected = uiState.selectedCategory == category
                    FilterChipPill(
                        label = category.label,
                        selected = selected,
                        onClick = { onSelectCategory(category) },
                    )
                }
            }
        }

        // 2. Empty onboarding card if no music folders or tracks exist yet
        if (!hasAnyFoldersOrTracks && uiState.selectedCategory != MusicLibraryCategory.Playlists) {
            item(key = "no-folder-onboarding") {
                Box(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                    HeroCard(
                        symbol = MaterialSymbols.Folder,
                        title = "No default music folder selected",
                        description = "Select your Music folder, SD card, or USB OTG storage to browse folders, albums, artists, and edit tags offline.",
                        bottomContent = {
                            Box(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
                                PodcastyPillButton(
                                    label = "Select Default Music Folder",
                                    onClick = onSelectDefaultMusicFolder,
                                    variant = PodcastyPillButtonVariant.Primary,
                                )
                            }
                        },
                    )
                }
            }
            return@LazyColumn
        }

        // 3. Multi-select batch action bar when songs are selected
        if (uiState.isMultiSelectActive) {
            item(key = "multi-select-bar") {
                val visibleSongs = when (uiState.selectedCategory) {
                    MusicLibraryCategory.Songs -> uiState.songs
                    MusicLibraryCategory.Albums -> uiState.albumSongs
                    MusicLibraryCategory.Artists -> uiState.artistSongs
                    else -> uiState.directFolderSongs
                }
                val selectedEpisodes = visibleSongs.filter { it.id in uiState.selectedSongIds }
                val selectedIdsList = uiState.selectedSongIds.toList()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.primary.copy(alpha = 0.12f))
                        .border(1.dp, colors.primary.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${uiState.selectedSongIds.size} selected",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.textMain,
                            modifier = Modifier.weight(1f),
                        )
                        FilterChipPill(
                            label = "Select all (${visibleSongs.size})",
                            selected = false,
                            onClick = { onSelectAllVisible(visibleSongs) },
                        )
                        Spacer(Modifier.width(6.dp))
                        FilterChipPill(
                            label = "Done",
                            selected = true,
                            onClick = onClearSelection,
                        )
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChipPill("Play", false) {
                            if (selectedIdsList.isNotEmpty()) onPlayEpisodes(selectedIdsList, false)
                            onClearSelection()
                        }
                        FilterChipPill("Play next", false) {
                            if (selectedIdsList.isNotEmpty()) onEpisodesPlayNext(selectedIdsList)
                            onClearSelection()
                        }
                        FilterChipPill("Add to queue", false) {
                            if (selectedIdsList.isNotEmpty()) onEpisodesAddToQueue(selectedIdsList)
                            onClearSelection()
                        }
                        FilterChipPill("Add to playlist", false) {
                            if (selectedIdsList.isNotEmpty()) {
                                onEpisodeContextBottomSheet(EpisodeContextBottomSheetRequest.CollectionPicker(selectedIdsList, addOnly = true))
                            }
                            onClearSelection()
                        }
                        FilterChipPill("Edit tags", false) {
                            if (selectedEpisodes.isNotEmpty()) {
                                onEpisodeContextBottomSheet(EpisodeContextBottomSheetRequest.TagEditor(selectedEpisodes))
                            }
                            onClearSelection()
                        }
                        FilterChipPill("Delete", false) {
                            if (selectedEpisodes.isNotEmpty()) {
                                onEpisodeContextBottomSheet(EpisodeContextBottomSheetRequest.DeleteFiles(selectedEpisodes))
                            }
                            onClearSelection()
                        }
                    }
                }
            }
        }

        // 4. Active Category Content
        when (uiState.selectedCategory) {
            MusicLibraryCategory.Folders -> {
                // Folder Mode Switcher (Linear | Hierarchical) + Quick Tools
                item(key = "folder-mode-toolbar") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChipPill(
                            label = "Hierarchical",
                            selected = uiState.folderViewMode == FolderViewMode.Hierarchical,
                            onClick = { onSetFolderViewMode(FolderViewMode.Hierarchical) },
                        )
                        FilterChipPill(
                            label = "Linear",
                            selected = uiState.folderViewMode == FolderViewMode.Linear,
                            onClick = { onSetFolderViewMode(FolderViewMode.Linear) },
                        )
                        FilterChipPill(
                            label = when (uiState.folderSortOption) {
                                FolderSortOption.Name -> "Sort: Name"
                                FolderSortOption.TrackCount -> "Sort: Tracks"
                                FolderSortOption.RecentlyAdded -> "Sort: Recent"
                            },
                            selected = false,
                            onClick = {
                                val next = when (uiState.folderSortOption) {
                                    FolderSortOption.Name -> FolderSortOption.TrackCount
                                    FolderSortOption.TrackCount -> FolderSortOption.RecentlyAdded
                                    FolderSortOption.RecentlyAdded -> FolderSortOption.Name
                                }
                                onSetFolderSortOption(next)
                            },
                        )
                        if (uiState.selectedTreeUri != null || uiState.selectedLinearFolder != null) {
                            val linear = uiState.selectedLinearFolder
                            val tree = linear?.treeUri ?: uiState.selectedTreeUri
                            val path = linear?.relativePath ?: uiState.currentSubpath
                            val favorite = uiState.favoriteFolders.any { it.treeUri == tree && it.relativePath == path }
                            FilterChipPill(label = if (favorite) "Unfavorite folder" else "Favorite folder", selected = favorite, onClick = onToggleFavoriteFolder)
                        }
                        FilterChipPill(
                            label = "Queues (${uiState.musicQueues.size.coerceAtLeast(1)})",
                            selected = false,
                            onClick = onOpenQueueManager,
                        )
                        if (uiState.excludedFolders.isNotEmpty()) {
                            FilterChipPill(
                                label = "Excluded (${uiState.excludedFolders.size})",
                                selected = false,
                                onClick = onOpenExcludedFolders,
                            )
                        }
                    }
                }

                if (uiState.favoriteFolders.isNotEmpty()) {
                    item(key = "favorite-folder-shortcuts") {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            uiState.favoriteFolders.forEach { folder -> FilterChipPill(label = "★ ${folder.name}", selected = false, onClick = { onOpenFavoriteFolder(folder) }) }
                        }
                    }
                }

                // Linear Mode vs Hierarchical Mode
                if (uiState.folderViewMode == FolderViewMode.Linear && uiState.selectedLinearFolder == null) {
                    item(key = "linear-search") {
                        LibraryTextFilter(
                            value = uiState.searchQuery,
                            onValueChange = onSearchQueryChange,
                            placeholder = "Search all music folders…",
                        )
                    }
                    if (scanState.isScanning) {
                        item(key = "linear-scan-card") { ScanProgressCard(scanState) }
                    }
                    if (uiState.linearFolders.isEmpty()) {
                        item(key = "linear-empty") {
                            Box(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                                HeroCard(
                                    symbol = MaterialSymbols.Folder,
                                    title = "No music folders found",
                                    description = if (scanState.isScanning) scanState.lastMessage ?: "Indexing…" else "Tap Rescan or select a music folder.",
                                )
                            }
                        }
                    } else {
                        itemsIndexed(
                            items = uiState.linearFolders,
                            key = { _, f -> "linear_${f.folderKey}" },
                            contentType = { _, _ -> FolderBrowserItemType.FOLDER },
                        ) { idx, folder ->
                            LinearFolderRow(
                                folder = folder,
                                menuExpanded = contextLinearFolderKey == folder.folderKey,
                                hazeState = hazeState,
                                onOpen = { onOpenLinearFolder(folder) },
                                onOpenMenu = { contextLinearFolderKey = folder.folderKey },
                                onDismissMenu = { if (contextLinearFolderKey == folder.folderKey) contextLinearFolderKey = null },
                                onPlayAll = { onPlayLinearFolderDirect(folder.treeUri, folder.relativePath, false) },
                                onShuffle = { onPlayLinearFolderDirect(folder.treeUri, folder.relativePath, true) },
                                onAddToQueue = { onAddFolderTracksToQueue(folder.treeUri, folder.relativePath) },
                                onRefresh = { onRefreshSingleFolder(folder.treeUri) },
                                onExclude = { onExcludeFolder(folder) },
                            )
                            if (idx < uiState.linearFolders.lastIndex) {
                                Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(1.dp).background(colors.borderGlass))
                            }
                        }
                    }
                } else if (uiState.folderViewMode == FolderViewMode.Linear && uiState.selectedLinearFolder != null) {
                    val lf = uiState.selectedLinearFolder
                    item(key = "linear-detail-header") {
                        FolderSummaryHeaderCard(
                            breadcrumbText = "${lf.storageType.label} › ${lf.displayPath}",
                            subtitle = "${lf.trackCount} songs · ${formatPlaybackTime(lf.totalDurationMs)}",
                            scanState = scanState,
                            canGoUp = true,
                            upLabel = "All folders",
                            onGoUp = onNavigateUpFolder,
                            onChangeFolder = onSelectDefaultMusicFolder,
                            onRescan = onRescanFolders,
                        )
                    }
                    item(key = "linear-detail-search") {
                        LibraryTextFilter(
                            value = uiState.searchQuery,
                            onValueChange = onSearchQueryChange,
                            placeholder = "Search in ${lf.folderName}…",
                        )
                    }
                    if (uiState.directFolderSongs.isNotEmpty()) {
                        item(key = "linear-detail-actions") {
                            LibraryPlaybackActions(
                                playLabel = "Play (${uiState.directFolderSongs.size})",
                                shuffleLabel = "Shuffle",
                                onPlay = { onPlayAllFolderDirect(false) },
                                onShuffle = { onPlayAllFolderDirect(true) },
                                hazeState = hazeState,
                            )
                        }
                    }
                    renderSongListSection(
                        keyPrefix = "linear_song",
                        songs = uiState.directFolderSongs,
                        currentEpisodeId = currentEpisodeId,
                        isPlaying = isPlaying,
                        playbackQueue = playbackQueue,
                        hazeState = hazeState,
                        selectedIds = uiState.selectedSongIds,
                        isMultiSelect = uiState.isMultiSelectActive,
                        contextEpisode = contextEpisode,
                        onContextEpisodeChange = { contextEpisode = it },
                        onToggleSelect = onToggleSelectSong,
                        onEpisodeClick = { ep -> onEpisodeClick(uiState.directFolderSongs, ep.id) },
                        onEpisodePlayNext = onEpisodePlayNext,
                        onEpisodeAddToQueue = onEpisodeAddToQueue,
                        onEpisodeSaveToggle = onEpisodeSaveToggle,
                        onEpisodeDownloadToggle = onEpisodeDownloadToggle,
                        onEpisodeGoToPodcast = onEpisodeGoToPodcast,
                        onEpisodeGoToHost = onEpisodeGoToHost,
                        onEpisodeContextBottomSheet = onEpisodeContextBottomSheet,
                    )
                } else {
                    // MODE 2 — HIERARCHICAL FOLDER VIEW
                    if (uiState.storageRoots.size > 1 && uiState.currentSubpath.isEmpty()) {
                        item(key = "storage-roots-strip") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 24.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                uiState.storageRoots.forEach { root ->
                                    FilterChipPill(
                                        label = "${root.displayName} (${root.trackCount})",
                                        selected = root.treeUri == uiState.selectedTreeUri,
                                        onClick = { onOpenStorageRoot(root.treeUri) },
                                    )
                                }
                            }
                        }
                    }

                    val breadcrumb = buildString {
                        append(activeStorageLabel)
                        append(" › ")
                        append(activeRootLabel)
                        if (uiState.currentSubpath.isNotEmpty()) {
                            uiState.currentSubpath.split('/').filter { it.isNotBlank() }.forEach { seg ->
                                append(" › ")
                                append(seg)
                            }
                        }
                    }
                    item(key = "hier-summary-header") {
                        val subLabel = buildString {
                            append("${uiState.childFolders.size} subfolders")
                            if (uiState.nestedSubfolderCount > uiState.childFolders.size) {
                                append(" (${uiState.nestedSubfolderCount} total)")
                            }
                            append(" · ${uiState.directFolderSongs.size} direct")
                            if (uiState.recursiveFolderSongCount > uiState.directFolderSongs.size) {
                                append(" (${uiState.recursiveFolderSongCount} recursive)")
                            }
                            if (uiState.recursiveFolderDurationMs > 0L) {
                                append(" · ${formatPlaybackTime(uiState.recursiveFolderDurationMs)}")
                            }
                        }
                        FolderSummaryHeaderCard(
                            breadcrumbText = breadcrumb,
                            subtitle = subLabel,
                            scanState = scanState,
                            canGoUp = uiState.currentSubpath.isNotEmpty(),
                            upLabel = "Up folder",
                            onGoUp = onNavigateUpFolder,
                            onChangeFolder = onSelectDefaultMusicFolder,
                            onRescan = onRescanFolders,
                        )
                    }
                    item(key = "hier-search-filter") {
                        LibraryTextFilter(
                            value = uiState.searchQuery,
                            onValueChange = onSearchQueryChange,
                            placeholder = "Search folders & songs…",
                        )
                    }
                    if (uiState.directFolderSongs.isNotEmpty() || uiState.recursiveFolderSongCount > 0) {
                        item(key = "hier-playback-actions") {
                            Column {
                                LibraryPlaybackActions(
                                    playLabel = if (uiState.directFolderSongs.isNotEmpty()) "Play folder (${uiState.directFolderSongs.size})" else "Play all (${uiState.recursiveFolderSongCount})",
                                    shuffleLabel = "Shuffle all",
                                    onPlay = {
                                        if (uiState.directFolderSongs.isNotEmpty()) onPlayAllFolderDirect(false)
                                        else onPlayAllFolderRecursive(false)
                                    },
                                    onShuffle = { onPlayAllFolderRecursive(true) },
                                    hazeState = hazeState,
                                )
                                if (uiState.directFolderSongs.isNotEmpty() && uiState.recursiveFolderSongCount > uiState.directFolderSongs.size) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        FilterChipPill("Recursive Play All (${uiState.recursiveFolderSongCount})", false) {
                                            onPlayAllFolderRecursive(false)
                                        }
                                        FilterChipPill("Sort: ${uiState.songSortOption.name}", false) {
                                            val next = SongSortOption.entries[(uiState.songSortOption.ordinal + 1) % SongSortOption.entries.size]
                                            onSetSongSortOption(next)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Immediate child subfolders
                    if (uiState.childFolders.isNotEmpty()) {
                        itemsIndexed(
                            items = uiState.childFolders,
                            key = { _, sub -> "hier_sub_${sub.relativePath}" },
                            contentType = { _, _ -> FolderBrowserItemType.FOLDER },
                        ) { index, subfolder ->
                            HierarchicalSubfolderRow(
                                subfolder = subfolder,
                                onClick = { onOpenSubfolder(subfolder.relativePath) },
                            )
                            if (index < uiState.childFolders.lastIndex) {
                                Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(1.dp).background(colors.borderGlass))
                            }
                        }
                    }

                    // Songs directly inside current folder (distinguished cleanly from nested folders)
                    if (uiState.directFolderSongs.isNotEmpty()) {
                        item(key = "hier-direct-songs-header") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${uiState.directFolderSongs.size} songs in this folder",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = colors.textMuted,
                                    modifier = Modifier.weight(1f),
                                )
                                FilterChipPill("Select", uiState.isMultiSelectActive) {
                                    if (uiState.isMultiSelectActive) onClearSelection() else onSelectAllVisible(uiState.directFolderSongs)
                                }
                            }
                        }
                        renderSongListSection(
                            keyPrefix = "hier_song",
                            songs = uiState.directFolderSongs,
                            currentEpisodeId = currentEpisodeId,
                            isPlaying = isPlaying,
                            playbackQueue = playbackQueue,
                            hazeState = hazeState,
                            selectedIds = uiState.selectedSongIds,
                            isMultiSelect = uiState.isMultiSelectActive,
                            contextEpisode = contextEpisode,
                            onContextEpisodeChange = { contextEpisode = it },
                            onToggleSelect = onToggleSelectSong,
                            onEpisodeClick = { ep -> onEpisodeClick(uiState.directFolderSongs, ep.id) },
                            onEpisodePlayNext = onEpisodePlayNext,
                            onEpisodeAddToQueue = onEpisodeAddToQueue,
                            onEpisodeSaveToggle = onEpisodeSaveToggle,
                            onEpisodeDownloadToggle = onEpisodeDownloadToggle,
                            onEpisodeGoToPodcast = onEpisodeGoToPodcast,
                            onEpisodeGoToHost = onEpisodeGoToHost,
                            onEpisodeContextBottomSheet = onEpisodeContextBottomSheet,
                        )
                    } else if (uiState.childFolders.isEmpty()) {
                        item(key = "hier-empty") {
                            Box(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                                HeroCard(
                                    symbol = MaterialSymbols.Folder,
                                    title = if (scanState.isScanning) "Indexing audio files in background…" else "No songs in this folder",
                                    description = if (uiState.searchQuery.isNotBlank()) "Try a different search filter." else "Check child folders or tap Rescan.",
                                )
                            }
                        }
                    }
                }
            }

            MusicLibraryCategory.Albums -> {
                val selAlbum = uiState.selectedAlbum
                if (selAlbum == null) {
                    item(key = "albums-search") {
                        LibraryTextFilter(
                            value = uiState.searchQuery,
                            onValueChange = onSearchQueryChange,
                            placeholder = "Search albums…",
                        )
                    }
                    if (uiState.albums.isEmpty()) {
                        item(key = "albums-empty") {
                            Box(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                                HeroCard(MaterialSymbols.Album, "No local albums", "Scan a music folder to browse albums offline.")
                            }
                        }
                    } else {
                        itemsIndexed(uiState.albums, key = { _, alb -> "alb_${alb.albumId}" }) { idx, album ->
                            AlbumSummaryRow(album = album, onClick = { onSelectAlbum(album.albumId) })
                            if (idx < uiState.albums.lastIndex) {
                                Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(1.dp).background(colors.borderGlass))
                            }
                        }
                    }
                } else {
                    item(key = "album-detail-header") {
                        FolderSummaryHeaderCard(
                            breadcrumbText = selAlbum.albumTitle,
                            subtitle = "${selAlbum.artistName} · ${selAlbum.trackCount} tracks · ${formatPlaybackTime(selAlbum.totalDurationMs)}",
                            scanState = LibraryScanState(),
                            canGoUp = true,
                            upLabel = "All albums",
                            onGoUp = { onSelectAlbum(null) },
                            onChangeFolder = null,
                            onRescan = null,
                        )
                    }
                    if (uiState.albumSongs.isNotEmpty()) {
                        item(key = "album-actions") {
                            LibraryPlaybackActions(
                                playLabel = "Play album",
                                shuffleLabel = "Shuffle",
                                onPlay = { onPlayEpisodes(uiState.albumSongs.map { it.id }, false) },
                                onShuffle = { onPlayEpisodes(uiState.albumSongs.map { it.id }, true) },
                                hazeState = hazeState,
                            )
                        }
                    }
                    renderSongListSection(
                        keyPrefix = "album_song",
                        songs = uiState.albumSongs,
                        currentEpisodeId = currentEpisodeId,
                        isPlaying = isPlaying,
                        playbackQueue = playbackQueue,
                        hazeState = hazeState,
                        selectedIds = uiState.selectedSongIds,
                        isMultiSelect = uiState.isMultiSelectActive,
                        contextEpisode = contextEpisode,
                        onContextEpisodeChange = { contextEpisode = it },
                        onToggleSelect = onToggleSelectSong,
                        onEpisodeClick = { ep -> onEpisodeClick(uiState.albumSongs, ep.id) },
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

            MusicLibraryCategory.Artists -> {
                val selArtist = uiState.selectedArtist
                if (selArtist == null) {
                    item(key = "artists-search") {
                        LibraryTextFilter(
                            value = uiState.searchQuery,
                            onValueChange = onSearchQueryChange,
                            placeholder = "Search artists…",
                        )
                    }
                    if (uiState.artists.isEmpty()) {
                        item(key = "artists-empty") {
                            Box(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                                HeroCard(MaterialSymbols.Person, "No local artists", "Scan a music folder to browse artists offline.")
                            }
                        }
                    } else {
                        itemsIndexed(uiState.artists, key = { _, art -> "art_${art.artistId}" }) { idx, artist ->
                            ArtistSummaryRow(artist = artist, onClick = { onSelectArtist(artist.artistId) })
                            if (idx < uiState.artists.lastIndex) {
                                Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(1.dp).background(colors.borderGlass))
                            }
                        }
                    }
                } else {
                    item(key = "artist-detail-header") {
                        FolderSummaryHeaderCard(
                            breadcrumbText = selArtist.artistName,
                            subtitle = "${selArtist.albumCount} albums · ${selArtist.trackCount} songs",
                            scanState = LibraryScanState(),
                            canGoUp = true,
                            upLabel = "All artists",
                            onGoUp = { onSelectArtist(null) },
                            onChangeFolder = null,
                            onRescan = null,
                        )
                    }
                    if (uiState.artistSongs.isNotEmpty()) {
                        item(key = "artist-actions") {
                            LibraryPlaybackActions(
                                playLabel = "Play all",
                                shuffleLabel = "Shuffle",
                                onPlay = { onPlayEpisodes(uiState.artistSongs.map { it.id }, false) },
                                onShuffle = { onPlayEpisodes(uiState.artistSongs.map { it.id }, true) },
                                hazeState = hazeState,
                            )
                        }
                    }
                    renderSongListSection(
                        keyPrefix = "artist_song",
                        songs = uiState.artistSongs,
                        currentEpisodeId = currentEpisodeId,
                        isPlaying = isPlaying,
                        playbackQueue = playbackQueue,
                        hazeState = hazeState,
                        selectedIds = uiState.selectedSongIds,
                        isMultiSelect = uiState.isMultiSelectActive,
                        contextEpisode = contextEpisode,
                        onContextEpisodeChange = { contextEpisode = it },
                        onToggleSelect = onToggleSelectSong,
                        onEpisodeClick = { ep -> onEpisodeClick(uiState.artistSongs, ep.id) },
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

            MusicLibraryCategory.Songs -> {
                item(key = "songs-sort-strip") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val arrow = if (uiState.songSortOrder == SortOrder.Ascending) " ↑" else " ↓"
                        SongSortOption.entries.forEach { opt ->
                            val selected = uiState.songSortOption == opt
                            val label = when (opt) {
                                SongSortOption.Title -> "Name"
                                SongSortOption.Artist -> "Artist"
                                SongSortOption.Album -> "Album"
                                SongSortOption.Duration -> "Duration"
                                SongSortOption.DateAdded -> "Recent"
                            } + if (selected) arrow else ""
                            FilterChipPill(label = label, selected = selected, onClick = { onSetSongSortOption(opt) })
                        }
                        FilterChipPill("Queues", false, onOpenQueueManager)
                    }
                }
                item(key = "songs-search") {
                    LibraryTextFilter(
                        value = uiState.searchQuery,
                        onValueChange = onSearchQueryChange,
                        placeholder = "Search ${uiState.totalLocalMusicCount} songs…",
                    )
                }
                if (uiState.songs.isNotEmpty()) {
                    item(key = "songs-actions") {
                        LibraryPlaybackActions(
                            playLabel = "Play all (${uiState.totalLocalMusicCount})",
                            shuffleLabel = "Shuffle all",
                            onPlay = { onPlayAllSortedSongs(false) },
                            onShuffle = { onPlayAllSortedSongs(true) },
                            hazeState = hazeState,
                        )
                    }
                    renderSongListSection(
                        keyPrefix = "all_song",
                        songs = uiState.songs,
                        currentEpisodeId = currentEpisodeId,
                        isPlaying = isPlaying,
                        playbackQueue = playbackQueue,
                        hazeState = hazeState,
                        selectedIds = uiState.selectedSongIds,
                        isMultiSelect = uiState.isMultiSelectActive,
                        contextEpisode = contextEpisode,
                        onContextEpisodeChange = { contextEpisode = it },
                        onToggleSelect = onToggleSelectSong,
                        onEpisodeClick = { ep -> onEpisodeClick(uiState.songs, ep.id) },
                        onEpisodePlayNext = onEpisodePlayNext,
                        onEpisodeAddToQueue = onEpisodeAddToQueue,
                        onEpisodeSaveToggle = onEpisodeSaveToggle,
                        onEpisodeDownloadToggle = onEpisodeDownloadToggle,
                        onEpisodeGoToPodcast = onEpisodeGoToPodcast,
                        onEpisodeGoToHost = onEpisodeGoToHost,
                        onEpisodeContextBottomSheet = onEpisodeContextBottomSheet,
                    )
                } else {
                    item(key = "songs-empty") {
                        Box(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                            HeroCard(MaterialSymbols.MusicNote, "No songs found", "Scan a music folder or change your search filter.")
                        }
                    }
                }
            }

            MusicLibraryCategory.Playlists -> {
                item(key = "playlists-header-actions") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            PodcastyPillButton(
                                label = "Create playlist",
                                onClick = onCreatePlaylist,
                                variant = PodcastyPillButtonVariant.Primary,
                            )
                        }
                        Box(Modifier.weight(1f)) {
                            PodcastyPillButton(
                                label = "Music queues",
                                onClick = onOpenQueueManager,
                                variant = PodcastyPillButtonVariant.Secondary,
                            )
                        }
                    }
                }
                if (uiState.playlists.isEmpty()) {
                    item(key = "playlists-empty") {
                        Box(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                            HeroCard(
                                symbol = MaterialSymbols.QueueMusic,
                                title = "No local playlists yet",
                                description = "Create offline music playlists, add songs from Folders or Songs, reorder tracks, and play anytime in airplane mode.",
                            )
                        }
                    }
                } else {
                    itemsIndexed(uiState.playlists, key = { _, col -> "pl_${col.id}" }) { idx, pl ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenPlaylist(pl.id) }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(colors.card),
                                contentAlignment = Alignment.Center,
                            ) {
                                MaterialSymbol(MaterialSymbols.QueueMusic, null, size = 24.dp, tint = colors.primary)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = pl.name,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                    color = colors.textMain,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${pl.episodeIds.size} songs",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textMuted,
                                )
                            }
                            if (pl.episodeIds.isNotEmpty()) {
                                PodcastyIconButton(
                                    symbol = MaterialSymbols.PlayArrow,
                                    label = "Play playlist",
                                    onClick = { onPlayEpisodes(pl.episodeIds, false) },
                                )
                            }
                            PodcastyIconButton(
                                symbol = MaterialSymbols.Edit,
                                label = "Rename playlist",
                                onClick = { onRenamePlaylist(pl) },
                            )
                            PodcastyIconButton(
                                symbol = MaterialSymbols.Delete,
                                label = "Delete playlist",
                                onClick = { onDeletePlaylist(pl.id) },
                            )
                        }
                        if (idx < uiState.playlists.lastIndex) {
                            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(1.dp).background(colors.borderGlass))
                        }
                    }
                }
            }
        }
    }
}

private inline fun androidx.compose.foundation.lazy.LazyListScope.renderSongListSection(
    keyPrefix: String,
    songs: List<Episode>,
    currentEpisodeId: String?,
    isPlaying: Boolean,
    playbackQueue: PlaybackQueueSnapshot,
    hazeState: HazeState?,
    selectedIds: Set<String>,
    isMultiSelect: Boolean,
    contextEpisode: Episode?,
    crossinline onContextEpisodeChange: (Episode?) -> Unit,
    crossinline onToggleSelect: (String) -> Unit,
    crossinline onEpisodeClick: (Episode) -> Unit,
    noinline onEpisodePlayNext: (Episode) -> Unit,
    noinline onEpisodeAddToQueue: (Episode) -> Unit,
    noinline onEpisodeSaveToggle: (Episode, Boolean) -> Unit,
    noinline onEpisodeDownloadToggle: (Episode, Boolean) -> Unit,
    noinline onEpisodeGoToPodcast: (Episode) -> Unit,
    noinline onEpisodeGoToHost: (Episode) -> Unit,
    noinline onEpisodeContextBottomSheet: (EpisodeContextBottomSheetRequest) -> Unit,
) {
    itemsIndexed(
        items = songs,
        key = { _, ep -> "${keyPrefix}_${ep.id}" },
        contentType = { _, _ -> FolderBrowserItemType.MUSIC_FILE },
    ) { index, ep ->
        val colors = LocalPodcastyColors.current
        val selected = ep.id in selectedIds
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (selected) colors.primary.copy(alpha = 0.12f) else colors.background),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isMultiSelect) {
                Box(
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .size(28.dp)
                        .clickable { onToggleSelect(ep.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    MaterialSymbol(
                        symbol = if (selected) MaterialSymbols.CheckCircle else MaterialSymbols.Add,
                        contentDescription = if (selected) "Selected" else "Select",
                        size = 20.dp,
                        tint = if (selected) colors.primary else colors.textMuted,
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                EpisodeContextMenu(
                    episode = ep,
                    expanded = contextEpisode?.id == ep.id,
                    onDismiss = { if (contextEpisode?.id == ep.id) onContextEpisodeChange(null) },
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
                        subtitle = "${ep.hostName} · ${ep.podcastTitle} · ${formatCompactDuration(ep.durationMs)}",
                        artworkPath = ep.artworkAsset,
                        isCurrentPlaying = ep.id == currentEpisodeId,
                        isPlaying = isPlaying,
                        onClick = {
                            if (isMultiSelect) onToggleSelect(ep.id) else onEpisodeClick(ep)
                        },
                        onMoreClick = { onContextEpisodeChange(ep) },
                        onLongClick = { onToggleSelect(ep.id) },
                    )
                }
            }
        }
        if (index < songs.lastIndex) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(1.dp).background(colors.borderGlass))
        }
    }
}

@Composable
private fun FilterChipPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalPodcastyColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) colors.primary else colors.card)
            .border(
                width = 1.dp,
                color = if (selected) colors.primary else colors.borderGlass,
                shape = RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = if (selected) colors.onPrimary else colors.textMain,
            maxLines = 1,
        )
    }
}

@Composable
private fun ScanProgressCard(scanState: LibraryScanState) {
    val colors = LocalPodcastyColors.current
    Box(Modifier.padding(horizontal = 24.dp, vertical = 6.dp)) {
        PodcastyCard(contentPadding = PaddingValues(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = scanState.lastMessage ?: "Indexing audio files in background…",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.primary,
                )
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(999.dp)),
                    color = colors.primary,
                    trackColor = colors.borderGlass,
                )
            }
        }
    }
}

@Composable
private fun FolderSummaryHeaderCard(
    breadcrumbText: String,
    subtitle: String,
    scanState: LibraryScanState,
    canGoUp: Boolean,
    upLabel: String,
    onGoUp: () -> Unit,
    onChangeFolder: (() -> Unit)?,
    onRescan: (() -> Unit)?,
) {
    val colors = LocalPodcastyColors.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        PodcastyCard(contentPadding = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = breadcrumbText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textMain,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
                if (scanState.isScanning) {
                    Text(
                        text = scanState.lastMessage ?: "Indexing audio files in background…",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.primary,
                    )
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(999.dp)),
                        color = colors.primary,
                        trackColor = colors.borderGlass,
                    )
                }
                if (canGoUp || onChangeFolder != null || onRescan != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (canGoUp) {
                            Box(Modifier.weight(1f)) {
                                PodcastyPillButton(upLabel, onGoUp, PodcastyPillButtonVariant.Secondary)
                            }
                        }
                        if (onChangeFolder != null) {
                            Box(Modifier.weight(1f)) {
                                PodcastyPillButton("Change folder", onChangeFolder, PodcastyPillButtonVariant.Secondary)
                            }
                        }
                        if (onRescan != null) {
                            Box(Modifier.weight(1f)) {
                                PodcastyPillButton(
                                    label = if (scanState.isScanning) "Scanning…" else "Rescan",
                                    onClick = { if (!scanState.isScanning) onRescan() },
                                    variant = PodcastyPillButtonVariant.Secondary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal enum class FolderBrowserItemType {
    FOLDER,
    MUSIC_FILE,
}

@Composable
private fun FolderItemIconBox(size: Dp) {
    val colors = LocalPodcastyColors.current
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.card),
        contentAlignment = Alignment.Center,
    ) {
        MaterialSymbol(MaterialSymbols.Folder, null, size = 24.dp, tint = colors.primary)
    }
}

@Composable
private fun LinearFolderRow(
    folder: LinearFolderSummary,
    menuExpanded: Boolean,
    hazeState: HazeState?,
    onOpen: () -> Unit,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onAddToQueue: () -> Unit,
    onRefresh: () -> Unit,
    onExclude: () -> Unit,
) {
    val colors = LocalPodcastyColors.current

    AnchoredPopupMenu(
        expanded = menuExpanded,
        onDismissRequest = onDismissMenu,
        width = 224.dp,
        hazeState = hazeState,
        anchor = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = onOpen, onLongClick = onOpenMenu)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Folders always display the folder icon — never album artwork from contained files
                FolderItemIconBox(size = 48.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = folder.folderName,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = colors.textMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${folder.displayPath} · ${folder.trackCount} tracks · ${folder.storageType.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                PodcastyIconButton(
                    symbol = MaterialSymbols.MoreVert,
                    label = "Folder actions",
                    onClick = onOpenMenu,
                )
            }
        },
    ) {
        ContextActionMenu(
            entries = listOf(
                ContextActionMenuEntry.Action("Open folder", MaterialSymbols.FolderOpen) { onDismissMenu(); onOpen() },
                ContextActionMenuEntry.Action("Play all songs", MaterialSymbols.PlayArrow) { onDismissMenu(); onPlayAll() },
                ContextActionMenuEntry.Action("Shuffle folder", MaterialSymbols.Shuffle) { onDismissMenu(); onShuffle() },
                ContextActionMenuEntry.Action("Add folder to queue", MaterialSymbols.AddToQueue) { onDismissMenu(); onAddToQueue() },
                ContextActionMenuEntry.Action("Refresh folder", MaterialSymbols.Refresh) { onDismissMenu(); onRefresh() },
                ContextActionMenuEntry.Action("Exclude from library", MaterialSymbols.VisibilityOff, destructive = true) { onDismissMenu(); onExclude() },
            ),
        )
    }
}

@Composable
private fun HierarchicalSubfolderRow(
    subfolder: HierarchicalChildFolder,
    onClick: () -> Unit,
) {
    val colors = LocalPodcastyColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Subfolders always display the folder icon — never album artwork from contained files
        FolderItemIconBox(size = 44.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = subfolder.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = colors.textMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${subfolder.songCount} ${if (subfolder.songCount == 1) "song" else "songs"}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
        MaterialSymbol(MaterialSymbols.ChevronRight, null, size = 20.dp, tint = colors.textMuted)
    }
}

@Composable
private fun AlbumSummaryRow(
    album: AlbumSummary,
    onClick: () -> Unit,
) {
    val colors = LocalPodcastyColors.current
    val thumb = rememberArtworkThumbnail(album.artworkAsset, targetPx = 110)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(colors.card),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) {
                Image(thumb, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            } else {
                MaterialSymbol(MaterialSymbols.Album, null, size = 26.dp, tint = colors.primary)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = album.albumTitle,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = colors.textMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${album.artistName} · ${album.trackCount} tracks · ${formatCompactDuration(album.totalDurationMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MaterialSymbol(MaterialSymbols.ChevronRight, null, size = 20.dp, tint = colors.textMuted)
    }
}

@Composable
private fun ArtistSummaryRow(
    artist: ArtistSummary,
    onClick: () -> Unit,
) {
    val colors = LocalPodcastyColors.current
    val thumb = rememberArtworkThumbnail(artist.artworkAsset, targetPx = 110)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(50.dp).clip(RoundedCornerShape(999.dp)).background(colors.card),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) {
                Image(thumb, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            } else {
                MaterialSymbol(MaterialSymbols.Person, null, size = 24.dp, tint = colors.primary)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = artist.artistName,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = colors.textMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${artist.albumCount} albums · ${artist.trackCount} songs",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
        MaterialSymbol(MaterialSymbols.ChevronRight, null, size = 20.dp, tint = colors.textMuted)
    }
}
