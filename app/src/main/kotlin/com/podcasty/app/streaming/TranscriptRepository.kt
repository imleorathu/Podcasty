/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.streaming

import com.podcasty.app.transcript.TranscriptDocument
import com.podcasty.app.transcript.TranscriptSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class ResolvedTranscriptResult(
    val priority: CaptionSourcePriority,
    val document: TranscriptDocument? = null,
    val statusMessage: String,
)

/**
 * Resolves transcripts strictly in compliance with the 3-tier priority:
 *
 * - Priority A: Authorized YouTube Data API v3 (`captions.list` + `captions.download`)
 *   for videos owned/editable by the authenticated OAuth 2.0 user.
 * - Priority B: Publisher-provided WebVTT (.vtt), SRT (.srt), LRC, or timestamped JSON
 *   fetched purely in memory over HTTPS (never storing podcast audio/video).
 * - Priority C: Native YouTube captions enabled inside the embedded YouTube IFrame player
 *   (honestly reports that native captions remain inside the YouTube player).
 */
class TranscriptRepository(
    private val youTubeDataApiClient: YouTubeDataApiClient,
) {
    /**
     * Resolves captions for a YouTube podcast video following Priority A -> B -> C.
     */
    suspend fun resolveForYouTubeVideo(
        videoId: String,
        preferredLanguage: String = "en",
        publisherTranscriptUrl: String? = null,
    ): ResolvedTranscriptResult {
        // Priority A: Authorized YouTube Data API v3 captions (when OAuth is active)
        if (youTubeDataApiClient.hasActiveOAuthToken()) {
            val apiResult = youTubeDataApiClient.downloadAuthorizedCaption(videoId, preferredLanguage)
            if (apiResult.isSuccess) {
                val (track, rawBody) = apiResult.getOrThrow()
                val parsed = CaptionParser.parseTranscript(
                    episodeId = "yt_$videoId",
                    rawContent = rawBody,
                    fileNameOrUrl = "caption.vtt",
                    language = track.language,
                    source = TranscriptSource.AuthorizedImport,
                )
                if (parsed.isSuccess) {
                    return ResolvedTranscriptResult(
                        priority = CaptionSourcePriority.AuthorizedYouTubeApi,
                        document = parsed.getOrThrow(),
                        statusMessage = "Synced authorized YouTube transcript (${track.language.uppercase()}).",
                    )
                }
            }
        }

        // Priority B: Publisher-provided VTT/SRT transcript URL (fetched in memory only)
        if (!publisherTranscriptUrl.isNullOrBlank()) {
            val pubResult = fetchPublisherTranscriptInMemory(
                episodeId = "yt_$videoId",
                transcriptUrl = publisherTranscriptUrl,
                preferredLanguage = preferredLanguage,
            )
            if (pubResult.isSuccess) {
                return ResolvedTranscriptResult(
                    priority = CaptionSourcePriority.PublisherTranscript,
                    document = pubResult.getOrThrow(),
                    statusMessage = "Synced publisher transcript (in-memory).",
                )
            }
        }

        // Priority C: Native YouTube captions inside the embedded YouTube player
        return ResolvedTranscriptResult(
            priority = CaptionSourcePriority.NativeYouTubeCaptions,
            document = null,
            statusMessage = "Native YouTube captions are enabled inside the embedded YouTube player. Load a publisher VTT/SRT transcript or sign in with YouTube OAuth for videos you own to sync text below.",
        )
    }

    /**
     * Fetches a publisher-provided VTT / SRT / LRC / JSON transcript purely in memory over HTTPS.
     * Never writes podcast audio, video, or media files to disk.
     */
    suspend fun fetchPublisherTranscriptInMemory(
        episodeId: String,
        transcriptUrl: String,
        preferredLanguage: String = "en",
    ): Result<TranscriptDocument> = withContext(Dispatchers.IO) {
        runCatching {
            val uri = URI(transcriptUrl.trim())
            if (!uri.scheme.equals("https", ignoreCase = true)) {
                throw IllegalArgumentException("Transcript URL must use HTTPS.")
            }
            val raw = downloadTextInMemory(uri.toString(), MaxTranscriptBytes)
            CaptionParser.parseTranscript(
                episodeId = episodeId,
                rawContent = raw,
                fileNameOrUrl = uri.path,
                language = preferredLanguage,
                source = TranscriptSource.RssCreator,
            ).getOrThrow()
        }
    }

    private fun downloadTextInMemory(urlString: String, maxBytes: Int): String {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Podcasty/1.0 (Android)")
            setRequestProperty("Accept", "text/vtt, application/x-subrip, text/plain, application/json, */*")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Transcript unavailable (HTTP $code).")
            }
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            conn.inputStream.use { input ->
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    total += read
                    if (total > maxBytes) {
                        throw IllegalStateException("Transcript exceeds safe in-memory size limit.")
                    }
                    out.write(buf, 0, read)
                }
            }
            out.toString(Charsets.UTF_8.name())
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val MaxTranscriptBytes = 5 * 1024 * 1024 // 5 MB in-memory cap
    }
}
