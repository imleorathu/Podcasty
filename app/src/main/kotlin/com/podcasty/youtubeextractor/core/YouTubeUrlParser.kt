/*
 * Podcasty — Native Android YouTube Caption Extractor Core
 * Ported from "caption extractor android" (devhims/youtube-caption-extractor v1.10.2 port, MIT License).
 */
package com.podcasty.youtubeextractor.core

import java.net.URI
import java.net.URLDecoder

object YouTubeUrlParser {
    private val videoIdPattern = Regex("^[A-Za-z0-9_-]{11}$")
    private val allowedHosts = setOf(
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "music.youtube.com",
        "youtu.be",
        "www.youtu.be",
        "youtube-nocookie.com",
        "www.youtube-nocookie.com",
    )

    fun extractVideoId(input: String): String {
        val value = input.trim()
        if (videoIdPattern.matches(value)) return value

        val normalized = if (value.startsWith("http://") || value.startsWith("https://")) {
            value
        } else {
            "https://$value"
        }

        val uri = try {
            URI(normalized)
        } catch (error: Exception) {
            throw IllegalArgumentException("Enter a valid YouTube URL.", error)
        }

        val host = uri.host?.lowercase()
            ?: throw IllegalArgumentException("The URL does not contain a valid host.")
        if (host !in allowedHosts) {
            throw IllegalArgumentException("This is not a supported YouTube URL.")
        }

        val candidate = when {
            host == "youtu.be" || host == "www.youtu.be" ->
                uri.path.orEmpty().split('/').filter(String::isNotBlank).firstOrNull()
            else -> {
                val queryId = uri.rawQuery.orEmpty()
                    .split('&')
                    .mapNotNull { part ->
                        val pieces = part.split('=', limit = 2)
                        if (pieces.size == 2 && pieces[0] == "v") {
                            URLDecoder.decode(pieces[1], Charsets.UTF_8.name())
                        } else {
                            null
                        }
                    }
                    .firstOrNull()

                queryId ?: run {
                    val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
                    if (segments.size >= 2 && segments[0] in setOf("shorts", "live", "embed", "v")) {
                        segments[1]
                    } else {
                        null
                    }
                }
            }
        }

        if (candidate == null || !videoIdPattern.matches(candidate)) {
            throw IllegalArgumentException("Could not find an 11-character YouTube video ID in this URL.")
        }
        return candidate
    }
}
