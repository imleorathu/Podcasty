/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.player

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.podcasty.app.audio.AudioFormatCatalog
import com.podcasty.app.data.PodcastPreferencesRepository
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.MediaSourceType
import com.podcasty.app.domain.MusicQueue
import com.podcasty.app.domain.PlaybackItem
import com.podcasty.app.domain.PlaybackQueueSnapshot
import com.podcasty.app.domain.PlaybackState
import com.podcasty.app.domain.PodcastRepository
import com.podcasty.app.domain.RawPcmConfig
import com.podcasty.app.domain.RepeatMode
import com.podcasty.app.domain.SleepTimerPreset
import com.podcasty.app.domain.SleepTimerRequest
import com.podcasty.app.domain.SleepTimerState
import com.podcasty.app.storage.UsbStorageEvent
import com.podcasty.app.storage.UsbStorageMonitor
import com.podcasty.app.podcast.YouTubeCaptionExtractionResult
import com.podcasty.app.podcast.YouTubeCaptionExtractorClient
import com.podcasty.app.streaming.PodcastSourceResolver
import com.podcasty.app.streaming.YouTubeAudioStream
import com.podcasty.app.streaming.YouTubeAudioStreamResolver
import com.podcasty.app.transcript.CaptionSyncEngine
import com.podcasty.app.transcript.CaptionSyncSnapshot
import com.podcasty.app.transcript.Id3SyltParser
import com.podcasty.app.transcript.TranscriptDocument
import com.podcasty.app.transcript.TranscriptParser
import com.podcasty.app.transcript.TranscriptSource
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface PlaybackUiNotice {
    data class Info(val title: String, val message: String) : PlaybackUiNotice
    data class RawPcmConfigRequired(val episode: Episode, val sourceUri: String) : PlaybackUiNotice
    data class YouTubePlaybackHandoff(val episode: Episode, val youtubeUrl: String) : PlaybackUiNotice
}

