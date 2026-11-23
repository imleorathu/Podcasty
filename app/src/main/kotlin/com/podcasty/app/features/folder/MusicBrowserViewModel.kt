/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.folder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.podcasty.app.core.UnicodeSortUtil
import com.podcasty.app.data.PodcastPreferencesRepository
import com.podcasty.app.domain.AlbumSummary
import com.podcasty.app.domain.ArtistSummary
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.ExcludedFolder
import com.podcasty.app.domain.FolderSortOption
import com.podcasty.app.domain.FolderViewMode
import com.podcasty.app.domain.LinearFolderSummary
import com.podcasty.app.domain.MusicFolder
import com.podcasty.app.domain.MusicLibraryCategory
import com.podcasty.app.domain.MusicQueue
import com.podcasty.app.domain.PodcastCollection
import com.podcasty.app.domain.PodcastRepository
import com.podcasty.app.domain.SongSortOption
import com.podcasty.app.domain.SortOrder
import com.podcasty.app.domain.StorageProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HierarchicalChildFolder(
    val name: String,
    val relativePath: String,
    val songCount: Int,
)

data class StorageRootNode(
    val treeUri: String,
    val displayName: String,
    val storageType: StorageProviderType,
    val trackCount: Int,
    val isAvailable: Boolean,
)

data class MusicBrowserUiState(
    val favoriteFolders: List<com.podcasty.app.data.FavoriteMusicFolder> = emptyList(),
    val selectedCategory: MusicLibraryCategory = MusicLibraryCategory.Folders,
    val folderViewMode: FolderViewMode = FolderViewMode.Hierarchical,
    val folderSortOption: FolderSortOption = FolderSortOption.Name,
    val songSortOption: SongSortOption = SongSortOption.Title,
    val songSortOrder: SortOrder = SortOrder.Ascending,
    val searchQuery: String = "",
    // Hierarchical folder position
    val selectedTreeUri: String? = null,
    val currentSubpath: String = "",
    val storageRoots: List<StorageRootNode> = emptyList(),
    val childFolders: List<HierarchicalChildFolder> = emptyList(),
    val nestedSubfolderCount: Int = 0,
    val recursiveFolderSongCount: Int = 0,
    val recursiveFolderDurationMs: Long = 0L,
    val directFolderSongs: List<Episode> = emptyList(),
    // Linear folders
    val linearFolders: List<LinearFolderSummary> = emptyList(),
    val selectedLinearFolder: LinearFolderSummary? = null,
    // Albums / Artists / Songs / Playlists
    val albums: List<AlbumSummary> = emptyList(),
    val selectedAlbum: AlbumSummary? = null,
    val albumSongs: List<Episode> = emptyList(),
    val artists: List<ArtistSummary> = emptyList(),
    val selectedArtist: ArtistSummary? = null,
    val artistSongs: List<Episode> = emptyList(),
    val songs: List<Episode> = emptyList(),
    val totalLocalMusicCount: Int = 0,
    val playlists: List<PodcastCollection> = emptyList(),
    val excludedFolders: List<ExcludedFolder> = emptyList(),
    val musicQueues: List<MusicQueue> = emptyList(),
    val selectedSongIds: Set<String> = emptySet(),
    val isMultiSelectActive: Boolean = false,
)

