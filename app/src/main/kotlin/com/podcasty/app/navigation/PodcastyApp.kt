/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch
import com.podcasty.app.R
import com.podcasty.app.asr.LiveCaptionUiState
import com.podcasty.app.data.PodcastUserSettings
import com.podcasty.app.domain.AlbumSortOption
import com.podcasty.app.domain.Category
import com.podcasty.app.domain.CategorySortOption
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.EpisodeSortOption
import com.podcasty.app.domain.FolderSortOption
import com.podcasty.app.domain.FolderViewMode
import com.podcasty.app.domain.Host
import com.podcasty.app.domain.HostSortOption
import com.podcasty.app.domain.InsightPeriod
import com.podcasty.app.domain.InsightUiState
import com.podcasty.app.domain.LinearFolderSummary
import com.podcasty.app.domain.ListeningHistoryEntry
import com.podcasty.app.domain.MediaSourceType
import com.podcasty.app.domain.MusicFolder
import com.podcasty.app.domain.MusicLibraryCategory
import com.podcasty.app.domain.PlaybackQueueSnapshot
import com.podcasty.app.domain.PlaybackState
import com.podcasty.app.domain.Podcast
import com.podcasty.app.domain.PodcastCollection
import com.podcasty.app.domain.PodcastLayoutMode
import com.podcasty.app.domain.PodcastSortOption
import com.podcasty.app.domain.RepeatMode
import com.podcasty.app.domain.SleepTimerRequest
import com.podcasty.app.domain.SleepTimerState
import com.podcasty.app.domain.SongSortOption
import com.podcasty.app.domain.itemOrNull
import com.podcasty.app.domain.showsMiniPlayer
import com.podcasty.app.features.addpodcast.AddPodcastScreen
import com.podcasty.app.features.addpodcast.AddPodcastUiState
import com.podcasty.app.features.albums.AlbumDetailsScreen
import com.podcasty.app.features.albums.AlbumDetailsUiState
import com.podcasty.app.features.albums.LibraryAlbumsScreen
import com.podcasty.app.features.albums.LibraryAlbumsUiState
import com.podcasty.app.features.podcast.SupportedFormatsScreen
import com.podcasty.app.features.settings.BluetoothSettingsScreen
import com.podcasty.app.features.settings.LyricsCaptionsSettingsScreen
import com.podcasty.app.features.settings.MusicLibrarySettingsScreen
import com.podcasty.app.podcast.ParsedPodcastEpisode
import com.podcasty.app.podcast.ParsedPodcastFeed
import com.podcasty.app.storage.LibraryScanState
import com.podcasty.app.storage.UsbVolumeSummary
import com.podcasty.app.transcript.CaptionSyncSnapshot
import com.podcasty.app.transcript.TranscriptDocument
import com.podcasty.app.features.categories.CategoriesScreen
import com.podcasty.app.features.categories.CategoriesUiState
import com.podcasty.app.features.categories.CategoryDetailsScreen
import com.podcasty.app.features.collections.CollectionDetailsScreen
import com.podcasty.app.features.collections.CollectionsScreen
import com.podcasty.app.features.collections.CollectionsUiState
import com.podcasty.app.features.collections.CreateCollectionBottomSheet
import com.podcasty.app.features.episodes.EpisodesScreen
import com.podcasty.app.features.episodes.EpisodesUiState
import com.podcasty.app.features.folder.FolderBrowserScreen
import com.podcasty.app.features.folder.MusicBrowserUiState
import com.podcasty.app.features.home.HomeScreen
import com.podcasty.app.features.home.HomeUiState
import com.podcasty.app.features.hosts.HostDetailsScreen
import com.podcasty.app.features.hosts.HostsScreen
import com.podcasty.app.features.hosts.HostsUiState
import com.podcasty.app.features.insights.InsightScreen
import com.podcasty.app.features.library.DownloadsScreen
import com.podcasty.app.features.library.HistoryScreen
import com.podcasty.app.features.library.LibraryScreen
import com.podcasty.app.features.library.SavedEpisodesScreen
import com.podcasty.app.features.player.FullScreenPlayer
import com.podcasty.app.features.player.MiniPlayerHeight
import com.podcasty.app.features.player.MiniPlayerNavigationGap
import com.podcasty.app.features.podcasts.PodcastDetailsScreen
import com.podcasty.app.features.podcasts.PodcastDetailsUiState
import com.podcasty.app.features.podcasts.PodcastsScreen
import com.podcasty.app.features.podcasts.PodcastsUiState
import com.podcasty.app.features.search.SearchScreen
import com.podcasty.app.features.search.SearchUiState
import com.podcasty.app.features.settings.AboutScreen
import com.podcasty.app.features.settings.AppearanceScreen
import com.podcasty.app.features.settings.DownloadsSettingsScreen
import com.podcasty.app.features.settings.PlaybackSettingsScreen
import com.podcasty.app.features.settings.SettingsScreen
import com.podcasty.app.ui.components.AnchoredPopupMenuHost
import com.podcasty.app.ui.components.EpisodeContextBottomSheet
import com.podcasty.app.ui.components.EpisodeContextBottomSheetRequest
import com.podcasty.app.ui.components.LibrarySortHeaderButton
import com.podcasty.app.ui.components.LibrarySortOption
import com.podcasty.app.ui.components.MaterialSymbols
import com.podcasty.app.ui.components.PodcastyDialog
import com.podcasty.app.ui.components.PodcastyGlassIconButton
import com.podcasty.app.ui.components.PodcastyPillButtonVariant
import com.podcasty.app.ui.components.StackPageHeader
import com.podcasty.app.ui.components.StackPageLayout
import com.podcasty.app.ui.theme.LocalPodcastyColors
import com.podcasty.app.ui.theme.PodcastyTheme

@Stable
data class PodcastyAppModels(
    val homeState: HomeUiState,
    val insightState: InsightUiState,
    val episodesState: EpisodesUiState,
    val podcastsState: PodcastsUiState,
    val podcastDetailsState: PodcastDetailsUiState = PodcastDetailsUiState(),
    val libraryAlbumsState: LibraryAlbumsUiState = LibraryAlbumsUiState(),
    val albumDetailsState: AlbumDetailsUiState = AlbumDetailsUiState(),
    val hostsState: HostsUiState,
    val categoriesState: CategoriesUiState,
    val collectionsState: CollectionsUiState,
    val searchState: SearchUiState,
    val userSettings: PodcastUserSettings,
    val allEpisodes: List<Episode>,
    val allPodcasts: List<Podcast>,
    val allHosts: List<Host>,
    val allCategories: List<Category>,
    val allCollections: List<PodcastCollection>,
    val listeningHistory: List<ListeningHistoryEntry>,
    val playbackState: PlaybackState,
    val playbackPositionFlow: StateFlow<Long> = MutableStateFlow(0L),
    val captionSyncFlow: StateFlow<CaptionSyncSnapshot> = MutableStateFlow(CaptionSyncSnapshot()),
    val openFullScreenLyricsRequestId: Long = 0L,
    val currentPlaybackEpisode: Episode? = null,
    val playbackQueue: PlaybackQueueSnapshot,
    val sleepTimer: SleepTimerState,
    val systemVolume: Float,
    val musicFolders: List<MusicFolder> = emptyList(),
    val scanState: LibraryScanState = LibraryScanState(),
    val storageVolumes: List<UsbVolumeSummary> = emptyList(),
    val activeTranscriptDocument: TranscriptDocument? = null,
    val availableTranscriptDocuments: List<TranscriptDocument> = emptyList(),
    val addPodcastState: AddPodcastUiState = AddPodcastUiState(),
    val liveCaptionState: LiveCaptionUiState = LiveCaptionUiState(),
    val hasAsrTokenStored: Boolean = false,
    val hasYouTubeApiKeyStored: Boolean = false,
    val musicBrowserState: MusicBrowserUiState = MusicBrowserUiState(),
)

