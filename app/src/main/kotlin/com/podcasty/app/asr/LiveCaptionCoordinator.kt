/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.asr

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.podcasty.app.data.PodcastPreferencesRepository
import com.podcasty.app.data.PodcastUserSettings
import com.podcasty.app.domain.MediaSourceType
import com.podcasty.app.domain.PlaybackItem
import com.podcasty.app.domain.PlaybackState
import com.podcasty.app.domain.PodcastRepository
import com.podcasty.app.domain.itemOrNull
import com.podcasty.app.domain.playbackSpeedOrDefault
import com.podcasty.app.domain.positionMsOrZero
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs

/**
 * End-to-end coordinator tying together:
 * - [LiveCaptionAudioProcessor] (Media3 PCM tap with exact frame-to-media-ms mapping)
 * - [AudioChunkPreprocessor] (16 kHz mono resampling, RMS normalization, VAD, 100 ms chunking)
 * - Interchangeable [SpeechRecognitionEngine]s (Google Cloud STT, Sherpa-ONNX, Whisper.cpp)
 * - [LiveCaptionStateAggregator] (PARTIAL -> FINAL replacement, dedup, O(log N) playback sync)
 * - Room caption history persistence (when enabled) & Android ConnectivityManager recovery
 *
 * Strictly scoped to RSS / online podcast streams (`MediaSourceType.RssPodcast`).
 * Normal local music (`LocalMusic`) and demo tracks (`Demo`) NEVER activate ASR and preserve
 * their existing lyrics experience untouched.
 */
