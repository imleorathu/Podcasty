/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.podcasty.app.data.PodcastPreferencesRepository
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.MediaContentType
import com.podcasty.app.domain.PodcastRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class HomeUiState(
    val isLoaded: Boolean = false,
    val podcasts: List<Episode> = emptyList(),
    val optionalCategory: String = "",
    val optionalEpisodes: List<Episode> = emptyList(),
    val recentlyPlayed: List<Episode> = emptyList(),
    val recentlyAdded: List<Episode> = emptyList(),
    val popularFavourites: List<Episode> = emptyList(),
    val allEpisodes: List<Episode> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val repository: PodcastRepository,
    private val preferences: PodcastPreferencesRepository,
) : ViewModel() {
    // Indexed SQL queries keep Podcasts and Music strictly separated without scanning 7k+ rows in Kotlin.
    private val baseState = combine(
        repository.observePodcastEpisodes(limit = 20).distinctUntilChanged(),
        repository.observeRecentlyPlayedEpisodes(limit = 16).distinctUntilChanged(),
        repository.observeRecentMusicEpisodes(limit = 16).distinctUntilChanged(),
        repository.observeFavouriteMusicEpisodes(limit = 16).distinctUntilChanged(),
    ) { podcasts, recentlyPlayed, recentMusic, favMusic ->
        // Defense-in-depth guard: Home Podcasts strictly contains PODCAST content, never local music.
        val strictlyPodcasts = podcasts.filter { it.contentType == MediaContentType.Podcast }
        val strictlyMusic = recentMusic.filter { it.contentType == MediaContentType.Music }
        val popularFavourites = favMusic
            .filter { it.contentType == MediaContentType.Music }
            .ifEmpty { strictlyMusic }
        HomeUiState(
            isLoaded = true,
            podcasts = strictlyPodcasts,
            recentlyPlayed = recentlyPlayed,
            recentlyAdded = strictlyMusic,
            popularFavourites = popularFavourites,
            allEpisodes = emptyList(),
        )
    }.flowOn(Dispatchers.Default)

    private val optional = preferences.settings
        .map { it.optionalHomeCategory }
        .distinctUntilChanged()
        .flatMapLatest { category ->
            if (category.isBlank()) flowOf("" to emptyList<Episode>())
            else repository.observeSmartMusicCategory(category).map { category to it }
        }

    val uiState: StateFlow<HomeUiState> = combine(baseState, optional) { base, (category, episodes) ->
        base.copy(optionalCategory = category, optionalEpisodes = episodes)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), HomeUiState())

    class Factory(
        private val repository: PodcastRepository,
        private val preferences: PodcastPreferencesRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(repository, preferences) as T
    }
}
