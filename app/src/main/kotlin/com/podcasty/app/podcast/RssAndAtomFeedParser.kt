/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.podcast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

data class ParsedPodcastFeed(
    val feedUrl: String,
    val title: String,
    val description: String,
    val author: String,
    val publisher: String,
    val categoryName: String,
    val artworkUrl: String?,
    val episodes: List<ParsedPodcastEpisode>,
)

data class ParsedPodcastEpisode(
    val guid: String,
    val title: String,
    val description: String,
    val audioUrl: String,
    val durationMs: Long,
    val publishedAtMs: Long,
    val artworkUrl: String? = null,
    val transcriptUrl: String? = null,
    val transcriptMimeType: String? = null,
    val transcriptLanguage: String = "en-US",
)

sealed interface InspectedRemoteSource {
    data class RssOrAtom(val feed: ParsedPodcastFeed) : InspectedRemoteSource
    data class DirectAudio(
        val audioUrl: String,
        val title: String,
        val creator: String,
        val contentType: String?,
    ) : InspectedRemoteSource
}

/**
 * Complete RSS 2.0 and Atom 1.0 podcast feed fetcher and parser.
 *
 * - Uses XXE-hardened standard DOM (`DocumentBuilderFactory`) so it runs identically on Android and JVM tests.
 * - Extracts show & episode metadata, artwork, author, publisher, publication dates (RFC 2822 / ISO 8601),
 *   durations, audio enclosure URLs, and Podcasting 2.0 `<podcast:transcript>` links.
 * - Enforces redirect limits, private-network blocking, and maximum XML body size (6 MB) for safety.
 * - Never downloads audio enclosures to disk.
 */
object RssAndAtomFeedParser {
    private const val MaxFeedBytes = 6 * 1024 * 1024
    private const val MaxRedirects = 5

    suspend fun fetchAndInspect(
        rawUrl: String,
        allowPrivateNetworkHosts: Boolean = false,
    ): Result<InspectedRemoteSource> = withContext(Dispatchers.IO) {
        runCatching {
            val (finalUrl, contentType, bodyBytes) = fetchWithSafeRedirects(rawUrl.trim(), allowPrivateNetworkHosts)
            val mime = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            if (mime.startsWith("audio/")) {
                val fallbackTitle = deriveTitleFromAudioUrl(finalUrl)
                val host = runCatching { URI(finalUrl).host.orEmpty().removePrefix("www.") }.getOrDefault("Online Podcast")
                return@runCatching InspectedRemoteSource.DirectAudio(
                    audioUrl = finalUrl,
                    title = fallbackTitle,
                    creator = host.ifBlank { "Online Podcast" },
                    contentType = mime,
                )
            }
            val xml = bodyBytes.toString(Charsets.UTF_8)
            val parsed = parseXml(xml, feedUrl = finalUrl)
            if (parsed.episodes.isEmpty()) {
                throw IllegalArgumentException(
                    "No playable podcast audio episodes were found in this feed. Verify that the URL points to a valid RSS 2.0 or Atom podcast feed.",
                )
            }
            InspectedRemoteSource.RssOrAtom(parsed)
        }
    }

    fun parseXml(xml: String, feedUrl: String = ""): ParsedPodcastFeed {
        val cleanedXml = xml.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (cleanedXml.isBlank() || !cleanedXml.startsWith("<")) {
            throw IllegalArgumentException("Invalid RSS/Atom feed: response is not XML.")
        }
        val dbf = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val doc = try {
            dbf.newDocumentBuilder().parse(InputSource(StringReader(cleanedXml)))
        } catch (err: Exception) {
            throw IllegalArgumentException("Malformed XML feed: ${err.localizedMessage ?: "parse error"}")
        }
        val root = doc.documentElement ?: throw IllegalArgumentException("Empty XML document.")
        val rootName = localNameOf(root)
        return when (rootName) {
            "feed" -> parseAtomFeed(root, feedUrl)
            "rss", "rdf" -> parseRssFeed(root, feedUrl)
            else -> throw IllegalArgumentException("Unsupported XML root <$rootName>. Expected an RSS 2.0 or Atom feed.")
        }
    }

