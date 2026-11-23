/*
 * Podcasty — Android Podcast & Music Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.player

import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.podcasty.app.app.MainActivity
import com.podcasty.app.app.PodcastyApplication
import com.podcasty.app.asr.LiveCaptionAudioProcessor
import com.podcasty.app.audio.PodcastyAudioDspProcessor
import com.podcasty.app.audio.PodcastyAudioEffectsEngine
import com.podcasty.app.audio.WrappedPcmDataSource
import com.podcasty.app.data.PodcastPreferencesRepository
import com.podcasty.app.database.PodcastyDatabase
import com.podcasty.app.domain.RawPcmConfig
import com.podcasty.app.domain.SleepTimerPreset
import com.podcasty.app.domain.SleepTimerRequest
import com.podcasty.app.domain.SleepTimerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.kyant.taglib.TagLib
import com.podcasty.app.audio.ReplayGain
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first

/**
 * Foreground Media3 MediaSessionService powering:
 * - Single shared ExoPlayer instance with Media3 FFmpeg software decoder extension enabled
 * - Streaming Raw PCM / AIFF / Sun AU wrapper DataSource
 * - Service-owned monotonic-clock SleepTimerManager (survives screen-off & Activity death)
 * - Proper Bluetooth/wired headphone disconnect handling (ACTION_AUDIO_BECOMING_NOISY)
 * - Full Bluetooth headset / car MediaSession controls & lock-screen media metadata
 */
