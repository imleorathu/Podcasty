/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.podcast

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class YouTubeReferenceMetadata(
    val videoId: String,
    val canonicalUrl: String,
    val title: String,
    val channelName: String,
    val thumbnailUrl: String? = null,
)

data class RssFeedImportResult(
    val podcastTitle: String,
    val hostName: String,
    val description: String,
    val categoryName: String,
    val artworkUrl: String?,
    val episodes: List<RssEpisodeMetadata>,
)

data class RssEpisodeMetadata(
    val guid: String,
    val title: String,
    val summary: String,
    val audioUrl: String,
    val durationMs: Long,
    val publishedAt: Long,
    val transcriptUrl: String? = null,
    val transcriptMimeType: String? = null,
    val transcriptLanguage: String = "en",
)

/**
 * Policy-compliant YouTube episode reference & RSS podcast importer.
 *
 * IMPORTANT:
 * - Never extracts YouTube audio streams or uses unofficial caption scraping.
 * - Uses only YouTube's public oEmbed endpoint for display title/channel metadata, and launches
 *   YouTube audiovisual content in the official YouTube app / browser via Intent.ACTION_VIEW.
 * - Supports linking creator-provided RSS podcast feeds (including <podcast:transcript> tags)
 *   and user-imported authorized SRT/VTT/LRC transcript files.
 */
object YouTubeAndRssImporter {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val youtubeIdPatterns = listOf(
        Regex("(?:youtube\\.com/watch\\?.*v=|youtu\\.be/|youtube\\.com/live/|youtube\\.com/shorts/|music\\.youtube\\.com/watch\\?.*v=)([A-Za-z0-9_-]{11})"),
    )

    fun extractYouTubeVideoId(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        for (regex in youtubeIdPatterns) {
            val match = regex.find(trimmed)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    fun isYouTubeUrl(rawUrl: String): Boolean = extractYouTubeVideoId(rawUrl) != null

    fun canonicalYouTubeUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"

    fun openInOfficialYouTube(context: Context, youtubeUrl: String): Boolean {
        val uri = runCatching { Uri.parse(youtubeUrl) }.getOrNull() ?: return false
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /**
     * Resolves display metadata via YouTube's public oEmbed endpoint.
     * Falls back cleanly to a local placeholder title if offline.
     */
    suspend fun resolveYouTubeReference(rawUrl: String): Result<YouTubeReferenceMetadata> = withContext(Dispatchers.IO) {
        val videoId = extractYouTubeVideoId(rawUrl)
            ?: return@withContext Result.failure(IllegalArgumentException("Enter a valid YouTube podcast URL."))
        val canonical = canonicalYouTubeUrl(videoId)
        val oEmbed = runCatching {
            val encoded = URLEncoder.encode(canonical, "UTF-8")
            val body = httpGetText("https://www.youtube.com/oembed?url=$encoded&format=json")
            val obj = json.parseToJsonElement(body).jsonObject
            val title = obj["title"]?.jsonPrimitive?.content?.trim().orEmpty()
            val author = obj["author_name"]?.jsonPrimitive?.content?.trim().orEmpty()
            val thumb = obj["thumbnail_url"]?.jsonPrimitive?.content?.trim()
            YouTubeReferenceMetadata(
                videoId = videoId,
                canonicalUrl = canonical,
                title = title.ifBlank { "YouTube Podcast ($videoId)" },
                channelName = author.ifBlank { "YouTube Creator" },
                thumbnailUrl = thumb,
            )
        }.getOrElse {
            YouTubeReferenceMetadata(
                videoId = videoId,
                canonicalUrl = canonical,
                title = "YouTube Podcast ($videoId)",
                channelName = "YouTube Creator",
            )
        }
        Result.success(oEmbed)
    }

    /**
     * Fetches and parses a standard RSS 2.0 podcast feed (including Podcasting 2.0
     * `<podcast:transcript>` tags for creator-provided transcripts).
     */
    suspend fun fetchRssFeed(feedUrl: String): Result<RssFeedImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val xml = httpGetText(feedUrl.trim())
            parseRssFeedXml(xml)
        }
    }

    suspend fun fetchTranscriptText(url: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching { httpGetText(url.trim()) }
    }

    fun parseRssFeedXml(xml: String): RssFeedImportResult {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))

        var podcastTitle = ""
        var hostName = ""
        var description = ""
        var categoryName = "Podcasts"
        var artworkUrl: String? = null