    private fun parseRssFeed(root: Element, feedUrl: String): ParsedPodcastFeed {
        val channel = childElements(root).firstOrNull { localNameOf(it) == "channel" } ?: root

        var showTitle = ""
        var showDescription = ""
        var showAuthor = ""
        var showPublisher = ""
        var showCategory = "Podcasts"
        var showArtwork: String? = null
        val episodes = mutableListOf<ParsedPodcastEpisode>()

        for (child in childElements(channel)) {
            when (localNameOf(child)) {
                "title" -> if (showTitle.isEmpty()) showTitle = textOf(child)
                "description", "summary" -> if (showDescription.isEmpty()) showDescription = stripHtml(textOf(child))
                "author", "creator" -> if (showAuthor.isEmpty()) showAuthor = textOf(child)
                "managingeditor", "publisher" -> if (showPublisher.isEmpty()) showPublisher = textOf(child)
                "owner" -> {
                    val ownerName = childElements(child).firstOrNull { localNameOf(it) == "name" }?.let(::textOf).orEmpty()
                    if (ownerName.isNotBlank() && showPublisher.isEmpty()) showPublisher = ownerName
                }
                "category" -> {
                    val attr = child.getAttribute("text").trim()
                    val value = attr.ifBlank { textOf(child) }
                    if (value.isNotBlank() && showCategory == "Podcasts") showCategory = value
                }
                "image" -> {
                    val href = child.getAttribute("href").trim()
                    if (href.isNotBlank()) {
                        showArtwork = href
                    } else if (showArtwork == null) {
                        val urlText = childElements(child).firstOrNull { localNameOf(it) == "url" }?.let(::textOf).orEmpty()
                        if (urlText.isNotBlank()) showArtwork = urlText
                    }
                }
                "item" -> {
                    parseRssItem(child, showArtwork, episodes.size)?.let { episodes += it }
                }
            }
        }

        val resolvedTitle = showTitle.ifBlank { "RSS Podcast" }
        val resolvedAuthor = showAuthor.ifBlank { showPublisher.ifBlank { resolvedTitle } }
        val resolvedPublisher = showPublisher.ifBlank { resolvedAuthor }

        return ParsedPodcastFeed(
            feedUrl = feedUrl,
            title = resolvedTitle,
            description = showDescription,
            author = resolvedAuthor,
            publisher = resolvedPublisher,
            categoryName = showCategory,
            artworkUrl = showArtwork,
            episodes = episodes.take(100),
        )
    }

    private fun parseRssItem(
        item: Element,
        showArtwork: String?,
        indexSoFar: Int,
    ): ParsedPodcastEpisode? {
        var guid = ""
        var title = ""
        var description = ""
        var audioUrl = ""
        var durationMs = 0L
        var publishedAtMs = 0L
        var artwork: String? = null
        var transcriptUrl: String? = null
        var transcriptMime: String? = null
        var transcriptLang = "en-US"

        for (child in childElements(item)) {
            when (localNameOf(child)) {
                "title" -> if (title.isEmpty()) title = textOf(child)
                "guid" -> if (guid.isEmpty()) guid = textOf(child)
                "description", "summary" -> if (description.isEmpty()) description = stripHtml(textOf(child))
                "enclosure" -> {
                    val url = child.getAttribute("url").trim()
                    val type = child.getAttribute("type").trim().lowercase()
                    if (url.isNotBlank() && isValidAudioEnclosure(url, type)) {
                        audioUrl = url
                    }
                }
                "content" -> {
                    val url = child.getAttribute("url").trim()
                    val type = child.getAttribute("type").trim().lowercase()
                    if (audioUrl.isEmpty() && url.isNotBlank() && isValidAudioEnclosure(url, type)) {
                        audioUrl = url
                    }
                }
                "duration" -> durationMs = parseDurationMs(textOf(child))
                "pubdate", "published" -> if (publishedAtMs == 0L) publishedAtMs = parseFeedDateMs(textOf(child))
                "image" -> {
                    val href = child.getAttribute("href").trim()
                    if (href.isNotBlank()) artwork = href
                }
                "thumbnail" -> {
                    val url = child.getAttribute("url").trim()
                    if (url.isNotBlank() && artwork == null) artwork = url
                }
                "transcript" -> {
                    val tUrl = child.getAttribute("url").trim()
                    val tType = child.getAttribute("type").trim()
                    val tLang = child.getAttribute("language").trim().ifBlank { "en-US" }
                    if (tUrl.isNotBlank() && transcriptUrl == null) {
                        transcriptUrl = tUrl
                        transcriptMime = tType
                        transcriptLang = tLang
                    }
                }
            }
        }

        if (title.isBlank() || audioUrl.isBlank()) return null
        return ParsedPodcastEpisode(
            guid = guid.ifBlank { audioUrl },
            title = title,
            description = description,
            audioUrl = audioUrl,
            durationMs = durationMs,
            publishedAtMs = if (publishedAtMs > 0L) publishedAtMs else System.currentTimeMillis() - indexSoFar * 86_400_000L,
            artworkUrl = artwork ?: showArtwork,
            transcriptUrl = transcriptUrl,
            transcriptMimeType = transcriptMime,
            transcriptLanguage = transcriptLang,
        )
    }

