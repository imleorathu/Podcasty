/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.podcasts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.podcasty.app.core.UnicodeSortUtil
import com.podcasty.app.data.PodcastPreferencesRepository
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.MediaContentType
import com.podcasty.app.domain.Podcast
import com.podcasty.app.domain.PodcastLayoutMode
import com.podcasty.app.domain.PodcastRepository
import com.podcasty.app.domain.PodcastSortOption
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

data class PodcastsUiState(
    val isLoaded: Boolean = false,
    val podcasts: List<Podcast> = emptyList(),
    val podcastEpisodes: List<Episode> = emptyList(),
    val episodesByPodcast: Map<String, List<Episode>> = emptyMap(),
    val sortOption: PodcastSortOption = PodcastSortOption.DateAdded,
    val sortOrder: SortOrder = SortOrder.Descending,
    val layoutMode: PodcastLayoutMode = PodcastLayoutMode.Grid,
    val filterQuery: String = "",
)

data class PodcastDetailsUiState(
    val podcast: Podcast? = null,
    val episodes: List<Episode> = emptyList(),
    val isLoaded: Boolean = false,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class PodcastsViewModel(
    private val repository: PodcastRepository,
    private val preferences: PodcastPreferencesRepository,
) : ViewModel() {
    private val sortOption = MutableStateFlow(PodcastSortOption.DateAdded)
    private val sortOrder = MutableStateFlow(SortOrder.Descending)
    private val filterQuery = MutableStateFlow("")
    private val selectedPodcastId = MutableStateFlow<String?>(null)

    val uiState: StateFlow<PodcastsUiState> = combine(
        repository.observePodcastShows().distinctUntilChanged(),
        repository.observePodcastEpisodes(limit = 500).distinctUntilChanged(),
        preferences.settings,
        combine(sortOption, sortOrder, filterQuery.debounce(100L)) { o, ord, q -> Triple(o, ord, q) },
    ) { shows, episodes, settings, (option, order, query) ->
        // Strict defense-in-depth separation: NEVER mix local music albums into Podcasts.
        val strictlyPodcastShows = shows.filter { it.contentType == MediaContentType.Podcast }
        val strictlyPodcastEpisodes = episodes.filter { it.contentType == MediaContentType.Podcast }
        val trimmed = query.trim()
        val filteredEpisodes = if (trimmed.isEmpty()) strictlyPodcastEpisodes else strictlyPodcastEpisodes.filter {
            it.title.contains(trimmed, ignoreCase = true) ||
                it.podcastTitle.contains(trimmed, ignoreCase = true) ||
                it.hostName.contains(trimmed, ignoreCase = true)
        }
        val matchingShowIdsFromEpisodes = if (trimmed.isEmpty()) emptySet() else filteredEpisodes.mapTo(HashSet()) { it.podcastId }
        val filteredShows = if (trimmed.isEmpty()) strictlyPodcastShows else strictlyPodcastShows.filter {
            it.id in matchingShowIdsFromEpisodes ||
                it.title.contains(trimmed, ignoreCase = true) ||
                it.hostName.contains(trimmed, ignoreCase = true) ||
                it.categoryName.contains(trimmed, ignoreCase = true)
        }
        val comparator: Comparator<Podcast> = when (option) {
            PodcastSortOption.Title -> UnicodeSortUtil.precomputedKeyComparator(Podcast::title)
            PodcastSortOption.Host -> UnicodeSortUtil.precomputedKeyComparator<Podcast>(Podcast::hostName)
                .thenComparator { a, b -> UnicodeSortUtil.compareInternational(a.title, b.title) }
            PodcastSortOption.DateAdded -> compareBy { it.importedAt.takeIf { ts -> ts > 0L } ?: it.addedAt }
        }
        val sortedShows = if (order == SortOrder.Ascending) filteredShows.sortedWith(comparator)
        else filteredShows.sortedWith(comparator.reversed())
        PodcastsUiState(
            isLoaded = true,
            podcasts = sortedShows,
            podcastEpisodes = filteredEpisodes.sortedByDescending { it.importedAt.takeIf { ts -> ts > 0L } ?: it.publishedAt },
            episodesByPodcast = strictlyPodcastEpisodes.groupBy { it.podcastId }.mapValues { (_, eps) ->
                eps.sortedByDescending { it.importedAt.takeIf { ts -> ts > 0L } ?: it.publishedAt }
            },
            sortOption = option,
            sortOrder = order,
            layoutMode = settings.podcastLayoutMode,
            filterQuery = query,
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), PodcastsUiState())

    val podcastDetailsState: StateFlow<PodcastDetailsUiState> = selectedPodcastId
        .flatMapLatest { podcastId ->
            if (podcastId.isNullOrBlank()) {
                flowOf(PodcastDetailsUiState())
            } else {
                combine(
                    repository.observePodcastById(podcastId),
                    repository.observeEpisodesForPodcast(podcastId),
                ) { podcast, episodes ->
                    PodcastDetailsUiState(
                        podcast = podcast,
                        episodes = episodes,
                        isLoaded = true,
                    )
                }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), PodcastDetailsUiState())

    fun selectPodcast(podcastId: String?) {
        selectedPodcastId.value = podcastId
    }

    fun setSortOption(option: PodcastSortOption) {
        if (sortOption.value == option) {
            toggleSortOrder()
        } else {
            sortOption.value = option
            sortOrder.value = if (option == PodcastSortOption.DateAdded) SortOrder.Descending else SortOrder.Ascending
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
    }

    class Factory(
        private val repository: PodcastRepository,
        private val preferences: PodcastPreferencesRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PodcastsViewModel(repository, preferences) as T
    }
}