@Stable
data class PodcastyAppCallbacks(
    val onIntent: (AppIntent) -> Unit,
    val onFullScreenPlayerVisibilityChanged: (Boolean) -> Unit,
    // Playback
    val onPlayEpisode: (String, List<String>, Boolean) -> Unit,
    val onPlayEpisodes: (List<String>, Boolean) -> Unit,
    val onPrevious: () -> Unit,
    val onSkipBackward: () -> Unit,
    val onPlayPause: () -> Unit,
    val onSkipForward: () -> Unit,
    val onNext: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onPlaybackSpeedSelected: (Float) -> Unit,
    val onSleepTimerConfigured: (SleepTimerRequest) -> Unit,
    val onSleepFadeOutChanged: (Int) -> Unit,
    val onSelectTranscriptLanguage: (String) -> Unit,
    val onImportTranscriptForEpisode: (Episode) -> Unit,
    val onOpenYouTubeForEpisode: (Episode) -> Unit,
    val onLinkAudioForEpisode: (Episode) -> Unit,
    val onOpenTagEditorForEpisodes: (List<Episode>) -> Unit,
    // Add Podcast page & Live AI Captions
    val onAddPodcastUrlChange: (String) -> Unit,
    val onYouTubeLinkedAudioUrlChange: (String) -> Unit,
    val onLoadDiscoveredRssFeed: (String) -> Unit,
    val onInspectPodcastUrl: () -> Unit,
    val onAddPodcastCaptionLanguageSelected: (String) -> Unit,
    val onYouTubeAudioTrackSelected: (String) -> Unit = {},
    val onAddPreviewToLibrary: () -> Unit,
    val onPlayPreviewRssEpisode: (ParsedPodcastFeed, ParsedPodcastEpisode) -> Unit,
    val onPlayPreviewDirectAudio: () -> Unit,
    val onOpenYouTubeUrl: (String) -> Unit,
    val onDeleteImportedPodcast: (String) -> Unit,
    val onSelectAsrLanguage: (String) -> Unit,
    val onRetryLiveCaptions: () -> Unit,
    val onAutoStartLiveCaptionsChanged: (Boolean) -> Unit,
    val onSaveCaptionHistoryChanged: (Boolean) -> Unit,
    val onAsrEngineSelected: (String) -> Unit,
    val onSaveAsrConnection: (String, String?, String?) -> Unit,
    val onQueueEpisodeSelected: (String) -> Unit,
    val onQueueReordered: (List<String>) -> Unit,
    val onQueueEpisodeRemoved: (String) -> Unit,
    val onShuffleChange: (Boolean) -> Unit,
    val onRepeatModeChange: (RepeatMode) -> Unit,
    val onMiniPlayerDismiss: () -> Unit,
    val onSystemVolumeChange: (Float) -> Unit,
    val onOpenMediaOutputSwitcher: () -> Unit,
    // Library mutations
    val onEpisodePlayNext: (String) -> Unit,
    val onEpisodeAddToQueue: (String) -> Unit,
    val onEpisodesPlayNext: (List<String>) -> Unit,
    val onEpisodesAddToQueue: (List<String>) -> Unit,
    val onEpisodeSaveToggle: (String, Boolean) -> Unit,
    val onEpisodesSaveAll: (List<String>) -> Unit,
    val onEpisodeDownloadToggle: (String, Boolean) -> Unit,
    val onPodcastSubscribeToggle: (String, Boolean) -> Unit,
    val onCreateCollection: (String, List<String>) -> Unit,
    val onRenameCollection: (String, String) -> Unit,
    val onDeleteCollection: (String) -> Unit,
    val onCollectionMembershipChange: (String, List<String>, Boolean) -> Unit,
    val onReorderCollectionEpisodes: (String, List<String>) -> Unit,
    val onClearAllDownloads: () -> Unit,
    val onRestoreDemoDownloads: () -> Unit,
    val onClearListeningHistory: () -> Unit,
    // Filters / sorting / search / insights
    val onEpisodesSortOption: (EpisodeSortOption) -> Unit,
    val onEpisodesToggleSortOrder: () -> Unit,
    val onEpisodesFilterQuery: (String) -> Unit,
    val onPodcastsSortOption: (PodcastSortOption) -> Unit,
    val onPodcastsToggleSortOrder: () -> Unit,
    val onPodcastsLayoutMode: (PodcastLayoutMode) -> Unit,
    val onPodcastsFilterQuery: (String) -> Unit,
    val onAlbumsSortOption: (AlbumSortOption) -> Unit = {},
    val onAlbumsToggleSortOrder: () -> Unit = {},
    val onAlbumsLayoutMode: (PodcastLayoutMode) -> Unit = {},
    val onAlbumsFilterQuery: (String) -> Unit = {},
    val onAlbumsLoadMore: () -> Unit = {},
    val onHostsSortOption: (HostSortOption) -> Unit,
    val onHostsToggleSortOrder: () -> Unit,
    val onHostsFilterQuery: (String) -> Unit,
    val onCategoriesSortOption: (CategorySortOption) -> Unit,
    val onCategoriesToggleSortOrder: () -> Unit,
    val onCategoriesFilterQuery: (String) -> Unit,
    val onSearchQueryChange: (String) -> Unit,
    val onSearchSubmit: (String) -> Unit,
    val onSearchRemoveRecent: (String) -> Unit,
    val onSearchClearRecent: () -> Unit,
    val onSearchMusicOptionsChanged: (com.podcasty.app.features.search.MusicSearchOptions) -> Unit = {},
    val onInsightLibraryPeriod: (InsightPeriod) -> Unit,
    val onInsightListeningPeriod: (InsightPeriod) -> Unit,
    // Settings
    val onDefaultSpeedSelected: (Float) -> Unit,
    val onSkipBackwardSelected: (Int) -> Unit,
    val onSkipForwardSelected: (Int) -> Unit,
    val onShowSpeedBadgeChanged: (Boolean) -> Unit,
    val onKeepScreenOnChanged: (Boolean) -> Unit,
    val onLivingArtworkChanged: (Boolean) -> Unit,
    val onAutoDownloadChanged: (Boolean) -> Unit,
    // Music Library, Storage, Lyrics, Playback & Bluetooth Settings
    val onSelectDefaultMusicFolder: () -> Unit,
    val onSelectUsbOtgFolder: () -> Unit,
    val onScanMusicFolders: () -> Unit,
    val onExportPlaylists: () -> Unit = {},
    val onRestorePlaylists: () -> Unit = {},
    val onFindDuplicates: () -> Unit = {},
    val onScanMediaStore: () -> Unit,
    val onRemoveMusicFolder: (String) -> Unit,
    val onToggleMusicFolderEnabled: (String, Boolean) -> Unit = { _, _ -> },
    val onRefreshMusicFolder: (String) -> Unit = {},
    val onAutoLibraryRefreshChanged: (Boolean) -> Unit,
    val onIncludeSubfoldersChanged: (Boolean) -> Unit,
    val onAutoSyncLyricsChanged: (Boolean) -> Unit,
    val onAutoScrollLyricsChanged: (Boolean) -> Unit,
    val onPreferredTranscriptLanguageSelected: (String) -> Unit,
    val onCaptionFontScaleSelected: (Float) -> Unit,
    val onShowLockScreenLyricsChanged: (Boolean) -> Unit,
    val onLockScreenLyricsExpandedChanged: (Boolean) -> Unit = {},
    val onShowMiniPlayerLyricsChanged: (Boolean) -> Unit = {},
    val onShowLyricsTranslationChanged: (Boolean) -> Unit = {},
    val onShowLockScreenCaptionsChanged: (Boolean) -> Unit,
    val onShowTranscriptNotificationChanged: (Boolean) -> Unit,
    val onResumePreviousPlaybackChanged: (Boolean) -> Unit,
    val onGaplessPlaybackChanged: (Boolean) -> Unit,
    val onCrossfadeDurationChanged: (Int) -> Unit = {},
    val onPlaybackFadeDurationChanged: (Int) -> Unit = {},
    val onRemovePlayedFromQueueChanged: (Boolean) -> Unit = {},
    val onOptionalHomeCategoryChanged: (String) -> Unit = {},
    val onShakeSkipSensitivityChanged: (Int) -> Unit = {},
    val onReplayGainModeChanged: (Int) -> Unit = {},
    val onPlayerSecondaryControlsChanged: (List<String>) -> Unit = {},
    val onPauseOnHeadphoneDisconnectChanged: (Boolean) -> Unit,
    val onBluetoothMediaControlsChanged: (Boolean) -> Unit,
    val onAutoResumeBluetoothChanged: (Boolean) -> Unit,
    // Offline Music Browser, File Management & Multi-Queue
    val onMusicSelectCategory: (MusicLibraryCategory) -> Unit = {},
    val onMusicSetFolderViewMode: (FolderViewMode) -> Unit = {},
    val onMusicSetFolderSortOption: (FolderSortOption) -> Unit = {},
    val onMusicSetSongSortOption: (SongSortOption) -> Unit = {},
    val onMusicSearchQueryChange: (String) -> Unit = {},
    val onMusicOpenStorageRoot: (String) -> Unit = {},
    val onMusicToggleFavoriteFolder: () -> Unit = {},
    val onMusicOpenFavoriteFolder: (com.podcasty.app.data.FavoriteMusicFolder) -> Unit = {},
    val onMusicOpenSubfolder: (String) -> Unit = {},
    val onMusicNavigateUpFolder: () -> Unit = {},
    val onMusicOpenLinearFolder: (LinearFolderSummary) -> Unit = {},
    val onMusicExcludeFolder: (LinearFolderSummary) -> Unit = {},
    val onMusicSelectAlbum: (String?) -> Unit = {},
    val onMusicSelectArtist: (String?) -> Unit = {},
    val onMusicRefreshSingleFolder: (String) -> Unit = {},
    val onMusicPlayAllFolderRecursive: (Boolean) -> Unit = {},
    val onMusicPlayAllFolderDirect: (Boolean) -> Unit = {},
    val onMusicPlayLinearFolderDirect: (String, String, Boolean) -> Unit = { _, _, _ -> },
    val onMusicPlayAllSortedSongs: (Boolean) -> Unit = {},
    val onMusicAddFolderToQueue: (String, String) -> Unit = { _, _ -> },
    val onMusicToggleSelectSong: (String) -> Unit = {},
    val onMusicSelectAllVisible: (List<Episode>) -> Unit = {},
    val onMusicClearSelection: () -> Unit = {},
    val onMusicLoadMoreSongs: () -> Unit = {},
    val onMusicLoadMoreAlbums: () -> Unit = {},
    val onMusicLoadMoreArtists: () -> Unit = {},
    val onMusicLoadMoreFolderSongs: () -> Unit = {},
    val onOpenQueueManager: () -> Unit = {},
    val onOpenExcludedFolders: () -> Unit = {},
    val onOpenEqualizer: () -> Unit = {},
    val onHandleFileOrQueueRequest: (EpisodeContextBottomSheetRequest) -> Unit = {},
)

