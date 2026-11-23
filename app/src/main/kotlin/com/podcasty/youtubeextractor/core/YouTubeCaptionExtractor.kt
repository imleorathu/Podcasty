/*
 * Podcasty — Native Android YouTube Caption Extractor Core
 * Ported from "caption extractor android" (devhims/youtube-caption-extractor v1.10.2 port, MIT License).
 */
package com.podcasty.youtubeextractor.core

import android.os.Build
import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Native Kotlin port of the public v1.10.2 extraction flow from "caption extractor android".
 *
 * Extracts YouTube video title, description, and timestamped JSON3 caption tracks directly
 * on Android so uploaded/pasted YouTube links automatically populate the synchronized lyrics page.
 */
class YouTubeCaptionExtractor(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) {
    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private data class ClientProfile(
        val name: String,
        val clientName: String,
        val clientVersion: String,
        val clientNameHeader: String,
        val userAgent: String,
        val extraClientContext: Map<String, Any>,
    )

    internal data class CaptionTrack(
        val baseUrl: String,
        val vssId: String?,
        val languageCode: String?,
        val kind: String?,
    )

    private data class PlayerData(
        val json: JsonObject,
        val tracks: List<CaptionTrack>,
    )

    private val endpoint = "https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val clients = listOf(
        ClientProfile(
            name = "ios",
            clientName = "IOS",
            clientVersion = "20.10.4",
            clientNameHeader = "5",
            userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            extraClientContext = mapOf(
                "deviceMake" to "Apple",
                "deviceModel" to "iPhone16,2",
                "platform" to "MOBILE",
                "osName" to "iOS",
                "osVersion" to "18.3.2.22D82",
            ),
        ),
        ClientProfile(
            name = "android_vr",
            clientName = "ANDROID_VR",
            clientVersion = "1.62.20",
            clientNameHeader = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.62.20 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            extraClientContext = mapOf(
                "deviceMake" to "Oculus",
                "deviceModel" to "Quest 3",
                "platform" to "MOBILE",
                "osName" to "Android",
                "osVersion" to "12L",
                "androidSdkVersion" to 32,
            ),
        ),
        ClientProfile(
            name = "mweb",
            clientName = "MWEB",
            clientVersion = "2.20251209.01.00",
            clientNameHeader = "2",
            userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
            extraClientContext = mapOf(
                "platform" to "MOBILE",
                "osName" to "iOS",
                "osVersion" to "17.5.1",
            ),
        ),
    )

    suspend fun getVideoDetails(videoUrlOrId: String, lang: String = "en"): VideoDetails =
        withContext(Dispatchers.IO) {
            getVideoDetailsBlocking(videoUrlOrId, lang)
        }

    suspend fun getSubtitles(videoUrlOrId: String, lang: String = "en"): List<Subtitle> =
        withContext(Dispatchers.IO) {
            val videoId = YouTubeUrlParser.extractVideoId(videoUrlOrId)
            val player = fetchPlayer(videoId)
            extractSubtitles(player.tracks, lang)
        }

    private fun getVideoDetailsBlocking(videoUrlOrId: String, lang: String): VideoDetails {
        val videoId = YouTubeUrlParser.extractVideoId(videoUrlOrId)
        val player = fetchPlayer(videoId)
        val videoDetails = player.json["videoDetails"] as? JsonObject
        val title = videoDetails?.stringOrNull("title")?.takeIf { it.isNotBlank() } ?: "No title found"
        val description = videoDetails?.stringOrNull("shortDescription")?.takeIf { it.isNotBlank() }
            ?: "No description found"

        val selected = selectCaptionTrack(player.tracks, lang)
        return try {
            val subtitles = if (selected == null) emptyList() else fetchCaptionTrack(selected)
            VideoDetails(
                videoId = videoId,
                title = title,
                description = description,
                subtitles = subtitles,
                requestedLanguage = lang,
                selectedLanguage = selected?.languageCode,
                isAutoGenerated = selected?.kind == "asr" || selected?.vssId?.startsWith("a.") == true,
                warning = if (selected == null) "No caption track was found for this video." else null,
            )
        } catch (error: Exception) {
            VideoDetails(
                videoId = videoId,
                title = title,
                description = description,
                subtitles = emptyList(),
                requestedLanguage = lang,
                selectedLanguage = selected?.languageCode,
                isAutoGenerated = selected?.kind == "asr" || selected?.vssId?.startsWith("a.") == true,
                warning = "Video details were found, but captions could not be fetched: ${error.message ?: "Unknown error"}",
            )
        }
    }

    private fun fetchPlayer(videoId: String): PlayerData {
        var firstPlayable: JsonObject? = null
        var firstPlayableTracks: List<CaptionTrack> = emptyList()
        val failures = mutableListOf<String>()

        for (client in clients) {
            try {
                val json = fetchPlayerWithClient(videoId, client)
                val statusObject = json["playabilityStatus"] as? JsonObject
                val status = statusObject?.stringOrNull("status")
                if (!status.isNullOrBlank() && status != "OK") {
                    val reason = statusObject.stringOrNull("reason").orEmpty()
                    failures += "${client.name}: $status${if (reason.isNotBlank()) " - $reason" else ""}"
                    continue
                }

                val tracks = readCaptionTracks(json)
                if (firstPlayable == null) {
                    firstPlayable = json
                    firstPlayableTracks = tracks
                }
                if (tracks.isNotEmpty()) return PlayerData(json, tracks)
                failures += "${client.name}: OK but no caption tracks"
            } catch (error: Exception) {
                failures += "${client.name}: ${error.message ?: error.javaClass.simpleName}"
            }
        }

        if (firstPlayable != null) return PlayerData(firstPlayable, firstPlayableTracks)
        throw CaptionExtractionException(
            "Video not playable on any client. Attempts:\n${failures.joinToString("\n")}",
        )
    }

    private fun fetchPlayerWithClient(videoId: String, client: ClientProfile): JsonObject {
        val body = buildJsonObject {
            put(
                "context",
                buildJsonObject {
                    put(
                        "client",
                        buildJsonObject {
                            put("clientName", client.clientName)
                            put("clientVersion", client.clientVersion)
                            put("hl", "en")
                            put("gl", "US")
                            for ((k, v) in client.extraClientContext) {
                                when (v) {
                                    is String -> put(k, v)
                                    is Int -> put(k, v)
                                    is Boolean -> put(k, v)
                                }
                            }
                        },
                    )
                    put("user", buildJsonObject { put("lockedSafetyMode", false) })
                    put("request", buildJsonObject { put("useSsl", true) })
                },
            )
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

        val request = Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody(jsonMediaType))
            .header("Accept", "*/*")
            .header("User-Agent", client.userAgent)
            .header("X-YouTube-Client-Name", client.clientNameHeader)
            .header("X-YouTube-Client-Version", client.clientVersion)
            .header("Origin", "https://www.youtube.com")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Player request failed (${client.name}): HTTP ${response.code}")
            }
            val responseText = response.body?.string().orEmpty()
            if (responseText.isBlank()) throw IOException("Empty player response (${client.name})")
            return try {
                jsonParser.parseToJsonElement(responseText).jsonObject
            } catch (error: Exception) {
                throw IOException("Invalid player JSON (${client.name})", error)
            }
        }
    }

    internal fun readCaptionTracks(player: JsonObject): List<CaptionTrack> {
        val tracks = (player["captions"] as? JsonObject)
            ?.get("playerCaptionsTracklistRenderer")
            ?.let { it as? JsonObject }
            ?.get("captionTracks")
            ?.let { it as? JsonArray } ?: return emptyList()

        return buildList {
            for (element in tracks) {
                val item = element as? JsonObject ?: continue
                val baseUrl = item.stringOrNull("baseUrl").orEmpty()
                if (baseUrl.isBlank()) continue
                add(
                    CaptionTrack(
                        baseUrl = baseUrl,
                        vssId = item.stringOrNull("vssId")?.takeIf(String::isNotBlank),
                        languageCode = item.stringOrNull("languageCode")?.takeIf(String::isNotBlank),
                        kind = item.stringOrNull("kind")?.takeIf(String::isNotBlank),
                    ),
                )
            }
        }
    }

    internal fun selectCaptionTrack(tracks: List<CaptionTrack>, lang: String): CaptionTrack? {
        if (tracks.isEmpty()) return null
        val cleaned = lang.trim().ifBlank { "en" }
        val primary = cleaned.substringBefore('-').substringBefore('_').lowercase()
        val candidates = listOf(cleaned, primary).distinct()

        for (candidate in candidates) {
            tracks.firstOrNull { it.vssId.equals(".$candidate", ignoreCase = true) }?.let { return it }
            tracks.firstOrNull { it.vssId.equals("a.$candidate", ignoreCase = true) }?.let { return it }
            tracks.firstOrNull { it.languageCode.equals(candidate, ignoreCase = true) }?.let { return it }
            tracks.firstOrNull {
                it.languageCode?.substringBefore('-').equals(candidate, ignoreCase = true)
            }?.let { return it }
            tracks.firstOrNull { it.vssId?.contains(".$candidate", ignoreCase = true) == true }?.let { return it }
        }
        return tracks.first()
    }

    private fun extractSubtitles(tracks: List<CaptionTrack>, lang: String): List<Subtitle> {
        val track = selectCaptionTrack(tracks, lang) ?: return emptyList()
        return fetchCaptionTrack(track)
    }

    private fun fetchCaptionTrack(track: CaptionTrack): List<Subtitle> {
        val baseUrl = track.baseUrl.toHttpUrlOrNull()
            ?: throw CaptionExtractionException("The caption track URL is invalid.")
        val captionUrl = baseUrl.newBuilder()
            .removeAllQueryParameters("fmt")
            .addQueryParameter("fmt", "json3")
            .build()

        val request = Request.Builder()
            .url(captionUrl)
            .get()
            .header("User-Agent", clients.first().userAgent)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw CaptionExtractionException("Caption fetch failed: HTTP ${response.code}")
            }
            val responseText = response.body?.string().orEmpty()
            return parseJson3CaptionResponse(responseText)
        }
    }

    internal fun parseJson3CaptionResponse(responseText: String): List<Subtitle> {
        if (responseText.isBlank()) return emptyList()
        val json = try {
            jsonParser.parseToJsonElement(responseText).jsonObject
        } catch (error: Exception) {
            throw CaptionExtractionException("Caption response was not valid JSON.", error)
        }
        val events = json["events"] as? JsonArray ?: return emptyList()
        val subtitles = ArrayList<Subtitle>(events.size)

        for (element in events) {
            val event = element as? JsonObject ?: continue
            if ((event["aAppend"] as? JsonPrimitive)?.intOrNull == 1) continue
            val segments = event["segs"] as? JsonArray ?: continue
            val rawText = buildString {
                for (segElem in segments) {
                    val segment = segElem as? JsonObject ?: continue
                    append(segment.stringOrNull("utf8").orEmpty())
                }
            }
            val cleanText = decodeAndStripHtml(rawText)
            if (cleanText.isBlank()) continue

            val startMs = (event["tStartMs"] as? JsonPrimitive)?.longOrNull ?: 0L
            val durationMs = (event["dDurationMs"] as? JsonPrimitive)?.longOrNull ?: 0L
            subtitles += Subtitle(
                start = (startMs / 1000.0).toString(),
                dur = (durationMs / 1000.0).toString(),
                text = cleanText,
            )
        }
        return subtitles
    }

    @Suppress("DEPRECATION")
    internal fun decodeAndStripHtml(raw: String): String {
        val androidDecoded = runCatching {
            val spanned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY)
            } else {
                Html.fromHtml(raw)
            }
            spanned?.toString()
        }.getOrNull()

        val text = androidDecoded ?: raw
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")

        return text
            .replace('\u00A0', ' ')
            .replace(Regex("\\r?\\n+"), " ")
            .replace(Regex("[ \\t]+"), " ")
            .trim()
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.content
}
