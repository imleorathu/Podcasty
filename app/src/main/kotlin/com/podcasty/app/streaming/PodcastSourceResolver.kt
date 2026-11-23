/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.streaming

import com.podcasty.app.podcast.YouTubeAndRssImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

sealed class SourceResolutionException(message: String) : IllegalArgumentException(message) {
    class InvalidUrl(message: String) : SourceResolutionException(message)
    class InsecureUrl(message: String) : SourceResolutionException(message)
    class UnsupportedSource(message: String) : SourceResolutionException(message)
    class NetworkFailure(message: String) : SourceResolutionException(message)
}

/**
 * Validates and classifies pasted online URLs strictly over HTTPS:
 * 1. Official YouTube URLs (standard, short youtu.be, live, shorts, embed, music.youtube.com)
 * 2. Publisher-provided transcript URLs (.vtt, .srt, .lrc, .json)
 * 3. Direct HTTPS podcast audio streams (.mp3, .m4a, .aac, .ogg, .opus, .wav, .flac, or audio MIME)
 * 4. Standard RSS 2.0 podcast feeds (.xml, .rss, /feed, or application/rss+xml)
 */
class PodcastSourceResolver {
    private val audioExtensions = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "mpga")
    private val transcriptExtensions = setOf("vtt", "srt", "lrc")
    private val rssExtensions = setOf("xml", "rss", "atom")

    fun validateHttpsUrl(rawInput: String): URI {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            throw SourceResolutionException.InvalidUrl("Paste a YouTube podcast URL, RSS feed URL, or HTTPS audio stream URL.")
        }
        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: throw SourceResolutionException.InvalidUrl("The entered URL is malformed.")
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https") {
            throw SourceResolutionException.InsecureUrl("Only secure HTTPS URLs are permitted for online podcast streaming.")
        }
        if (uri.host.isNullOrBlank()) {
            throw SourceResolutionException.InvalidUrl("URL must include a valid domain host.")
        }
        return uri
    }

    fun extractValidatedYouTubeVideoId(rawUrl: String): String? =
        extractYouTubeVideoId(rawUrl)

    suspend fun resolve(rawUrl: String): Result<ResolvedPodcastSource> = withContext(Dispatchers.IO) {
        runCatching {
            val uri = validateHttpsUrl(rawUrl)
            val normalizedUrl = uri.toString()

            // 1. Official YouTube video/podcast URL
            val ytVideoId = extractValidatedYouTubeVideoId(normalizedUrl)
            if (ytVideoId != null) {
                val meta = YouTubeAndRssImporter.resolveYouTubeReference(normalizedUrl).getOrThrow()
                return@runCatching ResolvedPodcastSource.YouTubeVideo(
                    videoId = ytVideoId,
                    canonicalUrl = meta.canonicalUrl,
                    title = meta.title,
                    channelName = meta.channelName,
                )
            }

            val pathExt = uri.path.orEmpty().substringAfterLast('/', "").substringAfterLast('.', "").lowercase()

            // 2. Publisher-provided transcript URL (.vtt / .srt / .lrc)
            if (pathExt in transcriptExtensions) {
                return@runCatching ResolvedPodcastSource.PublisherTranscriptUrl(
                    transcriptUrl = normalizedUrl,
                    formatHint = pathExt,
                )
            }

            // 3. Direct HTTPS audio URL by extension
            if (pathExt in audioExtensions) {
                val inferredTitle = uri.path.orEmpty()
                    .substringAfterLast('/')
                    .substringBeforeLast('.')
                    .replace('-', ' ')
                    .replace('_', ' ')
                    .ifBlank { "Online Podcast Stream" }
                return@runCatching ResolvedPodcastSource.DirectAudioStream(
                    streamUrl = normalizedUrl,
                    title = inferredTitle,
                    mimeType = mimeTypeForExtension(pathExt),
                )
            }

            // 4. RSS feed by extension or live HTTPS Content-Type probe
            val probedContentType = if (pathExt in rssExtensions) {
                "application/rss+xml"
            } else {
                probeContentType(normalizedUrl)
            }

            when {
                probedContentType != null && probedContentType.startsWith("audio/") -> {
                    ResolvedPodcastSource.DirectAudioStream(
                        streamUrl = normalizedUrl,
                        title = uri.path.orEmpty().substringAfterLast('/').ifBlank { uri.host ?: "Podcast Stream" },
                        mimeType = probedContentType,
                    )
                }
                probedContentType != null && (probedContentType.contains("vtt") || probedContentType.contains("subrip")) -> {
                    ResolvedPodcastSource.PublisherTranscriptUrl(
                        transcriptUrl = normalizedUrl,
                        formatHint = if (probedContentType.contains("vtt")) "vtt" else "srt",
                    )
                }
                probedContentType != null && (
                    probedContentType.contains("xml") ||
                        probedContentType.contains("rss") ||
                        probedContentType.contains("atom") ||
                        probedContentType.contains("text/plain")
                    ) -> {
                    val feed = YouTubeAndRssImporter.fetchRssFeed(normalizedUrl).getOrElse { err ->
                        throw SourceResolutionException.NetworkFailure(
                            "Could not load RSS podcast feed: ${err.localizedMessage ?: "network error"}",
                        )
                    }
                    if (feed.episodes.isEmpty()) {
                        throw SourceResolutionException.UnsupportedSource("No playable audio episodes found in this RSS feed.")
                    }
                    ResolvedPodcastSource.RssFeed(
                        feedUrl = normalizedUrl,
                        podcastTitle = feed.podcastTitle,
                        hostName = feed.hostName,
                        episodes = feed.episodes,
                    )
                }
                else -> {
                    throw SourceResolutionException.UnsupportedSource(
                        "Unsupported URL type (${probedContentType ?: "unknown content"}). Provide a YouTube URL, RSS feed URL, or HTTPS audio stream.",
                    )
                }
            }
        }
    }

    private fun probeContentType(urlString: String): String? {
        return runCatching {
            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Podcasty/1.0 (Android)")
            }
            try {
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_BAD_METHOD || code == HttpURLConnection.HTTP_NOT_IMPLEMENTED) {
                    return probeContentTypeWithRangeGet(urlString)
                }
                if (code !in 200..299) {
                    throw SourceResolutionException.NetworkFailure("Server returned HTTP $code while checking URL.")
                }
                conn.contentType?.substringBefore(';')?.trim()?.lowercase()
            } finally {
                conn.disconnect()
            }
        }.getOrElse { err ->
            if (err is SourceResolutionException) throw err
            throw SourceResolutionException.NetworkFailure("Network error while reaching URL: ${err.localizedMessage ?: "offline"}")
        }
    }

    private fun probeContentTypeWithRangeGet(urlString: String): String? {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            setRequestProperty("Range", "bytes=0-1023")
            setRequestProperty("User-Agent", "Podcasty/1.0 (Android)")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw SourceResolutionException.NetworkFailure("Server returned HTTP $code while checking URL.")
            }
            conn.contentType?.substringBefore(';')?.trim()?.lowercase()
        } finally {
            conn.disconnect()
        }
    }

    private fun mimeTypeForExtension(ext: String): String = when (ext) {
        "mp3", "mpga" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        else -> "audio/*"
    }

    companion object {
        private val youtubeVideoIdRegex = Regex("^[A-Za-z0-9_-]{11}$")

        fun extractYouTubeVideoId(rawUrl: String): String? {
            val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true)) return null
            val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
            val path = uri.path.orEmpty()
            val candidate = when (host) {
                "youtu.be" -> path.trim('/').substringBefore('/')
                "youtube.com", "music.youtube.com", "youtube-nocookie.com" -> {
                    when {
                        path.startsWith("/watch") -> parseQueryParam(uri.rawQuery, "v")
                        path.startsWith("/live/") -> path.removePrefix("/live/").substringBefore('/')
                        path.startsWith("/shorts/") -> path.removePrefix("/shorts/").substringBefore('/')
                        path.startsWith("/embed/") -> path.removePrefix("/embed/").substringBefore('/')
                        else -> null
                    }
                }
                else -> null
            }
            return candidate?.takeIf { youtubeVideoIdRegex.matches(it) }
        }

        private fun parseQueryParam(rawQuery: String?, key: String): String? {
            if (rawQuery.isNullOrBlank()) return null
            return rawQuery.split('&').firstNotNullOfOrNull { pair ->
                val idx = pair.indexOf('=')
                if (idx > 0 && pair.substring(0, idx) == key) pair.substring(idx + 1) else null
            }
        }
    }
}