class PodcastPlaybackService : MediaSessionService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null
    private lateinit var preferences: PodcastPreferencesRepository
    private lateinit var sleepTimerManager: SleepTimerManager

    private lateinit var volumeFader: PlaybackVolumeFader
    private var replayGainMode = 0
    private var replayGainJob: Job? = null

    private fun refreshReplayGain() {
        replayGainJob?.cancel()
        sharedAudioEffectsEngine.setReplayGain(0f)
        val item = exoPlayer?.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri ?: return
        val mode = replayGainMode
        if (mode == 0 || !isLocalPlayback()) return
        replayGainJob = serviceScope.launch {
            val gain = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                        val metadata = TagLib.getMetadata(descriptor.dup().detachFd(), false)
                        metadata?.propertyMap?.let { ReplayGain.gainDb(it, album = mode == 2) } ?: 0f
                    } ?: 0f
                }.getOrDefault(0f)
            }
            if (exoPlayer?.currentMediaItem?.mediaId == item.mediaId && replayGainMode == mode) {
                sharedAudioEffectsEngine.setReplayGain(gain)
            }
        }
    }

    private var shakeSensitivity = 0
    private var shakeRegistered = false
    private val shakeDetector = ShakeSkipDetector()
    private val shakeListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        override fun onSensorChanged(event: SensorEvent) {
            if (event.values.size < 3 || exoPlayer?.isPlaying != true || pausePending) return
            if (shakeDetector.sample(event.values[0], event.values[1], event.values[2], event.timestamp / 1_000_000L, shakeSensitivity)) {
                (application as? PodcastyApplication)?.container?.playbackController?.next()
            }
        }
    }

    private fun updateShakeRegistration() {
        val manager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val shouldRegister = shakeSensitivity > 0 && exoPlayer?.isPlaying == true && !pausePending && isLocalPlayback()
        if (shouldRegister == shakeRegistered) return
        shakeDetector.reset()
        if (shouldRegister) {
            val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            shakeRegistered = manager.registerListener(shakeListener, sensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            manager.unregisterListener(shakeListener)
            shakeRegistered = false
        }
    }

    private var sleepVolume = 1f
    private var fadeDurationMs = 0
    private var crossfadeDurationMs = 0
    private var crossfadeTriggeredForItemId: String? = null
    private var progressMonitorJob: Job? = null
    private var pausePending = false

    private fun updateProgressMonitor() {
        val player = exoPlayer
        val shouldRun = player != null && player.isPlaying && (
            sleepTimerManager.state.value.isActive ||
                (crossfadeDurationMs > 0 && isLocalPlayback())
            )
        if (!shouldRun) {
            progressMonitorJob?.cancel()
            progressMonitorJob = null
            return
        }
        if (progressMonitorJob?.isActive == true) return
        progressMonitorJob = serviceScope.launch {
            while (isActive) {
                val p = exoPlayer ?: break
                if (!p.isPlaying) break
                val sleepActive = sleepTimerManager.state.value.isActive
                val pos = p.currentPosition
                val dur = p.duration
                if (sleepActive) {
                    sleepTimerManager.onPlaybackProgress(pos, dur, p.playbackParameters.speed)
                } else if (crossfadeDurationMs > 0 && isLocalPlayback() && dur > crossfadeDurationMs * 2L) {
                    val remainingMs = (dur - pos).coerceAtLeast(0L)
                    val itemId = p.currentMediaItem?.mediaId
                    if (remainingMs in 1L..crossfadeDurationMs.toLong() && itemId != null && crossfadeTriggeredForItemId != itemId) {
                        crossfadeTriggeredForItemId = itemId
                        volumeFader.fadeTo(0f, remainingMs.toInt().coerceAtLeast(150)) {
                            val controller = (application as? PodcastyApplication)?.container?.playbackController
                            if (controller != null && p.currentMediaItem?.mediaId == itemId && p.isPlaying) {
                                controller.next()
                            }
                        }
                    }
                }
                val delayMs = if (sleepTimerManager.state.value.isFadingOut || crossfadeDurationMs > 0) 150L else 500L
                delay(delayMs)
            }
            progressMonitorJob = null
        }
    }

    private fun isLocalPlayback(): Boolean = when (exoPlayer?.currentMediaItem?.localConfiguration?.uri?.scheme) {
        "content", "file", "asset" -> true
        else -> false
    }

    private fun applyCombinedVolume() {
        exoPlayer?.volume = (sleepVolume * volumeFader.gain).coerceIn(0f, 1f)
    }

    private fun requestPlay() {
        pausedByNoisyEvent = false
        pausePending = false
        sharedPausePending = false
        volumeFader.cancel()
        val player = exoPlayer ?: return
        if (player.isPlaying) volumeFader.fadeTo(1f, fadeDurationMs)
        else if (isLocalPlayback() && fadeDurationMs > 0) volumeFader.setGain(0f)
        else volumeFader.setGain(1f)
        player.play()
    }

    private fun requestPause() {
        pausedByNoisyEvent = false
        val player = exoPlayer ?: return
        if (player.isPlaying && isLocalPlayback() && fadeDurationMs > 0) {
            pausePending = true
            sharedPausePending = true
            volumeFader.fadeTo(0f, fadeDurationMs) {
                player.pause()
                pausePending = false
                sharedPausePending = false
            }
            updateShakeRegistration()
        } else {
            volumeFader.cancel()
            player.pause()
        }
    }

    private var timerConfigured = false
    private var pauseOnNoisy: Boolean = true
    private var bluetoothMediaControlsEnabled: Boolean = true
    private var autoResumeOnBluetoothReconnect: Boolean = false
    private var pausedByNoisyEvent: Boolean = false
    private var audioReceiversRegistered: Boolean = false

    private val audioEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val player = exoPlayer ?: return
            when (intent?.action) {
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    if (pauseOnNoisy && player.isPlaying) {
                        pausedByNoisyEvent = true
                        player.pause()
                    }
                }
                AudioManager.ACTION_HEADSET_PLUG -> {
                    val state = intent.getIntExtra("state", -1)
                    if (state == 0 && pauseOnNoisy && player.isPlaying) {
                        pausedByNoisyEvent = true
                        player.pause()
                    }
                }

            }
        }
    }

    private val outputCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            val audioOutput = addedDevices.any { it.isSink && it.type in HeadphoneOutputTypes }
            if (audioOutput && autoResumeOnBluetoothReconnect && pausedByNoisyEvent) {
                val player = exoPlayer ?: return
                pausedByNoisyEvent = false
                if (player.mediaItemCount > 0) requestPlay()
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val player = exoPlayer ?: return
            if (pauseOnNoisy && player.isPlaying && removedDevices.any { it.isSink && it.type in HeadphoneOutputTypes }) {
                pausedByNoisyEvent = true
                volumeFader.cancel()
                pausePending = false
                sharedPausePending = false
                player.pause()
            }
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        preferences = PodcastPreferencesRepository(applicationContext)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // Enable Media3 FFmpeg software decoder extension alongside native MediaCodec, preserve
        // high-resolution 24/32-bit float PCM output via setEnableFloatOutput(true), and install
        // our transparent LiveCaptionAudioProcessor & PodcastyAudioDspProcessor (0-math bypass when off).
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(true)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(sharedLiveCaptionAudioProcessor, sharedAudioDspProcessor))
                .build()
        }
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        // Progressive HTTP streaming with HTTP Range request support & safe redirects (no audio file downloads)
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Podcasty/1.0 (Android)")
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
        val defaultMediaSourceFactory = DefaultMediaSourceFactory(
            DefaultDataSource.Factory(this, httpFactory),
        )
        val wrappedMediaSourceFactory = PodcastyMediaSourceFactory(
            context = applicationContext,
            delegate = defaultMediaSourceFactory,
        )

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(wrappedMediaSourceFactory)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(false)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        exoPlayer = player
        volumeFader = PlaybackVolumeFader(serviceScope, onVolume = { applyCombinedVolume() })

        // Attach single-instance hardware Equalizer/BassBoost/Virtualizer to ExoPlayer's real audio session
        sharedAudioEffectsEngine.attachToAudioSession(player.audioSessionId)
        // Ensure EqualizerController is initialized so persisted EQ settings apply immediately on service boot
        (application as? PodcastyApplication)?.container?.equalizerController

        sleepTimerManager = SleepTimerManager(
            scope = serviceScope,
            onSetPlayerVolume = { vol -> sleepVolume = vol.coerceIn(0f, 1f); applyCombinedVolume() },
            onTimerTriggeredPause = {
                volumeFader.cancel()
                pausePending = false
                sharedPausePending = false
                player.pause()
                pausedByNoisyEvent = false
            },
        )
        serviceScope.launch {
            val saved = preferences.settings.first().activeSleepTimer
            if (!timerConfigured && saved.isNotBlank()) {
                val parts = saved.split('|')
                if (parts.size == 4) {
                    val preset = runCatching { SleepTimerPreset.valueOf(parts[0]) }.getOrDefault(SleepTimerPreset.Off)
                    val deadline = parts[1].toLongOrNull()?.takeIf { it > 0L }
                    if (preset == SleepTimerPreset.EndOfEpisode || preset == SleepTimerPreset.AfterTracks ||
                        (deadline != null && deadline > System.currentTimeMillis())) {
                        sleepTimerManager.configure(SleepTimerRequest(
                            preset = preset, trackCount = parts[2].toIntOrNull(),
                            fadeOutSeconds = parts[3].toIntOrNull() ?: 30, deadlineEpochMs = deadline,
                        ))
                    }
                }
            }
            var lastSaved: String? = null
            sleepTimerManager.state.collect { state ->
                _sharedSleepTimerState.value = state
                updateProgressMonitor()
                val snapshot = if (state.isActive) "${state.preset.name}|${sleepTimerManager.deadlineEpochMs ?: 0}|${state.remainingTracks ?: 0}|${state.fadeOutSeconds}" else ""
                if (snapshot != lastSaved) {
                    lastSaved = snapshot
                    preferences.setActiveSleepTimer(snapshot)
                }
            }
        }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateShakeRegistration()
                updateProgressMonitor()
                if (isPlaying) {
                    pausedByNoisyEvent = false
                    if (!pausePending) {
                        val effectiveFadeInMs = maxOf(fadeDurationMs, if (crossfadeDurationMs > 0) (crossfadeDurationMs / 2).coerceAtLeast(300) else 0)
                        if (isLocalPlayback() && effectiveFadeInMs > 0) {
                            if (volumeFader.gain >= 1f) volumeFader.setGain(0f)
                            volumeFader.fadeTo(1f, effectiveFadeInMs)
                        } else volumeFader.setGain(1f)
                    }
                    sleepTimerManager.onNewPlaybackStarted()
                    if (player.audioSessionId > 0) {
                        sharedAudioEffectsEngine.attachToAudioSession(player.audioSessionId)
                    }
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                sharedAudioEffectsEngine.attachToAudioSession(audioSessionId)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                crossfadeTriggeredForItemId = null
                refreshReplayGain()
                updateShakeRegistration()
                updateProgressMonitor()
                if (player.isPlaying && isLocalPlayback() && crossfadeDurationMs > 0 && !pausePending) {
                    volumeFader.setGain(0f)
                    volumeFader.fadeTo(1f, (crossfadeDurationMs / 2).coerceAtLeast(300))
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                updateShakeRegistration()
                val audioFormat = tracks.groups
                    .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
                    ?.let { group ->
                        (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let { group.getTrackFormat(it) }
                    }
                val sinkFormat = sharedAudioDspProcessor.lastConfiguredFormat
                sharedAudioEffectsEngine.updateTrackAudioDiagnostics(
                    codecMimeType = audioFormat?.sampleMimeType ?: audioFormat?.containerMimeType,
                    sampleRateHz = sinkFormat.sampleRate.takeIf { it > 0 } ?: (audioFormat?.sampleRate ?: 0),
                    channelCount = sinkFormat.channelCount.takeIf { it > 0 } ?: (audioFormat?.channelCount ?: 0),
                    pcmEncoding = sinkFormat.encoding.takeIf { it != C.ENCODING_INVALID } ?: (audioFormat?.pcmEncoding ?: 0),
                    audioSessionId = player.audioSessionId,
                )
            }
        })

        serviceScope.launch {
            preferences.settings.collect { settings ->
                if (replayGainMode != settings.replayGainMode) {
                    replayGainMode = settings.replayGainMode
                    refreshReplayGain()
                }
                shakeSensitivity = settings.shakeSkipSensitivity
                updateShakeRegistration()
                fadeDurationMs = settings.playbackFadeDurationMs
                crossfadeDurationMs = settings.crossfadeDurationMs
                updateProgressMonitor()
                if (!settings.autoResumeOnBluetoothReconnect) pausedByNoisyEvent = false
                pauseOnNoisy = settings.pauseOnHeadphoneDisconnect
                bluetoothMediaControlsEnabled = settings.bluetoothMediaControls
                autoResumeOnBluetoothReconnect = settings.autoResumeOnBluetoothReconnect
                player.setHandleAudioBecomingNoisy(false) // Receiver owns preference-aware pause/resume state.
            }
        }

        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val sessionPlayer = QueueForwardingPlayer(player)
        mediaSession = MediaSession.Builder(this, sessionPlayer)
            .setSessionActivity(sessionActivityIntent)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    val baseCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                        .buildUpon()
                        .add(SessionCommand(ActionConfigureSleepTimer, Bundle.EMPTY))
                        .add(SessionCommand(ActionNotifyTrackCompleted, Bundle.EMPTY))
                        .build()
                    val playerCommands = if (bluetoothMediaControlsEnabled || controller.packageName == packageName) {
                        MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                    } else {
                        Player.Commands.Builder().add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM).build()
                    }
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(baseCommands)
                        .setAvailablePlayerCommands(playerCommands)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle,
                ): ListenableFuture<SessionResult> {
                    return when (customCommand.customAction) {
                        ActionConfigureSleepTimer -> {
                            timerConfigured = true
                            val presetName = args.getString(ExtraPreset, SleepTimerPreset.Off.name)
                            val preset = runCatching { SleepTimerPreset.valueOf(presetName) }.getOrDefault(SleepTimerPreset.Off)
                            val customMinutes = args.getInt(ExtraCustomMinutes, 0).takeIf { it > 0 }
                            val trackCount = args.getInt(ExtraTrackCount, 0).takeIf { it > 0 }
                            val fadeOutSec = args.getInt(ExtraFadeOutSeconds, 30)
                            sleepTimerManager.configure(
                                SleepTimerRequest(
                                    preset = preset,
                                    customMinutes = customMinutes,
                                    trackCount = trackCount,
                                    fadeOutSeconds = fadeOutSec,
                                    deadlineEpochMs = args.getLong(ExtraDeadlineEpochMs, 0L).takeIf { it > 0L },
                                ),
                            )
                            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        }
                        ActionNotifyTrackCompleted -> {
                            val shouldStop = sleepTimerManager.onTrackCompleted()
                            if (shouldStop) {
                                // Stop at the service boundary before a controller round trip can
                                // repeat or advance the audio timeline.
                                volumeFader.cancel()
                                pausePending = false
                                sharedPausePending = false
                                player.pause()
                                pausedByNoisyEvent = false
                            }
                            val resultBundle = Bundle().apply { putBoolean(ExtraShouldStop, shouldStop) }
                            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, resultBundle))
                        }
                        else -> super.onCustomCommand(session, controller, customCommand, args)
                    }
                }
            })
            .build()

        registerAudioReceivers()
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager).registerAudioDeviceCallback(outputCallback, Handler(Looper.getMainLooper()))
    }

    private fun registerAudioReceivers() {
        if (audioReceiversRegistered) return
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(AudioManager.ACTION_HEADSET_PLUG)
        }
        ContextCompat.registerReceiver(this, audioEventReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        audioReceiversRegistered = true
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        (application as? PodcastyApplication)?.container?.playbackController?.onServiceDestroyed()
        LockScreenLyricsNotificationController(applicationContext).dismiss()
        if (audioReceiversRegistered) {
            runCatching { unregisterReceiver(audioEventReceiver) }
            audioReceiversRegistered = false
        }
        (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(shakeListener)
        shakeRegistered = false
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager).unregisterAudioDeviceCallback(outputCallback)
        volumeFader.cancel()
        sharedPausePending = false
        sleepTimerManager.cancel(restoreVolume = false)
        replayGainJob?.cancel()
        sharedAudioEffectsEngine.setReplayGain(0f)
        sharedAudioEffectsEngine.release()
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        exoPlayer = null
        mediaSession = null
        super.onDestroy()
    }

    /**
     * Bridges MediaSession transport controls (Next / Previous from lock screen, QS tile, and
     * Bluetooth headsets) to PodcastPlaybackController's full queue while keeping ExoPlayer's
     * play/pause/seek timeline direct so scrubbing and state transitions update immediately.
     */
    @UnstableApi
    private inner class QueueForwardingPlayer(delegate: Player) : ForwardingPlayer(delegate) {
        override fun play() = requestPlay()
        override fun pause() = requestPause()
        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (playWhenReady) requestPlay() else requestPause()
        }
        override fun stop() {
            pausedByNoisyEvent = false
            pausePending = false
            sharedPausePending = false
            volumeFader.cancel()
            super.stop()
        }

        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands()
                .buildUpon()
                .addAll(
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                )
                .build()

        override fun isCommandAvailable(command: Int): Boolean =
            when (command) {
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                -> true
                else -> super.isCommandAvailable(command)
            }

        override fun seekToNext() {
            val controller = (application as? PodcastyApplication)?.container?.playbackController
            if (controller != null) controller.next() else super.seekToNext()
        }

        override fun seekToNextMediaItem() {
            val controller = (application as? PodcastyApplication)?.container?.playbackController
            if (controller != null) controller.next() else super.seekToNextMediaItem()
        }

        override fun seekToPrevious() {
            val controller = (application as? PodcastyApplication)?.container?.playbackController
            if (controller != null) controller.previous() else super.seekToPrevious()
        }

        override fun seekToPreviousMediaItem() {
            val controller = (application as? PodcastyApplication)?.container?.playbackController
            if (controller != null) controller.previous() else super.seekToPreviousMediaItem()
        }
    }

    /**
     * MediaSource.Factory that intercepts Raw PCM / AIFF / Sun AU URIs and streams them
     * through WrappedPcmDataSource while delegating everything else to Media3's default factory.
     */
    @UnstableApi
    private class PodcastyMediaSourceFactory(
        private val context: Context,
        private val delegate: DefaultMediaSourceFactory,
    ) : MediaSource.Factory {
        override fun setDrmSessionManagerProvider(
            drmSessionManagerProvider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider,
        ): MediaSource.Factory {
            delegate.setDrmSessionManagerProvider(drmSessionManagerProvider)
            return this
        }

        override fun setLoadErrorHandlingPolicy(
            loadErrorHandlingPolicy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy,
        ): MediaSource.Factory {
            delegate.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            return this
        }

        override fun getSupportedTypes(): IntArray = delegate.supportedTypes

        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            val extras = mediaItem.mediaMetadata.extras
            val formatHint = extras?.getString(ExtraFormatHint)?.lowercase()
            val uri = mediaItem.localConfiguration?.uri
            val pathHint = (extras?.getString(ExtraFileName) ?: uri?.lastPathSegment.orEmpty()).lowercase()

            if (extras?.getBoolean(ExtraYouTubeStream) == true) {
                val youTubeHttpFactory = DefaultHttpDataSource.Factory()
                    .setUserAgent(YouTubeBrowserUserAgent)
                    .setDefaultRequestProperties(
                        mapOf(
                            "Referer" to "https://www.youtube.com/",
                            "Origin" to "https://www.youtube.com",
                            "Accept-Language" to "en-US,en;q=0.9",
                        ),
                    )
                    .setConnectTimeoutMs(12_000)
                    .setReadTimeoutMs(20_000)
                    .setAllowCrossProtocolRedirects(true)
                return ProgressiveMediaSource.Factory(youTubeHttpFactory)
                    .createMediaSource(mediaItem)
            }

            val mode = when {
                formatHint == "pcm" || pathHint.endsWith(".pcm") || pathHint.endsWith(".raw") ->
                    WrappedPcmDataSource.Mode.RawPcm
                formatHint == "aiff" || pathHint.endsWith(".aiff") || pathHint.endsWith(".aif") ->
                    WrappedPcmDataSource.Mode.Aiff
                formatHint == "au" || pathHint.endsWith(".au") || pathHint.endsWith(".snd") ->
                    WrappedPcmDataSource.Mode.SunAu
                else -> null
            }

            if (mode != null && uri != null) {
                val pcmConfig = if (mode == WrappedPcmDataSource.Mode.RawPcm) {
                    val stored = runCatching {
                        runBlocking(Dispatchers.IO) {
                            PodcastyDatabase.getInstance(context).dao().getRawPcmConfig(uri.toString())
                        }
                    }.getOrNull()
                    if (stored != null) {
                        RawPcmConfig(
                            sourceUri = stored.sourceUri,
                            sampleRateHz = stored.sampleRateHz,
                            bitDepth = stored.bitDepth,
                            channelCount = stored.channelCount,
                            bigEndian = stored.bigEndian,
                            signedPcm = stored.signedPcm,
                        )
                    } else {
                        RawPcmConfig(sourceUri = uri.toString())
                    }
                } else {
                    null
                }
                val factory = WrappedPcmDataSource.Factory(context, pcmConfig, mode)
                return ProgressiveMediaSource.Factory(factory).createMediaSource(mediaItem)
            }
            return delegate.createMediaSource(mediaItem)
        }
    }

    companion object {
        private val HeadphoneOutputTypes = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        const val ActionConfigureSleepTimer = "com.podcasty.app.action.CONFIGURE_SLEEP_TIMER"
        const val ActionNotifyTrackCompleted = "com.podcasty.app.action.NOTIFY_TRACK_COMPLETED"
        const val ExtraPreset = "preset"
        const val ExtraCustomMinutes = "custom_minutes"
        const val ExtraTrackCount = "track_count"
        const val ExtraDeadlineEpochMs = "deadline_epoch_ms"
        const val ExtraFadeOutSeconds = "fade_out_seconds"
        const val ExtraShouldStop = "should_stop"
        const val ExtraFormatHint = "format_hint"
        const val ExtraFileName = "file_name"
        const val ExtraYouTubeStream = "youtube_stream"
        const val ExtraStreamMimeType = "stream_mime_type"

        private const val YouTubeBrowserUserAgent =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        @Volatile var sharedPausePending: Boolean = false
            private set

        private val _sharedSleepTimerState = MutableStateFlow(SleepTimerState())
        val sharedSleepTimerState: StateFlow<SleepTimerState> = _sharedSleepTimerState.asStateFlow()

        @UnstableApi
        val sharedLiveCaptionAudioProcessor: LiveCaptionAudioProcessor = LiveCaptionAudioProcessor()

        @UnstableApi
        val sharedAudioDspProcessor: PodcastyAudioDspProcessor = PodcastyAudioDspProcessor()

        @UnstableApi
        val sharedAudioEffectsEngine: PodcastyAudioEffectsEngine = PodcastyAudioEffectsEngine(sharedAudioDspProcessor)
    }
}
