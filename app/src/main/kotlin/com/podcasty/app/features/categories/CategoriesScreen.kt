/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.categories

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import com.podcasty.app.R
import com.podcasty.app.core.UnicodeSortUtil
import com.podcasty.app.domain.Category
import com.podcasty.app.domain.CategorySortOption
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.Podcast
import com.podcasty.app.domain.PodcastRepository
import com.podcasty.app.domain.SortOrder
import com.podcasty.app.ui.components.CategoryRow
import com.podcasty.app.ui.components.DetailHero
import com.podcasty.app.ui.components.EpisodeContextBottomSheetRequest
import com.podcasty.app.ui.components.GroupContextMenu
import com.podcasty.app.ui.components.HeroCard
import com.podcasty.app.ui.components.InsetListDivider
import com.podcasty.app.ui.components.LibraryTextFilter
import com.podcasty.app.ui.components.LibraryVirtualList
import com.podcasty.app.ui.components.MaterialSymbols
import com.podcasty.app.ui.components.PodcastRow

data class CategoriesUiState(
    val isLoaded: Boolean = false,
    val categories: List<Category> = emptyList(),
    val sortOption: CategorySortOption = CategorySortOption.Name,
    val sortOrder: SortOrder = SortOrder.Ascending,
    val filterQuery: String = "",
)

class CategoriesViewModel(private val repository: PodcastRepository) : ViewModel() {
    private val sortOption = MutableStateFlow(CategorySortOption.Name)
    private val sortOrder = MutableStateFlow(SortOrder.Ascending)
    private val filterQuery = MutableStateFlow("")

    val uiState: StateFlow<CategoriesUiState> = combine(
        repository.categories.distinctUntilChanged(),
        sortOption,
        sortOrder,
        filterQuery,
    ) { categories, option, order, query ->
        val trimmed = query.trim()
        val filtered = if (trimmed.isEmpty()) categories else categories.filter {
            it.name.contains(trimmed, ignoreCase = true)
        }
        val comparator: Comparator<Category> = when (option) {
            CategorySortOption.Name -> UnicodeSortUtil.precomputedKeyComparator(Category::name)
            CategorySortOption.DateAdded -> compareBy { it.createdAt }
        }
        val sorted = if (order == SortOrder.Ascending) filtered.sortedWith(comparator)
        else filtered.sortedWith(comparator.reversed())
        CategoriesUiState(true, sorted, option, order, query)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), CategoriesUiState())

    fun setSortOption(option: CategorySortOption) {
        if (sortOption.value == option) toggleSortOrder()
        else {
            sortOption.value = option
            sortOrder.value = if (option == CategorySortOption.DateAdded) SortOrder.Descending else SortOrder.Ascending
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
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CategoriesViewModel(repository) as T
    }
}

@Composable
fun CategoriesScreen(
    uiState: CategoriesUiState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onFilterQueryChange: (String) -> Unit,
    onCategoryClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listPadding = PaddingValues(
        top = contentPadding.calculateTopPadding(),
        bottom = contentPadding.calculateBottomPadding(),
    )
    LibraryVirtualList(
        items = uiState.categories,
        isLoaded = uiState.isLoaded,
        key = Category::id,
        contentType = "category_row",
        listState = listState,
        contentPadding = listPadding,
        modifier = modifier,
        filterKey = "categories-filter",
        filterActive = uiState.filterQuery.isNotBlank(),
        filterContent = { showDecorations ->
            LibraryTextFilter(
                value = uiState.filterQuery,
                onValueChange = onFilterQueryChange,
                showPlaceholderAndLeadingSymbol = showDecorations,
            )
        },
        alphabeticalIndexKey = if (uiState.sortOption == CategorySortOption.Name) Category::name else null,
        emptyContent = {
            Box(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                HeroCard(
                    symbol = MaterialSymbols.Label,
                    title = stringResource(
                        if (uiState.filterQuery.isNotBlank()) R.string.categories_no_match_title else R.string.categories_empty_title,
                    ),
                    description = stringResource(
                        if (uiState.filterQuery.isNotBlank()) R.string.filter_no_match_description else R.string.categories_empty_description,
                    ),
                )
            }
        },
    ) { category ->
        CategoryRow(
            name = category.name,
            onClick = { onCategoryClick(category.id) },
        )
    }
}

@Composable
fun CategoryDetailsScreen(
    category: Category?,
    podcasts: List<Podcast>,
    episodes: List<Episode>,
    listState: LazyListState,
    contentPadding: PaddingValues,
    hazeState: HazeState?,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlayNext: (List<String>) -> Unit,
    onAddToQueue: (List<String>) -> Unit,
    onEpisodeContextBottomSheet: (EpisodeContextBottomSheetRequest) -> Unit,
    onPodcastClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (category == null) {
        HeroCard(
            symbol = MaterialSymbols.Label,
            title = stringResource(R.string.category_details_empty_title),
            description = stringResource(R.string.category_details_empty_description),
            modifier = modifier.fillMaxSize().padding(contentPadding),
        )
        return
    }
    var menuExpanded by remember(category.id) { mutableStateOf(false) }
    val episodeIds = remember(episodes) { episodes.map { it.id } }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
    ) {
        item(key = "category-hero") {
            DetailHero(
                title = category.name,
                subtitle = stringResource(
                    R.string.category_details_summary,
                    pluralStringResource(R.plurals.host_details_podcast_count, podcasts.size, podcasts.size),
                    pluralStringResource(R.plurals.podcast_details_episode_count, episodes.size, episodes.size),
                ),
                playLabel = stringResource(R.string.player_play),
                shuffleLabel = stringResource(R.string.player_shuffle),
                moreLabel = stringResource(R.string.category_row_more_options),
                modifier = Modifier.fillMaxWidth().padding(
                    start = 24.dp,
                    top = contentPadding.calculateTopPadding(),
                    end = 24.dp,
                    bottom = 20.dp,
                ),
                showArtwork = false,
                onPlayClick = onPlay,
                onShuffleClick = onShuffle,
                onMoreClick = { menuExpanded = true },
                moreAction = { moreButton ->
                    GroupContextMenu(
                        episodeIds = episodeIds,
                        expanded = menuExpanded,
                        onDismiss = { menuExpanded = false },
                        hazeState = hazeState,
                        onPlayNext = onPlayNext,
                        onAddToQueue = onAddToQueue,
                        onAddToCollection = { ids ->
                            onEpisodeContextBottomSheet(EpisodeContextBottomSheetRequest.CollectionPicker(ids, addOnly = true))
                        },
                        anchor = moreButton,
                    )
                },
            )
        }
        itemsIndexed(podcasts, key = { _, podcast -> podcast.id }) { index, podcast ->
            if (index == 0) InsetListDivider()
            PodcastRow(
                title = podcast.title,
                host = podcast.hostName,
                artworkPath = podcast.artworkAsset,
                onClick = { onPodcastClick(podcast.id) },
            )
            InsetListDivider()
        }
    }
}
