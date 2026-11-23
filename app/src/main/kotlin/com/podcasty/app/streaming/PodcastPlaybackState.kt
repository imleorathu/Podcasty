/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.streaming

import com.podcasty.app.podcast.RssEpisodeMetadata
import com.podcasty.app.transcript.TranscriptCue
import com.podcasty.app.transcript.TranscriptDocument

enum class ActivePlaybackSourceType {
    LocalLibrary,
    YouTubeEmbedded,
    DirectAudioStream,
    RssAudioStream,
}

enum class StreamingPlaybackStatus {
    Idle,
    Resolving,
    Buffering,
    Playing,
    Paused,
    Ended,
    Error,
}

enum class CaptionSourcePriority(val label: String) {
    AuthorizedYouTubeApi("Authorized YouTube transcript (API v3)"),
    PublisherTranscript("Publisher transcript (in-memory)"),
    NativeYouTubeCaptions("Native YouTube captions"),
    LocalOrEmbedded("Local / embedded lyrics"),
    None("Unavailable"),
}

sealed interface ResolvedPodcastSource {
    data class YouTubeVideo(
        val videoId: String,
        val canonicalUrl: String,
        val title: String,
        val channelName: String,
    ) : ResolvedPodcastSource

    data class DirectAudioStream(
        val streamUrl: String,
        val title: String,
        val mimeType: String?,
        val transcriptUrl: String? = null,
    ) : ResolvedPodcastSource

    data class RssFeed(
        val feedUrl: String,
        val podcastTitle: String,
        val hostName: String,
        val episodes: List<RssEpisodeMetadata>,
    ) : ResolvedPodcastSource

    data class PublisherTranscriptUrl(
        val transcriptUrl: String,
        val formatHint: String,
    ) : ResolvedPodcastSource
}

data class PodcastPlaybackState(
    val sourceType: ActivePlaybackSourceType = ActivePlaybackSourceType.LocalLibrary,
    val status: StreamingPlaybackStatus = StreamingPlaybackStatus.Idle,
    val sourceUrl: String = "",
    val title: String = "",
    val subtitle: String = "",
    val youtubeVideoId: String? = null,
    val streamAudioUrl: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val captionPriority: CaptionSourcePriority = CaptionSourcePriority.None,
    val captionStatusMessage: String? = null,
    val transcriptDocument: TranscriptDocument? = null,
    val activeCueIndex: Int = -1,
    val activeCue: TranscriptCue? = null,
    val rssEpisodes: List<RssEpisodeMetadata> = emptyList(),
    val activeRssEpisodeIndex: Int = 0,
    val errorMessage: String? = null,
    val isOAuthSignedIn: Boolean = false,
) {
    val isOnlineActive: Boolean
        get() = sourceType != ActivePlaybackSourceType.LocalLibrary

    val isYouTubeActive: Boolean
        get() = sourceType == ActivePlaybackSourceType.YouTubeEmbedded && !youtubeVideoId.isNullOrBlank()

    val isOnlineAudioStreamActive: Boolean
        get() = sourceType == ActivePlaybackSourceType.DirectAudioStream ||
            sourceType == ActivePlaybackSourceType.RssAudioStream
}
