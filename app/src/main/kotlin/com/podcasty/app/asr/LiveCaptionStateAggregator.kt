/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.asr

import kotlin.math.abs

/**
 * Pure, deterministic state machine for live podcast caption synchronization.
 *
 * Guarantees:
 * 1. Displays PARTIAL text immediately.
 * 2. Replaces PARTIAL cleanly when the matching FINAL segment arrives.
 * 3. Deduplicates repeated or heavily overlapping sentences.
 * 4. Preserves all previously finalized sentences in chronological `startMs` order.
 * 5. Computes active sentence index via O(log N) binary search against playback position (zero drift).
 * 6. Supports seeking forward/backward into previously recognized captions without losing history.
 */
class LiveCaptionStateAggregator {
    private val finalized = mutableListOf<LiveCaptionSegment>()
    private var activePartial: LiveCaptionSegment? = null

    fun reset(seedFinalized: List<LiveCaptionSegment> = emptyList()) {
        finalized.clear()
        finalized.addAll(
            seedFinalized
                .filter { it.text.isNotBlank() }
                .map { it.copy(status = LiveCaptionStatus.FINAL) }
                .sortedBy { it.startMs },
        )
        activePartial = null
    }

    fun onIncomingSegment(segment: LiveCaptionSegment): Pair<List<LiveCaptionSegment>, LiveCaptionSegment?> {
        val cleanedText = segment.text.replace(Regex("\\s+"), " ").trim()
        if (cleanedText.isBlank()) return snapshot()
        val normalized = segment.copy(
            text = cleanedText,
            startMs = segment.startMs.coerceAtLeast(0L),
            endMs = segment.endMs.coerceAtLeast(segment.startMs.coerceAtLeast(0L)),
        )

        if (normalized.status == LiveCaptionStatus.PARTIAL) {
            activePartial = normalized
            return snapshot()
        }

        // FINAL segment arrived: clear partial and insert/merge without duplicating sentences
        activePartial = null
        val normKey = normalizeForDedup(normalized.text)
        val duplicateIndex = finalized.indexOfFirst { existing ->
            val existingKey = normalizeForDedup(existing.text)
            val timeNear = abs(existing.startMs - normalized.startMs) <= 3_500L ||
                (normalized.startMs <= existing.endMs + 1_000L && normalized.endMs >= existing.startMs - 1_000L)
            timeNear && (existingKey == normKey || existingKey.contains(normKey) || normKey.contains(existingKey))
        }

        if (duplicateIndex >= 0) {
            val existing = finalized[duplicateIndex]
            // Keep the richer / longer finalized sentence while preserving earliest start timestamp
            val mergedText = if (normalized.text.length >= existing.text.length) normalized.text else existing.text
            finalized[duplicateIndex] = existing.copy(
                startMs = minOf(existing.startMs, normalized.startMs),
                endMs = maxOf(existing.endMs, normalized.endMs),
                text = mergedText,
                language = normalized.language,
                status = LiveCaptionStatus.FINAL,
            )
        } else {
            val insertAt = finalized.indexOfFirst { it.startMs > normalized.startMs }
            if (insertAt < 0) finalized.add(normalized) else finalized.add(insertAt, normalized)
        }
        return snapshot()
    }

    fun clearPartialOnly(): Pair<List<LiveCaptionSegment>, LiveCaptionSegment?> {
        activePartial = null
        return snapshot()
    }

    fun snapshot(): Pair<List<LiveCaptionSegment>, LiveCaptionSegment?> =
        finalized.toList() to activePartial

    companion object {
        /**
         * O(log N) binary search mapping the current media playback position to the active
         * synchronized caption line. Handles finalized sentences + trailing live partial line.
         */
        fun findActiveIndex(
            finalized: List<LiveCaptionSegment>,
            activePartial: LiveCaptionSegment?,
            playbackPositionMs: Long,
        ): Int {
            val total = finalized.size + if (activePartial != null) 1 else 0
            if (total == 0) return -1

            // If playback position is inside or after the live partial's start timestamp, highlight partial
            if (activePartial != null && playbackPositionMs >= activePartial.startMs - 250L) {
                val lastFinalEnd = finalized.lastOrNull()?.endMs ?: 0L
                if (playbackPositionMs >= lastFinalEnd - 150L) {
                    return finalized.size
                }
            }
            if (finalized.isEmpty()) return if (activePartial != null) 0 else -1

            var low = 0
            var high = finalized.lastIndex
            var best = -1
            while (low <= high) {
                val mid = (low + high) ushr 1
                val seg = finalized[mid]
                if (seg.startMs <= playbackPositionMs + 150L) {
                    best = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            return best.coerceAtLeast(0)
        }

        internal fun normalizeForDedup(text: String): String =
            text.lowercase()
                .replace(Regex("[^\\p{L}\\p{N}\\s]"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
    }
}
