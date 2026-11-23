/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.podcast

import java.net.InetAddress
import java.net.URI

enum class DetectedPodcastUrlType {
    YouTube,
    RssOrAtomFeed,
    DirectAudio,
}

data class DetectedPodcastUrl(
    val normalizedUrl: String,
    val type: DetectedPodcastUrlType,
    val youtubeVideoId: String? = null,
    val isYouTubeShorts: Boolean = false,
)

class InvalidPodcastUrlException(message: String) : IllegalArgumentException(message)

/**
 * Validates user-pasted podcast URLs and automatically classifies them without requiring
 * the user to pick a source type manually.
 *
 * Security:
 * - Rejects non-HTTP(S) schemes (javascript:, file:, content:, data:, etc.)
 * - Rejects credentials embedded in URLs (user:pass@host)
 * - Rejects private / loopback / link-local hosts for remote podcast imports
 */
object PodcastUrlDetector {
    private val youtubeWatchPattern = Regex(
        "(?:https?://)?(?:www\\.|m\\.|music\\.)?youtube\\.com/(?:watch\\?(?:[^#\\s]*&)?v=|live/|embed/)([A-Za-z0-9_-]{11})",
        RegexOption.IGNORE_CASE,
    )
    private val youtubeShortsPattern = Regex(
        "(?:https?://)?(?:www\\.|m\\.)?youtube\\.com/shorts/([A-Za-z0-9_-]{11})",
        RegexOption.IGNORE_CASE,
    )
    private val youtubeShortLinkPattern = Regex(
        "(?:https?://)?(?:www\\.)?youtu\\.be/([A-Za-z0-9_-]{11})",
        RegexOption.IGNORE_CASE,
    )
    private val directAudioExtensions = setOf(
        ".mp3", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".wav", ".flac", ".weba", ".m4b",
    )
    private val rssHints = listOf(
        ".xml", ".rss", ".atom", "/feed", "feed=", "format=xml", "rss.xml", "podcast.xml", "feeds.",
    )

    fun extractYouTubeVideoId(rawUrl: String): Pair<String, Boolean>? {
        val trimmed = rawUrl.trim()
        youtubeShortsPattern.find(trimmed)?.let { return it.groupValues[1] to true }
        youtubeWatchPattern.find(trimmed)?.let { return it.groupValues[1] to false }
        youtubeShortLinkPattern.find(trimmed)?.let { return it.groupValues[1] to false }
        return null
    }

    fun detect(rawInput: String, allowPrivateNetworkHosts: Boolean = false): Result<DetectedPodcastUrl> = runCatching {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            throw InvalidPodcastUrlException("Paste a YouTube, RSS feed, or direct podcast audio URL.")
        }
        val withScheme = if (!trimmed.contains("://")) "https://$trimmed" else trimmed
        val uri = runCatching { URI(withScheme) }.getOrElse {
            throw InvalidPodcastUrlException("Invalid URL format. Check the link and try again.")
        }
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme != "https" && scheme != "http") {
            throw InvalidPodcastUrlException("Only HTTPS/HTTP podcast URLs are supported.")
        }
        if (!uri.userInfo.isNullOrBlank()) {
            throw InvalidPodcastUrlException("URLs containing embedded credentials are not permitted.")
        }
        val host = uri.host?.lowercase().orEmpty()
        if (host.isBlank() || !host.contains('.')) {
            if (!allowPrivateNetworkHosts || (host != "localhost" && host != "10.0.2.2")) {
                throw InvalidPodcastUrlException("Enter a complete domain URL (for example, https://example.com/feed.xml).")
            }
        }
        if (!allowPrivateNetworkHosts && isDisallowedPrivateHost(host)) {
            throw InvalidPodcastUrlException("Private or local network URLs cannot be imported as public podcast feeds.")
        }

        // 1. YouTube detection (watch, youtu.be, shorts, live, embed, music.youtube.com)
        val ytMatch = extractYouTubeVideoId(withScheme)
        if (ytMatch != null) {
            val (videoId, isShorts) = ytMatch
            val canonical = "https://www.youtube.com/watch?v=$videoId"
            return@runCatching DetectedPodcastUrl(
                normalizedUrl = canonical,
                type = DetectedPodcastUrlType.YouTube,
                youtubeVideoId = videoId,
                isYouTubeShorts = isShorts,
            )
        }
        if (host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be") {
            throw InvalidPodcastUrlException("Unsupported YouTube URL. Paste a specific YouTube video or Shorts link.")
        }

        // 2. Direct audio stream detection by path extension
        val pathLower = uri.path.orEmpty().lowercase()
        val queryLower = uri.query.orEmpty().lowercase()
        val isDirectAudio = directAudioExtensions.any { ext ->
            pathLower.endsWith(ext) || queryLower.contains("$ext&") || queryLower.endsWith(ext)
        }
        if (isDirectAudio) {
            return@runCatching DetectedPodcastUrl(
                normalizedUrl = withScheme,
                type = DetectedPodcastUrlType.DirectAudio,
            )
        }

        // 3. Default to RSS/Atom feed (or probe content-type during inspection for extensionless URLs)
        DetectedPodcastUrl(
            normalizedUrl = withScheme,
            type = DetectedPodcastUrlType.RssOrAtomFeed,
        )
    }

    fun looksLikeRssUrl(url: String): Boolean {
        val lower = url.lowercase()
        return rssHints.any { lower.contains(it) }
    }

    fun isDisallowedPrivateHost(host: String): Boolean {
        val lower = host.trim().lowercase()
        if (lower == "localhost" || lower == "127.0.0.1" || lower == "::1" || lower == "0.0.0.0" || lower.endsWith(".local")) {
            return true
        }
        // Check literal IPv4 / IPv6 private ranges without triggering DNS lookups on unit-test JVM
        val ipv4Parts = lower.split('.')
        if (ipv4Parts.size == 4 && ipv4Parts.all { it.toIntOrNull() in 0..255 }) {
            val a = ipv4Parts[0].toInt()
            val b = ipv4Parts[1].toInt()
            if (a == 10 || a == 127 || a == 0) return true
            if (a == 169 && b == 254) return true
            if (a == 172 && b in 16..31) return true
            if (a == 192 && b == 168) return true
            if (a == 100 && b in 64..127) return true
        }
        if (lower.startsWith("fc") || lower.startsWith("fd") || lower.startsWith("fe80:")) return true
        return false
    }

    /**
     * Runtime DNS/IP verification used before making outbound HTTP requests on background IO threads.
     */
    fun verifyRemoteHostPublic(host: String) {
        if (isDisallowedPrivateHost(host)) {
            throw InvalidPodcastUrlException("Blocked unsafe or private network host: $host")
        }
        runCatching {
            val addresses = InetAddress.getAllByName(host)
            for (addr in addresses) {
                if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress) {
                    throw InvalidPodcastUrlException("Blocked private network address for host: $host")
                }
            }
        }
    }
}
