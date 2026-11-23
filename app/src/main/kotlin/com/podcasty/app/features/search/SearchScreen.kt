/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.search

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import com.podcasty.app.domain.SongSortOption
import com.podcasty.app.domain.SortOrder
import com.podcasty.app.domain.MediaSourceType
import com.podcasty.app.ui.components.Selection
import com.podcasty.app.ui.components.SelectionOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.podcasty.app.R
import com.podcasty.app.core.formatCompactDuration
import com.podcasty.app.data.PodcastPreferencesRepository
import com.podcasty.app.domain.Category
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.Host
import com.podcasty.app.domain.PlaybackQueueSnapshot
import com.podcasty.app.domain.Podcast
import com.podcasty.app.domain.PodcastCollection
import com.podcasty.app.domain.PodcastRepository
import com.podcasty.app.ui.components.DiscCard
import com.podcasty.app.ui.components.EpisodeContextBottomSheetRequest
import com.podcasty.app.ui.components.EpisodeContextMenu
import com.podcasty.app.ui.components.EpisodeRow
import com.podcasty.app.ui.components.HeroCard
import com.podcasty.app.ui.components.LibraryTextFilter
import com.podcasty.app.ui.components.MaterialSymbol
import com.podcasty.app.ui.components.MaterialSymbols
import com.podcasty.app.ui.components.PodcastContextMenu
import com.podcasty.app.ui.components.PodcastyCard
import com.podcasty.app.ui.theme.LocalPodcastyColors

data class MusicSearchOptions(
    val duration: Int = 0,
    val format: String = "",
    val sort: SongSortOption = SongSortOption.Title,
)

data class SearchUiState(
    val isLoaded: Boolean = false,
    val musicOptions: MusicSearchOptions = MusicSearchOptions(),
    val query: String = "",
    val recentSearches: List<String> = emptyList(),
    val episodes: List<Episode> = emptyList(),
    val podcasts: List<Podcast> = emptyList(),
    val hosts: List<Host> = emptyList(),
    val categories: List<Category> = emptyList(),
    val collections: List<PodcastCollection> = emptyList(),
    val allEpisodes: List<Episode> = emptyList(),
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val repository: PodcastRepository,
    private val preferences: PodcastPreferencesRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val musicOptions = MutableStateFlow(MusicSearchOptions())
    private val localResults = combine(query.debounce(150L), musicOptions) { q, options -> q to options }
        .distinctUntilChanged().flatMapLatest { (q, options) ->
            if (q.isBlank()) flowOf(emptyList<Episode>())
            else repository.observeLocalSongsPaged(
                query = q, sortOption = options.sort, sortOrder = SortOrder.Ascending, limit = 120,
                minDurationMs = when (options.duration) { 2 -> 300_000L; 3 -> 1_200_000L; else -> 0L },
                maxDurationMs = when (options.duration) { 1 -> 299_999L; 2 -> 1_199_999L; else -> Long.MAX_VALUE },
                format = options.format,
            )
        }

    fun setMusicOptions(options: MusicSearchOptions) { musicOptions.value = options }


    @OptIn(FlowPreview::class)
    val uiState: StateFlow<SearchUiState> = combine(
        combine(
            combine(repository.episodes.distinctUntilChanged(), localResults) { episodes, local ->
                local + episodes.filter { it.sourceType != MediaSourceType.LocalMusic }
            },
            repository.podcasts.distinctUntilChanged(),
            repository.hosts.distinctUntilChanged(),
        ) { e, p, h -> Triple(e, p, h) },
        combine(repository.categories.distinctUntilChanged(), repository.collections.distinctUntilChanged()) { c, col -> c to col },
        combine(query.debounce(150L), preferences.settings, musicOptions) { q, s, options -> Triple(q, s.recentSearches, options) },
    ) { (allEpisodes, allPodcasts, allHosts), (allCategories, allCollections), (q, recent, options) ->
        val trimmed = q.trim()
        if (trimmed.isEmpty()) {
            SearchUiState(
                isLoaded = true,
                musicOptions = options,
                query = q,
                recentSearches = recent,
                allEpisodes = emptyList(),
            )
        } else {
            SearchUiState(
                isLoaded = true,
                musicOptions = options,
                query = q,
                recentSearches = recent,
                episodes = allEpisodes.filter {
                    it.sourceType == MediaSourceType.LocalMusic || it.title.contains(trimmed, ignoreCase = true) ||
                        it.podcastTitle.contains(trimmed, ignoreCase = true) ||
                        it.hostName.contains(trimmed, ignoreCase = true) ||
                        it.categoryName.contains(trimmed, ignoreCase = true) ||
                        it.summary.contains(trimmed, ignoreCase = true)
                }.take(120),
                podcasts = allPodcasts.filter {
                    it.title.contains(trimmed, ignoreCase = true) ||
                        it.hostName.contains(trimmed, ignoreCase = true) ||
                        it.categoryName.contains(trimmed, ignoreCase = true) ||
                        it.description.contains(trimmed, ignoreCase = true)
                }.take(80),
                hosts = allHosts.filter {
                    it.name.contains(trimmed, ignoreCase = true) || it.bio.contains(trimmed, ignoreCase = true)
                }.take(80),
                categories = allCategories.filter { it.name.contains(trimmed, ignoreCase = true) }.take(50),
                collections = allCollections.filter { it.name.contains(trimmed, ignoreCase = true) }.take(50),
                allEpisodes = allEpisodes,
            )
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), SearchUiState())

    fun setQuery(value: String) {
        query.value = value
    }

    fun submitQuery(value: String = query.value) {
        query.value = value
        if (value.trim().length >= 2) {
            viewModelScope.launch { preferences.addRecentSearch(value) }
        }
    }

    fun removeRecentSearch(term: String) {
        viewModelScope.launch { preferences.removeRecentSearch(term) }
    }

    fun clearRecentSearches() {
        viewModelScope.launch { preferences.clearRecentSearches() }
    }

    fun clear() {
        query.value = ""
    }

    class Factory(
        private val repository: PodcastRepository,
        private val preferences: PodcastPreferencesRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SearchViewModel(repository, preferences) as T
    }
}

