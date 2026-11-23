/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.episodes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.podcasty.app.core.UnicodeSortUtil
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.EpisodeSortOption
import com.podcasty.app.domain.PodcastRepository
import com.podcasty.app.domain.SortOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

data class EpisodesUiState(
    val isLoaded: Boolean = false,
    val episodes: List<Episode> = emptyList(),
    val sortOption: EpisodeSortOption = EpisodeSortOption.DatePublished,
    val sortOrder: SortOrder = SortOrder.Descending,
    val filterQuery: String = "",
)

class EpisodesViewModel(
    private val repository: PodcastRepository,
) : ViewModel() {
    private val sortOption = MutableStateFlow(EpisodeSortOption.DatePublished)
    private val sortOrder = MutableStateFlow(SortOrder.Descending)
    private val filterQuery = MutableStateFlow("")

    @OptIn(FlowPreview::class)
    val uiState: StateFlow<EpisodesUiState> = combine(
        repository.episodes.distinctUntilChanged(),
        sortOption,
        sortOrder,
        filterQuery.debounce(100L),
    ) { allEpisodes, option, order, query ->
        val trimmed = query.trim()
        val filtered = if (trimmed.isEmpty()) {
            allEpisodes
        } else {
            allEpisodes.filter { ep ->
                ep.title.contains(trimmed, ignoreCase = true) ||
                    ep.podcastTitle.contains(trimmed, ignoreCase = true) ||
                    ep.hostName.contains(trimmed, ignoreCase = true) ||
                    ep.categoryName.contains(trimmed, ignoreCase = true)
            }
        }
        val comparator: Comparator<Episode> = when (option) {
            EpisodeSortOption.Title -> UnicodeSortUtil.precomputedKeyComparator(Episode::title)
            EpisodeSortOption.Podcast -> UnicodeSortUtil.precomputedKeyComparator<Episode>(Episode::podcastTitle)
                .thenBy { it.episodeNumber }
            EpisodeSortOption.Duration -> compareBy { it.durationMs }
            EpisodeSortOption.DatePublished -> compareBy { it.publishedAt }
            EpisodeSortOption.PlayCount -> compareBy<Episode> { it.playCount }.thenBy { it.publishedAt }
        }
        val sorted = if (order == SortOrder.Ascending) filtered.sortedWith(comparator)
        else filtered.sortedWith(comparator.reversed())
        EpisodesUiState(
            isLoaded = true,
            episodes = sorted,
            sortOption = option,
            sortOrder = order,
            filterQuery = query,
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), EpisodesUiState())

    fun setSortOption(option: EpisodeSortOption) {
        if (sortOption.value == option) {
            toggleSortOrder()
        } else {
            sortOption.value = option
            sortOrder.value = when (option) {
                EpisodeSortOption.Title, EpisodeSortOption.Podcast -> SortOrder.Ascending
                EpisodeSortOption.DatePublished, EpisodeSortOption.Duration, EpisodeSortOption.PlayCount -> SortOrder.Descending
            }
        }
    }

    fun toggleSortOrder() {
        sortOrder.value = if (sortOrder.value == SortOrder.Ascending) SortOrder.Descending else SortOrder.Ascending
    }

    fun setFilterQuery(query: String) {
        filterQuery.value = query
    }

    class Factory(private val repository: PodcastRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = EpisodesViewModel(repository) as T
    }
}
