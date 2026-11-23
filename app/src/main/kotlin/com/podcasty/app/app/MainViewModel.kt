/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.podcasty.app.data.PodcastPreferencesRepository
import com.podcasty.app.navigation.AppDestination
import com.podcasty.app.navigation.AppIntent
import com.podcasty.app.navigation.AppStackPage
import com.podcasty.app.navigation.AppUiState
import com.podcasty.app.navigation.destination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(
    private val preferences: PodcastPreferencesRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.settings.collect { settings ->
                _uiState.update { state ->
                    state.copy(
                        themeMode = settings.themeMode,
                        reduceTransparency = settings.reduceTransparency,
                    )
                }
            }
        }
    }

    fun dispatch(intent: AppIntent) {
        when (intent) {
            is AppIntent.SelectDestination -> _uiState.update { state ->
                if (state.selectedDestination == intent.destination) {
                    val resetStacks = state.destinationStacks.toMutableMap()
                    resetStacks[intent.destination] = listOf(AppStackPage.Root)
                    state.copy(destinationStacks = resetStacks)
                } else {
                    state.copy(selectedDestination = intent.destination)
                }
            }

            is AppIntent.OpenPage -> pushPage(intent.page)

            is AppIntent.OpenPodcastDetails -> _uiState.update { state ->
                pushPageOnState(
                    state.copy(selectedPodcastId = intent.podcastId),
                    AppStackPage.PodcastDetails,
                )
            }

            is AppIntent.OpenAlbumDetails -> _uiState.update { state ->
                pushPageOnState(
                    state.copy(selectedAlbumId = intent.albumId),
                    AppStackPage.AlbumDetails,
                )
            }

            is AppIntent.OpenHostDetails -> _uiState.update { state ->
                pushPageOnState(
                    state.copy(selectedHostId = intent.hostId),
                    AppStackPage.HostDetails,
                )
            }

            is AppIntent.OpenCategoryDetails -> _uiState.update { state ->
                pushPageOnState(
                    state.copy(selectedCategoryId = intent.categoryId),
                    AppStackPage.CategoryDetails,
                )
            }

            is AppIntent.OpenCollectionDetails -> _uiState.update { state ->
                pushPageOnState(
                    state.copy(selectedCollectionId = intent.collectionId),
                    AppStackPage.CollectionDetails,
                )
            }

            AppIntent.NavigateBack -> _uiState.update { state ->
                val dest = state.selectedDestination
                val stack = state.stackFor(dest)
                if (stack.size <= 1) return@update state
                val updatedStacks = state.destinationStacks.toMutableMap()
                updatedStacks[dest] = stack.dropLast(1)
                state.copy(destinationStacks = updatedStacks)
            }

            is AppIntent.SetThemeMode -> {
                viewModelScope.launch { preferences.setThemeMode(intent.mode) }
            }

            is AppIntent.SetReduceTransparency -> {
                viewModelScope.launch { preferences.setReduceTransparency(intent.enabled) }
            }
        }
    }

    private fun pushPage(page: AppStackPage) {
        _uiState.update { state -> pushPageOnState(state, page) }
    }

    private fun pushPageOnState(state: AppUiState, page: AppStackPage): AppUiState {
        val targetDest: AppDestination = if (page == AppStackPage.Root) state.selectedDestination else page.destination
        val currentStack = state.stackFor(targetDest)
        val updatedStack = if (currentStack.lastOrNull() == page) currentStack else currentStack + page
        val updatedStacks = state.destinationStacks.toMutableMap()
        updatedStacks[targetDest] = updatedStack
        return state.copy(
            selectedDestination = targetDest,
            destinationStacks = updatedStacks,
        )
    }

    class Factory(private val preferences: PodcastPreferencesRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(preferences) as T
    }
}