@OptIn(UnstableApi::class)
class LiveCaptionCoordinator(
    context: Context,
    private val audioProcessor: LiveCaptionAudioProcessor,
    private val playbackStateFlow: StateFlow<PlaybackState>,
    private val repository: PodcastRepository,
    private val preferences: PodcastPreferencesRepository,
    private val credentialStore: SecureCredentialStore,
    private val engines: Map<AsrEngineType, SpeechRecognitionEngine>? = null,
) {
    private val appContext = context.applicationContext
    private val lazyEngines = HashMap<AsrEngineType, SpeechRecognitionEngine>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preprocessor = AudioChunkPreprocessor(targetSampleRateHz = 16_000, chunkDurationMs = 100)
    private val aggregator = LiveCaptionStateAggregator()

    private val _uiState = MutableStateFlow(LiveCaptionUiState())
    val uiState: StateFlow<LiveCaptionUiState> = _uiState.asStateFlow()

    private var currentSettings = PodcastUserSettings()
    private var activeEpisodeId: String? = null
    private var activeSessionId: String? = null
    private var activeEngine: SpeechRecognitionEngine? = null
    private var eventCollectorJob: Job? = null
    private var audioPipelineJob: Job? = null
    private var lastTrackedPositionMs: Long = 0L
    private var lastWasPlaying: Boolean = false
    private var lastPlaybackSpeed: Float = 1.0f

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        scope.launch {
            preferences.settings.collect { settings ->
                val engineChanged = settings.asrEngineId != currentSettings.asrEngineId
                val languageChanged = settings.asrLanguageCode != currentSettings.asrLanguageCode
                val backendChanged = settings.asrBackendUrl != currentSettings.asrBackendUrl
                val autoStartChanged = settings.autoStartLiveCaptions != currentSettings.autoStartLiveCaptions
                currentSettings = settings

                val activeEp = activeEpisodeId
                val state = playbackStateFlow.value
                val item = state.itemOrNull()
                if (activeEp != null && item != null && item.sourceType == MediaSourceType.RssPodcast) {
                    if (!settings.autoStartLiveCaptions) {
                        stopRecognitionSession(keepHistory = true, statusMessage = "Live captions paused in settings.")
                    } else if (engineChanged || languageChanged || backendChanged || autoStartChanged) {
                        restartRecognitionForCurrentItem(item, state)
                    }
                }
            }
        }
        scope.launch {
            playbackStateFlow.collect { state ->
                handlePlaybackStateChange(state)
            }
        }
    }

    fun setLanguageAndRestart(languageCode: String) {
        scope.launch {
            preferences.setAsrLanguageCode(languageCode)
        }
    }

    fun retryRecognition() {
        val state = playbackStateFlow.value
        val item = state.itemOrNull() ?: return
        if (item.sourceType != MediaSourceType.RssPodcast) return
        restartRecognitionForCurrentItem(item, state)
    }

    fun onSeekPerformed(targetPositionMs: Long, playbackSpeed: Float) {
        if (activeEpisodeId == null) return
        lastTrackedPositionMs = targetPositionMs
        audioProcessor.updatePlaybackAnchor(targetPositionMs, playbackSpeed)
        preprocessor.reset()
        val (finalized, _) = aggregator.clearPartialOnly()
        val activeIdx = LiveCaptionStateAggregator.findActiveIndex(finalized, null, targetPositionMs)
        _uiState.update {
            it.copy(
                finalizedSegments = finalized,
                activePartial = null,
                activeSegmentIndex = activeIdx,
            )
        }
        if (playbackStateFlow.value is PlaybackState.Playing) {
            activeEngine?.resumeSession(targetPositionMs)
        }
    }

    fun onPlaybackSpeedChanged(newSpeed: Float, currentPositionMs: Long) {
        lastPlaybackSpeed = newSpeed
        if (activeEpisodeId != null) {
            audioProcessor.updatePlaybackAnchor(currentPositionMs, newSpeed)
        }
    }

    fun clearHistoryForEpisode(episodeId: String) {
        scope.launch(Dispatchers.IO) {
            repository.clearLiveCaptions(episodeId)
        }
        if (activeEpisodeId == episodeId) {
            aggregator.reset(emptyList())
            _uiState.update {
                it.copy(
                    finalizedSegments = emptyList(),
                    activePartial = null,
                    activeSegmentIndex = -1,
                )
            }
        }
    }

    fun release() {
        stopRecognitionSession(keepHistory = false)
        unregisterNetworkCallback()
    }

    private fun handlePlaybackStateChange(state: PlaybackState) {
        val item = state.itemOrNull()
        val isRssPodcast = item != null && item.sourceType == MediaSourceType.RssPodcast

        // Normal music / demo tracks: strictly disable ASR so existing lyrics remain untouched
        if (!isRssPodcast) {
            if (activeEpisodeId != null || _uiState.value.isPodcastCaptionMode) {
                stopRecognitionSession(keepHistory = false)
                activeEpisodeId = null
                _uiState.value = LiveCaptionUiState(isPodcastCaptionMode = false)
            }
            unregisterNetworkCallback()
            audioProcessor.setCaptureEnabled(false)
            return
        }
        if (networkCallback == null) {
            registerNetworkRecoveryCallback()
        }

        val episodeChanged = item.episodeId != activeEpisodeId
        val isPlaying = state is PlaybackState.Playing
        val isPaused = state is PlaybackState.Paused
        val isPreparing = state is PlaybackState.Preparing
        val positionMs = state.positionMsOrZero()
        val speed = state.playbackSpeedOrDefault()

        if (episodeChanged) {
            stopRecognitionSession(keepHistory = false)
            activeEpisodeId = item.episodeId
            lastTrackedPositionMs = positionMs
            lastWasPlaying = false
            lastPlaybackSpeed = speed
            scope.launch {
                val savedHistory = if (currentSettings.saveCaptionHistory) {
                    runCatching { repository.getLiveCaptions(item.episodeId) }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                if (activeEpisodeId != item.episodeId) return@launch
                aggregator.reset(savedHistory)
                val initialIdx = LiveCaptionStateAggregator.findActiveIndex(savedHistory, null, positionMs)
                val engineType = AsrEngineType.fromId(currentSettings.asrEngineId)
                _uiState.value = LiveCaptionUiState(
                    isPodcastCaptionMode = true,
                    episodeId = item.episodeId,
                    engineType = engineType,
                    languageCode = currentSettings.asrLanguageCode,
                    status = if (currentSettings.autoStartLiveCaptions) {
                        LiveCaptionSessionStatus.Initializing
                    } else {
                        LiveCaptionSessionStatus.Paused
                    },
                    finalizedSegments = savedHistory,
                    activePartial = null,
                    activeSegmentIndex = initialIdx,
                    statusMessage = if (currentSettings.autoStartLiveCaptions) {
                        "Preparing real-time AI captions…"
                    } else {
                        "Live captions disabled in settings."
                    },
                )
                if (currentSettings.autoStartLiveCaptions && (isPlaying || isPreparing)) {
                    startRecognitionForItem(item, positionMs, speed)
                }
            }
            return
        }

        // Detect external seeks (e.g. Bluetooth/notification seek) by jump > 2.5s
        if (abs(positionMs - lastTrackedPositionMs) > 2_500L) {
            onSeekPerformed(positionMs, speed)
        }
        lastTrackedPositionMs = positionMs

        if (speed != lastPlaybackSpeed) {
            onPlaybackSpeedChanged(speed, positionMs)
        }

        // Update active highlighted line on every playback position tick (O(log N))
        val current = _uiState.value
        val activeIdx = LiveCaptionStateAggregator.findActiveIndex(
            current.finalizedSegments,
            current.activePartial,
            positionMs,
        )
        if (activeIdx != current.activeSegmentIndex) {
            _uiState.update { it.copy(activeSegmentIndex = activeIdx) }
        }

        // Synchronize streaming lifecycle with play / pause transitions
        when {
            isPlaying && !lastWasPlaying -> {
                lastWasPlaying = true
                if (currentSettings.autoStartLiveCaptions) {
                    audioProcessor.updatePlaybackAnchor(positionMs, speed)
                    audioProcessor.setCaptureEnabled(true)
                    if (activeEngine == null) {
                        startRecognitionForItem(item, positionMs, speed)
                    } else {
                        activeEngine?.resumeSession(positionMs)
                        if (_uiState.value.status == LiveCaptionSessionStatus.Paused) {
                            _uiState.update {
                                it.copy(
                                    status = LiveCaptionSessionStatus.Listening,
                                    statusMessage = "Listening (${it.languageCode})…",
                                )
                            }
                        }
                    }
                }
            }
            isPaused && lastWasPlaying -> {
                lastWasPlaying = false
                audioProcessor.setCaptureEnabled(false)
                activeEngine?.pauseSession()
                _uiState.update {
                    if (it.status == LiveCaptionSessionStatus.Listening || it.status == LiveCaptionSessionStatus.Initializing) {
                        it.copy(
                            status = LiveCaptionSessionStatus.Paused,
                            statusMessage = "Paused — live speech capture suspended.",
                            isSpeechDetected = false,
                        )
                    } else {
                        it
                    }
                }
            }
            state is PlaybackState.Failed -> {
                lastWasPlaying = false
                audioProcessor.setCaptureEnabled(false)
                activeEngine?.pauseSession()
            }
        }
    }

    private fun restartRecognitionForCurrentItem(item: PlaybackItem, state: PlaybackState) {
        stopRecognitionSession(keepHistory = true)
        startRecognitionForItem(item, state.positionMsOrZero(), state.playbackSpeedOrDefault())
    }

    private fun resolveEngine(engineType: AsrEngineType): SpeechRecognitionEngine? {
        engines?.let { provided ->
            return provided[engineType] ?: provided[AsrEngineType.GoogleCloud]
        }
        return lazyEngines.getOrPut(engineType) {
            when (engineType) {
                AsrEngineType.GoogleCloud -> GoogleStreamingSpeechEngine()
                AsrEngineType.SherpaOnnx -> SherpaStreamingSpeechEngine(appContext)
                AsrEngineType.WhisperCpp -> WhisperSpeechEngine(appContext)
            }
        }
    }

    private fun startRecognitionForItem(item: PlaybackItem, mediaPositionMs: Long, speed: Float) {
        val engineType = AsrEngineType.fromId(currentSettings.asrEngineId)
        val engine = resolveEngine(engineType) ?: return
        val sessionId = "pod_${item.episodeId.take(16)}_${UUID.randomUUID().toString().take(8)}"
        val config = RecognitionSessionConfig(
            sessionId = sessionId,
            episodeId = item.episodeId,
            languageCode = currentSettings.asrLanguageCode,
            sampleRateHz = 16_000,
            initialMediaPositionMs = mediaPositionMs,
            backendWebSocketUrl = currentSettings.asrBackendUrl,
            authToken = credentialStore.getAsrAuthToken(),
        )

        when (val availability = engine.checkAvailability(config)) {
            is EngineAvailability.ConfigurationRequired -> {
                audioProcessor.setCaptureEnabled(false)
                _uiState.update {
                    it.copy(
                        isPodcastCaptionMode = true,
                        episodeId = item.episodeId,
                        sessionId = sessionId,
                        engineType = engineType,
                        languageCode = config.languageCode,
                        status = LiveCaptionSessionStatus.ConfigurationRequired,
                        statusMessage = null,
                        errorMessage = availability.message,
                    )
                }
                return
            }
            is EngineAvailability.ModelRequired -> {
                audioProcessor.setCaptureEnabled(false)
                _uiState.update {
                    it.copy(
                        isPodcastCaptionMode = true,
                        episodeId = item.episodeId,
                        sessionId = sessionId,
                        engineType = engineType,
                        languageCode = config.languageCode,
                        status = LiveCaptionSessionStatus.ConfigurationRequired,
                        statusMessage = null,
                        errorMessage = availability.message,
                    )
                }
                return
            }
            is EngineAvailability.UnsupportedLanguage -> {
                audioProcessor.setCaptureEnabled(false)
                _uiState.update {
                    it.copy(
                        isPodcastCaptionMode = true,
                        episodeId = item.episodeId,
                        sessionId = sessionId,
                        engineType = engineType,
                        languageCode = config.languageCode,
                        status = LiveCaptionSessionStatus.Error,
                        statusMessage = null,
                        errorMessage = "Language ${availability.languageCode} is not supported by ${engine.displayName}.",
                    )
                }
                return
            }
            EngineAvailability.Ready -> Unit
        }

        activeEngine = engine
        activeSessionId = sessionId
        preprocessor.reset()
        audioProcessor.updatePlaybackAnchor(mediaPositionMs, speed)
        val isPlayingNow = playbackStateFlow.value is PlaybackState.Playing
        audioProcessor.setCaptureEnabled(isPlayingNow)
        lastWasPlaying = isPlayingNow

        _uiState.update {
            it.copy(
                isPodcastCaptionMode = true,
                episodeId = item.episodeId,
                sessionId = sessionId,
                engineType = engineType,
                languageCode = config.languageCode,
                status = LiveCaptionSessionStatus.Initializing,
                statusMessage = "Connecting to ${engine.displayName} (${config.languageCode})…",
                errorMessage = null,
            )
        }

        eventCollectorJob?.cancel()
        eventCollectorJob = scope.launch {
            engine.startSession(config).collect { event ->
                if (activeSessionId != sessionId) return@collect
                handleRecognitionEvent(item.episodeId, event)
            }
        }

        audioPipelineJob?.cancel()
        audioPipelineJob = scope.launch(Dispatchers.Default) {
            while (isActive && activeSessionId == sessionId) {
                val frame = audioProcessor.capturedFrames.receiveCatching().getOrNull() ?: break
                if (!audioProcessor.isCaptureEnabled()) continue
                val chunks = preprocessor.processFrame(frame)
                for (chunk in chunks) {
                    if (!isActive || activeSessionId != sessionId) break
                    if (chunk.isSpeech != _uiState.value.isSpeechDetected) {
                        _uiState.update { it.copy(isSpeechDetected = chunk.isSpeech) }
                    }
                    engine.feedAudioChunk(chunk)
                }
            }
        }
    }

    private fun handleRecognitionEvent(episodeId: String, event: RecognitionEvent) {
        when (event) {
            is RecognitionEvent.Connected -> {
                val isPlayingNow = playbackStateFlow.value is PlaybackState.Playing
                _uiState.update {
                    it.copy(
                        status = if (isPlayingNow) LiveCaptionSessionStatus.Listening else LiveCaptionSessionStatus.Paused,
                        statusMessage = if (isPlayingNow) {
                            "Live AI captions active (${it.languageCode})"
                        } else {
                            "Paused — resume playback for live captions."
                        },
                        errorMessage = null,
                    )
                }
            }

            is RecognitionEvent.Caption -> {
                val (finalized, partial) = aggregator.onIncomingSegment(event.segment)
                val posMs = playbackStateFlow.value.positionMsOrZero()
                val activeIdx = LiveCaptionStateAggregator.findActiveIndex(finalized, partial, posMs)
                _uiState.update {
                    it.copy(
                        status = LiveCaptionSessionStatus.Listening,
                        finalizedSegments = finalized,
                        activePartial = partial,
                        activeSegmentIndex = activeIdx,
                        errorMessage = null,
                    )
                }
                if (event.segment.status == LiveCaptionStatus.FINAL && currentSettings.saveCaptionHistory) {
                    scope.launch(Dispatchers.IO) {
                        runCatching { repository.saveLiveCaptions(episodeId, listOf(event.segment)) }
                    }
                }
            }

            is RecognitionEvent.Reconnecting -> {
                _uiState.update {
                    it.copy(
                        status = LiveCaptionSessionStatus.Reconnecting,
                        statusMessage = "Reconnecting live captions (attempt ${event.attempt})…",
                        errorMessage = null,
                    )
                }
            }

            is RecognitionEvent.Error -> {
                audioProcessor.setCaptureEnabled(false)
                _uiState.update {
                    it.copy(
                        status = LiveCaptionSessionStatus.Error,
                        statusMessage = null,
                        errorMessage = event.message,
                        isSpeechDetected = false,
                    )
                }
            }

            is RecognitionEvent.Closed -> {
                if (_uiState.value.status == LiveCaptionSessionStatus.Listening) {
                    _uiState.update {
                        it.copy(
                            status = LiveCaptionSessionStatus.Paused,
                            statusMessage = event.reason,
                            isSpeechDetected = false,
                        )
                    }
                }
            }
        }
    }

    private fun stopRecognitionSession(keepHistory: Boolean, statusMessage: String? = null) {
        audioProcessor.setCaptureEnabled(false)
        audioPipelineJob?.cancel()
        audioPipelineJob = null
        eventCollectorJob?.cancel()
        eventCollectorJob = null
        activeEngine?.stopSession()
        activeEngine = null
        activeSessionId = null
        preprocessor.reset()
        lastWasPlaying = false
        if (!keepHistory) {
            aggregator.reset(emptyList())
        } else {
            val (finalized, _) = aggregator.clearPartialOnly()
            _uiState.update {
                it.copy(
                    status = LiveCaptionSessionStatus.Paused,
                    finalizedSegments = finalized,
                    activePartial = null,
                    statusMessage = statusMessage,
                    isSpeechDetected = false,
                )
            }
        }
    }

    private fun registerNetworkRecoveryCallback() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch {
                    val ui = _uiState.value
                    if (ui.isPodcastCaptionMode &&
                        (ui.status == LiveCaptionSessionStatus.Error || ui.status == LiveCaptionSessionStatus.Reconnecting) &&
                        playbackStateFlow.value is PlaybackState.Playing
                    ) {
                        retryRecognition()
                    }
                }
            }

            override fun onLost(network: Network) {
                scope.launch {
                    val ui = _uiState.value
                    if (ui.isPodcastCaptionMode && ui.engineType.requiresNetwork && ui.status == LiveCaptionSessionStatus.Listening) {
                        _uiState.update {
                            it.copy(
                                status = LiveCaptionSessionStatus.Reconnecting,
                                statusMessage = "Network lost — waiting to reconnect live captions…",
                            )
                        }
                    }
                }
            }
        }
        networkCallback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
    }

    private fun unregisterNetworkCallback() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        networkCallback?.let { cb -> runCatching { cm.unregisterNetworkCallback(cb) } }
        networkCallback = null
    }
}
