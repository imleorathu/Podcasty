/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.podcasty.app.data.PodcastPreferencesRepository
import com.podcasty.app.domain.AlbumSortOption
import com.podcasty.app.domain.AlbumSummary
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.PodcastLayoutMode
import com.podcasty.app.domain.PodcastRepository
import com.podcasty.app.domain.SortOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryAlbumsUiState(
    val isLoaded: Boolean = false,
    val albums: List<AlbumSummary> = emptyList(),
    val totalAlbumCount: Int = 0,
    val sortOption: AlbumSortOption = AlbumSortOption.Title,
    val sortOrder: SortOrder = SortOrder.Ascending,
    val layoutMode: PodcastLayoutMode = PodcastLayoutMode.Grid,
    val filterQuery: String = "",
    val canLoadMore: Boolean = false,
)

data class AlbumDetailsUiState(
    val album: AlbumSummary? = null,
    val tracks: List<Episode> = emptyList(),
    val isLoaded: Boolean = false,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class LibraryAlbumsViewModel(
    private val repository: PodcastRepository,
    private val preferences: PodcastPreferencesRepository,
) : ViewModel() {
    private val sortOption = MutableStateFlow(AlbumSortOption.Title)
    private val sortOrder = MutableStateFlow(SortOrder.Ascending)
    private val filterQuery = MutableStateFlow("")
    private val albumPageLimit = MutableStateFlow(DefaultPageSize)
    private val selectedAlbumId = MutableStateFlow<String?>(null)

    private data class AlbumQueryKey(
        val query: String,
        val sortOption: AlbumSortOption,
        val sortOrder: SortOrder,
        val limit: Int,
    )

    private val queryKeyFlow = combine(
        filterQuery.debounce(120L),
        sortOption,
        sortOrder,
        albumPageLimit,
    ) { q, opt, ord, limit ->
        AlbumQueryKey(q.trim(), opt, ord, limit)
    }.distinctUntilChanged()

    private val albumsFlow = queryKeyFlow.flatMapLatest { key ->
        repository.observeLocalAlbumsSorted(
            query = key.query,
            sortOption = key.sortOption,
            sortOrder = key.sortOrder,
            limit = key.limit,
        )
    }

    val uiState: StateFlow<LibraryAlbumsUiState> = combine(
        albumsFlow,
        repository.observeMusicAlbumCount(),
        preferences.settings,
        combine(sortOption, sortOrder, filterQuery, albumPageLimit) { opt, ord, q, limit ->
            AlbumQueryKey(q, opt, ord, limit)
        },
    ) { albums, totalCount, settings, key ->
        LibraryAlbumsUiState(
            isLoaded = true,
            albums = albums,
            totalAlbumCount = totalCount,
            sortOption = key.sortOption,
            sortOrder = key.sortOrder,
            layoutMode = settings.podcastLayoutMode,
            filterQuery = key.query,
            canLoadMore = albums.size >= key.limit && albums.size < totalCount,
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), LibraryAlbumsUiState())

    val albumDetailsState: StateFlow<AlbumDetailsUiState> = selectedAlbumId
        .flatMapLatest { albumId ->
            if (albumId.isNullOrBlank()) {
                flowOf(AlbumDetailsUiState())
            } else {
                combine(
                    repository.observeMusicAlbumById(albumId),
                    repository.observeSongsForAlbum(albumId),
                ) { album, tracks ->
                    AlbumDetailsUiState(
                        album = album,
                        tracks = tracks,
                        isLoaded = true,
                    )
                }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), AlbumDetailsUiState())

    fun selectAlbum(albumId: String?) {
        selectedAlbumId.value = albumId
    }

    fun setSortOption(option: AlbumSortOption) {
        if (sortOption.value == option) {
            toggleSortOrder()
        } else {
            sortOption.value = option
            sortOrder.value = if (option == AlbumSortOption.TrackCount) SortOrder.Descending else SortOrder.Ascending
        }
    }

    fun toggleSortOrder() {
        sortOrder.value = if (sortOrder.value == SortOrder.Ascending) SortOrder.Descending else SortOrder.Ascending
    }

    fun setLayoutMode(mode: PodcastLayoutMode) {
        viewModelScope.launch { preferences.setPodcastLayoutMode(mode) }
    }

    fun setFilterQuery(query: String) {
        filterQuery.value = query
        albumPageLimit.value = DefaultPageSize
    }

    fun loadMoreAlbums() {
        val current = albumPageLimit.value
        if (current < MaxPageSize) {
            albumPageLimit.value = (current + PageIncrement).coerceAtMost(MaxPageSize)
        }
    }

    companion object {
        private const val DefaultPageSize = 120
        private const val PageIncrement = 120
        private const val MaxPageSize = 2_000
    }

    class Factory(
        private val repository: PodcastRepository,
        private val preferences: PodcastPreferencesRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LibraryAlbumsViewModel(repository, preferences) as T
    }
}
