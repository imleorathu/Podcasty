/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.streaming

import com.podcasty.app.transcript.TranscriptDocument
import com.podcasty.app.transcript.TranscriptParser
import com.podcasty.app.transcript.TranscriptSource

/**
 * Reusable CaptionParser facade validating and parsing SRT, WebVTT, LRC, Enhanced LRC,
 * and timestamped Podcast JSON transcripts in memory with strict validation against
 * malformed timestamps.
 */
object CaptionParser {
    fun parseTranscript(
        episodeId: String,
        rawContent: String,
        fileNameOrUrl: String? = null,
        language: String = "en",
        source: TranscriptSource = TranscriptSource.AuthorizedImport,
    ): Result<TranscriptDocument> {
        val trimmed = rawContent.trimStart('\uFEFF').trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Transcript content is empty."))
        }
        val doc = TranscriptParser.parse(
            episodeId = episodeId,
            content = trimmed,
            fileName = fileNameOrUrl,
            language = language,
            source = source,
        )
        if (doc.cues.isEmpty()) {
            return Result.failure(IllegalArgumentException("No valid caption cues or timestamps were found in the transcript."))
        }
        val invalidTiming = doc.cues.any { it.startMs >= 0L && it.endMs < it.startMs }
        if (invalidTiming) {
            return Result.failure(IllegalArgumentException("Transcript contains invalid timestamps (end before start)."))
        }
        return Result.success(doc)
    }
}
