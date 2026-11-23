/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.streaming

import com.podcasty.app.transcript.CaptionSyncEngine
import com.podcasty.app.transcript.CaptionSyncSnapshot
import com.podcasty.app.transcript.TranscriptDocument
import kotlinx.coroutines.flow.StateFlow

/**
 * Pause-aware LyricsSyncEngine wrapping Podcasty's O(log N) binary-search CaptionSyncEngine.
 *
 * - Freezes synchronization updates while paused so background ticks never trigger recompositions.
 * - Immediately resynchronizes on seek (even while paused) and when playback resumes.
 * - Emits StateFlow updates only when the active cue index or transcript document changes.
 */
class LyricsSyncEngine(
    private val delegate: CaptionSyncEngine = CaptionSyncEngine(),
) {
    val snapshot: StateFlow<CaptionSyncSnapshot> = delegate.snapshot

    @Volatile
    private var isPaused: Boolean = true

    @Volatile
    private var lastKnownPositionMs: Long = 0L

    fun load(document: TranscriptDocument?, initialPositionMs: Long = 0L) {
        lastKnownPositionMs = initialPositionMs.coerceAtLeast(0L)
        if (document == null || document.cues.isEmpty()) {
            delegate.clear()
        } else {
            delegate.loadDocument(document, lastKnownPositionMs)
        }
    }

    fun clear() {
        isPaused = true
        lastKnownPositionMs = 0L
        delegate.clear()
    }

    fun onPlaybackStateChanged(isPlaying: Boolean, positionMs: Long = lastKnownPositionMs) {
        val wasPaused = isPaused
        isPaused = !isPlaying
        lastKnownPositionMs = positionMs.coerceAtLeast(0L)
        if (isPlaying && wasPaused) {
            delegate.onPositionChanged(lastKnownPositionMs)
        }
    }

    fun onPositionTick(positionMs: Long): CaptionSyncSnapshot {
        val clamped = positionMs.coerceAtLeast(0L)
        lastKnownPositionMs = clamped
        if (isPaused) return delegate.snapshot.value
        return delegate.onPositionChanged(clamped)
    }

    fun onSeek(positionMs: Long): CaptionSyncSnapshot {
        val clamped = positionMs.coerceAtLeast(0L)
        lastKnownPositionMs = clamped
        return delegate.onSeek(clamped)
    }
}
