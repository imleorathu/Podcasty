/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.transcript

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reusable timestamp-based caption synchronization engine.
 *
 * Uses O(log N) binary search over sorted timed cues so long multi-hour podcast transcripts
 * resolve in microseconds, and only emits new snapshots when the active cue index or document
 * actually changes to prevent unnecessary UI recompositions and notification updates.
 */
class CaptionSyncEngine {
    private var currentDocument: TranscriptDocument = TranscriptDocument(episodeId = "")
    private var sortedTimedCues: List<TranscriptCue> = emptyList()
    private var lastPositionMs: Long = 0L

    private val _snapshot = MutableStateFlow(CaptionSyncSnapshot())
    val snapshot: StateFlow<CaptionSyncSnapshot> = _snapshot.asStateFlow()
    val hasActiveSubscribers: Boolean
        get() = _snapshot.subscriptionCount.value > 0

    fun loadDocument(document: TranscriptDocument, initialPositionMs: Long = 0L) {
        currentDocument = document
        sortedTimedCues = if (document.isTimed) {
            document.cues.filter { it.startMs >= 0L }.sortedBy { it.startMs }
        } else {
            emptyList()
        }
        lastPositionMs = initialPositionMs.coerceAtLeast(0L)
        emitSnapshot(findActiveCueIndex(sortedTimedCues, lastPositionMs), force = true)
    }

    fun clear() {
        currentDocument = TranscriptDocument(episodeId = "")
        sortedTimedCues = emptyList()
        lastPositionMs = 0L
        _snapshot.value = CaptionSyncSnapshot()
    }

    fun onPositionChanged(positionMs: Long): CaptionSyncSnapshot {
        val clamped = positionMs.coerceAtLeast(0L)
        lastPositionMs = clamped
        val nextIndex = findActiveCueIndex(sortedTimedCues, clamped)
        emitSnapshot(nextIndex, force = false)
        return _snapshot.value
    }

    fun onSeek(positionMs: Long): CaptionSyncSnapshot {
        val clamped = positionMs.coerceAtLeast(0L)
        lastPositionMs = clamped
        val nextIndex = findActiveCueIndex(sortedTimedCues, clamped)
        emitSnapshot(nextIndex, force = true)
        return _snapshot.value
    }

    fun currentCues(): List<TranscriptCue> =
        if (sortedTimedCues.isNotEmpty()) sortedTimedCues else currentDocument.cues

    private fun emitSnapshot(activeIndex: Int, force: Boolean) {
        val prev = _snapshot.value
        if (!force &&
            prev.episodeId == currentDocument.episodeId &&
            prev.language == currentDocument.language &&
            prev.activeCueIndex == activeIndex &&
            prev.totalCues == sortedTimedCues.size
        ) {
            return
        }
        val activeCue = sortedTimedCues.getOrNull(activeIndex)
        val previousCue = if (activeIndex > 0) sortedTimedCues.getOrNull(activeIndex - 1) else null
        val nextCue = if (activeIndex >= 0) sortedTimedCues.getOrNull(activeIndex + 1) else sortedTimedCues.firstOrNull()
        _snapshot.value = CaptionSyncSnapshot(
            episodeId = currentDocument.episodeId,
            language = currentDocument.language,
            activeCueIndex = activeIndex,
            activeCue = activeCue,
            previousCue = previousCue,
            nextCue = nextCue,
            nextCueStartMs = nextCue?.startMs,
            totalCues = if (sortedTimedCues.isNotEmpty()) sortedTimedCues.size else currentDocument.cues.size,
            isTimed = sortedTimedCues.isNotEmpty(),
        )
    }

    companion object {
        /**
         * Binary searches for the last cue whose startMs <= positionMs.
         * Returns -1 when cues are empty or playback position is before the first lyric cue (intro).
         */
        fun findActiveCueIndex(cues: List<TranscriptCue>, positionMs: Long): Int {
            if (cues.isEmpty()) return -1
            if (positionMs < cues[0].startMs) return -1
            var low = 0
            var high = cues.lastIndex
            var result = 0
            while (low <= high) {
                val mid = (low + high) ushr 1
                if (cues[mid].startMs <= positionMs) {
                    result = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            return result
        }
    }
}
