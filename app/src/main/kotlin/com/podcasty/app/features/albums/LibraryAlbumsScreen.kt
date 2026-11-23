/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.albums

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.podcasty.app.R
import com.podcasty.app.core.formatCompactDuration
import com.podcasty.app.domain.AlbumSortOption
import com.podcasty.app.domain.AlbumSummary
import com.podcasty.app.domain.PodcastLayoutMode
import com.podcasty.app.ui.components.DiscGridItem
import com.podcasty.app.ui.components.HeroCard
import com.podcasty.app.ui.components.LibraryTextFilter
import com.podcasty.app.ui.components.LibraryVirtualList
import com.podcasty.app.ui.components.MaterialSymbols
import com.podcasty.app.ui.components.PodcastRow
import com.podcasty.app.ui.components.discGridColumnCount
import com.podcasty.app.ui.components.discGridItems

@Composable
fun LibraryAlbumsScreen(
    uiState: LibraryAlbumsUiState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onFilterQueryChange: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val alphaKey: ((AlbumSummary) -> String)? = when (uiState.sortOption) {
        AlbumSortOption.Title -> AlbumSummary::albumTitle
        AlbumSortOption.Artist -> AlbumSummary::artistName
        AlbumSortOption.TrackCount -> null
    }
    val listPadding = PaddingValues(
        top = contentPadding.calculateTopPadding(),
        bottom = contentPadding.calculateBottomPadding(),
    )

    // Automatically trigger next SQL page when approaching bottom on large music libraries
    val shouldLoadMore by remember(uiState.canLoadMore, uiState.albums.size) {
        derivedStateOf {
            if (!uiState.canLoadMore) return@derivedStateOf false
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisible >= totalItems - 6
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }

    BoxWithConstraints(modifier = modifier) {
        val columns = discGridColumnCount(maxWidth - 48.dp)
        val isGrid = uiState.layoutMode == PodcastLayoutMode.Grid

        LibraryVirtualList(
            items = uiState.albums,
            isLoaded = uiState.isLoaded,
            key = AlbumSummary::albumId,
            contentType = if (isGrid) "album_grid_row" else "album_row",
            listState = listState,
            contentPadding = listPadding,
            filterKey = "albums-filter",
            filterActive = uiState.filterQuery.isNotBlank(),
            filterContent = { showDecorations ->
                LibraryTextFilter(
                    value = uiState.filterQuery,
                    onValueChange = onFilterQueryChange,
                    showPlaceholderAndLeadingSymbol = showDecorations,
                )
            },
            alphabeticalIndexKey = alphaKey,
            alphabeticalIndexItemsPerLazyItem = if (isGrid) columns else 1,
            emptyContent = {
                Box(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                    HeroCard(
                        symbol = MaterialSymbols.Album,
                        title = stringResource(
                            if (uiState.filterQuery.isNotBlank()) R.string.albums_no_match_title
                            else R.string.albums_empty_title,
                        ),
                        description = stringResource(
                            if (uiState.filterQuery.isNotBlank()) R.string.filter_no_match_description
                            else R.string.albums_empty_description,
                        ),
                    )
                }
            },
            customItemsContent = if (isGrid) {
                {
                    discGridItems(
                        items = uiState.albums.map { album ->
                            DiscGridItem(
                                id = album.albumId,
                                title = album.albumTitle,
                                subtitle = "${album.artistName} · ${album.trackCount} ${if (album.trackCount == 1) "track" else "tracks"}",
                                artworkPath = album.artworkAsset,
                                fallbackSymbol = MaterialSymbols.Album,
                            )
                        },
                        columns = columns,
                        horizontalContentPadding = 24.dp,
                        onClick = onAlbumClick,
                    )
                }
            } else {
                null
            },
        ) { album ->
            val trackLabel = pluralStringResource(R.plurals.album_details_track_count, album.trackCount, album.trackCount)
            val durationText = if (album.totalDurationMs > 0L) " · ${formatCompactDuration(album.totalDurationMs)}" else ""
            PodcastRow(
                title = album.albumTitle,
                host = "${album.artistName} · $trackLabel$durationText",
                artworkPath = album.artworkAsset,
                onClick = { onAlbumClick(album.albumId) },
            )
        }
    }
}