        var inItem = false
        var itemGuid = ""
        var itemTitle = ""
        var itemSummary = ""
        var itemAudioUrl = ""
        var itemDurationMs = 0L
        var itemTranscriptUrl: String? = null
        var itemTranscriptMime: String? = null
        var itemTranscriptLang = "en"
        val episodes = mutableListOf<RssEpisodeMetadata>()

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.orEmpty().lowercase()
                    when {
                        tag == "item" -> {
                            inItem = true
                            itemGuid = ""
                            itemTitle = ""
                            itemSummary = ""
                            itemAudioUrl = ""
                            itemDurationMs = 0L
                            itemTranscriptUrl = null
                            itemTranscriptMime = null
                            itemTranscriptLang = "en"
                        }
                        inItem && tag == "title" -> itemTitle = parser.nextText().trim()
                        inItem && tag == "guid" -> itemGuid = parser.nextText().trim()
                        inItem && (tag == "description" || tag == "summary") && itemSummary.isEmpty() -> {
                            itemSummary = parser.nextText().replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
                        }
                        inItem && tag == "enclosure" -> {
                            val type = parser.getAttributeValue(null, "type").orEmpty()
                            val url = parser.getAttributeValue(null, "url").orEmpty()
                            if (url.isNotBlank() && (type.isBlank() || type.startsWith("audio/") || url.endsWith(".mp3", true) || url.endsWith(".m4a", true))) {
                                itemAudioUrl = url
                            }
                        }
                        inItem && tag == "duration" -> {
                            itemDurationMs = parseItunesDurationMs(parser.nextText().trim())
                        }
                        inItem && tag == "transcript" -> {
                            val tUrl = parser.getAttributeValue(null, "url").orEmpty()
                            val tType = parser.getAttributeValue(null, "type").orEmpty()
                            val tLang = parser.getAttributeValue(null, "language")?.takeIf { it.isNotBlank() } ?: "en"
                            if (tUrl.isNotBlank() && itemTranscriptUrl == null) {
                                itemTranscriptUrl = tUrl
                                itemTranscriptMime = tType
                                itemTranscriptLang = tLang
                            }
                        }
                        !inItem && tag == "title" && podcastTitle.isEmpty() -> podcastTitle = parser.nextText().trim()
                        !inItem && tag == "author" && hostName.isEmpty() -> hostName = parser.nextText().trim()
                        !inItem && tag == "description" && description.isEmpty() -> {
                            description = parser.nextText().replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
                        }
                        !inItem && tag == "category" -> {
                            val catAttr = parser.getAttributeValue(null, "text")
                            if (!catAttr.isNullOrBlank()) categoryName = catAttr.trim()
                        }
                        !inItem && tag == "image" -> {
                            val href = parser.getAttributeValue(null, "href")
                            if (!href.isNullOrBlank()) artworkUrl = href.trim()
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("item", ignoreCase = true) && inItem) {
                        inItem = false
                        if (itemTitle.isNotBlank() && itemAudioUrl.isNotBlank()) {
                            episodes += RssEpisodeMetadata(
                                guid = itemGuid.ifBlank { itemAudioUrl },
                                title = itemTitle,
                                summary = itemSummary,
                                audioUrl = itemAudioUrl,
                                durationMs = itemDurationMs,
                                publishedAt = System.currentTimeMillis() - episodes.size * 86_400_000L,
                                transcriptUrl = itemTranscriptUrl,
                                transcriptMimeType = itemTranscriptMime,
                                transcriptLanguage = itemTranscriptLang,
                            )
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        return RssFeedImportResult(
            podcastTitle = podcastTitle.ifBlank { "RSS Podcast" },
            hostName = hostName.ifBlank { podcastTitle.ifBlank { "Podcast Host" } },
            description = description,
            categoryName = categoryName,
            artworkUrl = artworkUrl,
            episodes = episodes.take(40),
        )
    }

    internal fun parseItunesDurationMs(raw: String): Long {
        if (raw.isBlank()) return 0L
        raw.toLongOrNull()?.let { return it * 1_000L }
        val parts = raw.split(':').mapNotNull { it.trim().toLongOrNull() }
        return when (parts.size) {
            2 -> (parts[0] * 60L + parts[1]) * 1_000L
            3 -> (parts[0] * 3600L + parts[1] * 60L + parts[2]) * 1_000L
            else -> 0L
        }
    }

    private fun httpGetText(urlString: String): String {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Podcasty/1.0 (Android)")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code while loading $urlString")
            }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