class PodcastPlaybackController(
    private val context: Context,
    private val repository: PodcastRepository,
    private val preferences: PodcastPreferencesRepository,
    private val usbStorageMonitor: UsbStorageMonitor? = null,
    private val youTubeAudioResolver: YouTubeAudioStreamResolver = YouTubeAudioStreamResolver(),
    private val youTubeCaptionClient: YouTubeCaptionExtractorClient = YouTubeCaptionExtractorClient(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    private val captionSyncEngine = CaptionSyncEngine()
    private val captionNotificationController = CaptionNotificationController(context)
    private val lockScreenLyricsNotificationController = LockScreenLyricsNotificationController(context)
    private var gaplessPreloadJob: Job? = null
    private var prepareEpisodeJob: Job? = null
    private var restoreSessionJob: Job? = null
    private var transcriptLoadJob: Job? = null
    private var queuePersistJob: Job? = null
    private var seekPersistJob: Job? = null

    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    /**
     * Dedicated high-frequency playback position stream isolated from structural [playbackState]
     * so 120Hz/60Hz UI trees never recompose root screens on every progress tick.
     */
    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()

    private val _queue = MutableStateFlow(PlaybackQueueSnapshot())
    val queue: StateFlow<PlaybackQueueSnapshot> = _queue.asStateFlow()

    val musicQueues: StateFlow<List<MusicQueue>> = repository.observeMusicQueues()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val sleepTimer: StateFlow<SleepTimerState> = PodcastPlaybackService.sharedSleepTimerState

    val captionSync: StateFlow<CaptionSyncSnapshot> = captionSyncEngine.snapshot

    private val _availableTranscripts = MutableStateFlow<List<TranscriptDocument>>(emptyList())
    val availableTranscripts: StateFlow<List<TranscriptDocument>> = _availableTranscripts.asStateFlow()

    private val _activeTranscriptDocument = MutableStateFlow<TranscriptDocument?>(null)
    val activeTranscriptDocument: StateFlow<TranscriptDocument?> = _activeTranscriptDocument.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _uiNotices = MutableSharedFlow<PlaybackUiNotice>(extraBufferCapacity = 8)
    val uiNotices: SharedFlow<PlaybackUiNotice> = _uiNotices.asSharedFlow()

    var onLocalPlaybackStarting: (() -> Unit)? = null
    var onSeekPerformed: ((Long, Float) -> Unit)? = null
    var onSpeedChanged: ((Float, Long) -> Unit)? = null
    var onPlaybackActiveChanged: ((Boolean) -> Unit)? = null

    private val _currentEpisodeState = MutableStateFlow<Episode?>(null)
    val currentEpisodeState: StateFlow<Episode?> = _currentEpisodeState.asStateFlow()

    private var clearedQueueBackup: Triple<PlaybackQueueSnapshot, List<String>, Long>? = null
    private var originalQueueIds: List<String> = emptyList()
    private var currentEpisode: Episode? = null
        set(value) {
            field = value
            _currentEpisodeState.value = value
        }
    private var currentItem: PlaybackItem? = null
    private var currentYouTubeStream: YouTubeAudioStream? = null
    private var youTubeRetryEpisodeId: String? = null
    private var pauseRequested = false
    private var pendingPlayAction: (() -> Unit)? = null
    private var positionTickerJob: Job? = null
    private var sessionAccumulatedSeconds: Double = 0.0
    private var lastTickTimestampMs: Long = 0L
    private var lastProgressPersistMs: Long = 0L
    private var seekGuardTargetMs: Long? = null
    private var seekGuardUntilElapsedMs: Long = 0L
    private var seekRebufferSuppressUntilElapsedMs: Long = 0L

    private var skipBackwardSec: Int = 10
    private var skipForwardSec: Int = 30
    private var sleepFadeOutSec: Int = 30
    private var resumePreviousPlayback: Boolean = true
    private var gaplessPlayback: Boolean = true
    private var crossfadeDurationMs: Int = 0
    private var removePlayedFromQueue: Boolean = false
    private var preferredTranscriptLanguage: String = "en"
    private var showLockScreenCaptions: Boolean = false
    private var showTranscriptNotification: Boolean = false
    private var showLockScreenLyrics: Boolean = true
    private var lockScreenLyricsExpanded: Boolean = true
    private var showLyricsTranslation: Boolean = true
    @Volatile
    private var isScreenInteractive: Boolean = true
    private var screenStateReceiverRegistered: Boolean = false

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val interactive = when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> false
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> true
                else -> (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
            }
            if (isScreenInteractive == interactive && intent?.action != Intent.ACTION_USER_PRESENT) return
            isScreenInteractive = interactive
            lockScreenLyricsNotificationController.onScreenInteractiveChanged(interactive)
            if (interactive) {
                val controller = mediaController
                if (controller != null && currentItem != null) {
                    val pos = controller.currentPosition.coerceAtLeast(0L)
                    _playbackPositionMs.value = pos
                    captionSyncEngine.onPositionChanged(pos)
                }
                syncLyricsAndCaptionNotifications()
            }
            if (mediaController?.isPlaying == true) {
                stopTicker()
                updateTicker()
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val controller = mediaController
            val isTransientSeekRebuffer = !isPlaying &&
                SystemClock.elapsedRealtime() < seekRebufferSuppressUntilElapsedMs &&
                controller?.playWhenReady == true &&
                (controller.playbackState == Player.STATE_BUFFERING || controller.playbackState == Player.STATE_READY)
            if (isTransientSeekRebuffer) {
                return
            }
            if (isPlaying && controller?.playbackState == Player.STATE_READY) {
                seekRebufferSuppressUntilElapsedMs = 0L
            }
            if (!isPlaying) {
                flushListeningSession(completed = false, skipped = false)
                persistCurrentPosition(markPlayed = false)
            } else {
                lastTickTimestampMs = System.currentTimeMillis()
            }
            publishState()
            syncLyricsAndCaptionNotifications()
            updateTicker()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val controller = mediaController
            if (playbackState == Player.STATE_ENDED) {
                seekRebufferSuppressUntilElapsedMs = 0L
                seekGuardTargetMs = null
                seekGuardUntilElapsedMs = 0L
                // If user manually sought to the very end while paused, stay PAUSED at the end
                // instead of auto-advancing or resuming playback.
                if (controller?.playWhenReady != true || pauseRequested) {
                    val duration = controller?.duration?.takeIf { it > 0L } ?: currentItem?.durationMs ?: 0L
                    publishState(overridePositionMs = duration)
                    syncLyricsAndCaptionNotifications()
                    updateTicker()
                    return
                }
                onEpisodeCompleted()
                return
            }
            if (playbackState == Player.STATE_READY) {
                youTubeRetryEpisodeId = null
                seekRebufferSuppressUntilElapsedMs = 0L
            }
            val isTransientSeekRebuffer = shouldSuppressSeekRebuffer(
                playbackState = playbackState,
                nowElapsedMs = SystemClock.elapsedRealtime(),
                suppressUntilElapsedMs = seekRebufferSuppressUntilElapsedMs,
                currentState = _playbackState.value,
                currentItemId = currentItem?.episodeId,
            )
            if (isTransientSeekRebuffer) {
                return
            }
            publishState()
            syncLyricsAndCaptionNotifications()
            updateTicker()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
            ) {
                val controller = mediaController
                val duration = controller?.duration?.takeIf { it > 0L } ?: currentItem?.durationMs ?: 0L
                val rawPos = if (duration > 0L) {
                    newPosition.positionMs.coerceIn(0L, duration)
                } else {
                    newPosition.positionMs.coerceAtLeast(0L)
                }
                val resolved = resolveGuardedPosition(rawPos, duration, clearGuardOnMatch = true)
                _playbackPositionMs.value = resolved
                val prev = _playbackState.value
                if (prev is PlaybackState.Paused && prev.item == currentItem) {
                    val effectiveDur = duration.takeIf { it > 0L } ?: prev.durationMs
                    if (prev.positionMs != resolved || prev.durationMs != effectiveDur) {
                        _playbackState.value = prev.copy(positionMs = resolved, durationMs = effectiveDur)
                    }
                }
                captionSyncEngine.onSeek(resolved)
                syncLyricsAndCaptionNotifications()
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            val speed = playbackParameters.speed.coerceIn(0.5f, 2.5f)
            if (_playbackSpeed.value != speed) {
                _playbackSpeed.value = speed
                publishState()
                if (mediaController?.isPlaying == true) {
                    stopTicker()
                    updateTicker()
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                captionSyncEngine.onSeek(0L)
                publishState(overridePositionMs = 0L)
                syncLyricsAndCaptionNotifications()
                return
            }
            if (mediaItem == null || mediaItem.mediaId == currentItem?.episodeId) return
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
            ) {
                handleGaplessTransitionTo(mediaItem.mediaId)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            stopTicker()
            captionNotificationController.dismiss()
            lockScreenLyricsNotificationController.dismiss()
            val item = currentItem
            val isYouTubeStream = item?.sourceType == MediaSourceType.YouTubeReference &&
                item.linkedAudioUri.isNullOrBlank()
            val episode = currentEpisode
            val videoId = currentYouTubeStream?.videoId
                ?: episode?.let(::youTubeVideoId)
            if (isYouTubeStream && episode != null && videoId != null &&
                youTubeRetryEpisodeId != episode.id
            ) {
                youTubeRetryEpisodeId = episode.id
                val resumePositionMs = (mediaController?.currentPosition ?: 0L).coerceAtLeast(0L)
                val wasPlaying = mediaController?.playWhenReady == true
                scope.launch {
                    youTubeAudioResolver.invalidate(videoId)
                    prepareEpisode(
                        episode = episode,
                        autoPlay = wasPlaying,
                        startPositionMs = resumePositionMs,
                        youTubeRetry = true,
                    )
                }
                return
            }
            if (isYouTubeStream && episode != null) {
                val url = episode.youtubeUrl ?: episode.sourceUri
                if (!url.isNullOrBlank()) {
                    _uiNotices.tryEmit(PlaybackUiNotice.YouTubePlaybackHandoff(episode, url))
                }
                _playbackState.value = PlaybackState.Failed(
                    "YouTube audio stream expired or was blocked. Open the video in YouTube or try again.",
                    item,
                )
                return
            }
            val isRssStreamError = item?.sourceType == MediaSourceType.RssPodcast
            if (item?.sourceType == MediaSourceType.LocalMusic && !item.sourceUri.isNullOrBlank()) {
                val sourceUri = item.sourceUri
                scope.launch {
                    val accessible = withContext(Dispatchers.IO) { isUriAccessible(sourceUri) }
                    val message = if (!accessible) {
                        "Storage disconnected or audio file is no longer accessible. Playback stopped safely."
                    } else {
                        "Cannot decode or play audio: ${error.errorCodeName} (${error.localizedMessage ?: "unsupported codec or damaged file"})"
                    }
                    _playbackState.value = PlaybackState.Failed(message, item)
                    _uiNotices.tryEmit(PlaybackUiNotice.Info("Playback unavailable", message))
                }
                return
            }
            val message = if (isRssStreamError) {
                "Unable to stream podcast episode (network interrupted or audio link expired): ${error.errorCodeName}."
            } else {
                "Cannot decode or play audio: ${error.errorCodeName} (${error.localizedMessage ?: "unsupported codec or damaged file"})"
            }
            _playbackState.value = PlaybackState.Failed(message, item)
            _uiNotices.tryEmit(PlaybackUiNotice.Info("Playback unavailable", message))
        }
    }

    init {
        isScreenInteractive = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(context, screenStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            screenStateReceiverRegistered = true
        }
        scope.launch {
            preferences.settings.collect { settings ->
                skipBackwardSec = settings.skipBackwardSeconds
                skipForwardSec = settings.skipForwardSeconds
                sleepFadeOutSec = settings.sleepFadeOutSeconds
                resumePreviousPlayback = settings.resumePreviousPlayback
                gaplessPlayback = settings.gaplessPlayback
                crossfadeDurationMs = settings.crossfadeDurationMs
                removePlayedFromQueue = settings.removePlayedFromQueue
                preferredTranscriptLanguage = settings.preferredTranscriptLanguage
                showLockScreenCaptions = settings.showLockScreenCaptions
                showTranscriptNotification = settings.showTranscriptNotification
                showLockScreenLyrics = settings.showLockScreenLyrics
                lockScreenLyricsExpanded = settings.lockScreenLyricsExpanded
                showLyricsTranslation = settings.showLyricsTranslation
                if (currentItem == null) {
                    _playbackSpeed.value = settings.defaultPlaybackSpeed
                }
                syncLyricsAndCaptionNotifications()
                if (mediaController?.isPlaying == true) {
                    stopTicker()
                    updateTicker()
                }
                preloadNextGaplessItemIfNeeded()
            }
        }
        scope.launch {
            sleepTimer.map { it.preset to it.remainingTracks }.distinctUntilChanged().collect { preloadNextGaplessItemIfNeeded() }
        }
        scope.launch {
            captionSyncEngine.snapshot.collect {
                syncLyricsAndCaptionNotifications()
            }
        }
        usbStorageMonitor?.let { monitor ->
            scope.launch {
                monitor.events.collect { event ->
                    if (event is UsbStorageEvent.Detached) {
                        val active = currentEpisode
                        val activeUri = active?.sourceUri
                        if (active != null && active.sourceType == MediaSourceType.LocalMusic && activeUri != null) {
                            val stillReadable = withContext(Dispatchers.IO) { isUriAccessible(activeUri) }
                            if (!stillReadable) {
                                pause()
                                val msg = "${event.deviceName} was disconnected during playback. Playback stopped safely."
                                _playbackState.value = PlaybackState.Failed(msg, currentItem)
                                _uiNotices.tryEmit(PlaybackUiNotice.Info("USB storage disconnected", msg))
                            }
                        }
                    }
                }
            }
        }
        connect()
    }

    private fun connect() {
        val token = SessionToken(context, ComponentName(context, PodcastPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                runCatching { future.get() }.onSuccess { controller ->
                    mediaController = controller
                    controller.addListener(playerListener)
                    controller.playbackParameters = PlaybackParameters(_playbackSpeed.value)
                    pendingPlayAction?.let { action ->
                        pendingPlayAction = null
                        action()
                    } ?: restoreLastSessionIfIdle()
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun restoreLastSessionIfIdle() {
        val controller = mediaController ?: return
        if (controller.mediaItemCount > 0 || currentItem != null) return
        restoreSessionJob?.cancel()
        restoreSessionJob = scope.launch {
            repository.ensureSeeded()
            val activeQueue = repository.ensureDefaultMusicQueue()
            val settings = preferences.settings.first()
            if (!settings.resumePreviousPlayback) {
                _queue.value = PlaybackQueueSnapshot(
                    activeQueueId = activeQueue.id,
                    activeQueueName = activeQueue.name,
                    shuffle = activeQueue.shuffle,
                    repeatMode = activeQueue.repeatMode,
                )
                return@launch
            }
            if (activeQueue.episodeIds.isNotEmpty()) {
                val targetId = activeQueue.currentEpisodeId?.takeIf { it in activeQueue.episodeIds }
                    ?: activeQueue.episodeIds.first()
                val episode = repository.getEpisodeById(targetId)
                if (episode != null) {
                    originalQueueIds = activeQueue.episodeIds
                    val idx = activeQueue.episodeIds.indexOf(targetId).coerceAtLeast(0)
                    _queue.value = PlaybackQueueSnapshot(
                        activeQueueId = activeQueue.id,
                        activeQueueName = activeQueue.name,
                        activeEpisodeIds = activeQueue.episodeIds,
                        currentIndex = idx,
                        shuffle = activeQueue.shuffle,
                        repeatMode = activeQueue.repeatMode,
                    )
                    val startPos = activeQueue.currentPositionMs.takeIf { it > 0L } ?: episode.playbackPositionMs
                    prepareEpisode(episode, autoPlay = false, startPositionMs = startPos)
                    return@launch
                }
            }
            val lastId = settings.lastEpisodeId ?: return@launch
            val episode = repository.getEpisodeById(lastId) ?: return@launch
            val allInPodcast = repository.getEpisodesByIds(listOf(lastId))
                .ifEmpty { listOf(episode) }
                .map { it.id }
            val queueIds = if (lastId in allInPodcast) allInPodcast else listOf(lastId)
            originalQueueIds = queueIds
            val index = queueIds.indexOf(lastId).coerceAtLeast(0)
            _queue.value = PlaybackQueueSnapshot(
                activeQueueId = activeQueue.id,
                activeQueueName = activeQueue.name,
                activeEpisodeIds = queueIds,
                currentIndex = index,
                shuffle = activeQueue.shuffle,
                repeatMode = activeQueue.repeatMode,
            )
            persistActiveQueueSnapshot()
            prepareEpisode(episode, autoPlay = false, startPositionMs = episode.playbackPositionMs)
        }
    }

    fun playEpisode(episodeId: String, contextEpisodeIds: List<String> = listOf(episodeId), shuffle: Boolean = false) {
        pauseRequested = false
        restoreSessionJob?.cancel()
        onLocalPlaybackStarting?.invoke()
        prepareEpisodeJob?.cancel()
        prepareEpisodeJob = scope.launch {
            val activeQueue = repository.ensureDefaultMusicQueue()
            val (boundedOriginal, activeIds) = withContext(Dispatchers.Default) {
                val baseQueue = contextEpisodeIds.distinct().ifEmpty { listOf(episodeId) }
                val safeQueue = if (episodeId in baseQueue) baseQueue else listOf(episodeId) + baseQueue
                if (shuffle) {
                    val rest = safeQueue.filterNot { it == episodeId }.shuffled().take(MaxPlaybackQueueSize - 1)
                    val active = listOf(episodeId) + rest
                    active to active
                } else {
                    val windowed = windowQueueAround(safeQueue, episodeId, MaxPlaybackQueueSize)
                    windowed to windowed
                }
            }
            originalQueueIds = boundedOriginal
            val startIndex = activeIds.indexOf(episodeId).coerceAtLeast(0)
            _queue.value = _queue.value.copy(
                activeQueueId = activeQueue.id,
                activeQueueName = activeQueue.name,
                activeEpisodeIds = activeIds,
                currentIndex = startIndex,
                shuffle = shuffle,
            )
            persistActiveQueueSnapshot()
            loadEpisodeAtCurrentIndex(autoPlay = true, isSkip = currentEpisode != null)
        }
    }

    private fun windowQueueAround(ids: List<String>, targetId: String, maxSize: Int): List<String> {
        if (ids.size <= maxSize) return ids
        val targetIdx = ids.indexOf(targetId).coerceAtLeast(0)
        val halfBefore = (maxSize / 4).coerceAtLeast(1)
        val start = (targetIdx - halfBefore).coerceIn(0, (ids.size - maxSize).coerceAtLeast(0))
        val end = (start + maxSize).coerceAtMost(ids.size)
        return ids.subList(start, end)
    }

    fun playEpisodes(episodeIds: List<String>, shuffle: Boolean = false) {
        if (episodeIds.isEmpty()) return
        val distinct = episodeIds.distinct()
        val firstId = if (shuffle) distinct.random() else distinct.first()
        playEpisode(firstId, distinct, shuffle = shuffle)
    }

    /**
     * Streams a legitimate HTTPS podcast audio URL progressively through the existing single
     * Media3 ExoPlayer instance without downloading or writing media files to device storage.
     */
    fun playDirectOnlineStream(
        streamUrl: String,
        title: String,
        subtitle: String,
    ) {
        val streamId = "online_stream_${streamUrl.hashCode()}"
        val item = PlaybackItem(
            episodeId = streamId,
            podcastId = "online_podcast_stream",
            hostId = "online_podcast_host",
            title = title.ifBlank { "Online Podcast Stream" },
            podcastTitle = subtitle.ifBlank { "Online Stream" },
            hostName = subtitle.ifBlank { "Online Stream" },
            artworkAsset = null,
            audioAsset = streamUrl,
            durationMs = 0L,
            sourceType = MediaSourceType.RssPodcast,
            sourceUri = streamUrl,
        )
        currentEpisode = null
        currentItem = item
        sessionAccumulatedSeconds = 0.0
        lastTickTimestampMs = System.currentTimeMillis()
        _playbackState.value = PlaybackState.Preparing(item)

        val applyToController: () -> Unit = {
            val controller = mediaController
            if (controller != null) {
                val mediaItem = MediaItem.Builder()
                    .setMediaId(streamId)
                    .setUri(Uri.parse(streamUrl))
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(item.title)
                            .setArtist(item.podcastTitle)
                            .setAlbumTitle(item.podcastTitle)
                            .build(),
                    )
                    .build()
                controller.setMediaItem(mediaItem, 0L)
                controller.playbackParameters = PlaybackParameters(_playbackSpeed.value)
                controller.prepare()
                controller.play()
                publishState(overridePositionMs = 0L)
                updateTicker()
            }
        }
        if (mediaController != null) applyToController() else pendingPlayAction = applyToController
    }

    fun playRawPcmWithConfig(episodeId: String, config: RawPcmConfig) {
        pauseRequested = false
        scope.launch {
            repository.saveRawPcmConfig(config)
            val episode = repository.getEpisodeById(episodeId) ?: return@launch
            prepareEpisode(episode, autoPlay = true, startPositionMs = episode.playbackPositionMs, skipPcmPrompt = true)
        }
    }

    fun selectTranscriptLanguage(language: String) {
        val doc = _availableTranscripts.value.firstOrNull { it.language.equals(language, ignoreCase = true) } ?: return
        _activeTranscriptDocument.value = doc
        val pos = mediaController?.currentPosition ?: currentEpisode?.playbackPositionMs ?: 0L
        captionSyncEngine.loadDocument(doc, initialPositionMs = pos)
        syncLyricsAndCaptionNotifications()
        scope.launch { preferences.setPreferredTranscriptLanguage(language) }
    }

    fun refreshTranscriptForCurrentEpisode() {
        val episode = currentEpisode ?: return
        scope.launch {
            val refreshed = repository.getEpisodeById(episode.id) ?: episode
            currentEpisode = refreshed
            loadTranscriptForEpisode(refreshed, mediaController?.currentPosition ?: refreshed.playbackPositionMs)
        }
    }

    fun refreshCurrentEpisode(episodeId: String? = null) {
        val targetId = episodeId ?: currentEpisode?.id ?: return
        if (currentEpisode?.id != targetId) return
        scope.launch {
            val refreshed = repository.getEpisodeById(targetId) ?: return@launch
            if (currentEpisode?.id != targetId) return@launch
            val oldArtwork = currentItem?.artworkAsset
            currentEpisode = refreshed
            val updatedItem = currentItem?.copy(
                title = refreshed.title,
                podcastTitle = refreshed.podcastTitle,
                hostName = refreshed.hostName,
                artworkAsset = refreshed.artworkAsset,
                audioAsset = refreshed.audioAsset,
                sourceUri = refreshed.sourceUri,
                linkedAudioUri = refreshed.linkedAudioUri,
            )
            if (updatedItem != null && updatedItem != currentItem) {
                currentItem = updatedItem
                if (oldArtwork != updatedItem.artworkAsset) {
                    com.podcasty.app.ui.components.invalidateArtworkCache(oldArtwork)
                    com.podcasty.app.ui.components.invalidateArtworkCache(updatedItem.artworkAsset)
                }
                val prev = _playbackState.value
                _playbackState.value = when (prev) {
                    is PlaybackState.Playing -> prev.copy(item = updatedItem)
                    is PlaybackState.Paused -> prev.copy(item = updatedItem)
                    is PlaybackState.Preparing -> prev.copy(item = updatedItem)
                    is PlaybackState.Failed -> prev.copy(item = updatedItem)
                    else -> prev
                }
                // Seamlessly sync Media3 lock-screen / notification metadata without interrupting audio
                val controller = mediaController
                val activeMediaItem = controller?.currentMediaItem
                val activeUri = activeMediaItem?.localConfiguration?.uri?.toString()
                if (controller != null && activeMediaItem?.mediaId == targetId && !activeUri.isNullOrBlank()) {
                    val artworkData = loadArtworkBytesForMediaSession(refreshed.artworkAsset)
                    if (currentEpisode?.id == targetId && controller.currentMediaItem?.mediaId == targetId) {
                        val nextMediaItem = buildMediaItem(
                            episode = refreshed,
                            playbackUriStr = activeUri,
                            mimeType = activeMediaItem.localConfiguration?.mimeType,
                            artworkData = artworkData,
                        )
                        runCatching {
                            controller.replaceMediaItem(controller.currentMediaItemIndex, nextMediaItem)
                        }
                    }
                }
                syncLyricsAndCaptionNotifications()
            }
        }
    }

    fun selectQueueEpisode(episodeId: String) {
        pauseRequested = false
        val snapshot = _queue.value
        val targetIndex = snapshot.activeEpisodeIds.indexOf(episodeId)
        if (targetIndex < 0) {
            playEpisode(episodeId)
            return
        }
        if (targetIndex == snapshot.currentIndex && currentItem?.episodeId == episodeId) {
            resume()
            return
        }
        prepareEpisodeJob?.cancel()
        prepareEpisodeJob = scope.launch {
            _queue.value = snapshot.copy(currentIndex = targetIndex)
            persistActiveQueueSnapshot()
            loadEpisodeAtCurrentIndex(autoPlay = true, isSkip = true)
        }
    }

    fun playNext(episodeId: String) {
        val snapshot = _queue.value
        if (snapshot.activeEpisodeIds.isEmpty()) {
            playEpisode(episodeId)
            return
        }
        if (snapshot.currentEpisodeId == episodeId) return
        if (episodeId !in snapshot.activeEpisodeIds && snapshot.activeEpisodeIds.size >= MaxPlaybackQueueSize) return
        val filtered = snapshot.activeEpisodeIds.filterNot { it == episodeId }.toMutableList()
        val currentId = snapshot.currentEpisodeId
        val insertIndex = (filtered.indexOf(currentId) + 1).coerceIn(0, filtered.size)
        filtered.add(insertIndex, episodeId)
        originalQueueIds = originalQueueIds.filterNot { it == episodeId } + episodeId
        val newCurrentIndex = filtered.indexOf(currentId).coerceAtLeast(0)
        _queue.value = snapshot.copy(activeEpisodeIds = filtered, currentIndex = newCurrentIndex)
        persistActiveQueueSnapshot()
        preloadNextGaplessItemIfNeeded()
    }

    fun addToQueue(episodeIds: List<String>) {
        if (episodeIds.isEmpty()) return
        val snapshot = _queue.value
        if (snapshot.activeEpisodeIds.isEmpty()) {
            playEpisodes(episodeIds)
            return
        }
        val remainingSlots = (MaxPlaybackQueueSize - snapshot.activeEpisodeIds.size).coerceAtLeast(0)
        if (remainingSlots <= 0) return
        val existingSet = snapshot.activeEpisodeIds.toHashSet()
        val toAppend = episodeIds.asSequence().filter { it !in existingSet }.distinct().take(remainingSlots).toList()
        if (toAppend.isEmpty()) return
        originalQueueIds = (originalQueueIds + toAppend).distinct().take(MaxPlaybackQueueSize)
        _queue.value = snapshot.copy(activeEpisodeIds = snapshot.activeEpisodeIds + toAppend)
        persistActiveQueueSnapshot()
        preloadNextGaplessItemIfNeeded()
    }

    fun removeFromQueue(episodeId: String) {
        val snapshot = _queue.value
        val index = snapshot.activeEpisodeIds.indexOf(episodeId)
        if (index < 0) return
        if (episodeId == snapshot.currentEpisodeId) {
            val remaining = snapshot.activeEpisodeIds.filterNot { it == episodeId }
            if (remaining.isEmpty()) {
                clearQueue()
                return
            }
            val wasPlaying = mediaController?.playWhenReady == true
            prepareEpisodeJob?.cancel()
            prepareEpisodeJob = scope.launch {
                flushListeningSession(completed = false, skipped = true)
                persistCurrentPosition(markPlayed = false)
                originalQueueIds = originalQueueIds.filterNot { it == episodeId }
                _queue.value = snapshot.copy(activeEpisodeIds = remaining, currentIndex = index.coerceAtMost(remaining.lastIndex))
                persistActiveQueueSnapshot()
                loadEpisodeAtCurrentIndex(autoPlay = wasPlaying, isSkip = false)
            }
            return
        }
        val updated = snapshot.activeEpisodeIds.toMutableList().apply { removeAt(index) }
        originalQueueIds = originalQueueIds.filterNot { it == episodeId }
        val newCurrentIndex = updated.indexOf(snapshot.currentEpisodeId).coerceAtLeast(0)
        _queue.value = snapshot.copy(activeEpisodeIds = updated, currentIndex = newCurrentIndex)
        persistActiveQueueSnapshot()
        preloadNextGaplessItemIfNeeded()
    }

    fun reorderQueue(orderedEpisodeIds: List<String>) {
        val snapshot = _queue.value
        val currentId = snapshot.currentEpisodeId
        // Reject stale/malformed drag results rather than dropping or injecting queue items.
        if (orderedEpisodeIds.size != snapshot.activeEpisodeIds.size ||
            orderedEpisodeIds.toSet().size != orderedEpisodeIds.size ||
            orderedEpisodeIds.toSet() != snapshot.activeEpisodeIds.toSet()) return
        val newIndex = orderedEpisodeIds.indexOf(currentId).coerceAtLeast(0)
        if (!snapshot.shuffle) {
            originalQueueIds = orderedEpisodeIds
        }
        _queue.value = snapshot.copy(activeEpisodeIds = orderedEpisodeIds, currentIndex = newIndex)
        persistActiveQueueSnapshot()
        preloadNextGaplessItemIfNeeded()
    }

    fun setShuffle(enabled: Boolean) {
        val snapshot = _queue.value
        if (snapshot.shuffle == enabled || snapshot.activeEpisodeIds.isEmpty()) return
        val currentId = snapshot.currentEpisodeId
        val nextIds = if (enabled) {
            val others = snapshot.activeEpisodeIds.filterNot { it == currentId }.shuffled()
            if (currentId != null) listOf(currentId) + others else others
        } else {
            val base = originalQueueIds.filter { it in snapshot.activeEpisodeIds }
            val extra = snapshot.activeEpisodeIds.filter { it !in base }
            base + extra
        }
        val newIndex = nextIds.indexOf(currentId).coerceAtLeast(0)
        _queue.value = snapshot.copy(activeEpisodeIds = nextIds, currentIndex = newIndex, shuffle = enabled)
        persistActiveQueueSnapshot()
        preloadNextGaplessItemIfNeeded()
    }

    fun setRepeatMode(mode: RepeatMode) {
        _queue.value = _queue.value.copy(repeatMode = mode)
        persistActiveQueueSnapshot()
        preloadNextGaplessItemIfNeeded()
    }

    // --- Multiple Persistent Queues (single shared ExoPlayer) ---

    fun createMusicQueue(name: String, initialEpisodeIds: List<String> = emptyList(), activate: Boolean = false) {
        scope.launch {
            val newId = repository.createMusicQueue(name, initialEpisodeIds, activate = activate)
            if (activate) {
                switchToQueueInternal(newId, autoPlay = initialEpisodeIds.isNotEmpty())
            }
        }
    }

    fun renameMusicQueue(queueId: String, newName: String) {
        scope.launch {
            repository.renameMusicQueue(queueId, newName)
            if (_queue.value.activeQueueId == queueId) {
                _queue.value = _queue.value.copy(activeQueueName = newName.trim().ifBlank { _queue.value.activeQueueName })
            }
        }
    }

    fun deleteMusicQueue(queueId: String) {
        scope.launch {
            val wasActive = _queue.value.activeQueueId == queueId
            repository.deleteMusicQueue(queueId)
            if (wasActive) {
                val active = repository.ensureDefaultMusicQueue()
                switchToQueueInternal(active.id, autoPlay = false)
            }
        }
    }

    fun switchToQueue(queueId: String, autoPlay: Boolean = true) {
        pauseRequested = !autoPlay
        scope.launch {
            switchToQueueInternal(queueId, autoPlay = autoPlay)
        }
    }

    fun addEpisodesToSpecificQueue(queueId: String, episodeIds: List<String>, insertNext: Boolean = false) {
        if (episodeIds.isEmpty()) return
        if (queueId == _queue.value.activeQueueId) {
            if (insertNext) {
                episodeIds.asReversed().forEach(::playNext)
            } else {
                addToQueue(episodeIds)
            }
            return
        }
        // Editing another queue never interrupts active playback
        scope.launch {
            repository.addEpisodesToMusicQueue(queueId, episodeIds)
        }
    }

    private suspend fun switchToQueueInternal(queueId: String, autoPlay: Boolean) {
        val currentSnap = _queue.value
        if (currentSnap.activeQueueId != queueId) {
            repository.updateMusicQueuePlaybackState(
                queueId = currentSnap.activeQueueId,
                shuffle = currentSnap.shuffle,
                repeatMode = currentSnap.repeatMode,
                currentEpisodeId = currentSnap.currentEpisodeId,
                currentPositionMs = mediaController?.currentPosition ?: 0L,
            )
        }
        repository.setActiveMusicQueue(queueId)
        val target = repository.getMusicQueues().firstOrNull { it.id == queueId } ?: return
        originalQueueIds = target.episodeIds
        val targetEpisodeId = target.currentEpisodeId?.takeIf { it in target.episodeIds }
            ?: target.episodeIds.firstOrNull()
        val targetIndex = targetEpisodeId?.let { target.episodeIds.indexOf(it) }?.coerceAtLeast(0) ?: -1
        _queue.value = PlaybackQueueSnapshot(
            activeQueueId = target.id,
            activeQueueName = target.name,
            activeEpisodeIds = target.episodeIds,
            currentIndex = targetIndex,
            shuffle = target.shuffle,
            repeatMode = target.repeatMode,
        )
        if (targetEpisodeId != null) {
            if (currentEpisode?.id == targetEpisodeId && mediaController != null) {
                preloadNextGaplessItemIfNeeded()
                if (autoPlay && mediaController?.isPlaying != true) resume()
            } else {
                val episode = repository.getEpisodeById(targetEpisodeId) ?: return
                val startPos = target.currentPositionMs.takeIf { it > 0L } ?: episode.playbackPositionMs
                prepareEpisode(episode, autoPlay = autoPlay, startPositionMs = startPos)
            }
        } else {
            preloadNextGaplessItemIfNeeded()
        }
    }

    private fun persistActiveQueueSnapshot(immediate: Boolean = false) {
        val snap = _queue.value
        val pos = mediaController?.currentPosition ?: 0L
        queuePersistJob?.cancel()
        queuePersistJob = scope.launch {
            if (!immediate) delay(300L)
            repository.ensureDefaultMusicQueue()
            repository.replaceMusicQueueItems(snap.activeQueueId, snap.activeEpisodeIds)
            repository.updateMusicQueuePlaybackState(
                queueId = snap.activeQueueId,
                shuffle = snap.shuffle,
                repeatMode = snap.repeatMode,
                currentEpisodeId = snap.currentEpisodeId,
                currentPositionMs = pos,
            )
        }
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
        if (controller.isPlaying && !PodcastPlaybackService.sharedPausePending) {
            pause()
        } else {
            resume()
        }
    }

    fun pause() {
        pauseRequested = true
        seekRebufferSuppressUntilElapsedMs = 0L
        // Execute Media3 pause immediately before any background Room write
        mediaController?.pause()
        persistCurrentPosition(markPlayed = false)
    }

    fun resume() {
        pauseRequested = false
        restoreSessionJob?.cancel()
        val controller = mediaController ?: return
        val episode = currentEpisode
        if (episode != null && episode.isYouTubeReference &&
            episode.linkedAudioUri.isNullOrBlank() && controller.mediaItemCount == 0
        ) {
            prepareEpisodeJob?.cancel()
            prepareEpisodeJob = scope.launch {
                prepareEpisode(episode, autoPlay = true, startPositionMs = episode.playbackPositionMs)
            }
            return
        }
        if (controller.playbackState == Player.STATE_ENDED) {
            controller.seekTo(0L)
        }
        controller.play()
    }

    fun seekTo(positionMs: Long) {
        val controller = mediaController ?: return
        val duration = controller.duration.takeIf { it > 0L } ?: currentItem?.durationMs ?: 0L
        val target = if (duration > 0L) {
            positionMs.coerceIn(0L, duration)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        // Guard against stale pre-seek Media3 positions and transient STATE_BUFFERING -> Preparing
        // UI flashes in BOTH playing and paused states (seeking must never blank the progress bar,
        // revert elapsed time, jump artwork, or change playing/paused state).
        val nowElapsed = SystemClock.elapsedRealtime()
        seekGuardTargetMs = target
        seekGuardUntilElapsedMs = nowElapsed + SeekGuardWindowMs
        seekRebufferSuppressUntilElapsedMs = nowElapsed + SeekRebufferSuppressWindowMs

        controller.seekTo(target)
        _playbackPositionMs.value = target
        captionSyncEngine.onSeek(target)
        onSeekPerformed?.invoke(target, _playbackSpeed.value)

        // Keep PlaybackState.Paused.positionMs (and newly resolved duration) in sync with target
        // without emitting extra structural updates during active Playing ticks.
        val prev = _playbackState.value
        when {
            prev is PlaybackState.Paused -> {
                val effectiveDur = duration.takeIf { it > 0L } ?: prev.durationMs
                if (prev.positionMs != target || prev.durationMs != effectiveDur) {
                    _playbackState.value = prev.copy(positionMs = target, durationMs = effectiveDur)
                }
            }
            prev is PlaybackState.Playing && prev.durationMs <= 0L && duration > 0L -> {
                publishState(overridePositionMs = target)
            }
        }
        syncLyricsAndCaptionNotifications()

        seekPersistJob?.cancel()
        seekPersistJob = scope.launch {
            delay(300L)
            persistCurrentPosition(markPlayed = false, overridePositionMs = target)
        }
    }

    private fun effectiveCurrentPositionMs(): Long {
        val guardTarget = seekGuardTargetMs
        if (guardTarget != null && SystemClock.elapsedRealtime() < seekGuardUntilElapsedMs) {
            return guardTarget
        }
        val controller = mediaController
        return controller?.currentPosition?.coerceAtLeast(0L) ?: _playbackPositionMs.value
    }

    fun skipBackward() {
        seekTo(effectiveCurrentPositionMs() - skipBackwardSec * 1_000L)
    }

    fun skipForward() {
        seekTo(effectiveCurrentPositionMs() + skipForwardSec * 1_000L)
    }

    fun previous() {
        val controller = mediaController
        val position = controller?.currentPosition ?: 0L
        val snapshot = _queue.value
        if (snapshot.activeEpisodeIds.isEmpty()) return
        if (position > 4_000L || snapshot.currentIndex <= 0) {
            if (snapshot.currentIndex <= 0 && snapshot.repeatMode == RepeatMode.All && position <= 4_000L) {
                prepareEpisodeJob?.cancel()
                prepareEpisodeJob = scope.launch {
                    _queue.value = snapshot.copy(currentIndex = snapshot.activeEpisodeIds.lastIndex)
                    persistActiveQueueSnapshot()
                    loadEpisodeAtCurrentIndex(autoPlay = controller?.isPlaying == true, isSkip = true)
                }
            } else {
                seekTo(0L)
            }
            return
        }
        prepareEpisodeJob?.cancel()
        prepareEpisodeJob = scope.launch {
            _queue.value = snapshot.copy(currentIndex = snapshot.currentIndex - 1)
            persistActiveQueueSnapshot()
            loadEpisodeAtCurrentIndex(autoPlay = controller?.isPlaying == true, isSkip = true)
        }
    }

    fun next() {
        val snapshot = _queue.value
        if (snapshot.activeEpisodeIds.isEmpty()) return
        val wasPlaying = mediaController?.isPlaying == true
        val nextIndex = when {
            snapshot.currentIndex < snapshot.activeEpisodeIds.lastIndex -> snapshot.currentIndex + 1
            snapshot.repeatMode == RepeatMode.All -> 0
            else -> -1
        }
        if (nextIndex < 0) {
            mediaController?.pause()
            seekTo(0L)
            return
        }
        prepareEpisodeJob?.cancel()
        prepareEpisodeJob = scope.launch {
            _queue.value = snapshot.copy(currentIndex = nextIndex)
            persistActiveQueueSnapshot()
            loadEpisodeAtCurrentIndex(autoPlay = wasPlaying, isSkip = true)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.5f)
        _playbackSpeed.value = clamped
        mediaController?.playbackParameters = PlaybackParameters(clamped)
        onSpeedChanged?.invoke(clamped, mediaController?.currentPosition ?: 0L)
        scope.launch { preferences.setDefaultPlaybackSpeed(clamped) }
        publishState()
    }

    fun setSleepTimer(preset: SleepTimerPreset) {
        configureSleepTimer(SleepTimerRequest(preset = preset, fadeOutSeconds = sleepFadeOutSec))
    }

    fun configureSleepTimer(request: SleepTimerRequest) {
        val controller = mediaController ?: return
        val args = Bundle().apply {
            putString(PodcastPlaybackService.ExtraPreset, request.preset.name)
            request.customMinutes?.let { putInt(PodcastPlaybackService.ExtraCustomMinutes, it) }
            request.trackCount?.let { putInt(PodcastPlaybackService.ExtraTrackCount, it) }
            putInt(PodcastPlaybackService.ExtraFadeOutSeconds, request.fadeOutSeconds)
            request.deadlineEpochMs?.let { putLong(PodcastPlaybackService.ExtraDeadlineEpochMs, it) }
        }
        controller.sendCustomCommand(
            SessionCommand(PodcastPlaybackService.ActionConfigureSleepTimer, Bundle.EMPTY),
            args,
        )
    }

    fun undoClearQueue() {
        val backup = clearedQueueBackup ?: return
        clearedQueueBackup = null
        val episodeId = backup.first.currentEpisodeId ?: return
        pauseRequested = true
        prepareEpisodeJob?.cancel()
        prepareEpisodeJob = scope.launch {
            val episode = repository.getEpisodeById(episodeId) ?: return@launch
            repository.setActiveMusicQueue(backup.first.activeQueueId)
            originalQueueIds = backup.second
            _queue.value = backup.first
            prepareEpisode(episode, autoPlay = false, startPositionMs = backup.third)
            persistActiveQueueSnapshot()
        }
    }

    fun clearQueue() {
        if (_queue.value.activeEpisodeIds.isNotEmpty()) {
            clearedQueueBackup = Triple(_queue.value, originalQueueIds.toList(), mediaController?.currentPosition ?: 0L)
        }
        flushListeningSession(completed = false, skipped = false)
        persistCurrentPosition(markPlayed = false)
        stopTicker()
        gaplessPreloadJob?.cancel()
        prepareEpisodeJob?.cancel()
        transcriptLoadJob?.cancel()
        seekPersistJob?.cancel()
        setSleepTimer(SleepTimerPreset.Off)
        captionSyncEngine.clear()
        captionNotificationController.dismiss()
        lockScreenLyricsNotificationController.dismiss()
        _availableTranscripts.value = emptyList()
        _activeTranscriptDocument.value = null
        mediaController?.stop()
        mediaController?.clearMediaItems()
        currentEpisode = null
        currentItem = null
        originalQueueIds = emptyList()
        _playbackPositionMs.value = 0L
        _queue.value = _queue.value.copy(activeEpisodeIds = emptyList(), currentIndex = -1)
        _playbackState.value = PlaybackState.Idle
        scope.launch {
            preferences.setLastEpisodeId(null)
            persistActiveQueueSnapshot(immediate = true)
        }
    }

    private suspend fun loadEpisodeAtCurrentIndex(autoPlay: Boolean, isSkip: Boolean) {
        if (isSkip) {
            flushListeningSession(completed = false, skipped = true)
            persistCurrentPosition(markPlayed = false)
        }
        val episodeId = _queue.value.currentEpisodeId ?: return
        val episode = repository.getEpisodeById(episodeId) ?: return
        val resumePos = if (episode.isPlayed || (episode.durationMs > 0L && episode.playbackPositionMs >= episode.durationMs - 4_000L)) {
            0L
        } else {
            episode.playbackPositionMs
        }
        prepareEpisode(episode, autoPlay = autoPlay, startPositionMs = resumePos)
    }

    private suspend fun prepareEpisode(
        episode: Episode,
        autoPlay: Boolean,
        startPositionMs: Long,
        skipPcmPrompt: Boolean = false,
        youTubeRetry: Boolean = false,
    ) {
        if (!youTubeRetry) {
            youTubeRetryEpisodeId = null
        }
        currentYouTubeStream = null
        val item = PlaybackItem(
            episodeId = episode.id,
            podcastId = episode.podcastId,
            hostId = episode.hostId,
            title = episode.title,
            podcastTitle = episode.podcastTitle,
            hostName = episode.hostName,
            artworkAsset = episode.artworkAsset,
            audioAsset = episode.audioAsset,
            durationMs = episode.durationMs,
            sourceType = episode.sourceType,
            sourceUri = episode.sourceUri,
            youtubeUrl = episode.youtubeUrl,
            linkedAudioUri = episode.linkedAudioUri,
        )
        currentEpisode = episode
        currentItem = item
        sessionAccumulatedSeconds = 0.0
        lastTickTimestampMs = System.currentTimeMillis()
        seekGuardTargetMs = null
        seekGuardUntilElapsedMs = 0L
        seekRebufferSuppressUntilElapsedMs = 0L
        _playbackPositionMs.value = startPositionMs.coerceAtLeast(0L)
        scope.launch { preferences.setLastEpisodeId(episode.id) }
        // Load transcripts/embedded lyrics asynchronously so Room/TagLib I/O NEVER blocks audio playback start
        transcriptLoadJob?.cancel()
        transcriptLoadJob = scope.launch {
            loadTranscriptForEpisode(episode, startPositionMs)
        }

        // 1. Audio-only YouTube streaming through the app's native Media3 player.
        if (episode.isYouTubeReference && episode.linkedAudioUri.isNullOrBlank()) {
            prepareYouTubeAudio(
                episode = episode,
                item = item,
                autoPlay = autoPlay,
                startPositionMs = startPositionMs,
                youTubeRetry = youTubeRetry,
            )
            return
        }

        val playbackUriStr = episode.effectivePlaybackUri
        if (playbackUriStr.isNullOrBlank()) {
            val reason = "No playable audio source is linked to this episode."
            _playbackState.value = PlaybackState.Failed(reason, item)
            _uiNotices.tryEmit(PlaybackUiNotice.Info("Audio unavailable", reason))
            return
        }

        // 2. Fast-path for standard local formats: avoid 2 blocking I/O file opens before Media3 starts.
        // Only probe container headers when extension is raw PCM / unknown; missing/revoked files are caught by onPlayerError.
        var formatHint: String? = null
        if (episode.sourceType == MediaSourceType.LocalMusic) {
            val ext = episode.audioAsset.substringAfterLast('.', "").lowercase()
            val needsDeepProbe = ext.isEmpty() || ext in RawOrProbeExtensions
            if (needsDeepProbe) {
                val uri = Uri.parse(playbackUriStr)
                val accessible = withContext(Dispatchers.IO) { isUriAccessible(playbackUriStr) }
                if (!accessible) {
                    val reason = "Audio file is unavailable (permission revoked, file removed, or USB drive disconnected)."
                    _playbackState.value = PlaybackState.Failed(reason, item)
                    _uiNotices.tryEmit(PlaybackUiNotice.Info("File unavailable", reason))
                    return
                }
                val detected = withContext(Dispatchers.IO) {
                    AudioFormatCatalog.detectFromUri(context, uri, episode.audioAsset)
                }
                formatHint = detected.formatId
                if (!detected.isPlayable) {
                    val reason = detected.unsupportedReason
                        ?: "${detected.displayName} (${detected.codec}) is not supported for playback on this device."
                    _playbackState.value = PlaybackState.Failed(reason, item)
                    _uiNotices.tryEmit(PlaybackUiNotice.Info("Unsupported audio format", reason))
                    return
                }
                if (detected.requiresRawPcmParams && !skipPcmPrompt) {
                    val existingPcm = repository.getRawPcmConfig(playbackUriStr)
                    if (existingPcm == null) {
                        _playbackState.value = PlaybackState.Paused(item, 0L, episode.durationMs, _playbackSpeed.value)
                        _uiNotices.tryEmit(PlaybackUiNotice.RawPcmConfigRequired(episode, playbackUriStr))
                        return
                    }
                }
            } else {
                formatHint = ext
            }
        }

        _playbackState.value = PlaybackState.Preparing(item)
        val mediaArtworkData = loadArtworkBytesForMediaSession(episode.artworkAsset)

        val applyToController: () -> Unit = {
            val controller = mediaController
            if (controller != null) {
                val mediaItem = buildMediaItem(
                    episode = episode,
                    playbackUriStr = playbackUriStr,
                    formatHint = formatHint,
                    artworkData = mediaArtworkData,
                )
                controller.setMediaItem(mediaItem, startPositionMs.coerceAtLeast(0L))
                controller.playbackParameters = PlaybackParameters(_playbackSpeed.value)
                controller.prepare()
                persistActiveQueueSnapshot()
                if (autoPlay && !pauseRequested) {
                    controller.play()
                } else {
                    controller.pause()
                }
                publishState(overridePositionMs = startPositionMs)
                syncLyricsAndCaptionNotifications()
                updateTicker()
                preloadNextGaplessItemIfNeeded()
            }
        }

        if (mediaController != null) {
            applyToController()
        } else {
            pendingPlayAction = applyToController
        }
    }

    private suspend fun prepareYouTubeAudio(
        episode: Episode,
        item: PlaybackItem,
        autoPlay: Boolean,
        startPositionMs: Long,
        youTubeRetry: Boolean,
    ) {
        val videoId = youTubeVideoId(episode)
        if (videoId == null) {
            failYouTubePlayback(
                episode = episode,
                item = item,
                reason = "This YouTube link does not contain a supported video ID.",
                showHandoff = autoPlay,
            )
            return
        }

        _playbackState.value = PlaybackState.Preparing(item)
        val stream = youTubeAudioResolver.resolve(videoId).getOrElse { error ->
            failYouTubePlayback(
                episode = episode,
                item = item,
                reason = error.message ?: "Unable to resolve audio for this YouTube video.",
                showHandoff = autoPlay,
            )
            return
        }
        currentYouTubeStream = stream
        if (youTubeRetry) {
            youTubeRetryEpisodeId = null
        }

        val resolvedItem = item.copy(
            durationMs = item.durationMs.takeIf { it > 0L } ?: stream.durationMs,
            sourceUri = stream.url,
        )
        currentItem = resolvedItem
        _playbackState.value = PlaybackState.Preparing(resolvedItem)
        val mediaArtworkData = loadArtworkBytesForMediaSession(episode.artworkAsset)

        val applyToController: () -> Unit = {
            val controller = mediaController
            if (controller != null) {
                val mediaItem = buildMediaItem(
                    episode = episode,
                    playbackUriStr = stream.url,
                    mimeType = stream.mimeType,
                    isYouTubeStream = true,
                    artworkData = mediaArtworkData,
                )
                controller.setMediaItem(mediaItem, startPositionMs.coerceAtLeast(0L))
                controller.playbackParameters = PlaybackParameters(_playbackSpeed.value)
                controller.prepare()
                persistActiveQueueSnapshot()
                if (autoPlay && !pauseRequested) {
                    controller.play()
                } else {
                    controller.pause()
                }
                publishState(overridePositionMs = startPositionMs)
                syncLyricsAndCaptionNotifications()
                updateTicker()
                preloadNextGaplessItemIfNeeded()
            }
        }

        if (mediaController != null) {
            applyToController()
        } else {
            pendingPlayAction = applyToController
        }
    }

    private fun failYouTubePlayback(
        episode: Episode,
        item: PlaybackItem,
        reason: String,
        showHandoff: Boolean,
    ) {
        currentYouTubeStream = null
        _playbackState.value = PlaybackState.Paused(
            item = item,
            positionMs = 0L,
            durationMs = episode.durationMs,
            playbackSpeed = _playbackSpeed.value,
        )
        _uiNotices.tryEmit(PlaybackUiNotice.Info("YouTube audio unavailable", reason))
        val url = episode.youtubeUrl ?: episode.sourceUri
        if (showHandoff && !url.isNullOrBlank()) {
            _uiNotices.tryEmit(PlaybackUiNotice.YouTubePlaybackHandoff(episode, url))
        }
    }

    private fun youTubeVideoId(episode: Episode): String? =
        episode.youtubeUrl?.let(PodcastSourceResolver::extractYouTubeVideoId)
            ?: episode.sourceUri?.let(PodcastSourceResolver::extractYouTubeVideoId)

    private suspend fun loadTranscriptForEpisode(episode: Episode, initialPositionMs: Long) {
        val docs = repository.getTranscriptDocuments(episode.id).toMutableList()
        if (docs.isEmpty() && !episode.transcriptLrc.isNullOrBlank()) {
            docs += TranscriptParser.parse(
                episodeId = episode.id,
                content = episode.transcriptLrc,
                language = episode.transcriptLanguage,
                source = if (episode.isLocalAudioFile) TranscriptSource.EmbeddedTag else TranscriptSource.Seed,
            )
        }
        // Sidecar (.lrc/.srt/.vtt) or embedded TagLib lyrics fallback for local/downloaded audio files
        val audioUriForTags = episode.editableAudioUri
        val hasTimedDocAlready = docs.any { it.isTimed }
        if (!hasTimedDocAlready && !audioUriForTags.isNullOrBlank()) {
            val sidecarDoc = readSidecarTranscriptIfPresent(episode, audioUriForTags)
            if (sidecarDoc != null && sidecarDoc.cues.isNotEmpty()) {
                docs.add(0, sidecarDoc)
            } else {
                val embeddedDoc = readEmbeddedLyricsDocFromAudioUri(episode, audioUriForTags)
                if (embeddedDoc != null && embeddedDoc.cues.isNotEmpty()) {
                    if (embeddedDoc.isTimed) docs.add(0, embeddedDoc) else if (docs.isEmpty()) docs += embeddedDoc
                }
            }
        }
        _availableTranscripts.value = docs
        val pref = preferredTranscriptLanguage.trim()
        val chosen = if (pref.isEmpty() || pref.equals("auto", ignoreCase = true)) {
            docs.firstOrNull { it.isTimed } ?: docs.firstOrNull()
        } else {
            docs.firstOrNull { it.language.equals(pref, ignoreCase = true) && it.isTimed }
                ?: docs.firstOrNull {
                    it.language.substringBefore('-').equals(pref.substringBefore('-'), ignoreCase = true) && it.isTimed
                }
                ?: docs.firstOrNull { it.isTimed }
                ?: docs.firstOrNull { it.language.equals(pref, ignoreCase = true) }
                ?: docs.firstOrNull()
        }
        _activeTranscriptDocument.value = chosen
        if (chosen != null) {
            captionSyncEngine.loadDocument(chosen, initialPositionMs)
            syncLyricsAndCaptionNotifications()
            if (mediaController?.isPlaying == true) {
                stopTicker()
                updateTicker()
            }
        } else {
            captionSyncEngine.clear()
            syncLyricsAndCaptionNotifications()
            // Auto-extract YouTube captions in background for the music player's synchronized lyrics page
            // if a YouTube episode does not yet have a stored transcript
            if (episode.isYouTubeReference) {
                val videoId = youTubeVideoId(episode)
                if (videoId != null) {
                    val requestedLang = pref.takeIf { it.isNotEmpty() && !it.equals("auto", ignoreCase = true) }
                        ?: episode.transcriptLanguage.ifBlank { "en" }
                    scope.launch {
                        val settings = preferences.settings.first()
                        val extraction = youTubeCaptionClient.extractCaptions(
                            videoId = videoId,
                            languageCode = requestedLang,
                            configuredBackendUrl = settings.asrBackendUrl,
                        )
                        if (extraction is YouTubeCaptionExtractionResult.Success && extraction.transcript.cues.isNotEmpty()) {
                            val lrc = extraction.transcript.toLrcContent()
                            val savedDoc = repository.saveTranscript(
                                episodeId = episode.id,
                                rawContent = lrc,
                                fileName = "${videoId}_${extraction.transcript.language}.lrc",
                                language = extraction.transcript.language,
                                source = TranscriptSource.AuthorizedImport,
                            )
                            if (currentEpisode?.id == episode.id) {
                                _availableTranscripts.value = listOf(savedDoc)
                                _activeTranscriptDocument.value = savedDoc
                                val currentPos = mediaController?.currentPosition ?: initialPositionMs
                                captionSyncEngine.loadDocument(savedDoc, currentPos)
                                syncLyricsAndCaptionNotifications()
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun readSidecarTranscriptIfPresent(episode: Episode, audioUriStr: String): TranscriptDocument? =
        withContext(Dispatchers.IO) {
            // 1. Direct filesystem path or file:// URI
            val directFilePath = when {
                audioUriStr.startsWith("/") -> audioUriStr
                audioUriStr.startsWith("file://", ignoreCase = true) -> Uri.parse(audioUriStr).path
                else -> null
            }
            if (!directFilePath.isNullOrBlank()) {
                readSiblingSidecarFromPath(episode, directFilePath)?.let { return@withContext it }
            }

            // 2. SAF content:// tree document URI (e.g. primary:Music/Tamil/song.mp3 -> sibling song.lrc)
            val uri = runCatching { Uri.parse(audioUriStr) }.getOrNull() ?: return@withContext null
            if (uri.scheme.equals("content", ignoreCase = true)) {
                readSiblingSidecarFromSafUri(episode, uri)?.let { return@withContext it }
                // 3. MediaStore content://media/... URI fallback via DATA column if accessible
                readSiblingSidecarFromMediaStoreUri(episode, uri)?.let { return@withContext it }
            }
            null
        }

    private fun readSiblingSidecarFromPath(episode: Episode, filePath: String): TranscriptDocument? {
        val audioFile = File(filePath)
        val parent = audioFile.parentFile ?: return null
        val baseName = audioFile.nameWithoutExtension
        val candidate = listOf("lrc", "srt", "vtt")
            .asSequence()
            .flatMap { ext -> sequenceOf(File(parent, "$baseName.$ext"), File(parent, "$baseName.${ext.uppercase()}")) }
            .firstOrNull { it.exists() && it.isFile && it.canRead() }
            ?: return null
        val raw = runCatching { TranscriptParser.decodeBytesToUnicode(candidate.readBytes()) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return TranscriptParser.parse(
            episodeId = episode.id,
            content = raw,
            fileName = candidate.name,
            language = episode.transcriptLanguage,
            source = TranscriptSource.AuthorizedImport,
        )
    }

    private fun readSiblingSidecarFromSafUri(episode: Episode, docUri: Uri): TranscriptDocument? = runCatching {
        if (!DocumentsContract.isDocumentUri(context, docUri)) return@runCatching null
        val docId = DocumentsContract.getDocumentId(docUri)
        val slashIdx = docId.lastIndexOf('/')
        val parentDocId = when {
            slashIdx > 0 -> docId.substring(0, slashIdx)
            docId.contains(':') -> docId.substringBefore(':') + ":"
            else -> return@runCatching null
        }
        val audioDisplayName = episode.audioAsset.ifBlank { docId.substringAfterLast('/') }
        val baseName = audioDisplayName.substringBeforeLast('.')
        if (baseName.isBlank()) return@runCatching null
        val targetNames = listOf("$baseName.lrc", "$baseName.srt", "$baseName.vtt")
            .map { it.lowercase() }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(docUri, parentDocId)
        var bestMatch: Pair<Int, Pair<String, String>>? = null // priority to (childDocId, displayName)
        context.contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (idCol < 0 || nameCol < 0) return@use
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: continue
                val priority = targetNames.indexOf(name.lowercase())
                if (priority >= 0 && (bestMatch == null || priority < bestMatch!!.first)) {
                    val childId = cursor.getString(idCol) ?: continue
                    bestMatch = priority to (childId to name)
                    if (priority == 0) break
                }
            }
        }
        val (_, match) = bestMatch ?: return@runCatching null
        val sidecarUri = DocumentsContract.buildDocumentUriUsingTree(docUri, match.first)
        val raw = context.contentResolver.openInputStream(sidecarUri)?.use { input ->
            TranscriptParser.decodeBytesToUnicode(input.readBytes())
        }?.takeIf { it.isNotBlank() } ?: return@runCatching null
        TranscriptParser.parse(
            episodeId = episode.id,
            content = raw,
            fileName = match.second,
            language = episode.transcriptLanguage,
            source = TranscriptSource.AuthorizedImport,
        )
    }.getOrNull()

    private fun readSiblingSidecarFromMediaStoreUri(episode: Episode, mediaUri: Uri): TranscriptDocument? = runCatching {
        if (mediaUri.authority != MediaStore.AUTHORITY) return@runCatching null
        @Suppress("DEPRECATION")
        val dataPath = context.contentResolver.query(
            mediaUri,
            arrayOf(MediaStore.Audio.Media.DATA),
            null,
            null,
            null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        if (dataPath.isNullOrBlank()) null else readSiblingSidecarFromPath(episode, dataPath)
    }.getOrNull()

    private suspend fun readEmbeddedLyricsDocFromAudioUri(episode: Episode, audioUriStr: String): TranscriptDocument? =
        withContext(Dispatchers.IO) {
            val tagLyrics = runCatching {
                val pfd = openReadOnlyPfd(audioUriStr) ?: return@runCatching null
                pfd.use { descriptor ->
                    val map = com.kyant.taglib.TagLib.getMetadata(descriptor.dup().detachFd(), false)?.propertyMap
                    map?.entries
                        ?.firstOrNull { (k, v) ->
                            val upper = k.uppercase()
                            (upper == "LYRICS" || upper == "UNSYNCEDLYRICS" || upper == "SYLT" ||
                                upper.startsWith("LYRICS") || upper.startsWith("USLT")) &&
                                v.any { it.isNotBlank() }
                        }
                        ?.value
                        ?.firstOrNull { it.isNotBlank() }
                }
            }.getOrNull()

            val tagDoc = tagLyrics?.let {
                TranscriptParser.parse(
                    episodeId = episode.id,
                    content = it,
                    language = episode.transcriptLanguage,
                    source = TranscriptSource.EmbeddedTag,
                )
            }
            if (tagDoc != null && tagDoc.isTimed) return@withContext tagDoc

            // Fallback: parse raw ID3v2 SYLT binary frames directly when TagLib doesn't expose timed lyrics
            val syltTrack = runCatching {
                val stream = when {
                    audioUriStr.startsWith("/") -> File(audioUriStr).takeIf { it.exists() }?.inputStream()
                    audioUriStr.startsWith("file://", ignoreCase = true) ->
                        File(Uri.parse(audioUriStr).path.orEmpty()).takeIf { it.exists() }?.inputStream()
                    audioUriStr.startsWith("content://", ignoreCase = true) ->
                        context.contentResolver.openInputStream(Uri.parse(audioUriStr))
                    else -> null
                } ?: return@runCatching null
                stream.use { Id3SyltParser.extractFromStream(it) }
            }.getOrNull()
            if (syltTrack != null && syltTrack.cues.isNotEmpty()) {
                return@withContext TranscriptDocument(
                    episodeId = episode.id,
                    language = syltTrack.language,
                    format = com.podcasty.app.transcript.TranscriptFormat.Lrc,
                    source = TranscriptSource.EmbeddedTag,
                    cues = syltTrack.cues,
                )
            }
            tagDoc
        }

    private fun openReadOnlyPfd(audioUriStr: String): android.os.ParcelFileDescriptor? = when {
        audioUriStr.startsWith("/") -> android.os.ParcelFileDescriptor.open(
            File(audioUriStr),
            android.os.ParcelFileDescriptor.MODE_READ_ONLY,
        )
        audioUriStr.startsWith("file://", ignoreCase = true) -> {
            val file = File(Uri.parse(audioUriStr).path.orEmpty())
            if (!file.exists()) null
            else android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        }
        audioUriStr.startsWith("content://", ignoreCase = true) ->
            context.contentResolver.openFileDescriptor(Uri.parse(audioUriStr), "r")
        else -> null
    }

    private fun syncLyricsAndCaptionNotifications() {
        val item = currentItem
        val controller = mediaController
        val state = controller?.playbackState ?: Player.STATE_IDLE
        if (item == null || state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
            lockScreenLyricsNotificationController.dismiss()
            captionNotificationController.dismiss()
            return
        }
        val isPlaying = controller?.isPlaying == true
        val isPaused = !isPlaying && (state == Player.STATE_READY || state == Player.STATE_BUFFERING)

        if (item.sourceType == MediaSourceType.RssPodcast) {
            lockScreenLyricsNotificationController.dismiss()
            captionNotificationController.update(
                episodeTitle = item.title,
                podcastTitle = item.podcastTitle,
                cue = captionSyncEngine.snapshot.value.activeCue,
                isPlaying = isPlaying,
                showOnLockScreen = showLockScreenCaptions,
                showTranscriptNotification = showTranscriptNotification,
            )
        } else {
            captionNotificationController.dismiss()
            lockScreenLyricsNotificationController.update(
                episodeId = item.episodeId,
                songTitle = item.title,
                artistName = item.hostName,
                albumTitle = item.podcastTitle,
                snapshot = captionSyncEngine.snapshot.value,
                isPlaying = isPlaying,
                isPaused = isPaused,
                enabled = showLockScreenLyrics,
                expandedMode = lockScreenLyricsExpanded,
                showTranslation = showLyricsTranslation,
                isScreenInteractive = isScreenInteractive,
            )
        }
    }

    private fun onEpisodeCompleted() {
        flushListeningSession(completed = true, skipped = false)
        persistCurrentPosition(markPlayed = true, overridePositionMs = 0L)
        captionNotificationController.dismiss()
        lockScreenLyricsNotificationController.dismiss()

        val controller = mediaController
        val sleepFuture = controller?.sendCustomCommand(
            SessionCommand(PodcastPlaybackService.ActionNotifyTrackCompleted, Bundle.EMPTY),
            Bundle.EMPTY,
        )
        if (sleepFuture != null) {
            sleepFuture.addListener(
                {
                    val shouldStop = runCatching {
                        sleepFuture.get().extras.getBoolean(PodcastPlaybackService.ExtraShouldStop, false)
                    }.getOrDefault(false)
                    if (shouldStop) {
                        mediaController?.pause()
                        publishState(overridePositionMs = 0L)
                    } else {
                        advanceAfterCompletion()
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
        } else {
            advanceAfterCompletion()
        }
    }

    private fun removeCompletedQueueEntry(episodeId: String?) {
        if (!removePlayedFromQueue || episodeId == null || _queue.value.repeatMode != RepeatMode.Off) return
        val snapshot = _queue.value
        val remaining = snapshot.activeEpisodeIds.filterNot { it == episodeId }
        originalQueueIds = originalQueueIds.filterNot { it == episodeId }
        _queue.value = snapshot.copy(activeEpisodeIds = remaining, currentIndex = remaining.indexOf(snapshot.currentEpisodeId))
        persistActiveQueueSnapshot()
    }

    private fun advanceAfterCompletion() {
        val previous = _queue.value
        val nextId = previous.activeEpisodeIds.getOrNull(previous.currentIndex + 1)
        removeCompletedQueueEntry(previous.currentEpisodeId)
        if (removePlayedFromQueue && previous.repeatMode == RepeatMode.Off) {
            val updated = _queue.value
            val nextIndex = updated.activeEpisodeIds.indexOf(nextId)
            if (nextIndex >= 0) {
                scope.launch {
                    _queue.value = updated.copy(currentIndex = nextIndex)
                    loadEpisodeAtCurrentIndex(autoPlay = true, isSkip = false)
                }
            } else if (updated.activeEpisodeIds.isEmpty()) {
                clearQueue()
            } else {
                mediaController?.pause()
                publishState()
            }
            return
        }
        val snapshot = _queue.value
        when {
            snapshot.repeatMode == RepeatMode.One -> {
                mediaController?.seekTo(0L)
                mediaController?.play()
            }
            snapshot.currentIndex < snapshot.activeEpisodeIds.lastIndex -> {
                scope.launch {
                    _queue.value = snapshot.copy(currentIndex = snapshot.currentIndex + 1)
                    loadEpisodeAtCurrentIndex(autoPlay = true, isSkip = false)
                }
            }
            snapshot.repeatMode == RepeatMode.All && snapshot.activeEpisodeIds.isNotEmpty() -> {
                scope.launch {
                    _queue.value = snapshot.copy(currentIndex = 0)
                    loadEpisodeAtCurrentIndex(autoPlay = true, isSkip = false)
                }
            }
            else -> {
                mediaController?.pause()
                publishState()
                syncLyricsAndCaptionNotifications()
            }
        }
    }

    private fun resolveGuardedPosition(
        rawPositionMs: Long,
        durationMs: Long,
        clearGuardOnMatch: Boolean,
    ): Long {
        val clampedRaw = if (durationMs > 0L) rawPositionMs.coerceIn(0L, durationMs) else rawPositionMs.coerceAtLeast(0L)
        val guardTarget = seekGuardTargetMs
        val nowElapsed = SystemClock.elapsedRealtime()
        val result = resolveGuardedSeekPosition(
            rawPositionMs = clampedRaw,
            durationMs = durationMs,
            guardTargetMs = guardTarget,
            nowElapsedMs = nowElapsed,
            guardUntilElapsedMs = seekGuardUntilElapsedMs,
        )
        if (guardTarget != null && (result.guardCleared || (clearGuardOnMatch && result.matchedTarget))) {
            seekGuardTargetMs = null
            seekGuardUntilElapsedMs = 0L
        }
        return result.positionMs
    }

    private fun publishState(overridePositionMs: Long? = null) {
        val item = currentItem ?: return
        val controller = mediaController ?: return
        val duration = controller.duration.takeIf { it > 0L } ?: item.durationMs
        val position = if (overridePositionMs != null) {
            if (duration > 0L) overridePositionMs.coerceIn(0L, duration) else overridePositionMs.coerceAtLeast(0L)
        } else {
            resolveGuardedPosition(
                rawPositionMs = controller.currentPosition,
                durationMs = duration,
                clearGuardOnMatch = controller.playbackState == Player.STATE_READY,
            )
        }
        val speed = _playbackSpeed.value
        _playbackPositionMs.value = position
        captionSyncEngine.onPositionChanged(position)
        val prev = _playbackState.value
        val nextState = when {
            controller.playbackState == Player.STATE_BUFFERING && !controller.isPlaying -> {
                val suppressTransientSeek = shouldSuppressSeekRebuffer(
                    playbackState = controller.playbackState,
                    nowElapsedMs = SystemClock.elapsedRealtime(),
                    suppressUntilElapsedMs = seekRebufferSuppressUntilElapsedMs,
                    currentState = prev,
                    currentItemId = item.episodeId,
                )
                if (suppressTransientSeek) {
                    if (prev is PlaybackState.Paused && (prev.positionMs != position || (duration > 0L && prev.durationMs != duration))) {
                        prev.copy(positionMs = position, durationMs = duration.takeIf { it > 0L } ?: prev.durationMs)
                    } else {
                        prev
                    }
                } else {
                    PlaybackState.Preparing(item)
                }
            }
            controller.isPlaying -> {
                if (prev is PlaybackState.Playing &&
                    prev.item == item &&
                    prev.durationMs == duration &&
                    prev.playbackSpeed == speed
                ) {
                    prev
                } else {
                    PlaybackState.Playing(item, position, duration, speed)
                }
            }
            else -> {
                if (prev is PlaybackState.Paused &&
                    prev.item == item &&
                    prev.positionMs == position &&
                    prev.durationMs == duration &&
                    prev.playbackSpeed == speed
                ) {
                    prev
                } else {
                    PlaybackState.Paused(item, position, duration, speed)
                }
            }
        }
        if (nextState != prev) {
            _playbackState.value = nextState
        }
        onPlaybackActiveChanged?.invoke(controller.isPlaying)
    }

    private fun updateTicker() {
        val isPlaying = mediaController?.isPlaying == true
        if (isPlaying && positionTickerJob == null) {
            lastTickTimestampMs = System.currentTimeMillis()
            positionTickerJob = scope.launch {
                while (isActive) {
                    val hasUiCollectors = _playbackPositionMs.subscriptionCount.value > 0 ||
                        captionSyncEngine.hasActiveSubscribers
                    val snapshot = captionSyncEngine.snapshot.value
                    val lockScreenNotifActive = isScreenInteractive && snapshot.isTimed && (
                        (currentItem?.sourceType != MediaSourceType.RssPodcast && showLockScreenLyrics) ||
                            (currentItem?.sourceType == MediaSourceType.RssPodcast && (showLockScreenCaptions || showTranscriptNotification))
                        )
                    val baseDelayMs = computeBackgroundTickerDelayMs(
                        hasUiCollectors = hasUiCollectors,
                        isScreenInteractive = isScreenInteractive,
                        lockScreenLyricsActive = lockScreenNotifActive,
                        currentPositionMs = _playbackPositionMs.value,
                        nextCueStartMs = snapshot.nextCueStartMs,
                        playbackSpeed = _playbackSpeed.value,
                    )
                    delay(baseDelayMs)
                    val now = System.currentTimeMillis()
                    val deltaSec = ((now - lastTickTimestampMs).coerceIn(0L, 30_000L)) / 1000.0
                    lastTickTimestampMs = now
                    val controller = mediaController
                    if (controller?.isPlaying == true) {
                        sessionAccumulatedSeconds += deltaSec
                        val item = currentItem
                        val duration = controller.duration.takeIf { it > 0L } ?: item?.durationMs ?: 0L
                        val position = resolveGuardedPosition(
                            rawPositionMs = controller.currentPosition,
                            durationMs = duration,
                            clearGuardOnMatch = true,
                        )
                        // Emit ONLY to isolated playbackPositionMs flow (zero root Compose recomposition!)
                        _playbackPositionMs.value = position
                        captionSyncEngine.onPositionChanged(position)
                        // Only emit structural _playbackState if duration was previously unknown and just resolved
                        val prevState = _playbackState.value
                        if (prevState is PlaybackState.Playing && prevState.durationMs <= 0L && duration > 0L) {
                            _playbackState.value = prevState.copy(positionMs = position, durationMs = duration)
                        }
                        if (now - lastProgressPersistMs >= 30_000L) {
                            lastProgressPersistMs = now
                            persistCurrentPosition(markPlayed = false)
                        }
                    }
                }
            }
        } else if (!isPlaying) {
            stopTicker()
        }
    }

    private fun stopTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = null
    }

    private fun persistCurrentPosition(markPlayed: Boolean, overridePositionMs: Long? = null) {
        val episode = currentEpisode ?: return
        val controller = mediaController ?: return
        val duration = controller.duration.takeIf { it > 0L } ?: episode.durationMs
        val position = (overridePositionMs ?: effectiveCurrentPositionMs()).coerceAtLeast(0L)
        scope.launch {
            repository.updatePlaybackProgress(
                episodeId = episode.id,
                positionMs = position,
                durationMs = duration,
                markPlayed = markPlayed || (duration > 0L && position >= duration - 3_000L),
            )
        }
    }

    private fun flushListeningSession(completed: Boolean, skipped: Boolean) {
        val episode = currentEpisode ?: return
        val seconds = sessionAccumulatedSeconds.toInt()
        sessionAccumulatedSeconds = 0.0
        if (seconds < 3 && !completed) return
        scope.launch {
            repository.recordListeningSession(
                episode = episode,
                listenedSeconds = seconds.coerceAtLeast(1),
                completed = completed,
                skipped = skipped,
            )
        }
    }

    private fun isUriAccessible(uriString: String): Boolean = runCatching {
        context.contentResolver.openAssetFileDescriptor(Uri.parse(uriString), "r")?.use { true } ?: false
    }.getOrDefault(false)

    private suspend fun loadArtworkBytesForMediaSession(artworkAsset: String?): ByteArray? =
        withContext(Dispatchers.IO) {
            val path = artworkAsset?.takeIf { it.isNotBlank() } ?: return@withContext null
            val file = when {
                path.startsWith("/") -> File(path)
                path.startsWith("file://", ignoreCase = true) -> File(Uri.parse(path).path.orEmpty())
                else -> return@withContext null
            }
            if (!file.exists() || !file.isFile || file.length() <= 0L) return@withContext null
            runCatching {
                val raw = file.readBytes()
                if (raw.size <= 384 * 1024) {
                    return@runCatching raw
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 512 && bounds.outHeight / (sample * 2) >= 512) {
                    sample *= 2
                }
                val decoded = BitmapFactory.decodeByteArray(
                    raw,
                    0,
                    raw.size,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                ) ?: return@runCatching null
                try {
                    ByteArrayOutputStream().use { out ->
                        decoded.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        out.toByteArray()
                    }
                } finally {
                    decoded.recycle()
                }
            }.getOrNull()
        }

    private fun buildMediaItem(
        episode: Episode,
        playbackUriStr: String,
        mimeType: String? = null,
        formatHint: String? = null,
        isYouTubeStream: Boolean = false,
        artworkData: ByteArray? = null,
    ): MediaItem {
        val extras = Bundle().apply {
            if (isYouTubeStream) putBoolean(PodcastPlaybackService.ExtraYouTubeStream, true)
            mimeType?.let { putString(PodcastPlaybackService.ExtraStreamMimeType, it) }
            formatHint?.let { putString(PodcastPlaybackService.ExtraFormatHint, it) }
            putString(PodcastPlaybackService.ExtraFileName, episode.audioAsset)
        }
        val artworkUri = episode.artworkAsset?.let { path ->
            when {
                path.startsWith("/") -> Uri.fromFile(File(path))
                path.startsWith("file://", ignoreCase = true) ||
                    path.startsWith("https://", ignoreCase = true) ||
                    path.startsWith("http://", ignoreCase = true) -> Uri.parse(path)
                else -> null
            }
        }
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(episode.title)
            .setArtist(episode.hostName)
            .setAlbumTitle(episode.podcastTitle)
            .setArtworkUri(artworkUri)
            .setExtras(extras)
        if (artworkData != null && artworkData.isNotEmpty()) {
            metadataBuilder.setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        val builder = MediaItem.Builder()
            .setMediaId(episode.id)
            .setUri(Uri.parse(playbackUriStr))
            .setMediaMetadata(metadataBuilder.build())
        if (!mimeType.isNullOrBlank()) {
            builder.setMimeType(mimeType)
        }
        return builder.build()
    }

    private fun trimTrailingMediaItems(controller: MediaController) {
        while (controller.mediaItemCount > controller.currentMediaItemIndex + 1) {
            controller.removeMediaItem(controller.mediaItemCount - 1)
        }
    }

    private fun preloadNextGaplessItemIfNeeded() {
        val controller = mediaController ?: return
        val snapshot = _queue.value
        val targetRepeatMode = if (snapshot.repeatMode == RepeatMode.One && !sleepTimer.value.isActive) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
        if (controller.repeatMode != targetRepeatMode) {
            controller.repeatMode = targetRepeatMode
        }
        val timer = sleepTimer.value
        val stopAtBoundary = timer.preset == SleepTimerPreset.EndOfEpisode ||
            (timer.preset == SleepTimerPreset.AfterTracks && timer.remainingTracks == 1)
        if (!gaplessPlayback || crossfadeDurationMs > 0 || stopAtBoundary || snapshot.repeatMode == RepeatMode.One || currentItem == null) {
            gaplessPreloadJob?.cancel()
            trimTrailingMediaItems(controller)
            return
        }
        val nextIndex = when {
            snapshot.currentIndex in 0 until snapshot.activeEpisodeIds.lastIndex -> snapshot.currentIndex + 1
            snapshot.repeatMode == RepeatMode.All && snapshot.activeEpisodeIds.size > 1 -> 0
            else -> -1
        }
        val nextEpisodeId = snapshot.activeEpisodeIds.getOrNull(nextIndex)
        if (nextEpisodeId == null || nextEpisodeId == currentItem?.episodeId) {
            gaplessPreloadJob?.cancel()
            trimTrailingMediaItems(controller)
            return
        }
        val existingNextIdx = controller.currentMediaItemIndex + 1
        if (existingNextIdx < controller.mediaItemCount &&
            controller.getMediaItemAt(existingNextIdx).mediaId == nextEpisodeId
        ) {
            while (controller.mediaItemCount > existingNextIdx + 1) {
                controller.removeMediaItem(controller.mediaItemCount - 1)
            }
            return
        }
        trimTrailingMediaItems(controller)
        val expectedCurrentId = currentItem?.episodeId ?: return
        gaplessPreloadJob?.cancel()
        gaplessPreloadJob = scope.launch {
            val nextEpisode = repository.getEpisodeById(nextEpisodeId) ?: return@launch
            if (nextEpisode.isYouTubeReference && nextEpisode.linkedAudioUri.isNullOrBlank()) return@launch
            val nextUri = nextEpisode.effectivePlaybackUri ?: return@launch
            var formatHint: String? = null
            if (nextEpisode.sourceType == MediaSourceType.LocalMusic) {
                val accessible = withContext(Dispatchers.IO) { isUriAccessible(nextUri) }
                if (!accessible) return@launch
                val detected = withContext(Dispatchers.IO) {
                    AudioFormatCatalog.detectFromUri(context, Uri.parse(nextUri), nextEpisode.audioAsset)
                }
                if (!detected.isPlayable || detected.requiresRawPcmParams) return@launch
                formatHint = detected.formatId
            }
            val activeController = mediaController ?: return@launch
            if (currentItem?.episodeId != expectedCurrentId) return@launch
            val latestSnapshot = _queue.value
            val latestNextIdx = when {
                latestSnapshot.currentIndex in 0 until latestSnapshot.activeEpisodeIds.lastIndex -> latestSnapshot.currentIndex + 1
                latestSnapshot.repeatMode == RepeatMode.All && latestSnapshot.activeEpisodeIds.size > 1 -> 0
                else -> -1
            }
            if (latestSnapshot.activeEpisodeIds.getOrNull(latestNextIdx) != nextEpisodeId) return@launch
            trimTrailingMediaItems(activeController)
            val nextArtworkData = loadArtworkBytesForMediaSession(nextEpisode.artworkAsset)
            activeController.addMediaItem(
                buildMediaItem(
                    episode = nextEpisode,
                    playbackUriStr = nextUri,
                    formatHint = formatHint,
                    artworkData = nextArtworkData,
                ),
            )
        }
    }

    private fun handleGaplessTransitionTo(nextEpisodeId: String) {
        val completedId = _queue.value.currentEpisodeId
        flushListeningSession(completed = true, skipped = false)
        persistCurrentPosition(markPlayed = true, overridePositionMs = 0L)
        captionSyncEngine.clear()
        captionNotificationController.dismiss()
        lockScreenLyricsNotificationController.dismiss()
        _availableTranscripts.value = emptyList()
        _activeTranscriptDocument.value = null

        val controller = mediaController
        while ((controller?.currentMediaItemIndex ?: 0) > 0) {
            controller?.removeMediaItem(0)
        }
        val sleepFuture = controller?.sendCustomCommand(
            SessionCommand(PodcastPlaybackService.ActionNotifyTrackCompleted, Bundle.EMPTY),
            Bundle.EMPTY,
        )
        sleepFuture?.addListener(
            {
                val shouldStop = runCatching {
                    sleepFuture.get().extras.getBoolean(PodcastPlaybackService.ExtraShouldStop, false)
                }.getOrDefault(false)
                if (shouldStop) {
                    mediaController?.pause()
                }
            },
            ContextCompat.getMainExecutor(context),
        )

        removeCompletedQueueEntry(completedId)
        val snapshot = _queue.value
        val newIndex = snapshot.activeEpisodeIds.indexOf(nextEpisodeId)
        if (newIndex >= 0) {
            _queue.value = snapshot.copy(currentIndex = newIndex)
            persistActiveQueueSnapshot()
        }

        scope.launch {
            val episode = repository.getEpisodeById(nextEpisodeId) ?: return@launch
            val activeController = mediaController
            if (activeController != null && activeController.currentMediaItem?.mediaId != nextEpisodeId) {
                return@launch
            }
            val item = PlaybackItem(
                episodeId = episode.id,
                podcastId = episode.podcastId,
                hostId = episode.hostId,
                title = episode.title,
                podcastTitle = episode.podcastTitle,
                hostName = episode.hostName,
                artworkAsset = episode.artworkAsset,
                audioAsset = episode.audioAsset,
                durationMs = episode.durationMs,
                sourceType = episode.sourceType,
                sourceUri = episode.sourceUri,
                youtubeUrl = episode.youtubeUrl,
                linkedAudioUri = episode.linkedAudioUri,
            )
            currentEpisode = episode
            currentItem = item
            sessionAccumulatedSeconds = 0.0
            lastTickTimestampMs = System.currentTimeMillis()
            preferences.setLastEpisodeId(episode.id)
            val currentPos = activeController?.currentPosition ?: 0L
            publishState()
            syncLyricsAndCaptionNotifications()
            updateTicker()
            preloadNextGaplessItemIfNeeded()
            transcriptLoadJob?.cancel()
            transcriptLoadJob = scope.launch {
                loadTranscriptForEpisode(episode, currentPos)
            }
        }
    }

    fun onServiceDestroyed() {
        stopTicker()
        gaplessPreloadJob?.cancel()
        prepareEpisodeJob?.cancel()
        transcriptLoadJob?.cancel()
        queuePersistJob?.cancel()
        seekPersistJob?.cancel()
        captionNotificationController.dismiss()
        lockScreenLyricsNotificationController.dismiss()
    }

    fun release() {
        stopTicker()
        if (screenStateReceiverRegistered) {
            runCatching { context.unregisterReceiver(screenStateReceiver) }
            screenStateReceiverRegistered = false
        }
        gaplessPreloadJob?.cancel()
        prepareEpisodeJob?.cancel()
        transcriptLoadJob?.cancel()
        queuePersistJob?.cancel()
        seekPersistJob?.cancel()
        captionNotificationController.dismiss()
        lockScreenLyricsNotificationController.dismiss()
        mediaController?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        mediaController = null
    }

    companion object {
        private const val MaxPlaybackQueueSize = 2_000
        private const val SeekGuardWindowMs = 750L
        private const val SeekRebufferSuppressWindowMs = 800L
        private val RawOrProbeExtensions = setOf("pcm", "raw", "dsf", "dff", "wv", "ape", "mpc", "ac3", "eac3", "dts")
    }
}

internal data class GuardedSeekPositionResult(
    val positionMs: Long,
    val matchedTarget: Boolean,
    val guardCleared: Boolean,
)

/**
 * Prevents stale pre-seek Media3 positions (or late callbacks from an earlier rapid seek)
 * from overwriting the latest user-selected seek timestamp while the decoder repositions.
 */
internal fun resolveGuardedSeekPosition(
    rawPositionMs: Long,
    durationMs: Long,
    guardTargetMs: Long?,
    nowElapsedMs: Long,
    guardUntilElapsedMs: Long,
): GuardedSeekPositionResult {
    val safeRaw = if (durationMs > 0L) rawPositionMs.coerceIn(0L, durationMs) else rawPositionMs.coerceAtLeast(0L)
    if (guardTargetMs == null) {
        return GuardedSeekPositionResult(positionMs = safeRaw, matchedTarget = false, guardCleared = false)
    }
    val safeTarget = if (durationMs > 0L) guardTargetMs.coerceIn(0L, durationMs) else guardTargetMs.coerceAtLeast(0L)
    val toleranceMs = if (durationMs > 0L) minOf(1_500L, (durationMs / 4L).coerceAtLeast(250L)) else 1_500L
    val withinTolerance = kotlin.math.abs(safeRaw - safeTarget) <= toleranceMs
    if (withinTolerance) {
        return GuardedSeekPositionResult(positionMs = safeRaw, matchedTarget = true, guardCleared = true)
    }
    if (nowElapsedMs < guardUntilElapsedMs) {
        return GuardedSeekPositionResult(positionMs = safeTarget, matchedTarget = false, guardCleared = false)
    }
    return GuardedSeekPositionResult(positionMs = safeRaw, matchedTarget = false, guardCleared = true)
}

/**
 * Computes the optimal sleep delay for PodcastPlaybackController's position ticker:
 * - Active foreground UI collectors: smooth 70–180ms cadence scaled by playback speed.
 * - Backgrounded with screen ON and timed lock-screen lyrics active: sleeps directly until the
 *   next lyric cue boundary (clamped 180ms..5000ms) instead of polling blindly.
 * - Screen OFF or no timed lock-screen lyrics active: sleeps 15s (purely for periodic listening
 *   progress persistence), eliminating ~97% of background CPU wakeups.
 */
internal fun computeBackgroundTickerDelayMs(
    hasUiCollectors: Boolean,
    isScreenInteractive: Boolean,
    lockScreenLyricsActive: Boolean,
    currentPositionMs: Long,
    nextCueStartMs: Long?,
    playbackSpeed: Float,
): Long {
    val speed = playbackSpeed.coerceIn(0.5f, 2.5f)
    if (hasUiCollectors) {
        return (180f / speed).toLong().coerceIn(70L, 180L)
    }
    if (!isScreenInteractive || !lockScreenLyricsActive) {
        return 15_000L
    }
    if (nextCueStartMs == null) {
        return 5_000L
    }
    val remainingTrackMs = (nextCueStartMs - currentPositionMs).coerceAtLeast(0L)
    val wallMs = (remainingTrackMs / speed).toLong()
    return wallMs.coerceIn(180L, 5_000L)
}

/**
 * Suppresses transient STATE_BUFFERING -> Preparing UI flashes during local/paused seeks
 * so neither Playing nor Paused states blank the progress bar, disable controls, or bounce artwork.
 */
internal fun shouldSuppressSeekRebuffer(
    playbackState: Int,
    nowElapsedMs: Long,
    suppressUntilElapsedMs: Long,
    currentState: PlaybackState,
    currentItemId: String?,
): Boolean {
    if (playbackState != Player.STATE_BUFFERING) return false
    if (nowElapsedMs >= suppressUntilElapsedMs) return false
    if (currentItemId == null) return false
    return when (currentState) {
        is PlaybackState.Playing -> currentState.item.episodeId == currentItemId
        is PlaybackState.Paused -> currentState.item.episodeId == currentItemId
        else -> false
    }
}
