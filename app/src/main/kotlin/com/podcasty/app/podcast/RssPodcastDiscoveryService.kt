/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.podcast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

sealed interface OfficialRssDiscoveryResult {
    data class EpisodeAudioMatched(
        val podcastTitle: String,
        val feedUrl: String,
        val matchedEpisodeTitle: String,
        val audioUrl: String,
        val artworkUrl: String?,
        val confidence: Float,
    ) : OfficialRssDiscoveryResult

    data class ShowFeedFound(
        val podcastTitle: String,
        val feedUrl: String,
        val artworkUrl: String?,
        val episodeCount: Int,
    ) : OfficialRssDiscoveryResult

    data object NotFound : OfficialRssDiscoveryResult
}

/**
 * Discovers a podcast creator's official public RSS audio feed via the free public
 * Apple Podcasts / iTunes Search API (`https://itunes.apple.com/search?media=podcast`).
 *
 * When a user pastes a YouTube podcast link, this allows Podcasty to automatically find
 * the creator's official public RSS audio enclosure so the episode plays 100% natively in
 * Media3 ExoPlayer (with background playback, lock-screen controls, and synced transcripts)
 * without using a YouTube IFrame or unauthorized stream extraction.
 */
class RssPodcastDiscoveryService(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun discoverOfficialRssForYouTubeVideo(
        videoTitle: String,
        channelName: String,
    ): OfficialRssDiscoveryResult = withContext(Dispatchers.IO) {
        val cleanChannel = channelName.trim()
        val cleanTitle = videoTitle.trim()
        if (cleanChannel.isBlank() && cleanTitle.isBlank()) {
            return@withContext OfficialRssDiscoveryResult.NotFound
        }

        val candidates = searchItunesPodcasts(cleanChannel.ifBlank { cleanTitle })
            .ifEmpty { if (cleanTitle.isNotBlank() && cleanTitle != cleanChannel) searchItunesPodcasts(cleanTitle) else emptyList() }
        if (candidates.isEmpty()) return@withContext OfficialRssDiscoveryResult.NotFound

        val rankedShows = candidates
            .filter { it.feedUrl.startsWith("https://", ignoreCase = true) || it.feedUrl.startsWith("http://", ignoreCase = true) }
            .sortedByDescending { show ->
                val channelSim = maxOf(
                    tokenSimilarity(cleanChannel, show.collectionName),
                    tokenSimilarity(cleanChannel, show.artistName),
                )
                val titleSim = tokenSimilarity(cleanTitle, show.collectionName)
                channelSim * 1.2f + titleSim * 0.5f
            }
            .take(2)

        var fallbackShow: OfficialRssDiscoveryResult.ShowFeedFound? = null

        for (show in rankedShows) {
            val channelMatchScore = maxOf(
                tokenSimilarity(cleanChannel, show.collectionName),
                tokenSimilarity(cleanChannel, show.artistName),
            )
            if (channelMatchScore < 0.35f) continue

            val inspected = RssAndAtomFeedParser.fetchAndInspect(show.feedUrl).getOrNull()
                as? InspectedRemoteSource.RssOrAtom ?: continue
            val feed = inspected.feed
            if (feed.episodes.isEmpty()) continue

            if (fallbackShow == null) {
                fallbackShow = OfficialRssDiscoveryResult.ShowFeedFound(
                    podcastTitle = feed.title,
                    feedUrl = feed.feedUrl,
                    artworkUrl = feed.artworkUrl ?: show.artworkUrl,
                    episodeCount = feed.episodes.size,
                )
            }

            val bestEp = feed.episodes
                .map { ep -> ep to tokenSimilarity(cleanTitle, ep.title) }
                .maxByOrNull { it.second }

            if (bestEp != null && bestEp.second >= 0.45f) {
                return@withContext OfficialRssDiscoveryResult.EpisodeAudioMatched(
                    podcastTitle = feed.title,
                    feedUrl = feed.feedUrl,
                    matchedEpisodeTitle = bestEp.first.title,
                    audioUrl = bestEp.first.audioUrl,
                    artworkUrl = bestEp.first.artworkUrl ?: feed.artworkUrl ?: show.artworkUrl,
                    confidence = bestEp.second,
                )
            }
        }

        fallbackShow ?: OfficialRssDiscoveryResult.NotFound
    }

    internal data class ItunesPodcastCandidate(
        val collectionName: String,
        val artistName: String,
        val feedUrl: String,
        val artworkUrl: String?,
    )

    private fun searchItunesPodcasts(query: String): List<ItunesPodcastCandidate> = runCatching {
        val encoded = URLEncoder.encode(query.take(80), "UTF-8")
        val url = "https://itunes.apple.com/search?media=podcast&entity=podcast&limit=5&term=$encoded"
        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", "Podcasty/1.0 (Android)")
            .build()
        httpClient.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return@runCatching emptyList()
            parseItunesSearchJson(res.body?.string().orEmpty())
        }
    }.getOrDefault(emptyList())

    internal fun parseItunesSearchJson(body: String): List<ItunesPodcastCandidate> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val results = root["results"]?.jsonArray ?: return emptyList()
        return results.mapNotNull { elem ->
            val obj = elem.jsonObject
            val feedUrl = obj["feedUrl"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (feedUrl.isBlank()) return@mapNotNull null
            ItunesPodcastCandidate(
                collectionName = obj["collectionName"]?.jsonPrimitive?.content?.trim().orEmpty(),
                artistName = obj["artistName"]?.jsonPrimitive?.content?.trim().orEmpty(),
                feedUrl = feedUrl,
                artworkUrl = obj["artworkUrl600"]?.jsonPrimitive?.content?.trim()
                    ?: obj["artworkUrl100"]?.jsonPrimitive?.content?.trim(),
            )
        }
    }

    companion object {
        private val stopWords = setOf(
            "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "at", "by", "from",
            "podcast", "episode", "ep", "official", "full", "video", "youtube", "show", "clips", "channel",
        )

        internal fun tokenize(text: String): Set<String> =
            text.lowercase()
                .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length >= 2 && it !in stopWords }
                .toSet()

        internal fun tokenSimilarity(a: String, b: String): Float {
            val ta = tokenize(a)
            val tb = tokenize(b)
            if (ta.isEmpty() || tb.isEmpty()) return 0f
            val intersection = ta.intersect(tb).size
            if (intersection == 0) return 0f
            val overlap = intersection.toFloat() / minOf(ta.size, tb.size).toFloat()
            val dice = (2f * intersection.toFloat()) / (ta.size + tb.size).toFloat()
            return (overlap * 0.6f + dice * 0.4f).coerceIn(0f, 1f)
        }
    }
}

