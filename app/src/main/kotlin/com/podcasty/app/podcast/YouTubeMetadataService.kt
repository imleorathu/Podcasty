/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.podcast

import com.podcasty.ytmusicapi.YTMusicApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class YouTubeVideoMetadata(
    val videoId: String,
    val canonicalUrl: String,
    val title: String,
    val channelName: String,
    val description: String = "",
    val thumbnailUrl: String? = null,
    val durationMs: Long = 0L,
    val publishedAtRaw: String? = null,
    val isShorts: Boolean = false,
    val metadataSource: String = "YTMusic API",
)

/**
 * YouTube & YouTube Music metadata resolver powered by the integrated `YTMusicApiClient`
 * (`YTmusicapi` `get_song`), with fallback to YouTube Data API v3 and public oEmbed.
 */
object YouTubeMetadataService {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val isoDurationRegex = Regex("^PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?$")
    private val ytMusicClient = YTMusicApiClient()

    suspend fun fetchMetadata(
        videoId: String,
        isShorts: Boolean = false,
        optionalDataApiKey: String? = null,
    ): Result<YouTubeVideoMetadata> = withContext(Dispatchers.IO) {
        val canonicalUrl = "https://www.youtube.com/watch?v=$videoId"
        val defaultThumb = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

        // 1. Primary: Integrated YTmusicapi `get_song` (returns title, artist/channel, description, thumbnail, and exact duration)
        val ytMusicSong = ytMusicClient.getSong(videoId).getOrNull()
        if (ytMusicSong != null && !ytMusicSong.title.startsWith("YouTube Video (")) {
            return@withContext Result.success(
                YouTubeVideoMetadata(
                    videoId = videoId,
                    canonicalUrl = canonicalUrl,
                    title = ytMusicSong.title,
                    channelName = ytMusicSong.author.ifBlank { "YouTube Music" },
                    description = ytMusicSong.description.take(500),
                    thumbnailUrl = ytMusicSong.thumbnailUrl ?: defaultThumb,
                    durationMs = ytMusicSong.durationMs,
                    publishedAtRaw = ytMusicSong.publishDate,
                    isShorts = isShorts,
                    metadataSource = "YTMusic API",
                ),
            )
        }

        // 2. Secondary: Optional YouTube Data API v3 key if configured
        if (!optionalDataApiKey.isNullOrBlank()) {
            val apiResult = runCatching {
                val encodedKey = URLEncoder.encode(optionalDataApiKey.trim(), "UTF-8")
                val url = "https://www.googleapis.com/youtube/v3/videos?part=snippet,contentDetails&id=$videoId&key=$encodedKey"
                val body = httpGet(url)
                parseYouTubeDataApiResponse(body, videoId, canonicalUrl, isShorts, defaultThumb)
            }.getOrNull()
            if (apiResult != null) {
                return@withContext Result.success(apiResult)
            }
        }

        val oEmbedResult = runCatching {
            val encoded = URLEncoder.encode(canonicalUrl, "UTF-8")
            val body = httpGet("https://www.youtube.com/oembed?url=$encoded&format=json")
            val parsed = parseOEmbedResponse(body, videoId, canonicalUrl, isShorts, defaultThumb)
            if (ytMusicSong != null && ytMusicSong.durationMs > 0L) {
                parsed.copy(
                    durationMs = ytMusicSong.durationMs,
                    description = ytMusicSong.description.ifBlank { parsed.description },
                )
            } else {
                parsed
            }
        }.getOrElse {
            YouTubeVideoMetadata(
                videoId = videoId,
                canonicalUrl = canonicalUrl,
                title = ytMusicSong?.title ?: "YouTube Video ($videoId)",
                channelName = ytMusicSong?.author ?: "YouTube Channel",
                description = ytMusicSong?.description.orEmpty(),
                thumbnailUrl = ytMusicSong?.thumbnailUrl ?: defaultThumb,
                durationMs = ytMusicSong?.durationMs ?: 0L,
                isShorts = isShorts,
                metadataSource = if (ytMusicSong != null) "YTMusic API" else "Video ID fallback",
            )
        }
        Result.success(oEmbedResult)
    }

    internal fun parseYouTubeDataApiResponse(
        body: String,
        videoId: String,
        canonicalUrl: String,
        isShorts: Boolean,
        fallbackThumb: String,
    ): YouTubeVideoMetadata? {
        val root = json.parseToJsonElement(body).jsonObject
        val items = root["items"]?.jsonArray ?: return null
        val first = items.firstOrNull()?.jsonObject ?: return null
        val snippet = first["snippet"]?.jsonObject ?: return null
        val contentDetails = first["contentDetails"]?.jsonObject
        val thumbs = snippet["thumbnails"]?.jsonObject
        val thumbUrl = thumbs?.get("maxres")?.jsonObject?.get("url")?.jsonPrimitive?.content
            ?: thumbs?.get("high")?.jsonObject?.get("url")?.jsonPrimitive?.content
            ?: thumbs?.get("medium")?.jsonObject?.get("url")?.jsonPrimitive?.content
            ?: fallbackThumb
        val durationMs = parseIso8601DurationMs(contentDetails?.get("duration")?.jsonPrimitive?.content.orEmpty())
        return YouTubeVideoMetadata(
            videoId = videoId,
            canonicalUrl = canonicalUrl,
            title = snippet["title"]?.jsonPrimitive?.content?.trim().orEmpty().ifBlank { "YouTube Video ($videoId)" },
            channelName = snippet["channelTitle"]?.jsonPrimitive?.content?.trim().orEmpty().ifBlank { "YouTube Channel" },
            description = snippet["description"]?.jsonPrimitive?.content?.trim().orEmpty().take(500),
            thumbnailUrl = thumbUrl,
            durationMs = durationMs,
            publishedAtRaw = snippet["publishedAt"]?.jsonPrimitive?.content,
            isShorts = isShorts,
            metadataSource = "YouTube Data API v3",
        )
    }

    internal fun parseOEmbedResponse(
        body: String,
        videoId: String,
        canonicalUrl: String,
        isShorts: Boolean,
        fallbackThumb: String,
    ): YouTubeVideoMetadata {
        val obj = json.parseToJsonElement(body).jsonObject
        return YouTubeVideoMetadata(
            videoId = videoId,
            canonicalUrl = canonicalUrl,
            title = obj["title"]?.jsonPrimitive?.content?.trim().orEmpty().ifBlank { "YouTube Video ($videoId)" },
            channelName = obj["author_name"]?.jsonPrimitive?.content?.trim().orEmpty().ifBlank { "YouTube Channel" },
            thumbnailUrl = obj["thumbnail_url"]?.jsonPrimitive?.content?.trim()?.ifBlank { fallbackThumb } ?: fallbackThumb,
            isShorts = isShorts,
            metadataSource = "YouTube oEmbed",
        )
    }

    internal fun parseIso8601DurationMs(iso: String): Long {
        val match = isoDurationRegex.matchEntire(iso.trim().uppercase()) ?: return 0L
        val hours = match.groupValues[1].toLongOrNull() ?: 0L
        val minutes = match.groupValues[2].toLongOrNull() ?: 0L
        val seconds = match.groupValues[3].toLongOrNull() ?: 0L
        return (hours * 3600L + minutes * 60L + seconds) * 1_000L
    }

    private fun httpGet(urlString: String): String {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Podcasty/1.0 (Android)")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