/**
 * SQL-backed, memory-bounded Music Browser ViewModel driving:
 * - Folders (Linear & Hierarchical views with breadcrumb/position persistence)
 * - Albums, Artists, Paged Songs, and Local Playlists
 * - Multi-selection and Multi-Queue state
 *
 * Avoids holding all track entities in Compose or running .lowercase() comparators inside UI.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class MusicBrowserViewModel(
    private val repository: PodcastRepository,
    private val preferences: PodcastPreferencesRepository,
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val folderLimit = MutableStateFlow(DefaultPageSize)
    private val songsLimit = MutableStateFlow(DefaultPageSize)
    private val albumsLimit = MutableStateFlow(DefaultPageSize)
    private val artistsLimit = MutableStateFlow(DefaultPageSize)

    private val selectedTreeUri = MutableStateFlow<String?>(null)
    private val currentSubpath = MutableStateFlow("")
    private val selectedLinearFolderKey = MutableStateFlow<String?>(null)
    private val selectedAlbumId = MutableStateFlow<String?>(null)
    private val selectedArtistId = MutableStateFlow<String?>(null)
    private val songSortOrder = MutableStateFlow(SortOrder.Ascending)
    private val selectedSongIds = MutableStateFlow<Set<String>>(emptySet())
    private val isMultiSelectActive = MutableStateFlow(false)
    private var positionRestored = false

    init {
        viewModelScope.launch {
            val settings = preferences.settings.first()
            if (!positionRestored) {
                positionRestored = true
                selectedTreeUri.value = settings.lastFolderTreeUri ?: settings.defaultMusicFolderUri
                currentSubpath.value = settings.lastFolderSubpath
            }
        }
    }

    private val debouncedQuery = searchQuery.debounce(120L).distinctUntilChanged()
    private val libraryCategory = preferences.settings.map { it.musicLibraryCategory }.distinctUntilChanged()

    private val songsFlow = combine(
        libraryCategory,
        debouncedQuery,
        preferences.settings,
        songSortOrder,
        songsLimit,
    ) { category, q, settings, order, limit ->
        category to Quad(q, settings.songSortOption, order, limit)
    }.flatMapLatest { (category, query) ->
        if (category != MusicLibraryCategory.Songs) {
            flowOf(emptyList())
        } else {
            repository.observeLocalSongsPaged(query.a, query.b, query.c, query.d)
        }
    }

    private val albumsFlow = combine(libraryCategory, debouncedQuery, albumsLimit) { category, q, limit ->
        Triple(category, q, limit)
    }.flatMapLatest { (category, q, limit) ->
        if (category != MusicLibraryCategory.Albums) flowOf(emptyList())
        else repository.observeLocalAlbums(q, limit)
    }

    private val artistsFlow = combine(libraryCategory, debouncedQuery, artistsLimit) { category, q, limit ->
        Triple(category, q, limit)
    }.flatMapLatest { (category, q, limit) ->
        if (category != MusicLibraryCategory.Artists) flowOf(emptyList())
        else repository.observeLocalArtists(q, limit)
    }

    private val albumSongsFlow = combine(libraryCategory, selectedAlbumId) { category, id ->
        category to id
    }.flatMapLatest { (category, id) ->
        if (category != MusicLibraryCategory.Albums || id == null) flowOf(emptyList())
        else repository.observeSongsForAlbum(id)
    }

    private val artistSongsFlow = combine(libraryCategory, selectedArtistId) { category, id ->
        category to id
    }.flatMapLatest { (category, id) ->
        if (category != MusicLibraryCategory.Artists || id == null) flowOf(emptyList())
        else repository.observeSongsForArtist(id)
    }

    private val folderSelectionFlow = combine(
        selectedTreeUri,
        currentSubpath,
        selectedLinearFolderKey,
        repository.observeLinearFolderSummaries(),
        repository.musicFolders,
    ) { treeUri, subpath, linearKey, linearList, folders ->
        FolderNavBundle(treeUri, subpath, linearKey, linearList, folders)
    }

    private val directFolderSongsFlow = combine(
        libraryCategory,
        folderSelectionFlow,
        debouncedQuery,
        folderLimit,
    ) { category, nav, q, limit ->
        FolderSongsRequest(category, nav, q, limit)
    }.flatMapLatest { request ->
        if (request.category != MusicLibraryCategory.Folders) {
            flowOf(emptyList())
        } else {
            val nav = request.nav
            val activeLinear = nav.linearKey?.let { key -> nav.linearList.firstOrNull { it.folderKey == key } }
            if (activeLinear != null) {
                repository.observeDirectFolderSongs(
                    activeLinear.treeUri,
                    activeLinear.relativePath,
                    request.query,
                    request.limit,
                )
            } else {
                val resolvedTree = nav.treeUri ?: nav.folders.firstOrNull { it.isDefault }?.treeUri
                    ?: nav.folders.firstOrNull()?.treeUri
                    ?: nav.linearList.firstOrNull()?.treeUri
                if (resolvedTree == null || request.limit <= 0) {
                    flowOf(emptyList())
                } else {
                    repository.observeDirectFolderSongs(
                        resolvedTree,
                        nav.subpath.trim('/'),
                        request.query,
                        request.limit,
                    )
                }
            }
        }
    }

    private val baseDataFlow = combine(
        preferences.settings,
        folderSelectionFlow,
        directFolderSongsFlow,
        searchQuery,
        songSortOrder,
    ) { settings, nav, directSongs, rawQuery, order ->
        BaseBrowserBundle(settings, nav, directSongs, rawQuery, order)
    }

    private val catalogDataFlow = combine(
        albumsFlow,
        selectedAlbumId,
        albumSongsFlow,
        artistsFlow,
        selectedArtistId,
    ) { albums, selAlbId, albSongs, artists, selArtId ->
        CatalogBundle(albums, selAlbId, albSongs, artists, selArtId)
    }

    private val libraryTailFlow = combine(
        artistSongsFlow,
        songsFlow,
        repository.observeLocalMusicCount(),
        repository.collections,
        repository.observeExcludedFolders(),
    ) { artSongs, songs, localCount, collections, excluded ->
        LibraryTailBundle(artSongs, songs, localCount, collections, excluded)
    }

    private val selectionAndQueueFlow = combine(
        repository.observeMusicQueues(),
        selectedSongIds,
        isMultiSelectActive,
    ) { queues, selIds, multiActive ->
        Triple(queues, selIds, multiActive)
    }

    val uiState: StateFlow<MusicBrowserUiState> = combine(
        baseDataFlow,
        catalogDataFlow,
        libraryTailFlow,
        selectionAndQueueFlow,
    ) { base, catalog, tail, (queues, selIds, multiActive) ->
        val settings = base.settings
        val nav = base.nav
        val trimmedQuery = base.rawQuery.trim()
        val foldersByUri = nav.folders.associateBy { it.treeUri }

        val storageRoots = buildStorageRoots(nav.folders, nav.linearList)
        val activeTree = nav.treeUri?.takeIf { uri ->
            storageRoots.any { it.treeUri == uri } || nav.folders.any { it.treeUri == uri }
        } ?: settings.defaultMusicFolderUri?.takeIf { uri ->
            storageRoots.any { it.treeUri == uri }
        } ?: storageRoots.firstOrNull()?.treeUri

        val sortedLinear = sortAndFilterLinearFolders(nav.linearList, trimmedQuery, settings.folderSortOption)
        val selectedLinear = nav.linearKey?.let { k -> nav.linearList.firstOrNull { it.folderKey == k } }

        val hierarchySummary = if (activeTree != null) {
            buildHierarchicalMetrics(
                treeUri = activeTree,
                subpath = nav.subpath,
                allFolderSummaries = nav.linearList,
                folderMeta = foldersByUri[activeTree],
                query = trimmedQuery,
                folderSort = settings.folderSortOption,
            )
        } else {
            HierarchicalMetrics()
        }

        val sortedDirectSongs = sortSongsList(base.directFolderSongs, settings.songSortOption, base.songSortOrder)
        val userPlaylists = tail.collections.filter { !it.isSystem }

        MusicBrowserUiState(
            favoriteFolders = settings.favoriteMusicFolders,
            selectedCategory = settings.musicLibraryCategory,
            folderViewMode = settings.folderViewMode,
            folderSortOption = settings.folderSortOption,
            songSortOption = settings.songSortOption,
            songSortOrder = base.songSortOrder,
            searchQuery = base.rawQuery,
            selectedTreeUri = activeTree,
            currentSubpath = nav.subpath,
            storageRoots = storageRoots,
            childFolders = hierarchySummary.children,
            nestedSubfolderCount = hierarchySummary.nestedSubfolderCount,
            recursiveFolderSongCount = hierarchySummary.totalSongs,
            recursiveFolderDurationMs = hierarchySummary.totalDurationMs,
            directFolderSongs = sortedDirectSongs,
            linearFolders = sortedLinear,
            selectedLinearFolder = selectedLinear,
            albums = catalog.albums,
            selectedAlbum = catalog.selectedAlbumId?.let { id -> catalog.albums.firstOrNull { it.albumId == id } },
            albumSongs = catalog.albumSongs,
            artists = catalog.artists,
            selectedArtist = catalog.selectedArtistId?.let { id -> catalog.artists.firstOrNull { it.artistId == id } },
            artistSongs = tail.artistSongs,
            songs = tail.songs,
            totalLocalMusicCount = tail.localCount,
            playlists = userPlaylists,
            excludedFolders = tail.excluded,
            musicQueues = queues,
            selectedSongIds = selIds,
            isMultiSelectActive = multiActive,
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), MusicBrowserUiState())

    fun selectCategory(category: MusicLibraryCategory) {
        clearSelection()
        viewModelScope.launch { preferences.setMusicLibraryCategory(category) }
    }

    fun setFolderViewMode(mode: FolderViewMode) {
        selectedLinearFolderKey.value = null
        clearSelection()
        viewModelScope.launch { preferences.setFolderViewMode(mode) }
    }

    fun setFolderSortOption(option: FolderSortOption) {
        viewModelScope.launch { preferences.setFolderSortOption(option) }
    }

    fun setSongSortOption(option: SongSortOption) {
        val current = uiState.value.songSortOption
        if (current == option) {
            songSortOrder.value = if (songSortOrder.value == SortOrder.Ascending) SortOrder.Descending else SortOrder.Ascending
        } else {
            songSortOrder.value = if (option == SongSortOption.DateAdded || option == SongSortOption.Duration) {
                SortOrder.Descending
            } else {
                SortOrder.Ascending
            }
            viewModelScope.launch { preferences.setSongSortOption(option) }
        }
    }

    fun toggleSongSortOrder() {
        songSortOrder.value = if (songSortOrder.value == SortOrder.Ascending) SortOrder.Descending else SortOrder.Ascending
    }

    fun setSearchQuery(q: String) {
        searchQuery.value = q
    }

    fun toggleCurrentFolderFavorite() {
        val state = uiState.value
        val linear = state.selectedLinearFolder
        val tree = linear?.treeUri ?: state.selectedTreeUri ?: return
        val path = linear?.relativePath ?: state.currentSubpath
        val name = linear?.folderName ?: path.substringAfterLast('/').ifBlank { state.storageRoots.firstOrNull { it.treeUri == tree }?.displayName ?: "Music" }
        viewModelScope.launch { preferences.toggleFavoriteMusicFolder(com.podcasty.app.data.FavoriteMusicFolder(tree, path, name)) }
    }

    fun openFavoriteFolder(folder: com.podcasty.app.data.FavoriteMusicFolder) {
        selectedLinearFolderKey.value = null
        selectedTreeUri.value = folder.treeUri
        currentSubpath.value = folder.relativePath
        searchQuery.value = ""
        clearSelection()
        viewModelScope.launch {
            preferences.setFolderViewMode(FolderViewMode.Hierarchical)
            preferences.setLastFolderPosition(folder.treeUri, folder.relativePath)
        }
    }

    fun openStorageRoot(treeUri: String) {
        selectedLinearFolderKey.value = null
        selectedTreeUri.value = treeUri
        currentSubpath.value = ""
        searchQuery.value = ""
        clearSelection()
        viewModelScope.launch { preferences.setLastFolderPosition(treeUri, "") }
    }

    fun openHierarchicalSubfolder(relativePath: String) {
        val tree = uiState.value.selectedTreeUri ?: return
        val clean = relativePath.trim('/')
        currentSubpath.value = clean
        searchQuery.value = ""
        clearSelection()
        viewModelScope.launch { preferences.setLastFolderPosition(tree, clean) }
    }

    fun navigateUpFolder(): Boolean {
        if (selectedLinearFolderKey.value != null) {
            selectedLinearFolderKey.value = null
            searchQuery.value = ""
            clearSelection()
            return true
        }
        val cur = currentSubpath.value
        if (cur.isNotEmpty()) {
            val parent = cur.substringBeforeLast('/', "")
            openHierarchicalSubfolder(parent)
            return true
        }
        return false
    }

    fun openLinearFolder(folder: LinearFolderSummary) {
        selectedLinearFolderKey.value = folder.folderKey
        searchQuery.value = ""
        clearSelection()
    }

    fun closeLinearFolder() {
        selectedLinearFolderKey.value = null
        searchQuery.value = ""
        clearSelection()
    }

    fun openContainingFolder(episode: Episode) {
        val tree = episode.folderUri
        val rel = if (episode.audioAsset.contains('/')) episode.audioAsset.substringBeforeLast('/').trim('/') else ""
        selectedAlbumId.value = null
        selectedArtistId.value = null
        selectedLinearFolderKey.value = null
        if (!tree.isNullOrBlank()) {
            selectedTreeUri.value = tree
        }
        currentSubpath.value = rel
        searchQuery.value = ""
        clearSelection()
        viewModelScope.launch {
            preferences.setMusicLibraryCategory(MusicLibraryCategory.Folders)
            preferences.setFolderViewMode(FolderViewMode.Hierarchical)
            if (!tree.isNullOrBlank()) {
                preferences.setLastFolderPosition(tree, rel)
            }
        }
    }

    fun selectAlbum(albumId: String?) {
        selectedAlbumId.value = albumId
        clearSelection()
    }

    fun selectArtist(artistId: String?) {
        selectedArtistId.value = artistId
        clearSelection()
    }

    fun navigateBackInsideTab(): Boolean {
        val state = uiState.value
        if (state.isMultiSelectActive) {
            clearSelection()
            return true
        }
        if (state.selectedCategory == MusicLibraryCategory.Albums && selectedAlbumId.value != null) {
            selectedAlbumId.value = null
            return true
        }
        if (state.selectedCategory == MusicLibraryCategory.Artists && selectedArtistId.value != null) {
            selectedArtistId.value = null
            return true
        }
        if (state.selectedCategory == MusicLibraryCategory.Folders) {
            return navigateUpFolder()
        }
        return false
    }

    fun loadMoreSongs() {
        songsLimit.value = songsLimit.value + PageStep
    }

    fun loadMoreAlbums() {
        albumsLimit.value = albumsLimit.value + PageStep
    }

    fun loadMoreArtists() {
        artistsLimit.value = artistsLimit.value + PageStep
    }

    fun loadMoreFolderSongs() {
        folderLimit.value = folderLimit.value + PageStep
    }

    fun toggleSongSelection(episodeId: String) {
        val next = selectedSongIds.value.toMutableSet()
        if (!next.add(episodeId)) next.remove(episodeId)
        selectedSongIds.value = next
        isMultiSelectActive.value = next.isNotEmpty()
    }

    fun selectAllVisible(episodes: List<Episode>) {
        selectedSongIds.value = episodes.mapTo(LinkedHashSet(episodes.size)) { it.id }
        isMultiSelectActive.value = selectedSongIds.value.isNotEmpty()
    }

    fun clearSelection() {
        selectedSongIds.value = emptySet()
        isMultiSelectActive.value = false
    }

    suspend fun getAllSortedSongIdsForPlayback(): List<String> {
        val state = uiState.value
        return repository.getLocalSongIdsSorted(
            query = state.searchQuery,
            sortOption = state.songSortOption,
            sortOrder = state.songSortOrder,
        )
    }

    suspend fun getFolderSongIdsForPlayback(recursive: Boolean): List<String> {
        val state = uiState.value
        val selectedLinear = state.selectedLinearFolder
        if (selectedLinear != null) {
            return repository.getDirectFolderSongIds(selectedLinear.treeUri, selectedLinear.relativePath)
        }
        val tree = state.selectedTreeUri ?: return emptyList()
        return if (recursive) {
            repository.getRecursiveFolderSongIds(tree, state.currentSubpath)
        } else {
            repository.getDirectFolderSongIds(tree, state.currentSubpath)
        }
    }

    fun excludeFolder(folder: LinearFolderSummary) {
        viewModelScope.launch {
            repository.excludeFolder(
                folderKey = folder.folderKey,
                displayName = folder.displayPath,
                treeUri = folder.treeUri,
                relativePath = folder.relativePath,
            )
            if (selectedLinearFolderKey.value == folder.folderKey) {
                selectedLinearFolderKey.value = null
            }
        }
    }

    fun restoreExcludedFolder(folderKey: String) {
        viewModelScope.launch {
            repository.restoreExcludedFolder(folderKey)
        }
    }

    private fun buildStorageRoots(
        folders: List<MusicFolder>,
        linearSummaries: List<LinearFolderSummary>,
    ): List<StorageRootNode> {
        val out = LinkedHashMap<String, StorageRootNode>()
        val countByTree = HashMap<String, Int>()
        linearSummaries.forEach { row ->
            countByTree[row.treeUri] = (countByTree[row.treeUri] ?: 0) + row.trackCount
        }
        folders.forEach { f ->
            out[f.treeUri] = StorageRootNode(
                treeUri = f.treeUri,
                displayName = f.displayName,
                storageType = f.storageType,
                trackCount = countByTree[f.treeUri] ?: f.trackCount,
                isAvailable = f.isAvailable,
            )
        }
        val mediaStoreCount = countByTree[StorageProviderType.MediaStore.key] ?: 0
        if (mediaStoreCount > 0 && StorageProviderType.MediaStore.key !in out) {
            out[StorageProviderType.MediaStore.key] = StorageRootNode(
                treeUri = StorageProviderType.MediaStore.key,
                displayName = "Device Audio",
                storageType = StorageProviderType.MediaStore,
                trackCount = mediaStoreCount,
                isAvailable = true,
            )
        }
        return out.values.toList()
    }

    private fun sortAndFilterLinearFolders(
        folders: List<LinearFolderSummary>,
        query: String,
        sortOption: FolderSortOption,
    ): List<LinearFolderSummary> {
        val filtered = if (query.isEmpty()) {
            folders
        } else {
            folders.filter {
                it.folderName.contains(query, ignoreCase = true) ||
                    it.displayPath.contains(query, ignoreCase = true)
            }
        }
        val comparator: Comparator<LinearFolderSummary> = when (sortOption) {
            FolderSortOption.Name -> UnicodeSortUtil.precomputedKeyComparator(LinearFolderSummary::folderName)
                .thenComparator { a, b -> UnicodeSortUtil.compareInternational(a.displayPath, b.displayPath) }
            FolderSortOption.TrackCount -> compareByDescending<LinearFolderSummary> { it.trackCount }
                .thenComparator { a, b -> UnicodeSortUtil.compareInternational(a.folderName, b.folderName) }
            FolderSortOption.RecentlyAdded -> compareByDescending<LinearFolderSummary> { it.latestAddedAt }
                .thenComparator { a, b -> UnicodeSortUtil.compareInternational(a.folderName, b.folderName) }
        }
        return filtered.sortedWith(comparator)
    }

    private fun buildHierarchicalMetrics(
        treeUri: String,
        subpath: String,
        allFolderSummaries: List<LinearFolderSummary>,
        folderMeta: MusicFolder?,
        query: String,
        folderSort: FolderSortOption,
    ): HierarchicalMetrics {
        val cleanSub = subpath.trim('/')
        val prefix = if (cleanSub.isEmpty()) "" else "$cleanSub/"
        val counts = LinkedHashMap<String, Int>()
        val latest = HashMap<String, Long>()
        val canonicalName = LinkedHashMap<String, String>()
        val nestedDirSet = HashSet<String>()
        var totalSongs = 0
        var totalDuration = 0L

        for (summary in allFolderSummaries) {
            if (summary.treeUri != treeUri) continue
            val rel = summary.relativePath.trim('/')
            val insideTree = if (cleanSub.isEmpty()) {
                true
            } else {
                rel == cleanSub || rel.startsWith(prefix)
            }
            if (!insideTree) continue
            totalSongs += summary.trackCount
            totalDuration += summary.totalDurationMs

            if (rel == cleanSub) continue
            val remainder = if (prefix.isEmpty()) rel else rel.substring(prefix.length)
            if (remainder.isEmpty()) continue

            // Count nested unique directory prefixes without heavy split chains
            var slashIdx = remainder.indexOf('/')
            while (slashIdx >= 0) {
                nestedDirSet += remainder.substring(0, slashIdx)
                slashIdx = remainder.indexOf('/', slashIdx + 1)
            }
            nestedDirSet += remainder

            val childName = remainder.substringBefore('/')
            val childKey = UnicodeSortUtil.normalizeKey(childName)
            canonicalName.putIfAbsent(childKey, childName)
            counts[childKey] = (counts[childKey] ?: 0) + summary.trackCount
            val prevLatest = latest[childKey] ?: 0L
            if (summary.latestAddedAt > prevLatest) latest[childKey] = summary.latestAddedAt
        }

        val childList = counts.entries
            .mapNotNull { (key, count) ->
                val name = canonicalName[key] ?: key
                if (query.isNotEmpty() && !name.contains(query, ignoreCase = true)) return@mapNotNull null
                val relPath = if (cleanSub.isEmpty()) name else "$cleanSub/$name"
                HierarchicalChildFolder(
                    name = name,
                    relativePath = relPath,
                    songCount = count,
                )
            }
            .sortedWith(
                when (folderSort) {
                    FolderSortOption.Name -> UnicodeSortUtil.precomputedKeyComparator(HierarchicalChildFolder::name)
                    FolderSortOption.TrackCount -> compareByDescending<HierarchicalChildFolder> { it.songCount }
                        .thenComparator { a, b -> UnicodeSortUtil.compareInternational(a.name, b.name) }
                    FolderSortOption.RecentlyAdded -> compareByDescending<HierarchicalChildFolder> {
                        latest[UnicodeSortUtil.normalizeKey(it.name)] ?: 0L
                    }
                },
            )

        return HierarchicalMetrics(
            children = childList,
            nestedSubfolderCount = nestedDirSet.size,
            totalSongs = if (totalSongs == 0 && folderMeta != null && cleanSub.isEmpty()) folderMeta.trackCount else totalSongs,
            totalDurationMs = totalDuration,
        )
    }

    private fun sortSongsList(
        songs: List<Episode>,
        option: SongSortOption,
        order: SortOrder,
    ): List<Episode> {
        if (songs.size <= 1) return songs
        val comparator: Comparator<Episode> = when (option) {
            SongSortOption.Title -> UnicodeSortUtil.precomputedKeyComparator(Episode::title)
            SongSortOption.Artist -> UnicodeSortUtil.precomputedKeyComparator<Episode>(Episode::hostName)
                .thenComparator { a, b -> UnicodeSortUtil.compareInternational(a.title, b.title) }
            SongSortOption.Album -> UnicodeSortUtil.precomputedKeyComparator<Episode>(Episode::podcastTitle)
                .thenBy { it.trackNumber }
            SongSortOption.Duration -> compareBy { it.durationMs }
            SongSortOption.DateAdded -> compareBy { it.publishedAt }
        }
        return if (order == SortOrder.Ascending) songs.sortedWith(comparator)
        else songs.sortedWith(comparator.reversed())
    }

    private data class HierarchicalMetrics(
        val children: List<HierarchicalChildFolder> = emptyList(),
        val nestedSubfolderCount: Int = 0,
        val totalSongs: Int = 0,
        val totalDurationMs: Long = 0L,
    )

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
    private data class FolderSongsRequest(
        val category: MusicLibraryCategory,
        val nav: FolderNavBundle,
        val query: String,
        val limit: Int,
    )
    private data class FolderNavBundle(
        val treeUri: String?,
        val subpath: String,
        val linearKey: String?,
        val linearList: List<LinearFolderSummary>,
        val folders: List<MusicFolder>,
    )

    private data class BaseBrowserBundle(
        val settings: com.podcasty.app.data.PodcastUserSettings,
        val nav: FolderNavBundle,
        val directFolderSongs: List<Episode>,
        val rawQuery: String,
        val songSortOrder: SortOrder,
    )

    private data class CatalogBundle(
        val albums: List<AlbumSummary>,
        val selectedAlbumId: String?,
        val albumSongs: List<Episode>,
        val artists: List<ArtistSummary>,
        val selectedArtistId: String?,
    )

    private data class LibraryTailBundle(
        val artistSongs: List<Episode>,
        val songs: List<Episode>,
        val localCount: Int,
        val collections: List<PodcastCollection>,
        val excluded: List<ExcludedFolder>,
    )

    class Factory(
        private val repository: PodcastRepository,
        private val preferences: PodcastPreferencesRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MusicBrowserViewModel(repository, preferences) as T
    }

    companion object {
        private const val DefaultPageSize = 200
        private const val PageStep = 200
    }
}
