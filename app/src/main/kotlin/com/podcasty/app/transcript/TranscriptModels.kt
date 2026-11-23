/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.transcript

import kotlinx.serialization.Serializable

@Serializable
data class TranscriptCue(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speaker: String? = null,
)

enum class TranscriptFormat(val key: String, val label: String) {
    Lrc("lrc", "LRC"),
    EnhancedLrc("elrc", "Enhanced LRC"),
    Srt("srt", "SRT"),
    WebVtt("vtt", "WebVTT"),
    PodcastJson("json", "Podcast JSON"),
    PlainText("plain", "Plain text"),
    ;

    companion object {
        fun fromKey(key: String?): TranscriptFormat =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: PlainText
    }
}

enum class TranscriptSource(val key: String, val label: String) {
    Seed("seed", "Built-in"),
    RssCreator("rss", "Creator RSS transcript"),
    AuthorizedImport("imported", "Imported transcript"),
    EmbeddedTag("embedded", "Embedded audio lyrics"),
    ;

    companion object {
        fun fromKey(key: String?): TranscriptSource =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: AuthorizedImport
    }
}

data class TranscriptDocument(
    val episodeId: String,
    val language: String = "en",
    val format: TranscriptFormat = TranscriptFormat.PlainText,
    val source: TranscriptSource = TranscriptSource.AuthorizedImport,
    val cues: List<TranscriptCue> = emptyList(),
) {
    val isTimed: Boolean
        get() = cues.any { it.startMs >= 0L && it.endMs > it.startMs }
}

data class CaptionSyncSnapshot(
    val episodeId: String = "",
    val language: String = "en",
    val activeCueIndex: Int = -1,
    val activeCue: TranscriptCue? = null,
    val previousCue: TranscriptCue? = null,
    val nextCue: TranscriptCue? = null,
    val totalCues: Int = 0,
    val isTimed: Boolean = false,
)
