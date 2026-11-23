/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.podcast

import com.podcasty.app.transcript.TranscriptCue
import com.podcasty.app.transcript.TranscriptDocument
import com.podcasty.app.transcript.TranscriptFormat
import com.podcasty.app.transcript.TranscriptParser
import com.podcasty.app.transcript.TranscriptSource
import com.podcasty.youtubeextractor.core.Subtitle
import com.podcasty.youtubeextractor.core.YouTubeCaptionExtractor
import com.podcasty.youtubeextractor.core.YouTubeUrlParser
import com.podcasty.ytmusicapi.YTMusicApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

data class ExtractedYouTubeTranscript(
    val videoId: String,
    val title: String,
    val description: String,
    val language: String,
    val cues: List<TranscriptCue>,
) {
    fun toLrcContent(): String = TranscriptParser.toLrcString(cues)

    fun toTranscriptDocument(episodeId: String): TranscriptDocument = TranscriptDocument(
        episodeId = episodeId,
        language = language,
        format = TranscriptFormat.Lrc,
        source = TranscriptSource.AuthorizedImport,
        cues = cues,
    )
}

sealed interface YouTubeCaptionExtractionResult {
    data class Success(val transcript: ExtractedYouTubeTranscript) : YouTubeCaptionExtractionResult
    data class BackendNotConfigured(val message: String) : YouTubeCaptionExtractionResult
    data class Unavailable(val message: String) : YouTubeCaptionExtractionResult
    data class Error(val message: String) : YouTubeCaptionExtractionResult
}

/**
 * YouTube Caption Extractor Client integrating the native Android `YouTubeCaptionExtractor`
 * from the "caption extractor android" module directly on device, with an optional fallback
 * to the Node.js `/api/youtube/captions` backend if configured.
 *
 * When a user pastes or uploads a YouTube link, this extractor automatically fetches
 * the video's timestamped captions and maps `Subtitle(start, dur, text)` into synchronized
 * millisecond `TranscriptCue` lines for the music player's synchronized lyrics page.
 */
class YouTubeCaptionExtractorClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build(),
    private val nativeExtractor: YouTubeCaptionExtractor = YouTubeCaptionExtractor(httpClient),
    private val ytMusicClient: YTMusicApiClient = YTMusicApiClient(httpClient),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun extractCaptions(
        videoId: String,
        languageCode: String,
        configuredBackendUrl: String = "",
        authToken: String? = null,
    ): YouTubeCaptionExtractionResult = withContext(Dispatchers.IO) {
        val cleanId = runCatching { YouTubeUrlParser.extractVideoId(videoId) }.getOrNull()
            ?: videoId.trim().takeIf { VideoIdRegex.matches(it) }
            ?: return@withContext YouTubeCaptionExtractionResult.Error("Invalid YouTube video ID.")

        val primaryLang = languageCode.trim().substringBefore('-').substringBefore('_').lowercase().ifBlank { "en" }

        // 1. Primary: Built-in native Android YouTubeCaptionExtractor ("caption extractor android")
        val nativeAttempt = runCatching {
            nativeExtractor.getVideoDetails(videoUrlOrId = cleanId, lang = languageCode.ifBlank { primaryLang })
        }
        val nativeDetails = nativeAttempt.getOrNull()
        if (nativeDetails != null && nativeDetails.subtitles.isNotEmpty()) {
            val cues = subtitlesToTranscriptCues(nativeDetails.subtitles)
            if (cues.isNotEmpty()) {
                val resolvedLang = nativeDetails.selectedLanguage
                    ?.takeIf { it.isNotBlank() }
                    ?: TranscriptParser.detectScriptLanguage(cues.joinToString(" ") { it.text })
                    ?: primaryLang
                return@withContext YouTubeCaptionExtractionResult.Success(
                    ExtractedYouTubeTranscript(
                        videoId = cleanId,
                        title = nativeDetails.title.takeUnless { it == "No title found" }.orEmpty(),
                        description = nativeDetails.description.takeUnless { it == "No description found" }.orEmpty(),
                        language = resolvedLang,
                        cues = cues,
                    ),
                )
            }
        }

        // 2. Secondary: YTmusicapi mobile timed lyrics (`get_watch_playlist` + `get_lyrics(timestamps=True)`)
        val ytMusicLyrics = runCatching { ytMusicClient.getTimedLyricsForVideo(cleanId) }.getOrNull()
        if (ytMusicLyrics != null && ytMusicLyrics.hasTimestamps && ytMusicLyrics.lines.isNotEmpty()) {
            val cues = ytMusicLyrics.lines.mapIndexed { idx, line ->
                TranscriptCue(
                    id = idx.toLong(),
                    startMs = line.startTimeMs,
                    endMs = line.endTimeMs.coerceAtLeast(line.startTimeMs + 100L),
                    text = line.text,
                    speaker = null,
                )
            }
            val detectedLang = TranscriptParser.detectScriptLanguage(cues.joinToString(" ") { it.text }) ?: primaryLang
            return@withContext YouTubeCaptionExtractionResult.Success(
                ExtractedYouTubeTranscript(
                    videoId = cleanId,
                    title = nativeDetails?.title?.takeUnless { it == "No title found" }.orEmpty(),
                    description = nativeDetails?.description?.takeUnless { it == "No description found" }.orEmpty(),
                    language = detectedLang,
                    cues = cues,
                ),
            )
        }

        // 3. Fallback: Optional remote Node.js `/api/youtube/captions` backend if configured
        val endpointBase = deriveCaptionsEndpointUrl(configuredBackendUrl)
        if (endpointBase != null) {
            val remoteResult = fetchFromRemoteBackend(
                endpointBase = endpointBase,
                cleanId = cleanId,
                languageCode = languageCode,
                authToken = authToken,
            )
            if (remoteResult is YouTubeCaptionExtractionResult.Success) {
                return@withContext remoteResult
            }
        }

        // 3. Report status from native extractor
        val warning = nativeDetails?.warning ?: nativeAttempt.exceptionOrNull()?.localizedMessage
        YouTubeCaptionExtractionResult.Unavailable(
            warning ?: "No public captions or auto-generated subtitles were found for this YouTube video.",
        )
    }

    private fun fetchFromRemoteBackend(
        endpointBase: String,
        cleanId: String,
        languageCode: String,
        authToken: String?,
    ): YouTubeCaptionExtractionResult {
        val encodedLang = URLEncoder.encode(languageCode.ifBlank { "en" }, "UTF-8")
        val fullUrl = "$endpointBase?videoId=$cleanId&lang=$encodedLang"
        val reqBuilder = Request.Builder()
            .url(fullUrl)
            .get()
            .header("Accept", "application/json")
        authToken?.trim()?.takeIf { it.isNotEmpty() }?.let { token ->
            reqBuilder.header("Authorization", "Bearer $token")
        }

        return runCatching {
            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                when (response.code) {
                    in 200..299 -> {
                        val parsed = parseResponseJson(bodyStr, cleanId, languageCode)
                        if (parsed != null && parsed.cues.isNotEmpty()) {
                            YouTubeCaptionExtractionResult.Success(parsed)
                        } else {
                            YouTubeCaptionExtractionResult.Unavailable(
                                "No synced captions were returned for this YouTube video.",
                            )
                        }
                    }
                    401, 403 -> YouTubeCaptionExtractionResult.Error(
                        "Backend authentication failed (HTTP ${response.code}). Check your Bearer token in Settings.",
                    )
                    404 -> YouTubeCaptionExtractionResult.Unavailable(
                        extractErrorField(bodyStr) ?: "No public captions or subtitles are available for this YouTube video.",
                    )
                    429 -> YouTubeCaptionExtractionResult.Error(
                        "Caption extractor rate limit reached. Please try again in a moment.",
                    )
                    else -> YouTubeCaptionExtractionResult.Error(
                        extractErrorField(bodyStr) ?: "Caption extraction failed (HTTP ${response.code}).",
                    )
                }
            }
        }.getOrElse { err ->
            YouTubeCaptionExtractionResult.Error(
                "Could not reach Podcasty caption backend (${err.localizedMessage ?: "network error"}).",
            )
        }
    }

    /**
     * Converts `Subtitle(start, dur, text)` records from `com.podcasty.youtubeextractor.core`
     * into strictly sorted, non-overlapping millisecond `TranscriptCue` items for `CaptionSyncEngine`.
     */
    internal fun subtitlesToTranscriptCues(subtitles: List<Subtitle>): List<TranscriptCue> {
        if (subtitles.isEmpty()) return emptyList()
        data class MutableCue(val startMs: Long, var endMs: Long, val text: String)

        val raw = subtitles.mapNotNull { sub ->
            val cleanText = sub.text.trim()
            if (cleanText.isEmpty()) return@mapNotNull null
            val startSec = sub.startSeconds
            if (!startSec.isFinite() || startSec < 0.0) return@mapNotNull null
            val durSec = sub.durationSeconds
            val startMs = (startSec * 1000.0).roundToLong().coerceAtLeast(0L)
            val rawEndMs = if (durSec.isFinite() && durSec > 0.0) {
                ((startSec + durSec) * 1000.0).roundToLong()
            } else {
                startMs + 2_500L
            }
            MutableCue(
                startMs = startMs,
                endMs = maxOf(startMs + 100L, rawEndMs),
                text = cleanText,
            )
        }.sortedBy { it.startMs }

        for (i in 0 until raw.size - 1) {
            val current = raw[i]
            val next = raw[i + 1]
            if (current.endMs > next.startMs && next.startMs > current.startMs) {
                current.endMs = next.startMs
            }
            if (current.endMs < current.startMs) {
                current.endMs = current.startMs
            }
        }

        return raw.mapIndexed { idx, item ->
            TranscriptCue(
                id = idx.toLong(),
                startMs = item.startMs,
                endMs = item.endMs,
                text = item.text,
                speaker = null,
            )
        }
    }

    internal fun parseResponseJson(
        body: String,
        fallbackVideoId: String,
        fallbackLanguage: String,
    ): ExtractedYouTubeTranscript? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val rawCues = root["cues"]?.jsonArray ?: return null
        val cues = rawCues.mapIndexedNotNull { idx, elem ->
            val obj = elem as? JsonObject ?: return@mapIndexedNotNull null
            val text = obj["text"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (text.isBlank()) return@mapIndexedNotNull null
            val startMs = obj["startMs"]?.jsonPrimitive?.longOrNull ?: return@mapIndexedNotNull null
            val endMs = (obj["endMs"]?.jsonPrimitive?.longOrNull ?: (startMs + 2_000L)).coerceAtLeast(startMs)
            TranscriptCue(
                id = idx.toLong(),
                startMs = startMs.coerceAtLeast(0L),
                endMs = endMs.coerceAtLeast(startMs.coerceAtLeast(0L)),
                text = text,
                speaker = null,
            )
        }.sortedBy { it.startMs }
        if (cues.isEmpty()) return null
        return ExtractedYouTubeTranscript(
            videoId = root["videoId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: fallbackVideoId,
            title = root["title"]?.jsonPrimitive?.content?.trim().orEmpty(),
            description = root["description"]?.jsonPrimitive?.content?.trim().orEmpty(),
            language = root["language"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: fallbackLanguage.substringBefore('-').lowercase().ifBlank { "en" },
            cues = cues,
        )
    }

    private fun extractErrorField(body: String): String? = runCatching {
        (json.parseToJsonElement(body) as? JsonObject)?.get("error")?.jsonPrimitive?.content
    }.getOrNull()

    companion object {
        private val VideoIdRegex = Regex("^[A-Za-z0-9_-]{11}$")

        /**
         * Derives the REST `/api/youtube/captions` URL from a configured WebSocket or HTTP(S) backend URL:
         * - `wss://captions.example.com/ws/captions` -> `https://captions.example.com/api/youtube/captions`
         * - `ws://10.0.2.2:8443/ws/captions` -> `http://10.0.2.2:8443/api/youtube/captions`
         * - `https://captions.example.com` -> `https://captions.example.com/api/youtube/captions`
         * Rejects plain `ws://` or `http://` for non-local remote hosts.
         */
        fun deriveCaptionsEndpointUrl(configuredUrl: String): String? {
            val trimmed = configuredUrl.trim()
            if (trimmed.isEmpty()) return null
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
            val rawScheme = uri.scheme?.lowercase().orEmpty()
            val host = uri.host?.lowercase().orEmpty()
            if (host.isBlank()) return null
            val isLocalDev = host == "10.0.2.2" || host == "localhost" || host == "127.0.0.1"
            val httpScheme = when (rawScheme) {
                "wss", "https" -> "https"
                "ws", "http" -> if (isLocalDev) "http" else return null
                else -> return null
            }
            val portSuffix = if (uri.port > 0) ":${uri.port}" else ""
            return "$httpScheme://$host$portSuffix/api/youtube/captions"
        }
    }
}