    private fun parseAtomFeed(root: Element, feedUrl: String): ParsedPodcastFeed {
        var showTitle = ""
        var showDescription = ""
        var showAuthor = ""
        var showCategory = "Podcasts"
        var showArtwork: String? = null
        val episodes = mutableListOf<ParsedPodcastEpisode>()

        for (child in childElements(root)) {
            when (localNameOf(child)) {
                "title" -> if (showTitle.isEmpty()) showTitle = textOf(child)
                "subtitle", "summary" -> if (showDescription.isEmpty()) showDescription = stripHtml(textOf(child))
                "author" -> {
                    val name = childElements(child).firstOrNull { localNameOf(it) == "name" }?.let(::textOf).orEmpty()
                    if (name.isNotBlank() && showAuthor.isEmpty()) showAuthor = name
                }
                "logo", "icon" -> {
                    val url = textOf(child)
                    if (url.isNotBlank() && showArtwork == null) showArtwork = url
                }
                "category" -> {
                    val term = child.getAttribute("term").trim()
                    if (term.isNotBlank() && showCategory == "Podcasts") showCategory = term
                }
                "entry" -> {
                    parseAtomEntry(child, showArtwork, episodes.size)?.let { episodes += it }
                }
            }
        }

        val resolvedTitle = showTitle.ifBlank { "Atom Podcast" }
        val resolvedAuthor = showAuthor.ifBlank { resolvedTitle }

        return ParsedPodcastFeed(
            feedUrl = feedUrl,
            title = resolvedTitle,
            description = showDescription,
            author = resolvedAuthor,
            publisher = resolvedAuthor,
            categoryName = showCategory,
            artworkUrl = showArtwork,
            episodes = episodes.take(100),
        )
    }

    private fun parseAtomEntry(
        entry: Element,
        showArtwork: String?,
        indexSoFar: Int,
    ): ParsedPodcastEpisode? {
        var id = ""
        var title = ""
        var summary = ""
        var audioUrl = ""
        var durationMs = 0L
        var publishedAtMs = 0L
        var artwork: String? = null

        for (child in childElements(entry)) {
            when (localNameOf(child)) {
                "id" -> if (id.isEmpty()) id = textOf(child)
                "title" -> if (title.isEmpty()) title = textOf(child)
                "summary", "content" -> if (summary.isEmpty()) summary = stripHtml(textOf(child))
                "published", "updated" -> if (publishedAtMs == 0L) publishedAtMs = parseFeedDateMs(textOf(child))
                "duration" -> durationMs = parseDurationMs(textOf(child))
                "link" -> {
                    val rel = child.getAttribute("rel").trim().lowercase()
                    val type = child.getAttribute("type").trim().lowercase()
                    val href = child.getAttribute("href").trim()
                    if (rel == "enclosure" && href.isNotBlank() && isValidAudioEnclosure(href, type)) {
                        audioUrl = href
                    }
                }
                "image" -> {
                    val href = child.getAttribute("href").trim()
                    if (href.isNotBlank()) artwork = href
                }
            }
        }

        if (title.isBlank() || audioUrl.isBlank()) return null
        return ParsedPodcastEpisode(
            guid = id.ifBlank { audioUrl },
            title = title,
            description = summary,
            audioUrl = audioUrl,
            durationMs = durationMs,
            publishedAtMs = if (publishedAtMs > 0L) publishedAtMs else System.currentTimeMillis() - indexSoFar * 86_400_000L,
            artworkUrl = artwork ?: showArtwork,
        )
    }

