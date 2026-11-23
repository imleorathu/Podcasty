/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.navigation

import androidx.annotation.StringRes
import com.podcasty.app.R
import com.podcasty.app.ui.components.MaterialSymbols
import com.podcasty.app.ui.theme.ThemeMode

enum class AppDestination {
    Home,
    Folder,
    Insight,
    Library,
    Settings,
}

enum class AppStackPage {
    Root,
    LibrarySearch,
    LibraryAddPodcast,
    LibraryPodcasts,
    PodcastDetails,
    LibraryAlbums,
    AlbumDetails,
    LibraryEpisodes,
    LibraryHosts,
    HostDetails,
    LibraryCategories,
    CategoryDetails,
    LibraryCollections,
    CollectionDetails,
    LibrarySavedEpisodes,
    LibraryDownloads,
    LibraryHistory,
    SettingsAppearance,
    SettingsMusicLibrary,
    SettingsSupportedFormats,
    SettingsLyricsCaptions,
    SettingsPlayback,
    SettingsBluetooth,
    SettingsDownloads,
    SettingsAbout,
}

data class StackPageEntry(
    val destination: AppDestination,
    val page: AppStackPage,
    val index: Int,
)

val AppDestination.titleRes: Int
    @StringRes get() = when (this) {
        AppDestination.Home -> R.string.destination_home
        AppDestination.Folder -> R.string.destination_folder
        AppDestination.Insight -> R.string.destination_insight
        AppDestination.Library -> R.string.destination_library
        AppDestination.Settings -> R.string.destination_settings
    }

val AppDestination.symbol: String
    get() = when (this) {
        AppDestination.Home -> MaterialSymbols.Home
        AppDestination.Folder -> MaterialSymbols.Folder
        AppDestination.Insight -> MaterialSymbols.BarChart
        AppDestination.Library -> MaterialSymbols.Library
        AppDestination.Settings -> MaterialSymbols.Settings
    }

val AppStackPage.destination: AppDestination
    get() = when (this) {
        AppStackPage.Root -> AppDestination.Home
        AppStackPage.LibrarySearch,
        AppStackPage.LibraryAddPodcast,
        AppStackPage.LibraryPodcasts,
        AppStackPage.PodcastDetails,
        AppStackPage.LibraryAlbums,
        AppStackPage.AlbumDetails,
        AppStackPage.LibraryEpisodes,
        AppStackPage.LibraryHosts,
        AppStackPage.HostDetails,
        AppStackPage.LibraryCategories,
        AppStackPage.CategoryDetails,
        AppStackPage.LibraryCollections,
        AppStackPage.CollectionDetails,
        AppStackPage.LibrarySavedEpisodes,
        AppStackPage.LibraryDownloads,
        AppStackPage.LibraryHistory,
        -> AppDestination.Library
        AppStackPage.SettingsAppearance,
        AppStackPage.SettingsMusicLibrary,
        AppStackPage.SettingsSupportedFormats,
        AppStackPage.SettingsLyricsCaptions,
        AppStackPage.SettingsPlayback,
        AppStackPage.SettingsBluetooth,
        AppStackPage.SettingsDownloads,
        AppStackPage.SettingsAbout,
        -> AppDestination.Settings
    }

@StringRes
fun AppStackPage.titleRes(destination: AppDestination): Int = when (this) {
    AppStackPage.Root -> destination.titleRes
    AppStackPage.LibrarySearch -> R.string.library_search
    AppStackPage.LibraryAddPodcast -> R.string.library_add_podcast
    AppStackPage.LibraryPodcasts -> R.string.library_podcasts
    AppStackPage.PodcastDetails -> R.string.podcast_details_title
    AppStackPage.LibraryAlbums -> R.string.library_albums
    AppStackPage.AlbumDetails -> R.string.album_details_title
    AppStackPage.LibraryEpisodes -> R.string.library_episodes
    AppStackPage.LibraryHosts -> R.string.library_hosts
    AppStackPage.HostDetails -> R.string.host_details_title
    AppStackPage.LibraryCategories -> R.string.library_categories
    AppStackPage.CategoryDetails -> R.string.category_details_title
    AppStackPage.LibraryCollections -> R.string.library_collections
    AppStackPage.CollectionDetails -> R.string.collection_details_title
    AppStackPage.LibrarySavedEpisodes -> R.string.library_saved_episodes
    AppStackPage.LibraryDownloads -> R.string.library_downloads
    AppStackPage.LibraryHistory -> R.string.library_history
    AppStackPage.SettingsAppearance -> R.string.appearance_title
    AppStackPage.SettingsMusicLibrary -> R.string.settings_music_library
    AppStackPage.SettingsSupportedFormats -> R.string.music_library_supported_formats
    AppStackPage.SettingsLyricsCaptions -> R.string.settings_lyrics_captions
    AppStackPage.SettingsPlayback -> R.string.playback_settings_title
    AppStackPage.SettingsBluetooth -> R.string.settings_bluetooth
    AppStackPage.SettingsDownloads -> R.string.downloads_settings_title
    AppStackPage.SettingsAbout -> R.string.about_title
}

fun AppStackPage.isDetailHeroPage(): Boolean = when (this) {
    AppStackPage.PodcastDetails,
    AppStackPage.AlbumDetails,
    AppStackPage.HostDetails,
    AppStackPage.CategoryDetails,
    AppStackPage.CollectionDetails,
    -> true
    else -> false
}

fun rootDestinationStacks(): Map<AppDestination, List<AppStackPage>> =
    AppDestination.entries.associateWith { listOf(AppStackPage.Root) }

fun List<AppStackPage>.currentStackPage(destination: AppDestination): StackPageEntry =
    StackPageEntry(destination = destination, page = last(), index = lastIndex)

data class AppUiState(
    val selectedDestination: AppDestination = AppDestination.Home,
    val destinationStacks: Map<AppDestination, List<AppStackPage>> = rootDestinationStacks(),
    val selectedPodcastId: String? = null,
    val selectedAlbumId: String? = null,
    val selectedHostId: String? = null,
    val selectedCategoryId: String? = null,
    val selectedCollectionId: String? = null,
    val themeMode: ThemeMode = ThemeMode.System,
    val reduceTransparency: Boolean = false,
) {
    fun stackFor(destination: AppDestination): List<AppStackPage> =
        destinationStacks[destination].orEmpty().ifEmpty { listOf(AppStackPage.Root) }

    val currentPage: AppStackPage
        get() = stackFor(selectedDestination).last()
}

sealed interface AppIntent {
    data class SelectDestination(val destination: AppDestination) : AppIntent
    data class OpenPage(val page: AppStackPage) : AppIntent
    data class OpenPodcastDetails(val podcastId: String) : AppIntent
    data class OpenAlbumDetails(val albumId: String) : AppIntent
    data class OpenHostDetails(val hostId: String) : AppIntent
    data class OpenCategoryDetails(val categoryId: String) : AppIntent
    data class OpenCollectionDetails(val collectionId: String) : AppIntent
    data object NavigateBack : AppIntent
    data class SetThemeMode(val mode: ThemeMode) : AppIntent
    data class SetReduceTransparency(val enabled: Boolean) : AppIntent
}

enum class ContentScrollDirection { Up, Down }

data class ContentScrollDelta(
    val direction: ContentScrollDirection,
    val distancePx: Float,
)

internal fun contentScrollDelta(consumedY: Float): ContentScrollDelta? = when {
    consumedY < 0f -> ContentScrollDelta(ContentScrollDirection.Up, -consumedY)
    consumedY > 0f -> ContentScrollDelta(ContentScrollDirection.Down, consumedY)
    else -> null
}