@Composable
fun PodcastyApp(
    uiState: AppUiState,
    models: PodcastyAppModels,
    callbacks: PodcastyAppCallbacks,
) {
    PodcastyTheme(themeMode = uiState.themeMode) {
        val colors = LocalPodcastyColors.current
        val hazeState = if (uiState.reduceTransparency) null else rememberHazeState()
        val currentStackPage = uiState.stackFor(uiState.selectedDestination).currentStackPage(uiState.selectedDestination)
        val currentPage = currentStackPage.page

        val homeListState = remember { LazyListState() }
        val folderListState = remember { LazyListState() }
        val insightListState = remember { LazyListState() }
        val libraryListState = remember { LazyListState() }
        val addPodcastListState = remember { LazyListState() }
        val episodesListState = remember(models.episodesState.sortOption, models.episodesState.sortOrder) { LazyListState() }
        val podcastsListState = remember(models.podcastsState.sortOption, models.podcastsState.sortOrder, models.podcastsState.layoutMode) { LazyListState() }
        val albumsListState = remember(models.libraryAlbumsState.sortOption, models.libraryAlbumsState.sortOrder, models.libraryAlbumsState.layoutMode) { LazyListState() }
        val hostsListState = remember(models.hostsState.sortOption, models.hostsState.sortOrder) { LazyListState() }
        val categoriesListState = remember(models.categoriesState.sortOption, models.categoriesState.sortOrder) { LazyListState() }
        val collectionsListState = remember(Unit) { LazyListState() }
        val savedListState = remember { LazyListState() }
        val downloadsListState = remember { LazyListState() }
        val historyListState = remember { LazyListState() }
        val podcastDetailsListState = remember(uiState.selectedPodcastId) { LazyListState() }
        val albumDetailsListState = remember(uiState.selectedAlbumId) { LazyListState() }
        val hostDetailsListState = remember(uiState.selectedHostId) { LazyListState() }
        val categoryDetailsListState = remember(uiState.selectedCategoryId) { LazyListState() }

        val coroutineScope = rememberCoroutineScope()
        var previousDestination by remember { mutableStateOf(uiState.selectedDestination) }
        var previousHeaderWasBlurred by remember { mutableStateOf(false) }
        val destinationChanged = previousDestination != uiState.selectedDestination
        val animateHeaderChanges = !destinationChanged
        var settingsContentScrolled by remember { mutableStateOf(false) }
        var previousStackPage by remember { mutableStateOf(currentStackPage) }
        val isForwardHeaderTransition = currentStackPage.index >= previousStackPage.index

        val showsMiniPlayer = models.playbackState.showsMiniPlayer()
        var isFullScreenPlayerVisible by rememberSaveable { mutableStateOf(false) }
        var openLyricsPanelRequestId by remember { mutableStateOf(0L) }
        LaunchedEffect(Unit) {
            if (isFullScreenPlayerVisible) callbacks.onFullScreenPlayerVisibilityChanged(true)
        }
        var isFullScreenPlayerOpeningFromSwipe by remember { mutableStateOf(false) }
        var fullScreenPlayerDragProgress by remember { mutableFloatStateOf(0f) }
        var isFullScreenPlayerDragging by remember { mutableStateOf(false) }
        var pendingFullScreenPlayerAction by remember { mutableStateOf<(() -> Unit)?>(null) }
        var episodeContextSheet by remember { mutableStateOf<EpisodeContextBottomSheetRequest?>(null) }
        var showCreateCollectionSheet by rememberSaveable { mutableStateOf(false) }
        var createCollectionForEpisodes by remember { mutableStateOf<List<String>>(emptyList()) }
        var showClearHistoryDialog by rememberSaveable { mutableStateOf(false) }
        var isNavigationCompact by remember { mutableStateOf(false) }
        var detailHeroColor by remember { mutableStateOf<Color?>(null) }
        val navigationScrollAccumulator = remember { NavigationChromeScrollAccumulator() }
        val navigationScrollThresholdPx = with(LocalDensity.current) { 24.dp.toPx() }

        fun setFullScreenVisible(visible: Boolean) {
            if (isFullScreenPlayerVisible == visible) return
            isFullScreenPlayerVisible = visible
            callbacks.onFullScreenPlayerVisibilityChanged(visible)
        }

        LaunchedEffect(models.openFullScreenLyricsRequestId) {
            if (models.openFullScreenLyricsRequestId > 0L) {
                isFullScreenPlayerOpeningFromSwipe = false
                setFullScreenVisible(true)
                openLyricsPanelRequestId += 1L
            }
        }

        fun closeFullScreenPlayerThen(action: () -> Unit) {
            if (!isFullScreenPlayerVisible) {
                action()
                return
            }
            pendingFullScreenPlayerAction = action
            setFullScreenVisible(false)
            isFullScreenPlayerOpeningFromSwipe = false
            isFullScreenPlayerDragging = false
            fullScreenPlayerDragProgress = 0f
        }

        LaunchedEffect(showsMiniPlayer) {
            if (!showsMiniPlayer) {
                isNavigationCompact = false
                navigationScrollAccumulator.reset()
                setFullScreenVisible(false)
                isFullScreenPlayerOpeningFromSwipe = false
                isFullScreenPlayerDragging = false
                fullScreenPlayerDragProgress = 0f
            }
        }

        val navigationChromeHeight = when {
            showsMiniPlayer && isNavigationCompact -> CompactNavigationHeight
            showsMiniPlayer -> FloatingNavigationHeight + MiniPlayerHeight + MiniPlayerNavigationGap
            else -> FloatingNavigationHeight
        }
        val targetBottomPadding = navigationChromeHeight + FloatingNavigationBottomMargin + FloatingNavigationContentGap +
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val navigationBottomPadding by animateDpAsState(
            targetValue = targetBottomPadding,
            animationSpec = tween(420, easing = FastOutSlowInEasing),
            label = "navigation-bottom-padding",
        )

        val pageTitle = when {
            currentPage.isDetailHeroPage() -> ""
            uiState.selectedDestination == AppDestination.Home && currentPage == AppStackPage.Root ->
                stringResource(R.string.app_name)
            uiState.selectedDestination == AppDestination.Folder && currentPage == AppStackPage.Root -> {
                models.musicFolders.firstOrNull { it.treeUri == models.userSettings.defaultMusicFolderUri }?.displayName
                    ?: models.musicFolders.firstOrNull { it.isDefault }?.displayName
                    ?: models.userSettings.defaultMusicFolderName
                    ?: stringResource(R.string.destination_folder)
            }
            else -> stringResource(currentPage.titleRes(uiState.selectedDestination))
        }
        val showBack = currentPage != AppStackPage.Root
        val showSortAction = currentPage == AppStackPage.LibraryEpisodes ||
            currentPage == AppStackPage.LibraryPodcasts ||
            currentPage == AppStackPage.LibraryAlbums ||
            currentPage == AppStackPage.LibraryHosts ||
            currentPage == AppStackPage.LibraryCategories
        val showAddCollectionAction = currentPage == AppStackPage.LibraryCollections
        val showClearHistoryAction = currentPage == AppStackPage.LibraryHistory && models.listeningHistory.isNotEmpty()

        BackHandler(enabled = showBack) { callbacks.onIntent(AppIntent.NavigateBack) }

        val isContentScrolled by remember(
            uiState.selectedDestination,
            currentPage,
            homeListState,
            folderListState,
            insightListState,
            libraryListState,
            addPodcastListState,
            episodesListState,
            podcastsListState,
            albumsListState,
            hostsListState,
            categoriesListState,
            collectionsListState,
            savedListState,
            downloadsListState,
            historyListState,
            podcastDetailsListState,
            albumDetailsListState,
            hostDetailsListState,
            categoryDetailsListState,
            settingsContentScrolled,
        ) {
            derivedStateOf {
                when {
                    uiState.selectedDestination == AppDestination.Home && currentPage == AppStackPage.Root ->
                        homeListState.firstVisibleItemIndex > 0 || homeListState.firstVisibleItemScrollOffset > 0
                    uiState.selectedDestination == AppDestination.Folder && currentPage == AppStackPage.Root ->
                        folderListState.firstVisibleItemIndex > 0 || folderListState.firstVisibleItemScrollOffset > 0
                    uiState.selectedDestination == AppDestination.Insight && currentPage == AppStackPage.Root ->
                        insightListState.firstVisibleItemIndex > 0 || insightListState.firstVisibleItemScrollOffset > 0
                    uiState.selectedDestination == AppDestination.Library && currentPage == AppStackPage.Root ->
                        libraryListState.firstVisibleItemIndex > 0 || libraryListState.firstVisibleItemScrollOffset > 0
                    currentPage == AppStackPage.LibraryAddPodcast ->
                        addPodcastListState.firstVisibleItemIndex > 0 || addPodcastListState.firstVisibleItemScrollOffset > 0
                    currentPage == AppStackPage.LibraryEpisodes ->
                        episodesListState.firstVisibleItemIndex > 0 || episodesListState.firstVisibleItemScrollOffset > 0
                    currentPage == AppStackPage.LibraryPodcasts ->
                        podcastsListState.firstVisibleItemIndex > 0 || podcastsListState.firstVisibleItemScrollOffset > 0
                    currentPage == AppStackPage.LibraryAlbums ->
                        albumsListState.firstVisibleItemIndex > 0 || albumsListState.firstVisibleItemScrollOffset > 0
                    currentPage == AppStackPage.LibraryHosts ->
                        hostsListState.firstVisibleItemIndex > 0 || hostsListState.firstVisibleItemScrollOffset > 0
                    currentPage == AppStackPage.LibraryCategories ->
                        categoriesListState.firstVisibleItemIndex > 0 || categoriesListState.firstVisibleItemScrollOffset > 0
                    currentPage == AppStackPage.LibraryCollections ->
                        collectionsListState.firstVisibleItemIndex > 0 || collectionsListState.firstVisibleItemScrollOffset > 0
                    currentPage == AppStackPage.LibrarySavedEpisodes ->
                        savedListState.firstVisibleItemIndex > 0 || savedListState.firstVisibleItemScrollOffset > 0
                    currentPage == AppStackPage.LibraryDownloads ->
                        downloadsListState.firstVisibleItemIndex > 0 || downloadsListState.firstVisibleItemScrollOffset > 0
                    currentPage == AppStackPage.LibraryHistory ->
                        historyListState.firstVisibleItemIndex > 0 || historyListState.firstVisibleItemScrollOffset > 0
                    currentPage == AppStackPage.PodcastDetails ->
                        podcastDetailsListState.firstVisibleItemIndex > 0 || podcastDetailsListState.firstVisibleItemScrollOffset > 0
                    currentPage == AppStackPage.AlbumDetails ->
                        albumDetailsListState.firstVisibleItemIndex > 0 || albumDetailsListState.firstVisibleItemScrollOffset > 0
                    currentPage == AppStackPage.HostDetails ->
                        hostDetailsListState.firstVisibleItemIndex > 0 || hostDetailsListState.firstVisibleItemScrollOffset > 0
                    currentPage == AppStackPage.CategoryDetails ->
                        categoryDetailsListState.firstVisibleItemIndex > 0 || categoryDetailsListState.firstVisibleItemScrollOffset > 0
                    uiState.selectedDestination == AppDestination.Settings -> settingsContentScrolled
                    else -> false
                }
            }
        }
        val showHeaderBlur = isContentScrolled || (destinationChanged && previousHeaderWasBlurred)
        SideEffect {
            previousDestination = uiState.selectedDestination
            previousHeaderWasBlurred = isContentScrolled
            previousStackPage = currentStackPage
        }

        val currentPlaybackItem = models.playbackState.itemOrNull()
        val currentPlaybackEpisodeId = currentPlaybackItem?.episodeId
        val isPlayingNow = models.playbackState is PlaybackState.Playing
        val currentPlaybackEpisode = remember(models.currentPlaybackEpisode, models.allEpisodes, currentPlaybackEpisodeId) {
            models.currentPlaybackEpisode?.takeIf { it.id == currentPlaybackEpisodeId }
                ?: models.allEpisodes.firstOrNull { it.id == currentPlaybackEpisodeId }
        }
        val episodesById = remember(models.allEpisodes) { models.allEpisodes.associateBy { it.id } }

        val focusManager = LocalFocusManager.current
        val currentOnContentScroll = rememberUpdatedState<(ContentScrollDelta) -> Unit> { delta ->
            if (!showsMiniPlayer) return@rememberUpdatedState
            if (navigationScrollAccumulator.update(delta, navigationScrollThresholdPx)) {
                isNavigationCompact = navigationScrollAccumulator.compact
            }
        }
        val scrollConnection = remember {
            object : NestedScrollConnection {
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput) {
                        contentScrollDelta(consumed.y)?.let(currentOnContentScroll.value)
                    }
                    return Offset.Zero
                }
            }
        }

        AnchoredPopupMenuHost(hazeState = hazeState, dismissKey = currentStackPage) {
            Box(modifier = Modifier.fillMaxSize()) {
                Surface(
                    color = colors.background,
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(scrollConnection)
                        .then(if (hazeState == null) Modifier else Modifier.hazeSource(hazeState)),
                ) {
                    StackPageLayout(
                        title = pageTitle,
                        hazeState = hazeState,
                        contentBottomPadding = navigationBottomPadding,
                        isContentScrolled = false,
                        onBackClick = if (showBack) { { callbacks.onIntent(AppIntent.NavigateBack) } } else null,
                        showHeader = false,
                    ) { contentModifier, contentPadding ->
                        AnimatedContent(
                            targetState = currentStackPage,
                            modifier = contentModifier,
                            transitionSpec = {
                                if (targetState.destination != initialState.destination) {
                                    fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                                } else if (targetState.index >= initialState.index) {
                                    (slideInHorizontally { it } togetherWith slideOutHorizontally { -it / 4 }).apply {
                                        targetContentZIndex = 1f
                                    }
                                } else {
                                    (slideInHorizontally { -it / 4 } togetherWith slideOutHorizontally { it }).apply {
                                        targetContentZIndex = 0f
                                    }
                                }
                            },
                            label = "stack-page-content",
                        ) { entry ->
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(colors.background)
                                    .pointerInput(focusManager) {
                                        detectTapGestures { focusManager.clearFocus() }
                                    },
                            ) {
                                when (entry.destination) {
                                    AppDestination.Home -> HomeScreen(
                                        uiState = models.homeState,
                                        playbackQueue = models.playbackQueue,
                                        hazeState = hazeState,
                                        listState = homeListState,
                                        contentPadding = contentPadding,
                                        onEpisodeClick = { list, epId ->
                                            callbacks.onPlayEpisode(epId, list.map { it.id }, false)
                                        },
                                        onPodcastClick = { callbacks.onIntent(AppIntent.OpenPodcastDetails(it)) },
                                        onPodcastPlay = callbacks.onPlayEpisodes,
                                        onPodcastToggleSubscribe = callbacks.onPodcastSubscribeToggle,
                                        onEpisodePlayNext = { callbacks.onEpisodePlayNext(it.id) },
                                        onEpisodeAddToQueue = { callbacks.onEpisodeAddToQueue(it.id) },
                                        onEpisodeSaveToggle = { ep, saved -> callbacks.onEpisodeSaveToggle(ep.id, saved) },
                                        onEpisodeDownloadToggle = { ep, dl -> callbacks.onEpisodeDownloadToggle(ep.id, dl) },
                                        onEpisodeGoToPodcast = { ep ->
                                            if (ep.isMusicContent) callbacks.onIntent(AppIntent.OpenAlbumDetails(ep.podcastId))
                                            else callbacks.onIntent(AppIntent.OpenPodcastDetails(ep.podcastId))
                                        },
                                        onEpisodeGoToHost = { callbacks.onIntent(AppIntent.OpenHostDetails(it.hostId)) },
                                        onEpisodeContextBottomSheet = { episodeContextSheet = it },
                                        onPodcastPlayNext = callbacks.onEpisodesPlayNext,
                                        onPodcastAddToQueue = callbacks.onEpisodesAddToQueue,
                                        onPodcastSaveAll = callbacks.onEpisodesSaveAll,
                                        onSelectDefaultMusicFolder = callbacks.onSelectDefaultMusicFolder,
                                        onAddPodcastClick = { callbacks.onIntent(AppIntent.OpenPage(AppStackPage.LibraryAddPodcast)) },
                                        scanState = models.scanState,
                                        modifier = Modifier.fillMaxSize(),
                                    )

                                    AppDestination.Folder -> FolderBrowserScreen(
                                        uiState = models.musicBrowserState,
                                        defaultFolderName = models.userSettings.defaultMusicFolderName,
                                        scanState = models.scanState,
                                        currentEpisodeId = currentPlaybackEpisodeId,
                                        isPlaying = isPlayingNow,
                                        playbackQueue = models.playbackQueue,
                                        hazeState = hazeState,
                                        listState = folderListState,
                                        contentPadding = contentPadding,
                                        onSelectCategory = callbacks.onMusicSelectCategory,
                                        onSetFolderViewMode = callbacks.onMusicSetFolderViewMode,
                                        onSetFolderSortOption = callbacks.onMusicSetFolderSortOption,
                                        onSetSongSortOption = callbacks.onMusicSetSongSortOption,
                                        onSearchQueryChange = callbacks.onMusicSearchQueryChange,
                                        onOpenStorageRoot = callbacks.onMusicOpenStorageRoot,
                                        onToggleFavoriteFolder = callbacks.onMusicToggleFavoriteFolder,
                                        onOpenFavoriteFolder = callbacks.onMusicOpenFavoriteFolder,
                                        onOpenSubfolder = callbacks.onMusicOpenSubfolder,
                                        onNavigateUpFolder = callbacks.onMusicNavigateUpFolder,
                                        onOpenLinearFolder = callbacks.onMusicOpenLinearFolder,
                                        onExcludeFolder = callbacks.onMusicExcludeFolder,
                                        onOpenExcludedFolders = callbacks.onOpenExcludedFolders,
                                        onSelectAlbum = callbacks.onMusicSelectAlbum,
                                        onSelectArtist = callbacks.onMusicSelectArtist,
                                        onOpenPlaylist = { plId -> callbacks.onIntent(AppIntent.OpenCollectionDetails(plId)) },
                                        onCreatePlaylist = {
                                            createCollectionForEpisodes = emptyList()
                                            showCreateCollectionSheet = true
                                        },
                                        onRenamePlaylist = { pl ->
                                            callbacks.onIntent(AppIntent.OpenCollectionDetails(pl.id))
                                        },
                                        onDeletePlaylist = callbacks.onDeleteCollection,
                                        onSelectDefaultMusicFolder = callbacks.onSelectDefaultMusicFolder,
                                        onRescanFolders = callbacks.onScanMusicFolders,
                                        onRefreshSingleFolder = callbacks.onMusicRefreshSingleFolder,
                                        onPlayEpisodes = callbacks.onPlayEpisodes,
                                        onPlayAllFolderRecursive = callbacks.onMusicPlayAllFolderRecursive,
                                        onPlayAllFolderDirect = callbacks.onMusicPlayAllFolderDirect,
                                        onPlayLinearFolderDirect = callbacks.onMusicPlayLinearFolderDirect,
                                        onPlayAllSortedSongs = callbacks.onMusicPlayAllSortedSongs,
                                        onAddFolderTracksToQueue = callbacks.onMusicAddFolderToQueue,
                                        onOpenQueueManager = callbacks.onOpenQueueManager,
                                        onToggleSelectSong = callbacks.onMusicToggleSelectSong,
                                        onSelectAllVisible = callbacks.onMusicSelectAllVisible,
                                        onClearSelection = callbacks.onMusicClearSelection,
                                        onLoadMoreSongs = callbacks.onMusicLoadMoreSongs,
                                        onLoadMoreAlbums = callbacks.onMusicLoadMoreAlbums,
                                        onLoadMoreArtists = callbacks.onMusicLoadMoreArtists,
                                        onLoadMoreFolderSongs = callbacks.onMusicLoadMoreFolderSongs,
                                        onEpisodeClick = { list, epId ->
                                            callbacks.onPlayEpisode(epId, list.map { it.id }, false)
                                        },
                                        onEpisodePlayNext = { callbacks.onEpisodePlayNext(it.id) },
                                        onEpisodeAddToQueue = { callbacks.onEpisodeAddToQueue(it.id) },
                                        onEpisodesPlayNext = callbacks.onEpisodesPlayNext,
                                        onEpisodesAddToQueue = callbacks.onEpisodesAddToQueue,
                                        onEpisodeSaveToggle = { ep, saved -> callbacks.onEpisodeSaveToggle(ep.id, saved) },
                                        onEpisodeDownloadToggle = { ep, dl -> callbacks.onEpisodeDownloadToggle(ep.id, dl) },
                                        onEpisodeGoToPodcast = { callbacks.onIntent(AppIntent.OpenPodcastDetails(it.podcastId)) },
                                        onEpisodeGoToHost = { callbacks.onIntent(AppIntent.OpenHostDetails(it.hostId)) },
                                        onEpisodeContextBottomSheet = { episodeContextSheet = it },
                                        isFullScreenPlayerVisible = isFullScreenPlayerVisible,
                                        modifier = Modifier.fillMaxSize(),
                                    )

                                    AppDestination.Insight -> InsightScreen(
                                        state = models.insightState,
                                        hazeState = hazeState,
                                        listState = insightListState,
                                        contentPadding = contentPadding,
                                        onLibraryPeriodSelected = callbacks.onInsightLibraryPeriod,
                                        onListeningPeriodSelected = callbacks.onInsightListeningPeriod,
                                        onHostClick = { callbacks.onIntent(AppIntent.OpenHostDetails(it)) },
                                        onEpisodeClick = { epId ->
                                            callbacks.onPlayEpisode(epId, models.insightState.listening.topEpisodes.map { it.episode.id }, false)
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                    )

                                    AppDestination.Settings -> key(entry.page) {
                                        val scrollState = rememberScrollState()
                                        LaunchedEffect(entry, currentStackPage, scrollState.value) {
                                            if (entry == currentStackPage) {
                                                settingsContentScrolled = scrollState.value > 0
                                            }
                                        }
                                        val layoutDirection = LocalLayoutDirection.current
                                        val settingsMod = Modifier
                                            .fillMaxSize()
                                            .verticalScroll(scrollState)
                                            .padding(
                                                start = contentPadding.calculateStartPadding(layoutDirection),
                                                top = contentPadding.calculateTopPadding(),
                                                end = contentPadding.calculateEndPadding(layoutDirection),
                                                bottom = contentPadding.calculateBottomPadding(),
                                            )
                                        when (entry.page) {
                                            AppStackPage.SettingsAppearance -> AppearanceScreen(
                                                themeMode = uiState.themeMode,
                                                onThemeModeSelected = { callbacks.onIntent(AppIntent.SetThemeMode(it)) },
                                                reduceTransparency = uiState.reduceTransparency,
                                                onReduceTransparencyChanged = { callbacks.onIntent(AppIntent.SetReduceTransparency(it)) },
                                                hazeState = hazeState,
                                                modifier = settingsMod,
                                            )
                                            AppStackPage.SettingsMusicLibrary -> MusicLibrarySettingsScreen(
                                                settings = models.userSettings,
                                                folders = models.musicFolders,
                                                scanState = models.scanState,
                                                onSelectDefaultFolder = callbacks.onSelectDefaultMusicFolder,
                                                onSelectUsbOtgFolder = callbacks.onSelectUsbOtgFolder,
                                                onScanFoldersNow = callbacks.onScanMusicFolders,
                                                onExportPlaylists = callbacks.onExportPlaylists,
                                                onRestorePlaylists = callbacks.onRestorePlaylists,
                                                onFindDuplicates = callbacks.onFindDuplicates,
                                                onScanMediaStore = callbacks.onScanMediaStore,
                                                onAutoLibraryRefreshChanged = callbacks.onAutoLibraryRefreshChanged,
                                                onIncludeSubfoldersChanged = callbacks.onIncludeSubfoldersChanged,
                                                onSupportedFormatsSelected = {
                                                    callbacks.onIntent(AppIntent.OpenPage(AppStackPage.SettingsSupportedFormats))
                                                },
                                                onImportYouTubeOrRss = {
                                                    callbacks.onIntent(AppIntent.OpenPage(AppStackPage.LibraryAddPodcast))
                                                },
                                                onRemoveFolder = callbacks.onRemoveMusicFolder,
                                                onToggleFolderEnabled = callbacks.onToggleMusicFolderEnabled,
                                                onRefreshFolder = callbacks.onRefreshMusicFolder,
                                                modifier = settingsMod,
                                            )
                                            AppStackPage.SettingsSupportedFormats -> SupportedFormatsScreen(
                                                modifier = settingsMod,
                                            )
                                            AppStackPage.SettingsLyricsCaptions -> LyricsCaptionsSettingsScreen(
                                                settings = models.userSettings,
                                                hasAsrTokenStored = models.hasAsrTokenStored,
                                                hasYouTubeApiKeyStored = models.hasYouTubeApiKeyStored,
                                                hazeState = hazeState,
                                                onAutoSyncChanged = callbacks.onAutoSyncLyricsChanged,
                                                onAutoScrollChanged = callbacks.onAutoScrollLyricsChanged,
                                                onLanguageSelected = callbacks.onPreferredTranscriptLanguageSelected,
                                                onFontScaleSelected = callbacks.onCaptionFontScaleSelected,
                                                onShowLockScreenLyricsChanged = callbacks.onShowLockScreenLyricsChanged,
                                                onLockScreenLyricsExpandedChanged = callbacks.onLockScreenLyricsExpandedChanged,
                                                onShowMiniPlayerLyricsChanged = callbacks.onShowMiniPlayerLyricsChanged,
                                                onShowLyricsTranslationChanged = callbacks.onShowLyricsTranslationChanged,
                                                onShowLockScreenCaptionsChanged = callbacks.onShowLockScreenCaptionsChanged,
                                                onShowTranscriptNotificationChanged = callbacks.onShowTranscriptNotificationChanged,
                                                onKeepScreenOnChanged = callbacks.onKeepScreenOnChanged,
                                                onAutoStartLiveCaptionsChanged = callbacks.onAutoStartLiveCaptionsChanged,
                                                onSaveCaptionHistoryChanged = callbacks.onSaveCaptionHistoryChanged,
                                                onAsrEngineSelected = callbacks.onAsrEngineSelected,
                                                onAsrLanguageSelected = callbacks.onSelectAsrLanguage,
                                                onSaveAsrConnection = callbacks.onSaveAsrConnection,
                                                modifier = settingsMod,
                                            )
                                            AppStackPage.SettingsPlayback -> PlaybackSettingsScreen(
                                                settings = models.userSettings,
                                                hazeState = hazeState,
                                                onOpenEqualizer = callbacks.onOpenEqualizer,
                                                onDefaultSpeedSelected = callbacks.onDefaultSpeedSelected,
                                                onSkipBackwardSelected = callbacks.onSkipBackwardSelected,
                                                onSkipForwardSelected = callbacks.onSkipForwardSelected,
                                                onFadeOutSecondsSelected = callbacks.onSleepFadeOutChanged,
                                                onResumePreviousChanged = callbacks.onResumePreviousPlaybackChanged,
                                                onGaplessPlaybackChanged = callbacks.onGaplessPlaybackChanged,
                                                onCrossfadeDurationChanged = callbacks.onCrossfadeDurationChanged,
                                                onPlaybackFadeDurationChanged = callbacks.onPlaybackFadeDurationChanged,
                                                onRemovePlayedFromQueueChanged = callbacks.onRemovePlayedFromQueueChanged,
                                                onOptionalHomeCategoryChanged = callbacks.onOptionalHomeCategoryChanged,
                                                onShakeSkipSensitivityChanged = callbacks.onShakeSkipSensitivityChanged,
                                                onReplayGainModeChanged = callbacks.onReplayGainModeChanged,
                                                onPlayerSecondaryControlsChanged = callbacks.onPlayerSecondaryControlsChanged,
                                                onShowSpeedBadgeChanged = callbacks.onShowSpeedBadgeChanged,
                                                onKeepScreenOnChanged = callbacks.onKeepScreenOnChanged,
                                                onLivingArtworkChanged = callbacks.onLivingArtworkChanged,
                                                modifier = settingsMod,
                                            )
                                            AppStackPage.SettingsBluetooth -> BluetoothSettingsScreen(
                                                settings = models.userSettings,
                                                onPauseOnDisconnectChanged = callbacks.onPauseOnHeadphoneDisconnectChanged,
                                                onBluetoothMediaControlsChanged = callbacks.onBluetoothMediaControlsChanged,
                                                onAutoResumeChanged = callbacks.onAutoResumeBluetoothChanged,
                                                modifier = settingsMod,
                                            )
                                            AppStackPage.SettingsDownloads -> {
                                                val downloaded = remember(models.allEpisodes) { models.allEpisodes.filter { it.isDownloaded } }
                                                DownloadsSettingsScreen(
                                                    autoDownloadSaved = models.userSettings.autoDownloadSaved,
                                                    downloadedBytes = downloaded.sumOf { it.fileSizeBytes },
                                                    downloadedCount = downloaded.size,
                                                    volumes = models.storageVolumes,
                                                    folders = models.musicFolders,
                                                    onAutoDownloadChanged = callbacks.onAutoDownloadChanged,
                                                    onSelectInternalOrSdFolder = callbacks.onSelectDefaultMusicFolder,
                                                    onSelectUsbOtgFolder = callbacks.onSelectUsbOtgFolder,
                                                    onRefreshLibrary = callbacks.onScanMusicFolders,
                                                    onRestoreDemoDownloads = callbacks.onRestoreDemoDownloads,
                                                    onClearAllDownloads = callbacks.onClearAllDownloads,
                                                    modifier = settingsMod,
                                                )
                                            }
                                            AppStackPage.SettingsAbout -> AboutScreen(modifier = settingsMod)
                                            else -> SettingsScreen(
                                                onAppearanceSelected = { callbacks.onIntent(AppIntent.OpenPage(AppStackPage.SettingsAppearance)) },
                                                onMusicLibrarySelected = { callbacks.onIntent(AppIntent.OpenPage(AppStackPage.SettingsMusicLibrary)) },
                                                onLyricsCaptionsSelected = { callbacks.onIntent(AppIntent.OpenPage(AppStackPage.SettingsLyricsCaptions)) },
                                                onPlaybackSelected = { callbacks.onIntent(AppIntent.OpenPage(AppStackPage.SettingsPlayback)) },
                                                onBluetoothSelected = { callbacks.onIntent(AppIntent.OpenPage(AppStackPage.SettingsBluetooth)) },
                                                onDownloadsSelected = { callbacks.onIntent(AppIntent.OpenPage(AppStackPage.SettingsDownloads)) },
                                                onAboutSelected = { callbacks.onIntent(AppIntent.OpenPage(AppStackPage.SettingsAbout)) },
                                                modifier = settingsMod,
                                            )
                                        }
                                    }

                                    AppDestination.Library -> when (entry.page) {
                                        AppStackPage.LibrarySearch -> SearchScreen(
                                            uiState = models.searchState,
                                            playbackQueue = models.playbackQueue,
                                            hazeState = hazeState,
                                            contentPadding = contentPadding,
                                            onQueryChange = callbacks.onSearchQueryChange,
                                            onSubmitQuery = callbacks.onSearchSubmit,
                                            onRemoveRecentSearch = callbacks.onSearchRemoveRecent,
                                            onClearRecentSearches = callbacks.onSearchClearRecent,
                                            onMusicOptionsChanged = callbacks.onSearchMusicOptionsChanged,
                                            onEpisodeClick = { list, epId -> callbacks.onPlayEpisode(epId, list.map { it.id }, false) },
                                            onPodcastClick = { callbacks.onIntent(AppIntent.OpenPodcastDetails(it)) },
                                            onHostClick = { callbacks.onIntent(AppIntent.OpenHostDetails(it)) },
                                            onCategoryClick = { callbacks.onIntent(AppIntent.OpenCategoryDetails(it)) },
                                            onCollectionClick = { callbacks.onIntent(AppIntent.OpenCollectionDetails(it)) },
                                            onPodcastPlay = callbacks.onPlayEpisodes,
                                            onPodcastToggleSubscribe = callbacks.onPodcastSubscribeToggle,
                                            onEpisodePlayNext = { callbacks.onEpisodePlayNext(it.id) },
                                            onEpisodeAddToQueue = { callbacks.onEpisodeAddToQueue(it.id) },
                                            onEpisodeSaveToggle = { ep, saved -> callbacks.onEpisodeSaveToggle(ep.id, saved) },
                                            onEpisodeDownloadToggle = { ep, dl -> callbacks.onEpisodeDownloadToggle(ep.id, dl) },
                                            onEpisodeGoToPodcast = { callbacks.onIntent(AppIntent.OpenPodcastDetails(it.podcastId)) },
                                            onEpisodeGoToHost = { callbacks.onIntent(AppIntent.OpenHostDetails(it.hostId)) },
                                            onEpisodeContextBottomSheet = { episodeContextSheet = it },
                                            onPodcastPlayNext = callbacks.onEpisodesPlayNext,
                                            onPodcastAddToQueue = callbacks.onEpisodesAddToQueue,
                                            onPodcastSaveAll = callbacks.onEpisodesSaveAll,
                                            modifier = Modifier.fillMaxSize(),
                                        )

                                        AppStackPage.LibraryAddPodcast -> {
                                            val importedPodcasts = models.podcastsState.podcasts
                                            AddPodcastScreen(
                                                uiState = models.addPodcastState,
                                                importedPodcasts = importedPodcasts,
                                                currentEpisodeId = currentPlaybackEpisodeId,
                                                listState = addPodcastListState,
                                                contentPadding = contentPadding,
                                                onUrlChange = callbacks.onAddPodcastUrlChange,
                                                onYouTubeLinkedAudioUrlChange = callbacks.onYouTubeLinkedAudioUrlChange,
                                                onLoadDiscoveredRssFeed = callbacks.onLoadDiscoveredRssFeed,
                                                onInspectUrl = callbacks.onInspectPodcastUrl,
                                                onCaptionLanguageSelected = callbacks.onAddPodcastCaptionLanguageSelected,
                                                onYouTubeAudioTrackSelected = callbacks.onYouTubeAudioTrackSelected,
                                                onAddPreviewToLibrary = callbacks.onAddPreviewToLibrary,
                                                onPlayRssEpisode = callbacks.onPlayPreviewRssEpisode,
                                                onPlayDirectAudio = callbacks.onPlayPreviewDirectAudio,
                                                onOpenYouTubeUrl = callbacks.onOpenYouTubeUrl,
                                                onOpenPodcastDetails = { callbacks.onIntent(AppIntent.OpenPodcastDetails(it)) },
                                                onDeleteImportedPodcast = callbacks.onDeleteImportedPodcast,
                                                onOpenCaptionSettings = {
                                                    callbacks.onIntent(AppIntent.OpenPage(AppStackPage.SettingsLyricsCaptions))
                                                },
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }

                                        AppStackPage.LibraryEpisodes -> EpisodesScreen(
                                            uiState = models.episodesState,
                                            currentEpisodeId = currentPlaybackEpisodeId,
                                            isPlaying = isPlayingNow,
                                            playbackQueue = models.playbackQueue,
                                            hazeState = hazeState,
                                            listState = episodesListState,
                                            contentPadding = contentPadding,
                                            onFilterQueryChange = callbacks.onEpisodesFilterQuery,
                                            onPlayAll = { shuffle ->
                                                callbacks.onPlayEpisodes(models.episodesState.episodes.map { it.id }, shuffle)
                                            },
                                            onEpisodeClick = { list, epId -> callbacks.onPlayEpisode(epId, list.map { it.id }, false) },
                                            onEpisodePlayNext = { callbacks.onEpisodePlayNext(it.id) },
                                            onEpisodeAddToQueue = { callbacks.onEpisodeAddToQueue(it.id) },
                                            onEpisodeSaveToggle = { ep, saved -> callbacks.onEpisodeSaveToggle(ep.id, saved) },
                                            onEpisodeDownloadToggle = { ep, dl -> callbacks.onEpisodeDownloadToggle(ep.id, dl) },
                                            onEpisodeGoToPodcast = { callbacks.onIntent(AppIntent.OpenPodcastDetails(it.podcastId)) },
                                            onEpisodeGoToHost = { callbacks.onIntent(AppIntent.OpenHostDetails(it.hostId)) },
                                            onEpisodeContextBottomSheet = { episodeContextSheet = it },
                                            modifier = Modifier.fillMaxSize(),
                                        )

                                        AppStackPage.LibraryPodcasts -> PodcastsScreen(
                                            uiState = models.podcastsState,
                                            currentEpisodeId = currentPlaybackEpisodeId,
                                            isPlaying = isPlayingNow,
                                            playbackQueue = models.playbackQueue,
                                            hazeState = hazeState,
                                            listState = podcastsListState,
                                            contentPadding = contentPadding,
                                            onFilterQueryChange = callbacks.onPodcastsFilterQuery,
                                            onPodcastClick = { callbacks.onIntent(AppIntent.OpenPodcastDetails(it)) },
                                            onAddPodcastClick = { callbacks.onIntent(AppIntent.OpenPage(AppStackPage.LibraryAddPodcast)) },
                                            onPlayAll = { shuffle ->
                                                val allIds = models.podcastsState.podcastEpisodes.map { it.id }
                                                if (allIds.isNotEmpty()) callbacks.onPlayEpisodes(allIds, shuffle)
                                            },
                                            onPodcastPlay = callbacks.onPlayEpisodes,
                                            onPodcastToggleSubscribe = callbacks.onPodcastSubscribeToggle,
                                            onPodcastPlayNext = callbacks.onEpisodesPlayNext,
                                            onPodcastAddToQueue = callbacks.onEpisodesAddToQueue,
                                            onPodcastSaveAll = callbacks.onEpisodesSaveAll,
                                            onEpisodeClick = { list, epId -> callbacks.onPlayEpisode(epId, list.map { it.id }, false) },
                                            onEpisodePlayNext = { callbacks.onEpisodePlayNext(it.id) },
                                            onEpisodeAddToQueue = { callbacks.onEpisodeAddToQueue(it.id) },
                                            onEpisodeSaveToggle = { ep, saved -> callbacks.onEpisodeSaveToggle(ep.id, saved) },
                                            onEpisodeDownloadToggle = { ep, dl -> callbacks.onEpisodeDownloadToggle(ep.id, dl) },
                                            onEpisodeGoToPodcast = { callbacks.onIntent(AppIntent.OpenPodcastDetails(it.podcastId)) },
                                            onEpisodeGoToHost = { callbacks.onIntent(AppIntent.OpenHostDetails(it.hostId)) },
                                            onEpisodeContextBottomSheet = { episodeContextSheet = it },
                                            modifier = Modifier.fillMaxSize(),
                                        )

                                        AppStackPage.PodcastDetails -> {
                                            val podcast = models.podcastDetailsState.podcast
                                                ?: remember(models.allPodcasts, uiState.selectedPodcastId) {
                                                    models.allPodcasts.firstOrNull { it.id == uiState.selectedPodcastId }
                                                }
                                            val podcastEpisodes = if (models.podcastDetailsState.isLoaded) {
                                                models.podcastDetailsState.episodes
                                            } else {
                                                remember(models.allEpisodes, uiState.selectedPodcastId) {
                                                    models.allEpisodes.filter { it.podcastId == uiState.selectedPodcastId }
                                                        .sortedByDescending { it.episodeNumber }
                                                }
                                            }
                                            PodcastDetailsScreen(
                                                podcast = podcast,
                                                episodes = podcastEpisodes,
                                                currentEpisodeId = currentPlaybackEpisodeId,
                                                playbackQueue = models.playbackQueue,
                                                hazeState = hazeState,
                                                listState = podcastDetailsListState,
                                                contentPadding = contentPadding,
                                                onHeroColorChanged = { detailHeroColor = it },
                                                onPlay = { callbacks.onPlayEpisodes(podcastEpisodes.map { it.id }, false) },
                                                onShuffle = { callbacks.onPlayEpisodes(podcastEpisodes.map { it.id }, true) },
                                                onToggleSubscribe = callbacks.onPodcastSubscribeToggle,
                                                onEpisodeClick = { epId -> callbacks.onPlayEpisode(epId, podcastEpisodes.map { it.id }, false) },
                                                onEpisodePlayNext = callbacks.onEpisodePlayNext,
                                                onEpisodeAddToQueue = callbacks.onEpisodeAddToQueue,
                                                onEpisodeSaveToggle = callbacks.onEpisodeSaveToggle,
                                                onEpisodeDownloadToggle = callbacks.onEpisodeDownloadToggle,
                                                onEpisodeGoToHost = { callbacks.onIntent(AppIntent.OpenHostDetails(it)) },
                                                onPodcastPlayNext = callbacks.onEpisodesPlayNext,
                                                onPodcastAddToQueue = callbacks.onEpisodesAddToQueue,
                                                onPodcastSaveAll = callbacks.onEpisodesSaveAll,
                                                onEpisodeContextBottomSheet = { episodeContextSheet = it },
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }

                                        AppStackPage.LibraryAlbums -> LibraryAlbumsScreen(
                                            uiState = models.libraryAlbumsState,
                                            listState = albumsListState,
                                            contentPadding = contentPadding,
                                            onFilterQueryChange = callbacks.onAlbumsFilterQuery,
                                            onAlbumClick = { callbacks.onIntent(AppIntent.OpenAlbumDetails(it)) },
                                            onLoadMore = callbacks.onAlbumsLoadMore,
                                            modifier = Modifier.fillMaxSize(),
                                        )

                                        AppStackPage.AlbumDetails -> {
                                            val albumTracks = models.albumDetailsState.tracks
                                            AlbumDetailsScreen(
                                                album = models.albumDetailsState.album,
                                                tracks = albumTracks,
                                                isLoaded = models.albumDetailsState.isLoaded,
                                                currentEpisodeId = currentPlaybackEpisodeId,
                                                playbackQueue = models.playbackQueue,
                                                hazeState = hazeState,
                                                listState = albumDetailsListState,
                                                contentPadding = contentPadding,
                                                onHeroColorChanged = { detailHeroColor = it },
                                                onPlay = { callbacks.onPlayEpisodes(albumTracks.map { it.id }, false) },
                                                onShuffle = { callbacks.onPlayEpisodes(albumTracks.map { it.id }, true) },
                                                onTrackClick = { epId -> callbacks.onPlayEpisode(epId, albumTracks.map { it.id }, false) },
                                                onTrackPlayNext = callbacks.onEpisodePlayNext,
                                                onTrackAddToQueue = callbacks.onEpisodeAddToQueue,
                                                onTrackSaveToggle = callbacks.onEpisodeSaveToggle,
                                                onTrackDownloadToggle = callbacks.onEpisodeDownloadToggle,
                                                onTrackGoToHost = { callbacks.onIntent(AppIntent.OpenHostDetails(it)) },
                                                onAlbumPlayNext = callbacks.onEpisodesPlayNext,
                                                onAlbumAddToQueue = callbacks.onEpisodesAddToQueue,
                                                onAlbumSaveAll = callbacks.onEpisodesSaveAll,
                                                onEpisodeContextBottomSheet = { episodeContextSheet = it },
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }

                                        AppStackPage.LibraryHosts -> HostsScreen(
                                            uiState = models.hostsState,
                                            listState = hostsListState,
                                            contentPadding = contentPadding,
                                            onFilterQueryChange = callbacks.onHostsFilterQuery,
                                            onHostClick = { callbacks.onIntent(AppIntent.OpenHostDetails(it)) },
                                            modifier = Modifier.fillMaxSize(),
                                        )

                                        AppStackPage.HostDetails -> {
                                            val host = remember(models.allHosts, uiState.selectedHostId) {
                                                models.allHosts.firstOrNull { it.id == uiState.selectedHostId }
                                            }
                                            val hostPodcasts = remember(models.allPodcasts, uiState.selectedHostId) {
                                                models.allPodcasts.filter { it.hostId == uiState.selectedHostId }
                                            }
                                            val hostEpisodes = remember(models.allEpisodes, uiState.selectedHostId) {
                                                models.allEpisodes.filter { it.hostId == uiState.selectedHostId }
                                                    .sortedByDescending { it.publishedAt }
                                            }
                                            HostDetailsScreen(
                                                host = host,
                                                podcasts = hostPodcasts,
                                                episodes = hostEpisodes,
                                                listState = hostDetailsListState,
                                                contentPadding = contentPadding,
                                                hazeState = hazeState,
                                                onHeroColorChanged = { detailHeroColor = it },
                                                onPlay = { callbacks.onPlayEpisodes(hostEpisodes.map { it.id }, false) },
                                                onShuffle = { callbacks.onPlayEpisodes(hostEpisodes.map { it.id }, true) },
                                                onPlayNext = callbacks.onEpisodesPlayNext,
                                                onAddToQueue = callbacks.onEpisodesAddToQueue,
                                                onEpisodeContextBottomSheet = { episodeContextSheet = it },
                                                onPodcastClick = { callbacks.onIntent(AppIntent.OpenPodcastDetails(it)) },
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }

                                        AppStackPage.LibraryCategories -> CategoriesScreen(
                                            uiState = models.categoriesState,
                                            listState = categoriesListState,
                                            contentPadding = contentPadding,
                                            onFilterQueryChange = callbacks.onCategoriesFilterQuery,
                                            onCategoryClick = { callbacks.onIntent(AppIntent.OpenCategoryDetails(it)) },
                                            modifier = Modifier.fillMaxSize(),
                                        )

                                        AppStackPage.CategoryDetails -> {
                                            val category = remember(models.allCategories, uiState.selectedCategoryId) {
                                                models.allCategories.firstOrNull { it.id == uiState.selectedCategoryId }
                                            }
                                            val catPodcasts = remember(models.allPodcasts, uiState.selectedCategoryId) {
                                                models.allPodcasts.filter { it.categoryId == uiState.selectedCategoryId }
                                            }
                                            val catEpisodes = remember(models.allEpisodes, uiState.selectedCategoryId) {
                                                models.allEpisodes.filter { it.categoryId == uiState.selectedCategoryId }
                                                    .sortedByDescending { it.publishedAt }
                                            }
                                            CategoryDetailsScreen(
                                                category = category,
                                                podcasts = catPodcasts,
                                                episodes = catEpisodes,
                                                listState = categoryDetailsListState,
                                                contentPadding = contentPadding,
                                                hazeState = hazeState,
                                                onPlay = { callbacks.onPlayEpisodes(catEpisodes.map { it.id }, false) },
                                                onShuffle = { callbacks.onPlayEpisodes(catEpisodes.map { it.id }, true) },
                                                onPlayNext = callbacks.onEpisodesPlayNext,
                                                onAddToQueue = callbacks.onEpisodesAddToQueue,
                                                onEpisodeContextBottomSheet = { episodeContextSheet = it },
                                                onPodcastClick = { callbacks.onIntent(AppIntent.OpenPodcastDetails(it)) },
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }

                                        AppStackPage.LibraryCollections -> CollectionsScreen(
                                            uiState = models.collectionsState,
                                            hazeState = hazeState,
                                            listState = collectionsListState,
                                            contentPadding = contentPadding,
                                            onCollectionClick = { callbacks.onIntent(AppIntent.OpenCollectionDetails(it)) },
                                            onPlayNext = callbacks.onEpisodesPlayNext,
                                            onAddToQueue = callbacks.onEpisodesAddToQueue,
                                            onRenameCollection = callbacks.onRenameCollection,
                                            onDeleteCollection = callbacks.onDeleteCollection,
                                            modifier = Modifier.fillMaxSize(),
                                        )

                                        AppStackPage.CollectionDetails -> {
                                            val model = remember(models.collectionsState.items, uiState.selectedCollectionId) {
                                                models.collectionsState.items.firstOrNull { it.collection.id == uiState.selectedCollectionId }
                                            }
                                            CollectionDetailsScreen(
                                                model = model,
                                                currentEpisodeId = currentPlaybackEpisodeId,
                                                isPlaying = isPlayingNow,
                                                playbackQueue = models.playbackQueue,
                                                hazeState = hazeState,
                                                contentPadding = contentPadding,
                                                onHeroColorChanged = { detailHeroColor = it },
                                                onPlay = { model?.let { callbacks.onPlayEpisodes(it.episodes.map { e -> e.id }, false) } },
                                                onShuffle = { model?.let { callbacks.onPlayEpisodes(it.episodes.map { e -> e.id }, true) } },
                                                onEpisodeClick = { epId ->
                                                    model?.let { callbacks.onPlayEpisode(epId, it.episodes.map { e -> e.id }, false) }
                                                },
                                                onEpisodePlayNext = callbacks.onEpisodePlayNext,
                                                onEpisodeAddToQueue = callbacks.onEpisodeAddToQueue,
                                                onEpisodeSaveToggle = callbacks.onEpisodeSaveToggle,
                                                onEpisodeDownloadToggle = callbacks.onEpisodeDownloadToggle,
                                                onEpisodeGoToPodcast = { callbacks.onIntent(AppIntent.OpenPodcastDetails(it)) },
                                                onEpisodeGoToHost = { callbacks.onIntent(AppIntent.OpenHostDetails(it)) },
                                                onRemoveFromCollection = { epId ->
                                                    uiState.selectedCollectionId?.let { colId ->
                                                        callbacks.onCollectionMembershipChange(colId, listOf(epId), false)
                                                    }
                                                },
                                                onReorderEpisodes = { ordered ->
                                                    uiState.selectedCollectionId?.let { colId ->
                                                        callbacks.onReorderCollectionEpisodes(colId, ordered)
                                                    }
                                                },
                                                onCollectionPlayNext = callbacks.onEpisodesPlayNext,
                                                onCollectionAddToQueue = callbacks.onEpisodesAddToQueue,
                                                onCollectionRename = callbacks.onRenameCollection,
                                                onCollectionDelete = { colId ->
                                                    callbacks.onDeleteCollection(colId)
                                                    callbacks.onIntent(AppIntent.NavigateBack)
                                                },
                                                onEpisodeContextBottomSheet = { episodeContextSheet = it },
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }

                                        AppStackPage.LibrarySavedEpisodes -> {
                                            val saved = remember(models.allEpisodes) {
                                                models.allEpisodes.filter { it.isSaved }.sortedByDescending { it.publishedAt }
                                            }
                                            SavedEpisodesScreen(
                                                episodes = saved,
                                                currentEpisodeId = currentPlaybackEpisodeId,
                                                isPlaying = isPlayingNow,
                                                playbackQueue = models.playbackQueue,
                                                hazeState = hazeState,
                                                listState = savedListState,
                                                contentPadding = contentPadding,
                                                onPlayAll = { shuffle -> callbacks.onPlayEpisodes(saved.map { it.id }, shuffle) },
                                                onEpisodeClick = { list, epId -> callbacks.onPlayEpisode(epId, list.map { it.id }, false) },
                                                onEpisodePlayNext = { callbacks.onEpisodePlayNext(it.id) },
                                                onEpisodeAddToQueue = { callbacks.onEpisodeAddToQueue(it.id) },
                                                onEpisodeSaveToggle = { ep, isSaved -> callbacks.onEpisodeSaveToggle(ep.id, isSaved) },
                                                onEpisodeDownloadToggle = { ep, dl -> callbacks.onEpisodeDownloadToggle(ep.id, dl) },
                                                onEpisodeGoToPodcast = { callbacks.onIntent(AppIntent.OpenPodcastDetails(it.podcastId)) },
                                                onEpisodeGoToHost = { callbacks.onIntent(AppIntent.OpenHostDetails(it.hostId)) },
                                                onEpisodeContextBottomSheet = { episodeContextSheet = it },
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }

                                        AppStackPage.LibraryDownloads -> {
                                            val downloaded = remember(models.allEpisodes) {
                                                models.allEpisodes.filter { it.isDownloaded }.sortedByDescending { it.publishedAt }
                                            }
                                            DownloadsScreen(
                                                downloadedEpisodes = downloaded,
                                                currentEpisodeId = currentPlaybackEpisodeId,
                                                isPlaying = isPlayingNow,
                                                playbackQueue = models.playbackQueue,
                                                hazeState = hazeState,
                                                listState = downloadsListState,
                                                contentPadding = contentPadding,
                                                onPlayAll = { shuffle -> callbacks.onPlayEpisodes(downloaded.map { it.id }, shuffle) },
                                                onEpisodeClick = { list, epId -> callbacks.onPlayEpisode(epId, list.map { it.id }, false) },
                                                onEpisodePlayNext = { callbacks.onEpisodePlayNext(it.id) },
                                                onEpisodeAddToQueue = { callbacks.onEpisodeAddToQueue(it.id) },
                                                onEpisodeSaveToggle = { ep, saved -> callbacks.onEpisodeSaveToggle(ep.id, saved) },
                                                onEpisodeDownloadToggle = { ep, dl -> callbacks.onEpisodeDownloadToggle(ep.id, dl) },
                                                onEpisodeGoToPodcast = { callbacks.onIntent(AppIntent.OpenPodcastDetails(it.podcastId)) },
                                                onEpisodeGoToHost = { callbacks.onIntent(AppIntent.OpenHostDetails(it.hostId)) },
                                                onEpisodeContextBottomSheet = { episodeContextSheet = it },
                                                onRestoreDemoDownloads = callbacks.onRestoreDemoDownloads,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }

                                        AppStackPage.LibraryHistory -> HistoryScreen(
                                            entries = models.listeningHistory,
                                            episodesById = episodesById,
                                            currentEpisodeId = currentPlaybackEpisodeId,
                                            isPlaying = isPlayingNow,
                                            listState = historyListState,
                                            contentPadding = contentPadding,
                                            onPlayEpisode = { epId ->
                                                callbacks.onPlayEpisode(epId, models.listeningHistory.map { it.episodeId }.distinct(), false)
                                            },
                                            modifier = Modifier.fillMaxSize(),
                                        )

                                        else -> {
                                            val recentEpisodes = remember(models.allEpisodes) {
                                                models.allEpisodes.sortedByDescending { it.publishedAt }.take(12)
                                            }
                                            LibraryScreen(
                                                recentEpisodes = recentEpisodes,
                                                playbackQueue = models.playbackQueue,
                                                hazeState = hazeState,
                                                listState = libraryListState,
                                                contentPadding = contentPadding,
                                                onSearchSelected = { callbacks.onIntent(AppIntent.OpenPage(AppStackPage.LibrarySearch)) },
                                                onAddPodcastSelected = { callbacks.onIntent(AppIntent.OpenPage(AppStackPage.LibraryAddPodcast)) },
                                                onPodcastsSelected = { callbacks.onIntent(AppIntent.OpenPage(AppStackPage.LibraryPodcasts)) },
                                                onAlbumsSelected = { callbacks.onIntent(AppIntent.OpenPage(AppStackPage.LibraryAlbums)) },
                                                onEpisodesSelected = { callbacks.onIntent(AppIntent.OpenPage(AppStackPage.LibraryEpisodes)) },
                                                onHostsSelected = { callbacks.onIntent(AppIntent.OpenPage(AppStackPage.LibraryHosts)) },
                                                onCategoriesSelected = { callbacks.onIntent(AppIntent.OpenPage(AppStackPage.LibraryCategories)) },
                                                onCollectionsSelected = { callbacks.onIntent(AppIntent.OpenPage(AppStackPage.LibraryCollections)) },
                                                onSavedEpisodesSelected = { callbacks.onIntent(AppIntent.OpenPage(AppStackPage.LibrarySavedEpisodes)) },
                                                onDownloadsSelected = { callbacks.onIntent(AppIntent.OpenPage(AppStackPage.LibraryDownloads)) },
                                                onHistorySelected = { callbacks.onIntent(AppIntent.OpenPage(AppStackPage.LibraryHistory)) },
                                                onEpisodeClick = { list, epId -> callbacks.onPlayEpisode(epId, list.map { it.id }, false) },
                                                onEpisodePlayNext = { callbacks.onEpisodePlayNext(it.id) },
                                                onEpisodeAddToQueue = { callbacks.onEpisodeAddToQueue(it.id) },
                                                onEpisodeSaveToggle = { ep, saved -> callbacks.onEpisodeSaveToggle(ep.id, saved) },
                                                onEpisodeDownloadToggle = { ep, dl -> callbacks.onEpisodeDownloadToggle(ep.id, dl) },
                                                onEpisodeGoToPodcast = { callbacks.onIntent(AppIntent.OpenPodcastDetails(it.podcastId)) },
                                                onEpisodeGoToHost = { callbacks.onIntent(AppIntent.OpenHostDetails(it.hostId)) },
                                                onEpisodeContextBottomSheet = { episodeContextSheet = it },
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                DetailHeroHeaderGradient(
                    color = detailHeroColor,
                    visible = currentPage.isDetailHeroPage(),
                )

                StackPageHeader(
                    title = pageTitle,
                    hazeState = hazeState,
                    isContentScrolled = showHeaderBlur,
                    onBackClick = if (showBack) { { callbacks.onIntent(AppIntent.NavigateBack) } } else null,
                    hasActions = showSortAction || showAddCollectionAction || showClearHistoryAction,
                    animateChanges = animateHeaderChanges,
                    titleStackKey = "${uiState.selectedDestination.name}:${currentPage.name}",
                    isForward = isForwardHeaderTransition,
                    backGlassTintAlpha = if (currentPage.isDetailHeroPage()) 0.08f else null,
                ) {
                    when (currentPage) {
                        AppStackPage.LibraryCollections -> PodcastyGlassIconButton(
                            hazeState = hazeState,
                            symbol = MaterialSymbols.Add,
                            label = stringResource(R.string.collection_create),
                            onClick = { showCreateCollectionSheet = true },
                        )
                        AppStackPage.LibraryHistory -> if (models.listeningHistory.isNotEmpty()) {
                            PodcastyGlassIconButton(
                                hazeState = hazeState,
                                symbol = MaterialSymbols.Delete,
                                label = stringResource(R.string.history_clear),
                                onClick = { showClearHistoryDialog = true },
                            )
                        }
                        AppStackPage.LibraryEpisodes -> LibrarySortHeaderButton(
                            hazeState = hazeState,
                            options = listOf(
                                LibrarySortOption(EpisodeSortOption.DatePublished, R.string.sort_date_added),
                                LibrarySortOption(EpisodeSortOption.Title, R.string.sort_name),
                                LibrarySortOption(EpisodeSortOption.Podcast, R.string.sort_podcast),
                                LibrarySortOption(EpisodeSortOption.Duration, R.string.sort_duration),
                                LibrarySortOption(EpisodeSortOption.PlayCount, R.string.sort_play_count),
                            ),
                            selectedOption = models.episodesState.sortOption,
                            sortOrder = models.episodesState.sortOrder,
                            onSortOptionSelected = callbacks.onEpisodesSortOption,
                            onToggleSortOrder = callbacks.onEpisodesToggleSortOrder,
                        )
                        AppStackPage.LibraryPodcasts -> LibrarySortHeaderButton(
                            hazeState = hazeState,
                            options = listOf(
                                LibrarySortOption(PodcastSortOption.Title, R.string.sort_name),
                                LibrarySortOption(PodcastSortOption.Host, R.string.sort_host),
                                LibrarySortOption(PodcastSortOption.DateAdded, R.string.sort_date_added),
                            ),
                            selectedOption = models.podcastsState.sortOption,
                            sortOrder = models.podcastsState.sortOrder,
                            onSortOptionSelected = callbacks.onPodcastsSortOption,
                            onToggleSortOrder = callbacks.onPodcastsToggleSortOrder,
                            layoutMode = models.podcastsState.layoutMode,
                            onLayoutModeSelected = callbacks.onPodcastsLayoutMode,
                        )
                        AppStackPage.LibraryAlbums -> LibrarySortHeaderButton(
                            hazeState = hazeState,
                            options = listOf(
                                LibrarySortOption(AlbumSortOption.Title, R.string.sort_name),
                                LibrarySortOption(AlbumSortOption.Artist, R.string.sort_artist),
                                LibrarySortOption(AlbumSortOption.TrackCount, R.string.sort_track_count),
                            ),
                            selectedOption = models.libraryAlbumsState.sortOption,
                            sortOrder = models.libraryAlbumsState.sortOrder,
                            onSortOptionSelected = callbacks.onAlbumsSortOption,
                            onToggleSortOrder = callbacks.onAlbumsToggleSortOrder,
                            layoutMode = models.libraryAlbumsState.layoutMode,
                            onLayoutModeSelected = callbacks.onAlbumsLayoutMode,
                        )
                        AppStackPage.LibraryHosts -> LibrarySortHeaderButton(
                            hazeState = hazeState,
                            options = listOf(
                                LibrarySortOption(HostSortOption.Name, R.string.sort_name),
                                LibrarySortOption(HostSortOption.DateAdded, R.string.sort_date_added),
                            ),
                            selectedOption = models.hostsState.sortOption,
                            sortOrder = models.hostsState.sortOrder,
                            onSortOptionSelected = callbacks.onHostsSortOption,
                            onToggleSortOrder = callbacks.onHostsToggleSortOrder,
                        )
                        AppStackPage.LibraryCategories -> LibrarySortHeaderButton(
                            hazeState = hazeState,
                            options = listOf(
                                LibrarySortOption(CategorySortOption.Name, R.string.sort_name),
                                LibrarySortOption(CategorySortOption.DateAdded, R.string.sort_date_added),
                            ),
                            selectedOption = models.categoriesState.sortOption,
                            sortOrder = models.categoriesState.sortOrder,
                            onSortOptionSelected = callbacks.onCategoriesSortOption,
                            onToggleSortOrder = callbacks.onCategoriesToggleSortOrder,
                        )
                        else -> Unit
                    }
                }

                NavigationChrome(
                    selectedDestination = uiState.selectedDestination,
                    playbackState = models.playbackState,
                    playbackPositionFlow = models.playbackPositionFlow,
                    captionSyncFlow = models.captionSyncFlow,
                    showMiniPlayerLyrics = models.userSettings.showMiniPlayerLyrics,
                    playbackQueue = models.playbackQueue,
                    hazeState = hazeState,
                    compact = showsMiniPlayer && isNavigationCompact,
                    onExpandClick = {
                        isNavigationCompact = false
                        navigationScrollAccumulator.reset()
                    },
                    onDestinationSelected = { dest ->
                        if (dest == uiState.selectedDestination) {
                            coroutineScope.launch {
                                when (dest) {
                                    AppDestination.Home -> homeListState.animateScrollToItem(0)
                                    AppDestination.Folder -> folderListState.animateScrollToItem(0)
                                    AppDestination.Insight -> insightListState.animateScrollToItem(0)
                                    AppDestination.Library -> if (currentPage == AppStackPage.Root) libraryListState.animateScrollToItem(0)
                                    AppDestination.Settings -> Unit
                                }
                            }
                        }
                        callbacks.onIntent(AppIntent.SelectDestination(dest))
                    },
                    onPreviousClick = callbacks.onPrevious,
                    onPlayPauseClick = callbacks.onPlayPause,
                    onNextClick = callbacks.onNext,
                    onMiniPlayerDismiss = {
                        isNavigationCompact = false
                        navigationScrollAccumulator.reset()
                        callbacks.onMiniPlayerDismiss()
                    },
                    onOpenFullScreenPlayer = {
                        isFullScreenPlayerOpeningFromSwipe = false
                        setFullScreenVisible(true)
                    },
                    onOpenFullScreenLyrics = {
                        isFullScreenPlayerOpeningFromSwipe = false
                        setFullScreenVisible(true)
                        openLyricsPanelRequestId += 1L
                    },
                    onFullScreenPlayerDrag = { progress ->
                        isFullScreenPlayerDragging = true
                        fullScreenPlayerDragProgress = progress
                    },
                    onFullScreenPlayerDragEnd = { shouldOpen ->
                        isFullScreenPlayerDragging = false
                        fullScreenPlayerDragProgress = 0f
                        isFullScreenPlayerOpeningFromSwipe = shouldOpen
                        setFullScreenVisible(shouldOpen)
                    },
                    contextEpisode = currentPlaybackEpisode,
                    onEpisodeContextBottomSheet = { episodeContextSheet = it },
                    onEpisodeSaveToggle = callbacks.onEpisodeSaveToggle,
                    onEpisodeDownloadToggle = callbacks.onEpisodeDownloadToggle,
                    onEpisodePlayNext = callbacks.onEpisodePlayNext,
                    onEpisodeAddToQueue = callbacks.onEpisodeAddToQueue,
                    onEpisodeGoToPodcast = { callbacks.onIntent(AppIntent.OpenPodcastDetails(it)) },
                    onEpisodeGoToHost = { callbacks.onIntent(AppIntent.OpenHostDetails(it)) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(start = 20.dp, end = 20.dp, bottom = FloatingNavigationBottomMargin),
                )

                FullScreenPlayer(
                    visible = isFullScreenPlayerVisible,
                    dragProgress = fullScreenPlayerDragProgress,
                    isDragging = isFullScreenPlayerDragging,
                    openingFromMiniPlayerSwipe = isFullScreenPlayerOpeningFromSwipe,
                    playbackState = models.playbackState,
                    playbackPositionFlow = models.playbackPositionFlow,
                    currentEpisode = currentPlaybackEpisode,
                    queue = models.playbackQueue,
                    queueEpisodes = models.allEpisodes,
                    sleepTimer = models.sleepTimer,
                    skipBackwardSeconds = models.userSettings.skipBackwardSeconds,
                    skipForwardSeconds = models.userSettings.skipForwardSeconds,
                    livingArtworkBackground = models.userSettings.livingArtworkBackground,
                    showSpeedBadge = models.userSettings.showSpeedBadge,
                    secondaryControls = models.userSettings.playerSecondaryControls,
                    keepScreenOnForTranscript = models.userSettings.keepScreenOnForTranscript,
                    autoSyncLyrics = models.userSettings.autoSyncLyrics,
                    autoScrollLyrics = models.userSettings.autoScrollLyrics,
                    captionFontScale = models.userSettings.captionFontSizeScale,
                    showLyricsTranslation = models.userSettings.showLyricsTranslation,
                    openLyricsRequestId = openLyricsPanelRequestId,
                    onCaptionFontScaleSelected = callbacks.onCaptionFontScaleSelected,
                    onToggleLyricsTranslation = callbacks.onShowLyricsTranslationChanged,
                    sleepFadeOutSeconds = models.userSettings.sleepFadeOutSeconds,
                    activeTranscriptDocument = models.activeTranscriptDocument,
                    availableTranscriptDocuments = models.availableTranscriptDocuments,
                    isExtractingTranscript = (models.addPodcastState.preview as? com.podcasty.app.features.addpodcast.AddPodcastPreview.YouTubeVideo)
                        ?.let { it.isExtractingTranscript || it.isDownloadingAudio } == true,
                    liveCaptionState = models.liveCaptionState,
                    onSelectAsrLanguage = callbacks.onSelectAsrLanguage,
                    onRetryLiveCaptions = callbacks.onRetryLiveCaptions,
                    onOpenCaptionSettings = {
                        callbacks.onIntent(AppIntent.OpenPage(AppStackPage.SettingsLyricsCaptions))
                    },
                    volume = models.systemVolume,
                    onSeek = callbacks.onSeek,
                    onVolumeChange = callbacks.onSystemVolumeChange,
                    onPrevious = callbacks.onPrevious,
                    onSkipBackward = callbacks.onSkipBackward,
                    onPlayPause = callbacks.onPlayPause,
                    onSkipForward = callbacks.onSkipForward,
                    onNext = callbacks.onNext,
                    onPlaybackSpeedSelected = callbacks.onPlaybackSpeedSelected,
                    onSleepTimerConfigured = callbacks.onSleepTimerConfigured,
                    onSleepFadeOutChanged = callbacks.onSleepFadeOutChanged,
                    onSelectTranscriptLanguage = callbacks.onSelectTranscriptLanguage,
                    onImportTranscriptForEpisode = callbacks.onImportTranscriptForEpisode,
                    onOpenYouTubeForEpisode = callbacks.onOpenYouTubeForEpisode,
                    onLinkAudioForEpisode = callbacks.onLinkAudioForEpisode,
                    onQueueEpisodeSelected = callbacks.onQueueEpisodeSelected,
                    onQueueReordered = callbacks.onQueueReordered,
                    onQueueEpisodeRemoved = callbacks.onQueueEpisodeRemoved,
                    onShuffleChange = callbacks.onShuffleChange,
                    onRepeatModeChange = callbacks.onRepeatModeChange,
                    onOpenQueueManager = callbacks.onOpenQueueManager,
                    isSaved = currentPlaybackEpisode?.isSaved == true,
                    onSaveToggle = callbacks.onEpisodeSaveToggle,
                    onDownloadToggle = callbacks.onEpisodeDownloadToggle,
                    onEpisodePlayNext = callbacks.onEpisodePlayNext,
                    onEpisodeAddToQueue = callbacks.onEpisodeAddToQueue,
                    onEpisodeGoToPodcast = { callbacks.onIntent(AppIntent.OpenPodcastDetails(it)) },
                    onEpisodeGoToHost = { callbacks.onIntent(AppIntent.OpenHostDetails(it)) },
                    onOpenEqualizer = callbacks.onOpenEqualizer,
                    onEpisodeContextBottomSheet = { episodeContextSheet = it },
                    onCloseFullscreenThen = ::closeFullScreenPlayerThen,
                    onOpenMediaOutputSwitcher = callbacks.onOpenMediaOutputSwitcher,
                    onDismiss = {
                        setFullScreenVisible(false)
                        isFullScreenPlayerOpeningFromSwipe = false
                    },
                    onDismissAnimationFinished = {
                        pendingFullScreenPlayerAction?.let { action ->
                            pendingFullScreenPlayerAction = null
                            action()
                        }
                    },
                    hazeState = hazeState,
                )

                episodeContextSheet?.let { request ->
                    when (request) {
                        is EpisodeContextBottomSheetRequest.TagEditor -> {
                            LaunchedEffect(request) {
                                episodeContextSheet = null
                                callbacks.onOpenTagEditorForEpisodes(request.episodes)
                            }
                        }
                        is EpisodeContextBottomSheetRequest.ImportTranscript -> {
                            LaunchedEffect(request) {
                                episodeContextSheet = null
                                callbacks.onImportTranscriptForEpisode(request.episode)
                            }
                        }
                        is EpisodeContextBottomSheetRequest.LinkAudioSource -> {
                            LaunchedEffect(request) {
                                episodeContextSheet = null
                                callbacks.onLinkAudioForEpisode(request.episode)
                            }
                        }
                        is EpisodeContextBottomSheetRequest.OpenYouTube -> {
                            LaunchedEffect(request) {
                                episodeContextSheet = null
                                callbacks.onOpenYouTubeForEpisode(request.episode)
                            }
                        }
                        is EpisodeContextBottomSheetRequest.QueuePicker,
                        is EpisodeContextBottomSheetRequest.FileDetails,
                        is EpisodeContextBottomSheetRequest.RenameFile,
                        is EpisodeContextBottomSheetRequest.MoveFile,
                        is EpisodeContextBottomSheetRequest.CopyFile,
                        is EpisodeContextBottomSheetRequest.DeleteFiles,
                        is EpisodeContextBottomSheetRequest.ShareFile,
                        is EpisodeContextBottomSheetRequest.ViewLyrics,
                        is EpisodeContextBottomSheetRequest.OpenContainingFolder,
                        -> {
                            LaunchedEffect(request) {
                                episodeContextSheet = null
                                if (request is EpisodeContextBottomSheetRequest.OpenContainingFolder) {
                                    callbacks.onIntent(AppIntent.SelectDestination(AppDestination.Folder))
                                }
                                callbacks.onHandleFileOrQueueRequest(request)
                            }
                        }
                        else -> EpisodeContextBottomSheet(
                            request = request,
                            collections = models.allCollections,
                            onDismiss = { episodeContextSheet = null },
                            onPlayEpisode = { ep -> callbacks.onPlayEpisode(ep.id, listOf(ep.id), false) },
                            onToggleSave = { ep, saved -> callbacks.onEpisodeSaveToggle(ep.id, saved) },
                            onToggleDownload = { ep, dl -> callbacks.onEpisodeDownloadToggle(ep.id, dl) },
                            onCollectionMembershipChange = callbacks.onCollectionMembershipChange,
                            onCreateCollectionRequested = { epIds ->
                                episodeContextSheet = null
                                createCollectionForEpisodes = epIds
                                showCreateCollectionSheet = true
                            },
                        )
                    }
                }

                if (showCreateCollectionSheet) {
                    CreateCollectionBottomSheet(
                        onDismiss = {
                            showCreateCollectionSheet = false
                            createCollectionForEpisodes = emptyList()
                        },
                        onCreate = { name ->
                            showCreateCollectionSheet = false
                            callbacks.onCreateCollection(name, createCollectionForEpisodes)
                            createCollectionForEpisodes = emptyList()
                        },
                    )
                }

                if (showClearHistoryDialog) {
                    PodcastyDialog(
                        title = stringResource(R.string.history_clear_confirm_title),
                        description = stringResource(R.string.history_clear_confirm_description),
                        dismissLabel = stringResource(R.string.cancel),
                        onDismiss = { showClearHistoryDialog = false },
                        confirmLabel = stringResource(R.string.history_clear),
                        onConfirm = {
                            callbacks.onClearListeningHistory()
                            showClearHistoryDialog = false
                        },
                        confirmVariant = PodcastyPillButtonVariant.Destructive,
                    )
                }
            }
        }

        BackHandler(enabled = isFullScreenPlayerVisible) {
            setFullScreenVisible(false)
            isFullScreenPlayerOpeningFromSwipe = false
        }
    }
}

@Composable
private fun DetailHeroHeaderGradient(color: Color?, visible: Boolean) {
    val fade by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(1500, easing = FastOutSlowInEasing),
        label = "detail-header-glass-colour-fade",
    )
    color?.takeIf { fade > 0.01f }?.let {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(
                    Brush.verticalGradient(
                        0f to it.copy(alpha = 0.36f * fade),
                        1f to it.copy(alpha = 0f),
                    ),
                ),
        )
    }
}