    private fun childElements(element: Element): List<Element> {
        val list = mutableListOf<Element>()
        val nodes = element.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.nodeType == Node.ELEMENT_NODE) {
                list += n as Element
            }
        }
        return list
    }

    private fun localNameOf(element: Element): String =
        (element.localName ?: element.tagName.substringAfter(':')).lowercase()

    private fun textOf(element: Element): String =
        element.textContent?.trim().orEmpty()

    internal fun parseDurationMs(raw: String): Long {
        val cleaned = raw.trim()
        if (cleaned.isBlank()) return 0L
        cleaned.toLongOrNull()?.let { return it * 1_000L }
        cleaned.toDoubleOrNull()?.let { return (it * 1_000.0).toLong() }
        val parts = cleaned.split(':').mapNotNull { it.trim().toLongOrNull() }
        return when (parts.size) {
            2 -> (parts[0] * 60L + parts[1]) * 1_000L
            3 -> (parts[0] * 3600L + parts[1] * 60L + parts[2]) * 1_000L
            else -> 0L
        }
    }

    internal fun parseFeedDateMs(raw: String): Long {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return 0L
        runCatching { return Instant.parse(trimmed).toEpochMilli() }
        runCatching { return OffsetDateTime.parse(trimmed, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli() }
        runCatching { return ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
        val patterns = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, d MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
        )
        for (pattern in patterns) {
            runCatching {
                val parsed = SimpleDateFormat(pattern, Locale.US).parse(trimmed)
                if (parsed != null) return parsed.time
            }
        }
        return 0L
    }

    internal fun deriveTitleFromAudioUrl(url: String): String {
        val segment = runCatching { URI(url).path.orEmpty().substringAfterLast('/') }.getOrDefault("")
        val base = segment.substringBeforeLast('.').replace(Regex("[_-]+"), " ").trim()
        return base.ifBlank { "Online Podcast Episode" }
    }

    private fun isValidAudioEnclosure(url: String, mimeType: String): Boolean {
        val lowerUrl = url.lowercase()
        if (!lowerUrl.startsWith("https://") && !lowerUrl.startsWith("http://")) return false
        if (mimeType.startsWith("audio/")) return true
        if (mimeType == "application/octet-stream" || mimeType.isBlank()) {
            return lowerUrl.contains(".mp3") ||
                lowerUrl.contains(".m4a") ||
                lowerUrl.contains(".aac") ||
                lowerUrl.contains(".ogg") ||
                lowerUrl.contains(".opus") ||
                lowerUrl.contains(".wav") ||
                lowerUrl.contains(".flac") ||
                lowerUrl.contains("audio") ||
                lowerUrl.contains("media") ||
                lowerUrl.contains("episode")
        }
        return false
    }

    private fun stripHtml(raw: String): String =
        raw.replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun fetchWithSafeRedirects(
        startUrl: String,
        allowPrivateNetworkHosts: Boolean,
    ): Triple<String, String?, ByteArray> {
        var currentUrl = startUrl
        repeat(MaxRedirects) {
            val uri = URI(currentUrl)
            val scheme = uri.scheme?.lowercase().orEmpty()
            if (scheme != "https" && scheme != "http") {
                throw InvalidPodcastUrlException("Blocked non-HTTP(S) redirect target.")
            }
            if (!allowPrivateNetworkHosts) {
                PodcastUrlDetector.verifyRemoteHostPublic(uri.host.orEmpty())
            }
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Podcasty/1.0 (Android)")
                setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, audio/*;q=0.8")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IllegalStateException("Redirect ($code) missing Location header.")
                    currentUrl = uri.resolve(location).toString()
                } else if (code in 200..299) {
                    val contentType = conn.contentType
                    if (contentType?.lowercase()?.startsWith("audio/") == true) {
                        // Do NOT download audio body! Return immediately so Media3 streams it directly.
                        return Triple(currentUrl, contentType, ByteArray(0))
                    }
                    val bytes = conn.inputStream.use { input ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            total += read
                            if (total > MaxFeedBytes) {
                                throw IllegalArgumentException("Feed exceeds maximum supported size (6 MB).")
                            }
                            out.write(buf, 0, read)
                        }
                        out.toByteArray()
                    }
                    return Triple(currentUrl, contentType, bytes)
                } else {
                    throw IllegalStateException("Feed server returned HTTP $code.")
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("Too many redirects while loading podcast feed.")
    }
}
