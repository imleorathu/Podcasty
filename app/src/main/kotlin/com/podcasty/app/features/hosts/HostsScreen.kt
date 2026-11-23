/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.hosts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.Host
import com.podcasty.app.domain.HostSortOption
import com.podcasty.app.domain.Podcast
import com.podcasty.app.domain.PodcastRepository
import com.podcasty.app.domain.SortOrder
import com.podcasty.app.ui.components.ArtworkHeroBackdrop
import com.podcasty.app.ui.components.DetailHero
import com.podcasty.app.ui.components.DetailHeroArtworkShape
import com.podcasty.app.ui.components.EpisodeContextBottomSheetRequest
import com.podcasty.app.ui.components.GroupContextMenu
import com.podcasty.app.ui.components.HeroCard
import com.podcasty.app.ui.components.HostRow
import com.podcasty.app.ui.components.InsetListDivider
import com.podcasty.app.ui.components.LibraryTextFilter
import com.podcasty.app.ui.components.LibraryVirtualList
import com.podcasty.app.ui.components.MaterialSymbols
import com.podcasty.app.ui.components.PodcastRow
import com.podcasty.app.ui.theme.LocalPodcastyColors

data class HostsUiState(
    val isLoaded: Boolean = false,
    val hosts: List<Host> = emptyList(),
    val sortOption: HostSortOption = HostSortOption.Name,
    val sortOrder: SortOrder = SortOrder.Ascending,
    val filterQuery: String = "",
)

class HostsViewModel(private val repository: PodcastRepository) : ViewModel() {
    private val sortOption = MutableStateFlow(HostSortOption.Name)
    private val sortOrder = MutableStateFlow(SortOrder.Ascending)
    private val filterQuery = MutableStateFlow("")

    val uiState: StateFlow<HostsUiState> = combine(
        repository.hosts.distinctUntilChanged(),
        sortOption,
        sortOrder,
        filterQuery,
    ) { hosts, option, order, query ->
        val trimmed = query.trim()
        val filtered = if (trimmed.isEmpty()) hosts else hosts.filter {
            it.name.contains(trimmed, ignoreCase = true) || it.bio.contains(trimmed, ignoreCase = true)
        }
        val comparator: Comparator<Host> = when (option) {
            HostSortOption.Name -> UnicodeSortUtil.precomputedKeyComparator(Host::name)
            HostSortOption.DateAdded -> compareBy { it.createdAt }
        }
        val sorted = if (order == SortOrder.Ascending) filtered.sortedWith(comparator)
        else filtered.sortedWith(comparator.reversed())
        HostsUiState(true, sorted, option, order, query)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), HostsUiState())

    fun setSortOption(option: HostSortOption) {
        if (sortOption.value == option) toggleSortOrder()
        else {
            sortOption.value = option
            sortOrder.value = if (option == HostSortOption.DateAdded) SortOrder.Descending else SortOrder.Ascending
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
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HostsViewModel(repository) as T
    }
}

@Composable
fun HostsScreen(
    uiState: HostsUiState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onFilterQueryChange: (String) -> Unit,
    onHostClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listPadding = PaddingValues(
        top = contentPadding.calculateTopPadding(),
        bottom = contentPadding.calculateBottomPadding(),
    )
    LibraryVirtualList(
        items = uiState.hosts,
        isLoaded = uiState.isLoaded,
        key = Host::id,
        contentType = "host_row",
        listState = listState,
        contentPadding = listPadding,
        modifier = modifier,
        filterKey = "hosts-filter",
        filterActive = uiState.filterQuery.isNotBlank(),
        filterContent = { showDecorations ->
            LibraryTextFilter(
                value = uiState.filterQuery,
                onValueChange = onFilterQueryChange,
                showPlaceholderAndLeadingSymbol = showDecorations,
            )
        },
        alphabeticalIndexKey = if (uiState.sortOption == HostSortOption.Name) Host::name else null,
        emptyContent = {
            Box(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                HeroCard(
                    symbol = MaterialSymbols.People,
                    title = stringResource(
                        if (uiState.filterQuery.isNotBlank()) R.string.hosts_no_match_title else R.string.hosts_empty_title,
                    ),
                    description = stringResource(
                        if (uiState.filterQuery.isNotBlank()) R.string.filter_no_match_description else R.string.hosts_empty_description,
                    ),
                )
            }
        },
    ) { host ->
        HostRow(
            name = host.name,
            artworkPath = host.artworkAsset,
            onClick = { onHostClick(host.id) },
        )
    }
}

@Composable
fun HostDetailsScreen(
    host: Host?,
    podcasts: List<Podcast>,
    episodes: List<Episode>,
    listState: LazyListState,
    contentPadding: PaddingValues,
    hazeState: HazeState?,
    onHeroColorChanged: (Color) -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlayNext: (List<String>) -> Unit,
    onAddToQueue: (List<String>) -> Unit,
    onEpisodeContextBottomSheet: (EpisodeContextBottomSheetRequest) -> Unit,
    onPodcastClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (host == null) {
        HeroCard(
            symbol = MaterialSymbols.Person,
            title = stringResource(R.string.host_details_empty_title),
            description = stringResource(R.string.host_details_empty_description),
            modifier = modifier.fillMaxSize().padding(contentPadding),
        )
        return
    }
    val colors = LocalPodcastyColors.current
    var menuExpanded by remember(host.id) { mutableStateOf(false) }
    val episodeIds = remember(episodes) { episodes.map { it.id } }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
    ) {
        item(key = "host-hero") {
            ArtworkHeroBackdrop(host.artworkAsset, Modifier.fillMaxWidth(), onHeroColorChanged) {
                DetailHero(
                    title = host.name,
                    subtitle = stringResource(
                        R.string.host_details_summary,
                        pluralStringResource(R.plurals.host_details_podcast_count, podcasts.size, podcasts.size),
                        pluralStringResource(R.plurals.podcast_details_episode_count, episodes.size, episodes.size),
                    ),
                    playLabel = stringResource(R.string.player_play),
                    shuffleLabel = stringResource(R.string.player_shuffle),
                    moreLabel = stringResource(R.string.host_row_more_options),
                    modifier = Modifier.fillMaxWidth().padding(
                        start = 24.dp,
                        top = contentPadding.calculateTopPadding(),
                        end = 24.dp,
                        bottom = 20.dp,
                    ),
                    artworkPath = host.artworkAsset,
                    artworkShape = DetailHeroArtworkShape.Circle,
                    artworkSize = 144.dp,
                    fallbackSymbol = MaterialSymbols.Person,
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
        }
        if (host.bio.isNotBlank()) {
            item(key = "host-bio") {
                Text(
                    text = host.bio,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                )
            }
        }
        itemsIndexed(podcasts, key = { _, podcast -> podcast.id }) { index, podcast ->
            if (index == 0) InsetListDivider()
            PodcastRow(
                title = podcast.title,
                host = podcast.categoryName,
                artworkPath = podcast.artworkAsset,
                onClick = { onPodcastClick(podcast.id) },
            )
            InsetListDivider()
        }
    }
}
