/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.player

import android.os.SystemClock
import com.podcasty.app.domain.SleepTimerPreset
import com.podcasty.app.domain.SleepTimerRequest
import com.podcasty.app.domain.SleepTimerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Monotonic-clock sleep timer designed to run inside the playback service / controller
 * independent of Activity lifecycle so it continues accurately when the screen is off.
 *
 * Features:
 * - Preset durations (5/10/15/20/30/45/60 min) + custom duration
 * - Stop after current track/episode
 * - Stop after N tracks/episodes
 * - Smooth linear fade-out on player volume only (never modifies device system volume)
 * - Automatic volume restoration on cancel or new playback start
 */
class SleepTimerManager(
    private val scope: CoroutineScope,
    private val elapsedRealtimeMsProvider: () -> Long = { SystemClock.elapsedRealtime() },
    private val onSetPlayerVolume: (Float) -> Unit,
    private val onTimerTriggeredPause: () -> Unit,
    private val wallClockMsProvider: () -> Long = { System.currentTimeMillis() },
) {
    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private var timerJob: Job? = null
    private var endElapsedRealtimeMs: Long = 0L
    var deadlineEpochMs: Long? = null
        private set

    fun configure(request: SleepTimerRequest) {
        cancel(restoreVolume = true)
        val fadeSec = request.fadeOutSeconds.coerceIn(0, 120)

        when (request.preset) {
            SleepTimerPreset.Off -> {
                _state.value = SleepTimerState(preset = SleepTimerPreset.Off, fadeOutSeconds = fadeSec)
            }
            SleepTimerPreset.EndOfEpisode -> {
                _state.value = SleepTimerState(
                    preset = SleepTimerPreset.EndOfEpisode,
                    remainingTracks = 1,
                    fadeOutSeconds = fadeSec,
                )
            }
            SleepTimerPreset.AfterTracks -> {
                val count = (request.trackCount ?: 3).coerceIn(1, 99)
                _state.value = SleepTimerState(
                    preset = SleepTimerPreset.AfterTracks,
                    remainingTracks = count,
                    fadeOutSeconds = fadeSec,
                )
            }
            else -> {
                val minutes = when (request.preset) {
                    SleepTimerPreset.CustomDuration -> (request.customMinutes ?: 15).coerceIn(1, 360)
                    else -> (request.preset.minutes ?: 15).coerceIn(1, 360)
                }
                val totalMs = request.deadlineEpochMs?.let { (it - wallClockMsProvider()).coerceIn(0L, 24L * 60 * 60 * 1000) }
                    ?: (minutes * 60_000L)
                if (totalMs <= 0L) return
                deadlineEpochMs = wallClockMsProvider() + totalMs
                endElapsedRealtimeMs = elapsedRealtimeMsProvider() + totalMs
                _state.value = SleepTimerState(
                    preset = request.preset,
                    remainingSeconds = ((totalMs + 999L) / 1000L).toInt(),
                    fadeOutSeconds = fadeSec,
                    isFadingOut = false,
                    currentVolumeMultiplier = 1.0f,
                )
                startCountdownLoop(request.preset, fadeSec)
            }
        }
    }

    fun cancel(restoreVolume: Boolean = true) {
        timerJob?.cancel()
        timerJob = null
        endElapsedRealtimeMs = 0L
        deadlineEpochMs = null
        if (restoreVolume) {
            onSetPlayerVolume(1.0f)
        }
        val prevFade = _state.value.fadeOutSeconds
        _state.value = SleepTimerState(
            preset = SleepTimerPreset.Off,
            fadeOutSeconds = prevFade,
            isFadingOut = false,
            currentVolumeMultiplier = 1.0f,
        )
    }

    /** Track timers use media time divided by playback speed; duration timers use monotonic time. */
    fun onPlaybackProgress(positionMs: Long, durationMs: Long, speed: Float) {
        val current = _state.value
        val lastTrack = current.preset == SleepTimerPreset.EndOfEpisode ||
            (current.preset == SleepTimerPreset.AfterTracks && current.remainingTracks == 1)
        if (!lastTrack || durationMs <= 0L) return
        val remainingMs = ((durationMs - positionMs).coerceAtLeast(0L) / speed.coerceAtLeast(0.1f)).toLong()
        val volume = computeFadeVolume(remainingMs, current.fadeOutSeconds)
        onSetPlayerVolume(volume)
        _state.value = current.copy(isFadingOut = volume < 1f, currentVolumeMultiplier = volume)
    }

    fun onNewPlaybackStarted() {
        if (!_state.value.isFadingOut) {
            onSetPlayerVolume(1.0f)
        }
    }

    /**
     * Called when the currently playing track/episode finishes naturally.
     * Returns true if playback should stop now instead of advancing.
     */
    fun onTrackCompleted(): Boolean {
        val current = _state.value
        return when (current.preset) {
            SleepTimerPreset.EndOfEpisode -> {
                cancel(restoreVolume = true)
                true
            }
            SleepTimerPreset.AfterTracks -> {
                val remaining = (current.remainingTracks ?: 1) - 1
                if (remaining <= 0) {
                    cancel(restoreVolume = true)
                    true
                } else {
                    _state.value = current.copy(remainingTracks = remaining)
                    false
                }
            }
            else -> false
        }
    }

    /**
     * Computes player volume multiplier [0f..1f] for a given remaining duration & fade window.
     */
    fun computeFadeVolume(remainingMs: Long, fadeOutSeconds: Int): Float {
        if (fadeOutSeconds <= 0) return 1.0f
        val fadeMs = fadeOutSeconds * 1_000L
        if (remainingMs >= fadeMs) return 1.0f
        return (remainingMs.toFloat() / fadeMs.toFloat()).coerceIn(0f, 1f)
    }

    private fun startCountdownLoop(preset: SleepTimerPreset, fadeOutSeconds: Int) {
        timerJob = scope.launch {
            while (isActive) {
                val now = elapsedRealtimeMsProvider()
                val remainingMs = (endElapsedRealtimeMs - now).coerceAtLeast(0L)
                val remainingSec = ((remainingMs + 999L) / 1_000L).toInt()
                val volume = computeFadeVolume(remainingMs, fadeOutSeconds)
                val fading = fadeOutSeconds > 0 && remainingMs < fadeOutSeconds * 1_000L && remainingMs > 0L

                if (fading) {
                    onSetPlayerVolume(volume)
                }

                _state.value = SleepTimerState(
                    preset = preset,
                    remainingSeconds = remainingSec,
                    fadeOutSeconds = fadeOutSeconds,
                    isFadingOut = fading,
                    currentVolumeMultiplier = volume,
                )

                if (remainingMs <= 0L) {
                    deadlineEpochMs = null
                    onSetPlayerVolume(0f)
                    onTimerTriggeredPause()
                    // Restore normal player volume cleanly for the next playback session
                    onSetPlayerVolume(1.0f)
                    _state.value = SleepTimerState(
                        preset = SleepTimerPreset.Off,
                        fadeOutSeconds = fadeOutSeconds,
                        isFadingOut = false,
                        currentVolumeMultiplier = 1.0f,
                    )
                    break
                }
                delay(if (fading) 250L else 1_000L)
            }
        }
    }
}