@Composable
fun SearchScreen(
    uiState: SearchUiState,
    playbackQueue: PlaybackQueueSnapshot,
    hazeState: HazeState?,
    contentPadding: PaddingValues,
    onQueryChange: (String) -> Unit,
    onSubmitQuery: (String) -> Unit,
    onRemoveRecentSearch: (String) -> Unit,
    onClearRecentSearches: () -> Unit,
    onMusicOptionsChanged: (MusicSearchOptions) -> Unit = {},
    onEpisodeClick: (List<Episode>, String) -> Unit,
    onPodcastClick: (String) -> Unit,
    onHostClick: (String) -> Unit,
    onCategoryClick: (String) -> Unit,
    onCollectionClick: (String) -> Unit,
    onPodcastPlay: (List<String>, Boolean) -> Unit,
    onPodcastToggleSubscribe: (String, Boolean) -> Unit,
    onEpisodePlayNext: (Episode) -> Unit,
    onEpisodeAddToQueue: (Episode) -> Unit,
    onEpisodeSaveToggle: (Episode, Boolean) -> Unit,
    onEpisodeDownloadToggle: (Episode, Boolean) -> Unit,
    onEpisodeGoToPodcast: (Episode) -> Unit,
    onEpisodeGoToHost: (Episode) -> Unit,
    onEpisodeContextBottomSheet: (EpisodeContextBottomSheetRequest) -> Unit,
    onPodcastPlayNext: (List<String>) -> Unit,
    onPodcastAddToQueue: (List<String>) -> Unit,
    onPodcastSaveAll: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPodcastyColors.current
    var contextEpisode by remember { mutableStateOf<Episode?>(null) }
    var contextPodcastId by remember { mutableStateOf<String?>(null) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val rowDividerWidth = maxWidth * 0.8f
        Column(Modifier.fillMaxSize()) {
            LibraryTextFilter(
                value = uiState.query,
                onValueChange = onQueryChange,
                placeholder = stringResource(R.string.search_placeholder),
                onSubmit = { onSubmitQuery(uiState.query) },
                modifier = Modifier.padding(top = contentPadding.calculateTopPadding()),
            )
            if (uiState.query.isNotBlank()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Selection(
                        options = listOf(SelectionOption(0, label = "Any duration"), SelectionOption(1, label = "Under 5 min"), SelectionOption(2, label = "5–20 min"), SelectionOption(3, label = "20+ min")),
                        selectedValue = uiState.musicOptions.duration,
                        onValueSelected = { onMusicOptionsChanged(uiState.musicOptions.copy(duration = it)) }, hazeState = hazeState,
                        modifier = Modifier.weight(1f),
                    )
                    Selection(
                        options = listOf("", "mp3", "flac", "wav", "m4a", "ogg", "opus", "aiff").map { SelectionOption(it, label = if (it.isEmpty()) "Any format" else it.uppercase()) },
                        selectedValue = uiState.musicOptions.format,
                        onValueSelected = { onMusicOptionsChanged(uiState.musicOptions.copy(format = it)) }, hazeState = hazeState,
                        modifier = Modifier.weight(1f),
                    )
                    Selection(
                        options = listOf(SongSortOption.Title, SongSortOption.Artist, SongSortOption.Album, SongSortOption.Duration).map { SelectionOption(it, label = it.name) },
                        selectedValue = uiState.musicOptions.sort,
                        onValueSelected = { onMusicOptionsChanged(uiState.musicOptions.copy(sort = it)) }, hazeState = hazeState,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            LazyColumn(contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding())) {
                if (uiState.query.isBlank()) {
                    if (uiState.recentSearches.isNotEmpty()) {
                        item(key = "recent-searches") {
                            Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                                Row(
                                    Modifier.fillMaxWidth().padding(bottom = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stringResource(R.string.search_recent_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = colors.textMuted,
                                    )
                                    Text(
                                        text = stringResource(R.string.search_recent_clear),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = colors.primary,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable(onClick = onClearRecentSearches)
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                                PodcastyCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                                    uiState.recentSearches.forEachIndexed { idx, term ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 48.dp)
                                                .clickable { onSubmitQuery(term) }
                                                .padding(start = 16.dp, end = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            MaterialSymbol(MaterialSymbols.History, null, size = 18.dp, tint = colors.textMuted)
                                            Text(
                                                text = term,
                                                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = colors.textMain,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            IconButton(onClick = { onRemoveRecentSearch(term) }, modifier = Modifier.size(40.dp)) {
                                                MaterialSymbol(MaterialSymbols.Close, null, size = 16.dp, tint = colors.textMuted)
                                            }
                                        }
                                        if (idx < uiState.recentSearches.lastIndex) {
                                            HorizontalDivider(
                                                color = colors.borderGlass,
                                                modifier = Modifier.padding(horizontal = 16.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        item(key = "search-empty") {
                            Box(Modifier.padding(24.dp)) {
                                HeroCard(
                                    symbol = MaterialSymbols.Search,
                                    title = stringResource(R.string.search_empty_title),
                                    description = stringResource(R.string.search_empty_description),
                                )
                            }
                        }
                    }
                } else if (
                    uiState.isLoaded &&
                    uiState.episodes.isEmpty() &&
                    uiState.podcasts.isEmpty() &&
                    uiState.hosts.isEmpty() &&
                    uiState.categories.isEmpty() &&
                    uiState.collections.isEmpty()
                ) {
                    item(key = "search-no-results") {
                        Box(Modifier.padding(24.dp)) {
                            HeroCard(
                                symbol = MaterialSymbols.Search,
                                title = stringResource(R.string.search_no_results_title),
                                description = stringResource(R.string.search_no_results_description),
                            )
                        }
                    }
                } else {
                    searchSection(R.string.library_episodes, uiState.episodes, Episode::id, rows = 3, topPadding = 20.dp) { ep ->
                        EpisodeContextMenu(
                            episode = ep,
                            expanded = contextEpisode?.id == ep.id,
                            onDismiss = { contextEpisode = null },
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
                            Column {
                                EpisodeRow(
                                    title = ep.title,
                                    subtitle = "${ep.podcastTitle} · ${formatCompactDuration(ep.durationMs)}",
                                    artworkPath = ep.artworkAsset,
                                    contentPadding = PaddingValues(vertical = 6.dp),
                                    onClick = {
                                        onSubmitQuery(uiState.query)
                                        onEpisodeClick(uiState.episodes, ep.id)
                                    },
                                    onMoreClick = { contextEpisode = ep },
                                    onLongClick = { contextEpisode = ep },
                                )
                                val idx = uiState.episodes.indexOfFirst { it.id == ep.id }
                                val actualRows = minOf(3, uiState.episodes.size).coerceAtLeast(1)
                                if (actualRows > 1 && idx >= 0 && idx < uiState.episodes.lastIndex && idx % actualRows != actualRows - 1) {
                                    HorizontalDivider(
                                        color = colors.borderGlass,
                                        modifier = Modifier.width(rowDividerWidth).align(Alignment.CenterHorizontally),
                                    )
                                }
                            }
                        }
                    }
                    searchSection(R.string.library_podcasts, uiState.podcasts, Podcast::id, topPadding = 28.dp) { podcast ->
                        val eps = uiState.allEpisodes.filter { it.podcastId == podcast.id }
                        PodcastContextMenu(
                            episodes = eps,
                            isSubscribed = podcast.isSubscribed,
                            expanded = contextPodcastId == podcast.id,
                            onDismiss = { contextPodcastId = null },
                            hazeState = hazeState,
                            onPlay = { onPodcastPlay(eps.map { it.id }, false) },
                            onShuffle = { onPodcastPlay(eps.map { it.id }, true) },
                            onToggleSubscribe = { onPodcastToggleSubscribe(podcast.id, !podcast.isSubscribed) },
                            onPlayNext = onPodcastPlayNext,
                            onAddToQueue = onPodcastAddToQueue,
                            onSaveAll = onPodcastSaveAll,
                            onAddToCollection = { ids ->
                                onEpisodeContextBottomSheet(EpisodeContextBottomSheetRequest.CollectionPicker(ids, addOnly = true))
                            },
                        ) {
                            DiscCard(
                                title = podcast.title,
                                subtitle = podcast.hostName,
                                artworkPath = podcast.artworkAsset,
                                onClick = {
                                    onSubmitQuery(uiState.query)
                                    onPodcastClick(podcast.id)
                                },
                                onLongClick = { contextPodcastId = podcast.id },
                            )
                        }
                    }
                    searchSection(R.string.library_hosts, uiState.hosts, Host::id, topPadding = 16.dp) { host ->
                        DiscCard(
                            title = host.name,
                            subtitle = "",
                            artworkPath = host.artworkAsset,
                            fallbackSymbol = MaterialSymbols.Person,
                            artworkShape = CircleShape,
                            onClick = {
                                onSubmitQuery(uiState.query)
                                onHostClick(host.id)
                            },
                        )
                    }
                    searchSection(R.string.library_categories, uiState.categories, Category::id, topPadding = 16.dp) { category ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(colors.card)
                                .clickable {
                                    onSubmitQuery(uiState.query)
                                    onCategoryClick(category.id)
                                }
                                .padding(horizontal = 16.dp, vertical = 18.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                MaterialSymbol(MaterialSymbols.Label, null, size = 18.dp, tint = colors.primary)
                                Text(
                                    text = category.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = colors.textMain,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    searchSection(R.string.library_collections, uiState.collections, PodcastCollection::id, topPadding = 16.dp) { col ->
                        DiscCard(
                            title = col.name,
                            subtitle = "",
                            artworkPath = uiState.allEpisodes.firstOrNull { it.id in col.episodeIds }?.artworkAsset,
                            fallbackSymbol = MaterialSymbols.QueueMusic,
                            onClick = {
                                onSubmitQuery(uiState.query)
                                onCollectionClick(col.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun <T> LazyListScope.searchSection(
    @StringRes title: Int,
    values: List<T>,
    key: (T) -> String,
    rows: Int = 1,
    topPadding: Dp = 16.dp,
    row: @Composable (T) -> Unit,
) {
    if (values.isEmpty()) return
    item("section_$title") {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = LocalPodcastyColors.current.textMuted,
            modifier = Modifier.padding(start = 24.dp, top = topPadding, bottom = 12.dp),
        )
    }
    item("section_content_$title") {
        if (rows == 3) {
            LazyHorizontalGrid(
                rows = GridCells.Fixed(minOf(3, values.size)),
                modifier = Modifier.fillMaxWidth().height((minOf(3, values.size) * 61).dp),
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                gridItems(values, key = { "search_${title}_${key(it)}" }) { item ->
                    Box(Modifier.widthIn(max = 320.dp)) { row(item) }
                }
            }
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowItems(values, key = { "search_${title}_${key(it)}" }) { item ->
                    Box(Modifier.width(156.dp)) { row(item) }
                }
            }
        }
    }
}
