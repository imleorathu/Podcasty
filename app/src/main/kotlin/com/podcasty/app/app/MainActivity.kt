/*
 * Podcasty — Android Podcast & Music Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.app

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.media.AudioManager
import android.media.MediaRouter2
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.podcasty.app.R
import com.podcasty.app.asr.LiveCaptionUiState
import com.podcasty.app.data.PodcastUserSettings
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.MediaSourceType
import com.podcasty.app.domain.StorageProviderType
import com.podcasty.app.domain.itemOrNull
import com.podcasty.app.features.addpodcast.AddPodcastViewModel
import com.podcasty.app.features.albums.LibraryAlbumsViewModel
import com.podcasty.app.features.categories.CategoriesViewModel
import com.podcasty.app.features.collections.CollectionsViewModel
import com.podcasty.app.features.episodes.EpisodesViewModel
import com.podcasty.app.features.folder.DeleteFilesConfirmDialog
import com.podcasty.app.features.folder.DestinationFolderPickerSheet
import com.podcasty.app.features.folder.EmbeddedLyricsBottomSheet
import com.podcasty.app.features.folder.ExcludedFoldersBottomSheet
import com.podcasty.app.features.folder.FileDetailsBottomSheet
import com.podcasty.app.features.folder.MultiQueueManagerBottomSheet
import com.podcasty.app.features.folder.MusicBrowserViewModel
import com.podcasty.app.features.folder.RenameFileBottomSheet
import com.podcasty.app.features.home.HomeViewModel
import com.podcasty.app.features.hosts.HostsViewModel
import com.podcasty.app.features.insights.InsightViewModel
import com.podcasty.app.features.player.EqualizerBottomSheet
import com.podcasty.app.features.podcast.LinkEpisodeAudioBottomSheet
import com.podcasty.app.features.podcasts.PodcastsViewModel
import com.podcasty.app.features.search.SearchViewModel
import com.podcasty.app.features.tags.RawPcmConfigBottomSheet
import com.podcasty.app.features.tags.TagEditorBottomSheet
import com.podcasty.app.navigation.AppIntent
import com.podcasty.app.navigation.PodcastyApp
import com.podcasty.app.navigation.PodcastyAppCallbacks
import com.podcasty.app.navigation.PodcastyAppModels
import com.podcasty.app.player.PlaybackUiNotice
import com.podcasty.app.podcast.YouTubeAndRssImporter
import com.podcasty.app.storage.FileOperationResult
import com.podcasty.app.storage.LocalAudioFileDetails
import com.podcasty.app.storage.PendingMediaStoreFileAction
import com.podcasty.app.storage.UsbStorageEvent
import com.podcasty.app.tags.EditableAudioTags
import com.podcasty.app.tags.TagSaveResult
import com.podcasty.app.transcript.TranscriptDocument
import com.podcasty.app.transcript.TranscriptSource
import com.podcasty.app.ui.components.EpisodeContextBottomSheetRequest
import com.podcasty.app.ui.components.PodcastyDialog
import com.podcasty.app.ui.components.PodcastyPillButtonVariant
import com.podcasty.app.ui.theme.PodcastyTheme
import com.podcasty.app.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val container: AppContainer
        get() = (application as PodcastyApplication).container

    private var systemMusicVolumeState by mutableFloatStateOf(0.5f)
    private var openFullScreenLyricsRequestId by mutableStateOf(0L)

    // Modal state hosted safely at Activity level for SAF launchers & playback notices
    private var activeNoticeDialog by mutableStateOf<PlaybackUiNotice.Info?>(null)
    private var rawPcmPrompt by mutableStateOf<PlaybackUiNotice.RawPcmConfigRequired?>(null)
    private var youtubeHandoffPrompt by mutableStateOf<PlaybackUiNotice.YouTubePlaybackHandoff?>(null)
    private var linkAudioEpisode by mutableStateOf<Episode?>(null)
    private var hasAsrTokenStoredState by mutableStateOf(false)
    private var hasYouTubeApiKeyStoredState by mutableStateOf(false)

    private var tagEditorEpisodes by mutableStateOf<List<Episode>>(emptyList())
    private var tagEditorInitialTags by mutableStateOf(EditableAudioTags())
    private var isSavingTags by mutableStateOf(false)
    private var tagEditorStatus by mutableStateOf<String?>(null)
    private var pendingExportCopy by mutableStateOf<Pair<File, String>?>(null)

    private var pendingFolderIsUsbOtg: Boolean = false
    private var pendingTranscriptEpisode: Episode? = null

    // Offline Music File Manager & Multi-Queue modal state
    private var fileDetailsSheet by mutableStateOf<LocalAudioFileDetails?>(null)
    private var renameFileEpisode by mutableStateOf<Episode?>(null)
    private var transferFileRequest by mutableStateOf<Pair<Episode, Boolean>?>(null) // episode to move?
    private var deleteConfirmEpisodes by mutableStateOf<List<Episode>>(emptyList())
    private var lyricsViewState by mutableStateOf<Triple<Episode, String?, List<TranscriptDocument>>?>(null)
    private var showQueueManagerForIds by mutableStateOf<List<String>?>(null) // null = closed, empty = manage, non-empty = add picker
    private var showExcludedFoldersSheet by mutableStateOf(false)
    private var showEqualizerSheet by mutableStateOf(false)
    private var showDuplicateSheet by mutableStateOf(false)
    private var duplicateResult by mutableStateOf<com.podcasty.app.storage.DuplicateMusicResult?>(null)
    private var duplicateJob: kotlinx.coroutines.Job? = null
    private var pendingConsentAction: PendingMediaStoreFileAction? = null

    private val mediaStoreConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val pending = pendingConsentAction ?: return@registerForActivityResult
            pendingConsentAction = null
            val granted = result.resultCode == RESULT_OK
            lifecycleScope.launch {
                val res = container.musicFileManager.completePendingMediaStoreConsent(pending, granted)
                handleFileOperationResult(res)
            }
        }

    private val pickTransferDestFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val req = transferFileRequest
            transferFileRequest = null
            if (uri == null || req == null) return@registerForActivityResult
            lifecycleScope.launch {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
                val res = container.musicFileManager.copyOrMoveToSafFolder(req.first, uri, "", move = req.second)
                handleFileOperationResult(res)
            }
        }

    private val openFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val settings = container.preferences.settings.first()
            val forceStorage = if (pendingFolderIsUsbOtg) StorageProviderType.UsbOtg else null
            val folder = container.localMusicScanner.registerFolder(
                treeUri = uri,
                makeDefault = !pendingFolderIsUsbOtg || settings.defaultMusicFolderUri == null,
                includeSubfolders = settings.includeSubfolders,
                forceStorageType = forceStorage,
            )
            if (folder.isDefault) {
                container.preferences.setDefaultMusicFolder(folder.treeUri, folder.displayName)
            }
            container.localMusicScanner.startBackgroundScanFolder(
                treeUri = uri,
                includeSubfolders = settings.includeSubfolders,
            ) { result ->
                if (result.lastError != null) {
                    activeNoticeDialog = PlaybackUiNotice.Info("Folder scan notice", result.lastError)
                } else if (result.scannedFiles == 0) {
                    // If SAF folder returned 0 files and MediaStore permission wasn't granted yet, offer or run MediaStore scan
                    val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Manifest.permission.READ_MEDIA_AUDIO
                    } else {
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    }
                    if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
                        container.localMusicScanner.startBackgroundScanMediaStore()
                    } else {
                        runOnUiThread { audioPermissionLauncher.launch(perm) }
                    }
                }
            }
        }
    }

    private val openTranscriptFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val episode = pendingTranscriptEpisode ?: return@registerForActivityResult
        pendingTranscriptEpisode = null
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val fileName = resolveFileName(uri)
            val rawText = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        com.podcasty.app.transcript.TranscriptParser.decodeBytesToUnicode(input.readBytes())
                    }
                }.getOrNull()
            }
            if (rawText.isNullOrBlank()) {
                activeNoticeDialog = PlaybackUiNotice.Info(
                    "Import failed",
                    "Could not read transcript file. Supported formats: SRT, WebVTT, LRC, Enhanced LRC, Podcast JSON, and plain text.",
                )
                return@launch
            }
            val settings = container.preferences.settings.first()
            val doc = container.repository.saveTranscript(
                episodeId = episode.id,
                rawContent = rawText,
                fileName = fileName,
                language = settings.preferredTranscriptLanguage,
                source = TranscriptSource.AuthorizedImport,
            )
            container.playbackController.refreshTranscriptForCurrentEpisode()
            activeNoticeDialog = PlaybackUiNotice.Info(
                "Transcript imported",
                "Loaded ${doc.cues.size} cues (${doc.format.label}, ${doc.language.uppercase()}) for \"${episode.title}\".",
            )
        }
    }

    private val pickArtworkLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching raw
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= 720 && bounds.outHeight / (sample * 2) >= 720) {
                        sample *= 2
                    }
                    val decoded = android.graphics.BitmapFactory.decodeByteArray(
                        raw,
                        0,
                        raw.size,
                        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
                    ) ?: return@runCatching raw
                    try {
                        val out = java.io.ByteArrayOutputStream()
                        decoded.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
                        out.toByteArray()
                    } finally {
                        decoded.recycle()
                    }
                }.getOrNull()
            }
            if (bytes != null && bytes.isNotEmpty()) {
                tagEditorInitialTags = tagEditorInitialTags.copy(
                    artworkBytes = bytes,
                    artworkMimeType = "image/jpeg",
                    artworkModified = true,
                    removeArtwork = false,
                )
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND || level >= TRIM_MEMORY_RUNNING_LOW) {
            com.podcasty.app.ui.components.clearArtworkMemoryCache()
        }
    }

    private val exportPlaylistBackupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                com.podcasty.app.data.PlaylistBackupManager(this@MainActivity, container.database).exportTo(uri)
                activeNoticeDialog = PlaybackUiNotice.Info("Playlists exported", "Saved playlist references without copying music files.")
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                activeNoticeDialog = PlaybackUiNotice.Info("Export failed", error.localizedMessage ?: "Cannot export playlists.")
            }
        }
    }
    private val restorePlaylistBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val result = com.podcasty.app.data.PlaylistBackupManager(this@MainActivity, container.database).restoreFrom(uri)
                activeNoticeDialog = PlaybackUiNotice.Info("Playlists restored", "Restored ${result.playlists} playlists; skipped ${result.missingTracks} missing tracks. Existing entries were preserved.")
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                activeNoticeDialog = PlaybackUiNotice.Info("Restore failed", error.localizedMessage ?: "Invalid playlist backup.")
            }
        }
    }

    private val exportEditedAudioLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/*")) { targetUri ->
        val pending = pendingExportCopy ?: return@registerForActivityResult
        pendingExportCopy = null
        if (targetUri == null) {
            pending.first.delete()
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val ok = container.audioTagEditor.exportTempFileToUri(pending.first, targetUri)
            activeNoticeDialog = if (ok) {
                PlaybackUiNotice.Info("Edited copy exported", "Saved verified edited copy \"${pending.second}\".")
            } else {
                PlaybackUiNotice.Info("Export failed", "Could not write edited audio file to the chosen destination.")
            }
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            container.localMusicScanner.startBackgroundScanMediaStore()
        } else {
            activeNoticeDialog = PlaybackUiNotice.Info(
                "Permission required",
                "Grant audio media permission to scan MediaStore, or use Select Folder (SAF) which works without storage permissions.",
            )
        }
    }

    private val mainViewModel: MainViewModel by viewModels {
        MainViewModel.Factory(container.preferences)
    }
    private val homeViewModel: HomeViewModel by viewModels {
        HomeViewModel.Factory(container.repository, container.preferences)
    }
    private val insightViewModel: InsightViewModel by viewModels {
        InsightViewModel.Factory(container.repository)
    }
    private val episodesViewModel: EpisodesViewModel by viewModels {
        EpisodesViewModel.Factory(container.repository)
    }
    private val podcastsViewModel: PodcastsViewModel by viewModels {
        PodcastsViewModel.Factory(container.repository, container.preferences)
    }
    private val libraryAlbumsViewModel: LibraryAlbumsViewModel by viewModels {
        LibraryAlbumsViewModel.Factory(container.repository, container.preferences)
    }
    private val hostsViewModel: HostsViewModel by viewModels {
        HostsViewModel.Factory(container.repository)
    }
    private val categoriesViewModel: CategoriesViewModel by viewModels {
        CategoriesViewModel.Factory(container.repository)
    }
    private val collectionsViewModel: CollectionsViewModel by viewModels {
        CollectionsViewModel.Factory(container.repository)
    }
    private val searchViewModel: SearchViewModel by viewModels {
        SearchViewModel.Factory(container.repository, container.preferences)
    }
    private val musicBrowserViewModel: MusicBrowserViewModel by viewModels {
        MusicBrowserViewModel.Factory(container.repository, container.preferences)
    }
    private val addPodcastViewModel: AddPodcastViewModel by viewModels {
        AddPodcastViewModel.Factory(
            container.repository,
            container.preferences,
            container.credentialStore,
            container.youTubeCaptionClient,
            container.rssDiscoveryService,
            container.youTubeMusicDownloadManager,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra(ExtraOpenFullScreenLyrics, false) == true) {
            openFullScreenLyricsRequestId += 1L
            intent.removeExtra(ExtraOpenFullScreenLyrics)
        }
        updateRequestedOrientation(resources.configuration)
        systemMusicVolumeState = currentSystemMusicVolume()
        lifecycleScope.launch {
            val (hasAsr, hasYt) = withContext(Dispatchers.IO) {
                container.credentialStore.hasAsrAuthToken() to container.credentialStore.hasYouTubeApiKey()
            }
            hasAsrTokenStoredState = hasAsr
            hasYouTubeApiKeyStoredState = hasYt
        }
        addPodcastViewModel.onYouTubeReadyToPlayAudio = { episodeId ->
            // Do not interrupt an already-playing playback session when a background download completes
            if (container.playbackController.playbackState.value !is com.podcasty.app.domain.PlaybackState.Playing) {
                container.playbackController.playEpisode(episodeId, listOf(episodeId), false)
            }
        }
        addPodcastViewModel.onYouTubeTranscriptSynced = {
            container.playbackController.refreshTranscriptForCurrentEpisode()
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            lifecycleScope.launch {
                val settings = container.preferences.settings.first()
                if (!settings.hasRequestedNotificationPermission) {
                    container.preferences.markNotificationPermissionRequested()
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NotificationPermissionRequest)
                }
            }
        }

        // Observe playback notices & USB OTG reconnects
        lifecycleScope.launch {
            container.playbackController.uiNotices.collect { notice ->
                when (notice) {
                    is PlaybackUiNotice.Info -> activeNoticeDialog = notice
                    is PlaybackUiNotice.RawPcmConfigRequired -> rawPcmPrompt = notice
                    is PlaybackUiNotice.YouTubePlaybackHandoff -> youtubeHandoffPrompt = notice
                }
            }
        }
        lifecycleScope.launch {
            container.usbStorageMonitor.events.collect { event ->
                when (event) {
                    is UsbStorageEvent.Attached -> {
                        val settings = container.preferences.settings.first()
                        container.scanCoordinator.onUsbStorageAttached(
                            autoRefreshEnabled = settings.autoLibraryRefresh,
                            includeSubfolders = settings.includeSubfolders,
                        )
                    }
                    is UsbStorageEvent.Detached -> {
                        container.scanCoordinator.onUsbStorageDetached()
                    }
                }
            }
        }
        lifecycleScope.launch {
            val settings = container.preferences.settings.first()
            container.scanCoordinator.syncOnAppLaunch(
                autoLibraryRefresh = settings.autoLibraryRefresh,
                includeSubfolders = settings.includeSubfolders,
            )
            container.scanCoordinator.syncArtworkUpgradeIfNeeded(
                currentResolverVersion = settings.artworkResolverVersion,
                includeSubfolders = settings.includeSubfolders,
            ) {
                container.preferences.setArtworkResolverVersion(
                    com.podcasty.app.storage.LocalArtworkResolver.CurrentArtworkResolverVersion,
                )
            }
        }

        setContent {
            val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
            val homeState by homeViewModel.uiState.collectAsStateWithLifecycle()
            val insightState by insightViewModel.uiState.collectAsStateWithLifecycle()
            val episodesState by episodesViewModel.uiState.collectAsStateWithLifecycle()
            val podcastsState by podcastsViewModel.uiState.collectAsStateWithLifecycle()
            val podcastDetailsState by podcastsViewModel.podcastDetailsState.collectAsStateWithLifecycle()
            val libraryAlbumsState by libraryAlbumsViewModel.uiState.collectAsStateWithLifecycle()
            val albumDetailsState by libraryAlbumsViewModel.albumDetailsState.collectAsStateWithLifecycle()
            val hostsState by hostsViewModel.uiState.collectAsStateWithLifecycle()
            val categoriesState by categoriesViewModel.uiState.collectAsStateWithLifecycle()
            val collectionsState by collectionsViewModel.uiState.collectAsStateWithLifecycle()
            val searchState by searchViewModel.uiState.collectAsStateWithLifecycle()
            val musicBrowserState by musicBrowserViewModel.uiState.collectAsStateWithLifecycle()

            val userSettings by container.preferences.settings.collectAsStateWithLifecycle(initialValue = PodcastUserSettings())
            val allEpisodes by container.repository.episodes.collectAsStateWithLifecycle(initialValue = emptyList())
            val allPodcasts by container.repository.podcasts.collectAsStateWithLifecycle(initialValue = emptyList())
            val allHosts by container.repository.hosts.collectAsStateWithLifecycle(initialValue = emptyList())
            val allCategories by container.repository.categories.collectAsStateWithLifecycle(initialValue = emptyList())
            val allCollections by container.repository.collections.collectAsStateWithLifecycle(initialValue = emptyList())
            val listeningHistory by container.repository.listeningHistory.collectAsStateWithLifecycle(initialValue = emptyList())
            val musicFolders by container.repository.musicFolders.collectAsStateWithLifecycle(initialValue = emptyList())
            val scanState by container.localMusicScanner.scanState.collectAsStateWithLifecycle()
            val storageVolumes by container.usbStorageMonitor.removableVolumes.collectAsStateWithLifecycle()

            val playbackState by container.playbackController.playbackState.collectAsStateWithLifecycle()
            val currentPlaybackEpisode by container.playbackController.currentEpisodeState.collectAsStateWithLifecycle()
            val playbackQueue by container.playbackController.queue.collectAsStateWithLifecycle()
            val sleepTimer by container.playbackController.sleepTimer.collectAsStateWithLifecycle()
            val activeTranscriptDoc by container.playbackController.activeTranscriptDocument.collectAsStateWithLifecycle()
            val availableTranscriptDocs by container.playbackController.availableTranscripts.collectAsStateWithLifecycle()
            val addPodcastState by addPodcastViewModel.uiState.collectAsStateWithLifecycle()
            val isRssPlayback = playbackState.itemOrNull()?.sourceType == MediaSourceType.RssPodcast
            val liveCaptionState = if (isRssPlayback) {
                container.liveCaptionCoordinator.uiState.collectAsStateWithLifecycle().value
            } else {
                LiveCaptionUiState()
            }

            val scope = rememberCoroutineScope()
            var isFullScreenPlayerVisible by remember { mutableStateOf(false) }

            LaunchedEffect(collectionsViewModel) {
                collectionsViewModel.createdCollectionIds.collect { colId ->
                    mainViewModel.dispatch(AppIntent.OpenCollectionDetails(colId))
                }
            }

            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        val nextVol = currentSystemMusicVolume()
                        if (kotlin.math.abs(nextVol - systemMusicVolumeState) > 0.001f) {
                            systemMusicVolumeState = nextVol
                        }
                    }
                }
                var registered = false
                fun registerVolumeObserver() {
                    if (!registered) {
                        val nextVol = currentSystemMusicVolume()
                        if (kotlin.math.abs(nextVol - systemMusicVolumeState) > 0.001f) {
                            systemMusicVolumeState = nextVol
                        }
                        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
                        registered = true
                    }
                }
                fun unregisterVolumeObserver() {
                    if (registered) {
                        contentResolver.unregisterContentObserver(volumeObserver)
                        registered = false
                    }
                }
                val lifecycleObserver = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> registerVolumeObserver()
                        Lifecycle.Event.ON_STOP -> unregisterVolumeObserver()
                        else -> Unit
                    }
                }
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    registerVolumeObserver()
                }
                lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
                    unregisterVolumeObserver()
                }
            }

            val darkTheme = when (uiState.themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark, ThemeMode.Oled -> true
            }
            val latestDarkTheme by rememberUpdatedState(darkTheme)
            val latestUserSettings by rememberUpdatedState(userSettings)
            SideEffect {
                updateSystemBarAppearance(darkTheme, isFullScreenPlayerVisible)
            }

            LaunchedEffect(uiState.selectedPodcastId) {
                podcastsViewModel.selectPodcast(uiState.selectedPodcastId)
            }
            LaunchedEffect(uiState.selectedAlbumId) {
                libraryAlbumsViewModel.selectAlbum(uiState.selectedAlbumId)
            }

            val models = PodcastyAppModels(
                homeState = homeState,
                insightState = insightState,
                episodesState = episodesState,
                podcastsState = podcastsState,
                podcastDetailsState = podcastDetailsState,
                libraryAlbumsState = libraryAlbumsState,
                albumDetailsState = albumDetailsState,
                hostsState = hostsState,
                categoriesState = categoriesState,
                collectionsState = collectionsState,
                searchState = searchState,
                userSettings = userSettings,
                allEpisodes = allEpisodes,
                allPodcasts = allPodcasts,
                allHosts = allHosts,
                allCategories = allCategories,
                allCollections = allCollections,
                listeningHistory = listeningHistory,
                playbackState = playbackState,
                playbackPositionFlow = container.playbackController.playbackPositionMs,
                captionSyncFlow = container.playbackController.captionSync,
                openFullScreenLyricsRequestId = openFullScreenLyricsRequestId,
                currentPlaybackEpisode = currentPlaybackEpisode,
                playbackQueue = playbackQueue,
                sleepTimer = sleepTimer,
                systemVolume = systemMusicVolumeState,
                musicFolders = musicFolders,
                scanState = scanState,
                storageVolumes = storageVolumes,
                activeTranscriptDocument = activeTranscriptDoc,
                availableTranscriptDocuments = availableTranscriptDocs,
                addPodcastState = addPodcastState,
                liveCaptionState = liveCaptionState,
                hasAsrTokenStored = hasAsrTokenStoredState,
                hasYouTubeApiKeyStored = hasYouTubeApiKeyStoredState,
                musicBrowserState = musicBrowserState,
            )

            val callbacks = remember(scope) {
                PodcastyAppCallbacks(
                    onIntent = mainViewModel::dispatch,
                    onFullScreenPlayerVisibilityChanged = { visible ->
                        isFullScreenPlayerVisible = visible
                        updateSystemBarAppearance(latestDarkTheme, visible)
                    },
                onPlayEpisode = { episodeId, contextIds, shuffle ->
                    container.playbackController.playEpisode(episodeId, contextIds, shuffle)
                },
                onPlayEpisodes = { episodeIds, shuffle ->
                    container.playbackController.playEpisodes(episodeIds, shuffle)
                },
                onPrevious = container.playbackController::previous,
                onSkipBackward = container.playbackController::skipBackward,
                onPlayPause = container.playbackController::togglePlayPause,
                onSkipForward = container.playbackController::skipForward,
                onNext = container.playbackController::next,
                onSeek = container.playbackController::seekTo,
                onPlaybackSpeedSelected = container.playbackController::setPlaybackSpeed,
                onSleepTimerConfigured = container.playbackController::configureSleepTimer,
                onSleepFadeOutChanged = { sec -> scope.launch { container.preferences.setSleepFadeOutSeconds(sec) } },
                onSelectTranscriptLanguage = container.playbackController::selectTranscriptLanguage,
                onImportTranscriptForEpisode = { episode ->
                    pendingTranscriptEpisode = episode
                    openTranscriptFileLauncher.launch(arrayOf("*/*"))
                },
                onOpenYouTubeForEpisode = { episode ->
                    episode.youtubeUrl?.let { url -> YouTubeAndRssImporter.openInOfficialYouTube(this, url) }
                },
                onLinkAudioForEpisode = { episode -> linkAudioEpisode = episode },
                onOpenTagEditorForEpisodes = ::openTagEditor,
                onAddPodcastUrlChange = addPodcastViewModel::onUrlInputChange,
                onYouTubeLinkedAudioUrlChange = addPodcastViewModel::onYouTubeLinkedAudioUrlChange,
                onLoadDiscoveredRssFeed = addPodcastViewModel::loadDiscoveredRssFeedUrl,
                onInspectPodcastUrl = { addPodcastViewModel.inspectUrl(autoPlayYouTubeOnLoad = true) },
                onAddPodcastCaptionLanguageSelected = addPodcastViewModel::onCaptionLanguageSelected,
                onYouTubeAudioTrackSelected = addPodcastViewModel::onYouTubeAudioTrackSelected,
                onAddPreviewToLibrary = {
                    addPodcastViewModel.addPreviewToLibrary(
                        onYouTubeSaved = { _, episodeId ->
                            container.playbackController.playEpisode(episodeId, listOf(episodeId), false)
                        },
                    )
                },
                onPlayPreviewRssEpisode = { feed, episode ->
                    addPodcastViewModel.importAndPlayRssEpisode(feed, episode) { epId, allIds ->
                        container.playbackController.playEpisode(epId, allIds, false)
                    }
                },
                onPlayPreviewDirectAudio = {
                    addPodcastViewModel.addPreviewToLibrary(
                        onDirectAudioImported = { _, epId ->
                            container.playbackController.playEpisode(epId, listOf(epId), false)
                        },
                    )
                },
                onOpenYouTubeUrl = { url -> YouTubeAndRssImporter.openInOfficialYouTube(this, url) },
                onDeleteImportedPodcast = addPodcastViewModel::deleteImportedPodcast,
                onSelectAsrLanguage = { lang -> container.liveCaptionCoordinator.setLanguageAndRestart(lang) },
                onRetryLiveCaptions = { container.liveCaptionCoordinator.retryRecognition() },
                onAutoStartLiveCaptionsChanged = { v -> scope.launch { container.preferences.setAutoStartLiveCaptions(v) } },
                onSaveCaptionHistoryChanged = { v -> scope.launch { container.preferences.setSaveCaptionHistory(v) } },
                onAsrEngineSelected = { engineId -> scope.launch { container.preferences.setAsrEngineId(engineId) } },
                onSaveAsrConnection = { backendUrl, authToken, youtubeKey ->
                    scope.launch {
                        container.preferences.setAsrBackendUrl(backendUrl)
                        if (authToken != null) {
                            container.credentialStore.saveAsrAuthToken(authToken)
                            hasAsrTokenStoredState = container.credentialStore.hasAsrAuthToken()
                        }
                        if (youtubeKey != null) {
                            container.credentialStore.saveYouTubeApiKey(youtubeKey)
                            hasYouTubeApiKeyStoredState = container.credentialStore.hasYouTubeApiKey()
                        }
                    }
                },
                onQueueEpisodeSelected = container.playbackController::selectQueueEpisode,
                onQueueReordered = container.playbackController::reorderQueue,
                onQueueEpisodeRemoved = container.playbackController::removeFromQueue,
                onShuffleChange = container.playbackController::setShuffle,
                onRepeatModeChange = container.playbackController::setRepeatMode,
                onMiniPlayerDismiss = container.playbackController::clearQueue,
                onSystemVolumeChange = { vol ->
                    setSystemMusicVolume(vol.coerceIn(0f, 1f))
                    systemMusicVolumeState = currentSystemMusicVolume()
                },
                onOpenMediaOutputSwitcher = ::openMediaOutputSwitcher,
                onEpisodePlayNext = container.playbackController::playNext,
                onEpisodeAddToQueue = { epId -> container.playbackController.addToQueue(listOf(epId)) },
                onEpisodesPlayNext = { ids -> ids.asReversed().forEach(container.playbackController::playNext) },
                onEpisodesAddToQueue = container.playbackController::addToQueue,
                onEpisodeSaveToggle = { epId, saved ->
                    scope.launch {
                        container.repository.setEpisodeSaved(epId, saved)
                        if (saved && latestUserSettings.autoDownloadSaved) {
                            container.repository.setEpisodeDownloaded(epId, true)
                        }
                        container.playbackController.refreshCurrentEpisode(epId)
                    }
                },
                onEpisodesSaveAll = { ids ->
                    scope.launch {
                        ids.forEach { id ->
                            container.repository.setEpisodeSaved(id, true)
                            if (latestUserSettings.autoDownloadSaved) {
                                container.repository.setEpisodeDownloaded(id, true)
                            }
                        }
                    }
                },
                onEpisodeDownloadToggle = { epId, downloaded ->
                    scope.launch {
                        if (!downloaded) {
                            container.podcastAudioDownloader.deleteDownloadedAudio(epId)
                            container.repository.setEpisodeDownloaded(epId, false)
                        } else {
                            val ep = withContext(Dispatchers.IO) { container.repository.getEpisodeById(epId) }
                            val srcUrl = ep?.sourceUri
                            if (ep != null && ep.sourceType == com.podcasty.app.domain.MediaSourceType.RssPodcast &&
                                !srcUrl.isNullOrBlank() &&
                                (srcUrl.startsWith("https://", ignoreCase = true) || srcUrl.startsWith("http://", ignoreCase = true))
                            ) {
                                val res = container.podcastAudioDownloader.downloadOriginalAudio(
                                    episodeId = epId,
                                    sourceUrl = srcUrl,
                                    suggestedFileName = ep.audioAsset,
                                )
                                if (res.isSuccess) {
                                    container.repository.linkAudioToEpisode(epId, res.getOrThrow().localFileUri)
                                    container.repository.setEpisodeDownloaded(epId, true)
                                } else {
                                    activeNoticeDialog = PlaybackUiNotice.Info(
                                        "Download failed",
                                        res.exceptionOrNull()?.localizedMessage ?: "Could not download original podcast audio.",
                                    )
                                }
                            } else {
                                container.repository.setEpisodeDownloaded(epId, true)
                            }
                        }
                        container.playbackController.refreshCurrentEpisode(epId)
                    }
                },
                onPodcastSubscribeToggle = { podcastId, subscribed ->
                    scope.launch { container.repository.setPodcastSubscribed(podcastId, subscribed) }
                },
                onCreateCollection = collectionsViewModel::createCollection,
                onRenameCollection = collectionsViewModel::renameCollection,
                onDeleteCollection = collectionsViewModel::deleteCollection,
                onCollectionMembershipChange = collectionsViewModel::changeMembership,
                onReorderCollectionEpisodes = collectionsViewModel::reorderEpisodes,
                onClearAllDownloads = { scope.launch { container.repository.clearAllDownloads() } },
                onRestoreDemoDownloads = { scope.launch { container.repository.restoreDemoDownloads() } },
                onClearListeningHistory = { scope.launch { container.repository.clearListeningHistory() } },
                onEpisodesSortOption = episodesViewModel::setSortOption,
                onEpisodesToggleSortOrder = episodesViewModel::toggleSortOrder,
                onEpisodesFilterQuery = episodesViewModel::setFilterQuery,
                onPodcastsSortOption = podcastsViewModel::setSortOption,
                onPodcastsToggleSortOrder = podcastsViewModel::toggleSortOrder,
                onPodcastsLayoutMode = podcastsViewModel::setLayoutMode,
                onPodcastsFilterQuery = podcastsViewModel::setFilterQuery,
                onAlbumsSortOption = libraryAlbumsViewModel::setSortOption,
                onAlbumsToggleSortOrder = libraryAlbumsViewModel::toggleSortOrder,
                onAlbumsLayoutMode = libraryAlbumsViewModel::setLayoutMode,
                onAlbumsFilterQuery = libraryAlbumsViewModel::setFilterQuery,
                onAlbumsLoadMore = libraryAlbumsViewModel::loadMoreAlbums,
                onHostsSortOption = hostsViewModel::setSortOption,
                onHostsToggleSortOrder = hostsViewModel::toggleSortOrder,
                onHostsFilterQuery = hostsViewModel::setFilterQuery,
                onCategoriesSortOption = categoriesViewModel::setSortOption,
                onCategoriesToggleSortOrder = categoriesViewModel::toggleSortOrder,
                onCategoriesFilterQuery = categoriesViewModel::setFilterQuery,
                onSearchQueryChange = searchViewModel::setQuery,
                onSearchSubmit = searchViewModel::submitQuery,
                onSearchRemoveRecent = searchViewModel::removeRecentSearch,
                onSearchClearRecent = searchViewModel::clearRecentSearches,
                onSearchMusicOptionsChanged = searchViewModel::setMusicOptions,
                onInsightLibraryPeriod = insightViewModel::setLibraryPeriod,
                onInsightListeningPeriod = insightViewModel::setListeningPeriod,
                onDefaultSpeedSelected = { speed -> container.playbackController.setPlaybackSpeed(speed) },
                onSkipBackwardSelected = { sec -> scope.launch { container.preferences.setSkipBackwardSeconds(sec) } },
                onSkipForwardSelected = { sec -> scope.launch { container.preferences.setSkipForwardSeconds(sec) } },
                onShowSpeedBadgeChanged = { show -> scope.launch { container.preferences.setShowSpeedBadge(show) } },
                onKeepScreenOnChanged = { keep -> scope.launch { container.preferences.setKeepScreenOnForTranscript(keep) } },
                onLivingArtworkChanged = { living -> scope.launch { container.preferences.setLivingArtworkBackground(living) } },
                onAutoDownloadChanged = { auto -> scope.launch { container.preferences.setAutoDownloadSaved(auto) } },
                // New Settings callbacks
                onSelectDefaultMusicFolder = {
                    pendingFolderIsUsbOtg = false
                    openFolderLauncher.launch(null)
                },
                onSelectUsbOtgFolder = {
                    pendingFolderIsUsbOtg = true
                    openFolderLauncher.launch(null)
                },
                onFindDuplicates = {
                    duplicateJob?.cancel()
                    duplicateResult = null
                    showDuplicateSheet = true
                    duplicateJob = lifecycleScope.launch {
                        try {
                            duplicateResult = com.podcasty.app.storage.DuplicateMusicDetector(this@MainActivity, container.database.dao(), container.repository).find()
                        } catch (error: Exception) {
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            showDuplicateSheet = false
                            activeNoticeDialog = PlaybackUiNotice.Info("Comparison failed", error.localizedMessage ?: "Cannot compare files.")
                        }
                    }
                },
                onExportPlaylists = { exportPlaylistBackupLauncher.launch("Podcasty-playlists.json") },
                onRestorePlaylists = { restorePlaylistBackupLauncher.launch(arrayOf("application/json", "text/plain")) },
                onScanMusicFolders = {
                    container.localMusicScanner.startBackgroundScanAllFolders(latestUserSettings.includeSubfolders)
                },
                onScanMediaStore = ::triggerMediaStoreScan,
                onRemoveMusicFolder = { treeUri ->
                    scope.launch {
                        container.localMusicScanner.removeFolder(treeUri)
                        if (latestUserSettings.defaultMusicFolderUri == treeUri) {
                            container.preferences.setDefaultMusicFolder(null, null)
                        }
                    }
                },
                onToggleMusicFolderEnabled = { treeUri, enabled ->
                    scope.launch { container.repository.setMusicFolderEnabled(treeUri, enabled) }
                },
                onRefreshMusicFolder = { treeUri ->
                    container.scanCoordinator.requestFolderScan(Uri.parse(treeUri), latestUserSettings.includeSubfolders)
                },
                onAutoLibraryRefreshChanged = { v -> scope.launch { container.preferences.setAutoLibraryRefresh(v) } },
                onIncludeSubfoldersChanged = { v -> scope.launch { container.preferences.setIncludeSubfolders(v) } },
                onAutoSyncLyricsChanged = { v -> scope.launch { container.preferences.setAutoSyncLyrics(v) } },
                onAutoScrollLyricsChanged = { v -> scope.launch { container.preferences.setAutoScrollLyrics(v) } },
                onPreferredTranscriptLanguageSelected = { lang ->
                    scope.launch { container.preferences.setPreferredTranscriptLanguage(lang) }
                },
                onCaptionFontScaleSelected = { scale ->
                    scope.launch { container.preferences.setCaptionFontSizeScale(scale) }
                },
                onShowLockScreenCaptionsChanged = { v ->
                    scope.launch { container.preferences.setShowLockScreenCaptions(v) }
                },
                onShowLockScreenLyricsChanged = { v ->
                    scope.launch { container.preferences.setShowLockScreenLyrics(v) }
                },
                onLockScreenLyricsExpandedChanged = { v ->
                    scope.launch { container.preferences.setLockScreenLyricsExpanded(v) }
                },
                onShowMiniPlayerLyricsChanged = { v ->
                    scope.launch { container.preferences.setShowMiniPlayerLyrics(v) }
                },
                onShowLyricsTranslationChanged = { v ->
                    scope.launch { container.preferences.setShowLyricsTranslation(v) }
                },
                onShowTranscriptNotificationChanged = { v ->
                    scope.launch { container.preferences.setShowTranscriptNotification(v) }
                },
                onResumePreviousPlaybackChanged = { v ->
                    scope.launch { container.preferences.setResumePreviousPlayback(v) }
                },
                onPlayerSecondaryControlsChanged = { v -> scope.launch { container.preferences.setPlayerSecondaryControls(v) } },
                onReplayGainModeChanged = { v -> scope.launch { container.preferences.setReplayGainMode(v) } },
                onShakeSkipSensitivityChanged = { v -> scope.launch { container.preferences.setShakeSkipSensitivity(v) } },
                onOptionalHomeCategoryChanged = { v -> scope.launch { container.preferences.setOptionalHomeCategory(v) } },
                onRemovePlayedFromQueueChanged = { v -> scope.launch { container.preferences.setRemovePlayedFromQueue(v) } },
                onPlaybackFadeDurationChanged = { v -> scope.launch { container.preferences.setPlaybackFadeDurationMs(v) } },
                onGaplessPlaybackChanged = { v ->
                    scope.launch { container.preferences.setGaplessPlayback(v) }
                },
                onCrossfadeDurationChanged = { ms ->
                    scope.launch { container.preferences.setCrossfadeDurationMs(ms) }
                },
                onPauseOnHeadphoneDisconnectChanged = { v ->
                    scope.launch { container.preferences.setPauseOnHeadphoneDisconnect(v) }
                },
                onBluetoothMediaControlsChanged = { v ->
                    scope.launch { container.preferences.setBluetoothMediaControls(v) }
                },
                onAutoResumeBluetoothChanged = { v ->
                    scope.launch { container.preferences.setAutoResumeOnBluetoothReconnect(v) }
                },
                onMusicSelectCategory = musicBrowserViewModel::selectCategory,
                onMusicSetFolderViewMode = musicBrowserViewModel::setFolderViewMode,
                onMusicSetFolderSortOption = musicBrowserViewModel::setFolderSortOption,
                onMusicSetSongSortOption = musicBrowserViewModel::setSongSortOption,
                onMusicSearchQueryChange = musicBrowserViewModel::setSearchQuery,
                onMusicOpenStorageRoot = musicBrowserViewModel::openStorageRoot,
                onMusicToggleFavoriteFolder = musicBrowserViewModel::toggleCurrentFolderFavorite,
                onMusicOpenFavoriteFolder = musicBrowserViewModel::openFavoriteFolder,
                onMusicOpenSubfolder = musicBrowserViewModel::openHierarchicalSubfolder,
                onMusicNavigateUpFolder = { musicBrowserViewModel.navigateUpFolder() },
                onMusicOpenLinearFolder = musicBrowserViewModel::openLinearFolder,
                onMusicExcludeFolder = musicBrowserViewModel::excludeFolder,
                onMusicSelectAlbum = musicBrowserViewModel::selectAlbum,
                onMusicSelectArtist = musicBrowserViewModel::selectArtist,
                onMusicRefreshSingleFolder = { treeUri ->
                    container.localMusicScanner.startBackgroundScanFolder(Uri.parse(treeUri), latestUserSettings.includeSubfolders)
                },
                onMusicPlayAllFolderRecursive = { shuffle ->
                    scope.launch {
                        val ids = musicBrowserViewModel.getFolderSongIdsForPlayback(recursive = true)
                        if (ids.isNotEmpty()) container.playbackController.playEpisodes(ids, shuffle = shuffle)
                    }
                },
                onMusicPlayAllFolderDirect = { shuffle ->
                    scope.launch {
                        val ids = musicBrowserViewModel.getFolderSongIdsForPlayback(recursive = false)
                        if (ids.isNotEmpty()) container.playbackController.playEpisodes(ids, shuffle = shuffle)
                    }
                },
                onMusicPlayLinearFolderDirect = { treeUri, relPath, shuffle ->
                    scope.launch {
                        val ids = container.repository.getDirectFolderSongIds(treeUri, relPath)
                        if (ids.isNotEmpty()) container.playbackController.playEpisodes(ids, shuffle = shuffle)
                    }
                },
                onMusicPlayAllSortedSongs = { shuffle ->
                    scope.launch {
                        val ids = musicBrowserViewModel.getAllSortedSongIdsForPlayback()
                        if (ids.isNotEmpty()) container.playbackController.playEpisodes(ids, shuffle = shuffle)
                    }
                },
                onMusicAddFolderToQueue = { treeUri, relPath ->
                    scope.launch {
                        val ids = container.repository.getDirectFolderSongIds(treeUri, relPath)
                        if (ids.isNotEmpty()) container.playbackController.addToQueue(ids)
                    }
                },
                onMusicToggleSelectSong = musicBrowserViewModel::toggleSongSelection,
                onMusicSelectAllVisible = musicBrowserViewModel::selectAllVisible,
                onMusicClearSelection = musicBrowserViewModel::clearSelection,
                onMusicLoadMoreSongs = musicBrowserViewModel::loadMoreSongs,
                onMusicLoadMoreAlbums = musicBrowserViewModel::loadMoreAlbums,
                onMusicLoadMoreArtists = musicBrowserViewModel::loadMoreArtists,
                onMusicLoadMoreFolderSongs = musicBrowserViewModel::loadMoreFolderSongs,
                onOpenQueueManager = { showQueueManagerForIds = emptyList() },
                onOpenExcludedFolders = { showExcludedFoldersSheet = true },
                onOpenEqualizer = { showEqualizerSheet = true },
                onHandleFileOrQueueRequest = ::handleFileOrQueueRequest,
                )
            }

            PodcastyApp(uiState = uiState, models = models, callbacks = callbacks)

            // Feature dialogs & bottom sheets styled identically with PodcastyTheme
            PodcastyTheme(themeMode = uiState.themeMode) {
                activeNoticeDialog?.let { notice ->
                    PodcastyDialog(
                        title = notice.title,
                        description = notice.message,
                        dismissLabel = stringResource(R.string.ok),
                        onDismiss = { activeNoticeDialog = null },
                        confirmLabel = stringResource(R.string.ok),
                        onConfirm = { activeNoticeDialog = null },
                    )
                }

                rawPcmPrompt?.let { prompt ->
                    RawPcmConfigBottomSheet(
                        episode = prompt.episode,
                        sourceUri = prompt.sourceUri,
                        onApplyAndPlay = { config ->
                            container.playbackController.playRawPcmWithConfig(prompt.episode.id, config)
                        },
                        onDismiss = { rawPcmPrompt = null },
                    )
                }

                youtubeHandoffPrompt?.let { handoff ->
                    PodcastyDialog(
                        title = handoff.episode.title,
                        description = stringResource(R.string.add_podcast_youtube_fallback_notice),
                        dismissLabel = stringResource(R.string.close),
                        onDismiss = { youtubeHandoffPrompt = null },
                        confirmLabel = stringResource(R.string.youtube_open_official),
                        onConfirm = {
                            youtubeHandoffPrompt = null
                            YouTubeAndRssImporter.openInOfficialYouTube(this@MainActivity, handoff.youtubeUrl)
                        },
                        confirmVariant = PodcastyPillButtonVariant.Primary,
                    )
                }

                linkAudioEpisode?.let { ep ->
                    LinkEpisodeAudioBottomSheet(
                        episode = ep,
                        onSaveAudioUrl = { audioUrl ->
                            scope.launch {
                                container.repository.linkAudioToEpisode(ep.id, audioUrl)
                                if (!audioUrl.isNullOrBlank()) {
                                    container.playbackController.playEpisode(ep.id, listOf(ep.id), false)
                                }
                            }
                        },
                        onDismiss = { linkAudioEpisode = null },
                    )
                }

                if (tagEditorEpisodes.isNotEmpty()) {
                    TagEditorBottomSheet(
                        targetEpisodes = tagEditorEpisodes,
                        initialTags = tagEditorInitialTags,
                        isSaving = isSavingTags,
                        statusMessage = tagEditorStatus,
                        onPickArtwork = { pickArtworkLauncher.launch(arrayOf("image/*")) },
                        onSave = ::saveEditedTags,
                        onDismiss = {
                            if (!isSavingTags) {
                                tagEditorEpisodes = emptyList()
                                tagEditorStatus = null
                            }
                        },
                    )
                }

                fileDetailsSheet?.let { details ->
                    FileDetailsBottomSheet(
                        details = details,
                        onDismiss = { fileDetailsSheet = null },
                    )
                }

                renameFileEpisode?.let { ep ->
                    RenameFileBottomSheet(
                        episode = ep,
                        onRename = { newName ->
                            renameFileEpisode = null
                            scope.launch {
                                handleFileOperationResult(container.musicFileManager.renameFile(ep, newName))
                            }
                        },
                        onDismiss = { renameFileEpisode = null },
                    )
                }

                transferFileRequest?.let { (ep, isMove) ->
                    DestinationFolderPickerSheet(
                        title = if (isMove) "Move \"${ep.title}\" to…" else "Copy \"${ep.title}\" to…",
                        musicFolders = musicFolders,
                        linearFolders = musicBrowserState.linearFolders,
                        onSelectDestination = { treeUri, relativePath ->
                            transferFileRequest = null
                            scope.launch {
                                val res = container.musicFileManager.copyOrMoveToSafFolder(
                                    episode = ep,
                                    destTreeUri = treeUri,
                                    destSubfolderRelativePath = relativePath,
                                    move = isMove,
                                )
                                handleFileOperationResult(res)
                            }
                        },
                        onPickSystemFolder = { pickTransferDestFolderLauncher.launch(null) },
                        onDismiss = { transferFileRequest = null },
                    )
                }

                if (deleteConfirmEpisodes.isNotEmpty()) {
                    DeleteFilesConfirmDialog(
                        episodes = deleteConfirmEpisodes,
                        onConfirm = {
                            val targets = deleteConfirmEpisodes
                            deleteConfirmEpisodes = emptyList()
                            musicBrowserViewModel.clearSelection()
                            scope.launch {
                                val res = container.musicFileManager.deleteFiles(targets)
                                handleFileOperationResult(res)
                            }
                        },
                        onDismiss = { deleteConfirmEpisodes = emptyList() },
                    )
                }

                lyricsViewState?.let { (ep, rawTranscript, docs) ->
                    EmbeddedLyricsBottomSheet(
                        episode = ep,
                        transcriptText = rawTranscript,
                        documents = docs,
                        onEditTags = {
                            lyricsViewState = null
                            openTagEditor(listOf(ep))
                        },
                        onDismiss = { lyricsViewState = null },
                    )
                }

                showQueueManagerForIds?.let { targetIds ->
                    MultiQueueManagerBottomSheet(
                        queues = musicBrowserState.musicQueues,
                        activeQueueId = playbackQueue.activeQueueId,
                        targetEpisodeIdsToAdd = targetIds,
                        onSelectQueue = { qId -> container.playbackController.switchToQueue(qId, autoPlay = true) },
                        onAddEpisodesToQueue = { qId, ids ->
                            container.playbackController.addEpisodesToSpecificQueue(qId, ids)
                        },
                        onCreateQueue = { name, ids ->
                            container.playbackController.createMusicQueue(name, ids, activate = false)
                        },
                        onRenameQueue = container.playbackController::renameMusicQueue,
                        onDeleteQueue = container.playbackController::deleteMusicQueue,
                        onClearActiveQueue = container.playbackController::clearQueue,
                        onUndoClearActiveQueue = container.playbackController::undoClearQueue,
                        onSaveQueueAsPlaylist = { name, ids ->
                            collectionsViewModel.createCollection(name, ids)
                        },
                        onDismiss = { showQueueManagerForIds = null },
                    )
                }

                if (showExcludedFoldersSheet) {
                    ExcludedFoldersBottomSheet(
                        excludedFolders = musicBrowserState.excludedFolders,
                        onRestoreFolder = musicBrowserViewModel::restoreExcludedFolder,
                        onDismiss = { showExcludedFoldersSheet = false },
                    )
                }

                if (showDuplicateSheet) {
                    com.podcasty.app.features.folder.DuplicateMusicBottomSheet(
                        result = duplicateResult,
                        onDeleteSelected = { tracks -> showDuplicateSheet = false; deleteConfirmEpisodes = tracks },
                        onDismiss = { duplicateJob?.cancel(); showDuplicateSheet = false; duplicateResult = null },
                    )
                }

                if (showEqualizerSheet) {
                    val eqState by container.equalizerController.uiState.collectAsStateWithLifecycle()
                    EqualizerBottomSheet(
                        state = eqState,
                        onEnabledChanged = container.equalizerController::setEnabled,
                        onSelectPreset = container.equalizerController::selectPreset,
                        onBandLevelChanged = container.equalizerController::setBandLevelMb,
                        onResetBand = container.equalizerController::resetBand,
                        onPreampChanged = container.equalizerController::setPreampDb,
                        onAutoClippingProtectionChanged = container.equalizerController::setAutoClippingProtection,
                        onBassBoostChanged = container.equalizerController::setBassBoostStrength,
                        onVirtualizerChanged = container.equalizerController::setVirtualizerStrength,
                        onBalanceChanged = container.equalizerController::setBalance,
                        onMonoAudioChanged = container.equalizerController::setMonoAudio,
                        onSaveCustomPreset = container.equalizerController::saveAsCustomPreset,
                        onUpdateCustomPreset = container.equalizerController::updateCustomPreset,
                        onRenameCustomPreset = container.equalizerController::renameCustomPreset,
                        onDeleteCustomPreset = container.equalizerController::deleteCustomPreset,
                        onResetEqualizer = container.equalizerController::resetEqualizer,
                        onRestoreDefaults = container.equalizerController::restoreDefaultPresets,
                        onDismiss = { showEqualizerSheet = false },
                    )
                }
            }
        }
    }

    private fun handleFileOrQueueRequest(request: EpisodeContextBottomSheetRequest) {
        when (request) {
            is EpisodeContextBottomSheetRequest.QueuePicker -> showQueueManagerForIds = request.episodeIds
            is EpisodeContextBottomSheetRequest.FileDetails -> lifecycleScope.launch {
                val full = container.repository.getEpisodeById(request.episode.id) ?: request.episode
                fileDetailsSheet = container.musicFileManager.inspectFileDetails(full)
            }
            is EpisodeContextBottomSheetRequest.RenameFile -> renameFileEpisode = request.episode
            is EpisodeContextBottomSheetRequest.MoveFile -> transferFileRequest = request.episode to true
            is EpisodeContextBottomSheetRequest.CopyFile -> transferFileRequest = request.episode to false
            is EpisodeContextBottomSheetRequest.DeleteFiles -> deleteConfirmEpisodes = request.episodes
            is EpisodeContextBottomSheetRequest.ShareFile -> {
                val shareIntent = container.musicFileManager.buildShareIntent(request.episode)
                if (shareIntent == null) {
                    activeNoticeDialog = PlaybackUiNotice.Info("Share unavailable", "No shareable audio file URI was found for this track.")
                } else {
                    startActivity(Intent.createChooser(shareIntent, request.episode.title))
                }
            }
            is EpisodeContextBottomSheetRequest.ViewLyrics -> lifecycleScope.launch {
                val full = container.repository.getEpisodeById(request.episode.id) ?: request.episode
                val docs = container.repository.getTranscriptDocuments(full.id)
                lyricsViewState = Triple(full, full.transcriptLrc, docs)
            }
            is EpisodeContextBottomSheetRequest.OpenContainingFolder -> {
                musicBrowserViewModel.openContainingFolder(request.episode)
                mainViewModel.dispatch(AppIntent.SelectDestination(com.podcasty.app.navigation.AppDestination.Folder))
            }
            is EpisodeContextBottomSheetRequest.TagEditor -> openTagEditor(request.episodes)
            is EpisodeContextBottomSheetRequest.ImportTranscript -> {
                pendingTranscriptEpisode = request.episode
                openTranscriptFileLauncher.launch(arrayOf("*/*"))
            }
            is EpisodeContextBottomSheetRequest.LinkAudioSource -> linkAudioEpisode = request.episode
            is EpisodeContextBottomSheetRequest.OpenYouTube -> {
                request.episode.youtubeUrl?.let { url -> YouTubeAndRssImporter.openInOfficialYouTube(this, url) }
            }
            is EpisodeContextBottomSheetRequest.Info,
            is EpisodeContextBottomSheetRequest.CollectionPicker -> Unit
        }
    }

    private fun handleFileOperationResult(result: FileOperationResult) {
        when (result) {
            is FileOperationResult.Success -> {
                activeNoticeDialog = PlaybackUiNotice.Info("File operation completed", result.message)
            }
            is FileOperationResult.RequiresUserConsent -> {
                pendingConsentAction = result.pendingAction
                mediaStoreConsentLauncher.launch(IntentSenderRequest.Builder(result.intentSender).build())
            }
            is FileOperationResult.ReadOnlyOrUnsupported -> {
                activeNoticeDialog = PlaybackUiNotice.Info("Storage permission required", result.reason)
            }
            is FileOperationResult.Failed -> {
                activeNoticeDialog = PlaybackUiNotice.Info("File operation failed", result.reason)
            }
        }
    }

    private fun openTagEditor(episodes: List<Episode>) {
        val localTargets = episodes.filter { it.isLocalAudioFile }
        if (localTargets.isEmpty()) {
            activeNoticeDialog = PlaybackUiNotice.Info(
                "Tag editor",
                "Tag editing is available for local audio files selected from your music folder or USB storage.",
            )
            return
        }
        tagEditorEpisodes = localTargets
        tagEditorStatus = null
        if (localTargets.size == 1) {
            val ep = localTargets.first()
            tagEditorInitialTags = EditableAudioTags(
                title = ep.title,
                artist = ep.hostName,
                album = ep.podcastTitle,
                albumArtist = ep.albumArtist.orEmpty(),
                genre = ep.categoryName,
                year = if (ep.year > 0) ep.year.toString() else "",
                trackNumber = if (ep.trackNumber > 0) ep.trackNumber.toString() else "",
                discNumber = if (ep.discNumber > 0) ep.discNumber.toString() else "",
                composer = ep.composer.orEmpty(),
                comment = ep.comment.orEmpty(),
                lyrics = ep.transcriptLrc.orEmpty(),
            )
            lifecycleScope.launch {
                val uriStr = ep.editableAudioUri ?: ep.sourceUri ?: return@launch
                val uri = Uri.parse(uriStr)
                container.audioTagEditor.readTags(uri).onSuccess { loaded ->
                    tagEditorInitialTags = loaded
                }
            }
        } else {
            tagEditorInitialTags = EditableAudioTags()
        }
    }

    private fun saveEditedTags(updated: EditableAudioTags) {
        val targets = tagEditorEpisodes
        if (targets.isEmpty()) return
        lifecycleScope.launch {
            isSavingTags = true
            tagEditorStatus = null
            if (targets.size == 1) {
                val ep = targets.first()
                val uri = Uri.parse(requireNotNull(ep.editableAudioUri ?: ep.sourceUri))
                when (val result = container.audioTagEditor.saveTagsSafely(uri, ep.audioAsset.ifBlank { "${ep.id}.m4a" }, updated)) {
                    is TagSaveResult.SavedInPlace -> {
                        val artPath = persistEditedArtworkCache(ep.id, updated)
                        container.repository.updateEpisodeAfterTagEdit(ep.id, result.verifiedTags, artPath)
                        container.playbackController.refreshCurrentEpisode(ep.id)
                        isSavingTags = false
                        tagEditorEpisodes = emptyList()
                        activeNoticeDialog = PlaybackUiNotice.Info("Tags saved", "Verified and updated metadata for \"${result.verifiedTags.title}\".")
                    }
                    is TagSaveResult.NeedsExportCopy -> {
                        isSavingTags = false
                        tagEditorEpisodes = emptyList()
                        pendingExportCopy = result.verifiedTempFile to result.suggestedFileName
                        exportEditedAudioLauncher.launch(result.suggestedFileName)
                    }
                    is TagSaveResult.Failed -> {
                        isSavingTags = false
                        tagEditorStatus = result.reason
                    }
                }
            } else {
                val pairs = targets.mapNotNull { ep ->
                    (ep.editableAudioUri ?: ep.sourceUri)?.let { Uri.parse(it) to ep.audioAsset.ifBlank { "${ep.id}.m4a" } }
                }
                val summary = container.audioTagEditor.saveBulkTags(pairs, updated)
                targets.forEach { ep ->
                    val artPath = if (updated.artworkModified) persistEditedArtworkCache(ep.id, updated) else null
                    container.repository.updateEpisodeAfterTagEdit(ep.id, updated, artPath)
                    container.playbackController.refreshCurrentEpisode(ep.id)
                }
                isSavingTags = false
                tagEditorEpisodes = emptyList()
                val firstExport = summary.needsExportFiles.firstOrNull()
                if (firstExport != null) {
                    pendingExportCopy = firstExport
                    exportEditedAudioLauncher.launch(firstExport.second)
                } else {
                    activeNoticeDialog = PlaybackUiNotice.Info(
                        "Bulk tag edit finished",
                        "Updated ${summary.succeededCount} files (${summary.failedCount} failed).",
                    )
                }
            }
        }
    }

    private fun persistEditedArtworkCache(episodeId: String, tags: EditableAudioTags): String? {
        if (!tags.artworkModified || tags.removeArtwork || tags.artworkBytes == null) return null
        return container.localMusicScanner.artworkResolver.validateAndCacheImageBytes(tags.artworkBytes)
    }

    private fun triggerMediaStoreScan() {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
            container.localMusicScanner.startBackgroundScanMediaStore()
        } else {
            audioPermissionLauncher.launch(perm)
        }
    }

    private fun resolveFileName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(ExtraOpenFullScreenLyrics, false)) {
            openFullScreenLyricsRequestId += 1L
            intent.removeExtra(ExtraOpenFullScreenLyrics)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateRequestedOrientation(newConfig)
    }

    private fun updateRequestedOrientation(configuration: Configuration) {
        val orientation = if (configuration.smallestScreenWidthDp >= 600) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        if (requestedOrientation != orientation) requestedOrientation = orientation
    }

    private fun updateSystemBarAppearance(darkTheme: Boolean, fullScreenPlayerVisible: Boolean) {
        val lightSystemBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        val statusBarAppearance = if (darkTheme || fullScreenPlayerVisible) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        val navBarAppearance = if (darkTheme || fullScreenPlayerVisible) 0 else WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(statusBarAppearance or navBarAppearance, lightSystemBars)
    }

    private fun currentSystemMusicVolume(): Float {
        val manager = getSystemService(AudioManager::class.java) ?: return 0.5f
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return (manager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max.toFloat()).coerceIn(0f, 1f)
    }

    private fun setSystemMusicVolume(volume: Float) {
        val manager = getSystemService(AudioManager::class.java) ?: return
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, (max * volume).roundToInt(), 0)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val handled = super.onKeyDown(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            systemMusicVolumeState = currentSystemMusicVolume()
        }
        return handled
    }

    private fun openMediaOutputSwitcher() {
        val opened = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { MediaRouter2.getInstance(this).showSystemOutputSwitcher() }.getOrDefault(false)
        } else {
            false
        }
        if (!opened) {
            runCatching { startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) }
        }
    }

    companion object {
        const val ExtraOpenFullScreenLyrics = "com.podcasty.app.extra.OPEN_FULL_SCREEN_LYRICS"
        private const val NotificationPermissionRequest = 51
    }
}
