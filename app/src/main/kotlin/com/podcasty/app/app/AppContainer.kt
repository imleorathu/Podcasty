/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.app

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.podcasty.app.asr.LiveCaptionCoordinator
import com.podcasty.app.asr.SecureCredentialStore
import com.podcasty.app.audio.EqualizerController
import com.podcasty.app.data.PodcastPreferencesRepository
import com.podcasty.app.data.PodcastRepositoryImpl
import com.podcasty.app.database.PodcastyDatabase
import com.podcasty.app.domain.PodcastRepository
import com.podcasty.app.player.PodcastPlaybackController
import com.podcasty.app.player.PodcastPlaybackService
import com.podcasty.app.podcast.PodcastAudioDownloader
import com.podcasty.app.podcast.RssPodcastDiscoveryService
import com.podcasty.app.podcast.YouTubeCaptionExtractorClient
import com.podcasty.app.podcast.YouTubeMusicDownloadManager
import com.podcasty.app.storage.LocalMusicScanner
import com.podcasty.app.storage.MusicFileManager
import com.podcasty.app.storage.UsbStorageMonitor
import com.podcasty.app.storage.core.StoragePermissionMonitor
import com.podcasty.app.storage.monitoring.MediaStoreChangeObserver
import com.podcasty.app.storage.scanner.ScanCoordinator
import com.podcasty.app.streaming.YouTubeAudioStreamResolver
import com.podcasty.app.tags.AudioTagEditor

@OptIn(UnstableApi::class)
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val database: PodcastyDatabase = PodcastyDatabase.getInstance(appContext)
    val preferences: PodcastPreferencesRepository = PodcastPreferencesRepository(appContext)
    val credentialStore: SecureCredentialStore = SecureCredentialStore(appContext)
    private val repositoryImpl: PodcastRepositoryImpl = PodcastRepositoryImpl(database.dao())
    val repository: PodcastRepository = repositoryImpl
    val usbStorageMonitor: UsbStorageMonitor = UsbStorageMonitor(appContext).apply { start() }
    val storagePermissionMonitor: StoragePermissionMonitor = StoragePermissionMonitor(appContext, database.dao())
    val localMusicScanner: LocalMusicScanner = LocalMusicScanner(
        appContext,
        database.dao(),
        repositoryImpl::notifyLibraryMutated,
    )
    val scanCoordinator: ScanCoordinator = ScanCoordinator(
        context = appContext,
        dao = database.dao(),
        scanner = localMusicScanner,
        permissionMonitor = storagePermissionMonitor,
    ).also { coordinator ->
        localMusicScanner.scanCoordinator = coordinator
        coordinator.recoverOnStartup()
    }
    val mediaStoreObserver: MediaStoreChangeObserver = MediaStoreChangeObserver(appContext) {
        scanCoordinator.syncMediaStoreIfGenerationChanged()
    }.also { observer ->
        scanCoordinator.mediaStoreObserver = observer
        observer.start()
    }
    val musicFileManager: MusicFileManager by lazy { MusicFileManager(appContext, repository, localMusicScanner) }
    val audioTagEditor: AudioTagEditor by lazy {
        AudioTagEditor(appContext).apply { cleanupStaleWorkFiles() }
    }
    val youTubeCaptionClient: YouTubeCaptionExtractorClient by lazy { YouTubeCaptionExtractorClient() }
    val rssDiscoveryService: RssPodcastDiscoveryService by lazy { RssPodcastDiscoveryService() }
    val youTubeAudioStreamResolver: YouTubeAudioStreamResolver by lazy { YouTubeAudioStreamResolver() }
    val podcastAudioDownloader: PodcastAudioDownloader by lazy { PodcastAudioDownloader(appContext) }
    val youTubeMusicDownloadManager: YouTubeMusicDownloadManager by lazy {
        YouTubeMusicDownloadManager(
            context = appContext,
            audioStreamResolver = youTubeAudioStreamResolver,
            captionExtractorClient = youTubeCaptionClient,
        )
    }
    val playbackController: PodcastPlaybackController = PodcastPlaybackController(
        context = appContext,
        repository = repository,
        preferences = preferences,
        usbStorageMonitor = usbStorageMonitor,
        youTubeAudioResolver = youTubeAudioStreamResolver,
        youTubeCaptionClient = youTubeCaptionClient,
    ).also { controller ->
        controller.onPlaybackActiveChanged = { active ->
            localMusicScanner.isPlaybackActive = active
        }
    }
    val equalizerController: EqualizerController by lazy {
        EqualizerController(
            engine = PodcastPlaybackService.sharedAudioEffectsEngine,
            preferences = preferences,
            dao = database.dao(),
        )
    }
    val liveCaptionCoordinator: LiveCaptionCoordinator by lazy {
        LiveCaptionCoordinator(
            context = appContext,
            audioProcessor = PodcastPlaybackService.sharedLiveCaptionAudioProcessor,
            playbackStateFlow = playbackController.playbackState,
            repository = repository,
            preferences = preferences,
            credentialStore = credentialStore,
        ).also { coordinator ->
            playbackController.onSeekPerformed = coordinator::onSeekPerformed
            playbackController.onSpeedChanged = coordinator::onPlaybackSpeedChanged
        }
    }
}
