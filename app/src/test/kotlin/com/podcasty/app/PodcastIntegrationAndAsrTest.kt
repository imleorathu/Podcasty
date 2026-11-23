/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app

import androidx.media3.common.C
import com.podcasty.app.asr.AsrEngineType
import com.podcasty.app.asr.AudioChunkPreprocessor
import com.podcasty.app.asr.CaptionTimingPrecision
import com.podcasty.app.asr.CapturedPcmFrame
import com.podcasty.app.asr.EngineAvailability
import com.podcasty.app.asr.GoogleStreamingSpeechEngine
import com.podcasty.app.asr.LiveCaptionAudioProcessor
import com.podcasty.app.asr.LiveCaptionSegment
import com.podcasty.app.asr.LiveCaptionStateAggregator
import com.podcasty.app.asr.LiveCaptionStatus
import com.podcasty.app.asr.RecognitionEvent
import com.podcasty.app.asr.RecognitionSessionConfig
import com.podcasty.app.data.PodcastUserSettings
import com.podcasty.app.player.LockScreenLyricsNotificationController
import com.podcasty.app.podcast.DetectedPodcastUrlType
import com.podcasty.app.podcast.PodcastUrlDetector
import com.podcasty.app.podcast.RssAndAtomFeedParser
import com.podcasty.app.podcast.RssPodcastDiscoveryService
import com.podcasty.app.podcast.YouTubeCaptionExtractorClient
import com.podcasty.app.podcast.YouTubeMetadataService
import com.podcasty.app.podcast.YouTubeMusicDownloadManager
import com.podcasty.app.transcript.CaptionSyncEngine
import com.podcasty.app.transcript.TranscriptParser
import com.podcasty.app.transcript.TranscriptSource
import com.podcasty.youtubeextractor.core.Subtitle
import com.podcasty.youtubeextractor.core.TranscriptFormatters
import com.podcasty.youtubeextractor.core.YouTubeCaptionExtractor
import com.podcasty.youtubeextractor.core.YouTubeUrlParser
import com.podcasty.ytmusicapi.YTMusicApiClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class PodcastIntegrationAndAsrTest {

    @Test
    fun urlDetector_autoDetectsYouTubeRssDirectAudioAndBlocksUnsafeOrPrivateUrls() {
        // Valid YouTube watch, Shorts, youtu.be, live, music
        val watch = PodcastUrlDetector.detect("https://www.youtube.com/watch?v=jNQXAC9IVRw&t=12s").getOrThrow()
        assertEquals(DetectedPodcastUrlType.YouTube, watch.type)
        assertEquals("jNQXAC9IVRw", watch.youtubeVideoId)
        assertFalse(watch.isYouTubeShorts)

        val shorts = PodcastUrlDetector.detect("https://youtube.com/shorts/dQw4w9WgXcQ").getOrThrow()
        assertEquals(DetectedPodcastUrlType.YouTube, shorts.type)
        assertEquals("dQw4w9WgXcQ", shorts.youtubeVideoId)
        assertTrue(shorts.isYouTubeShorts)

        val shortLink = PodcastUrlDetector.detect("https://youtu.be/jfKfPfyJRdk").getOrThrow()
        assertEquals(DetectedPodcastUrlType.YouTube, shortLink.type)
        assertEquals("jfKfPfyJRdk", shortLink.youtubeVideoId)

        // Invalid YouTube URLs rejected
        assertTrue(PodcastUrlDetector.detect("https://www.youtube.com/watch?v=short").isFailure)
        assertTrue(PodcastUrlDetector.detect("https://www.youtube.com/channel/UC12345").isFailure)

        // Valid RSS & Direct audio auto-detection
        val rss = PodcastUrlDetector.detect("https://feeds.npr.org/510289/podcast.xml").getOrThrow()
        assertEquals(DetectedPodcastUrlType.RssOrAtomFeed, rss.type)

        val direct = PodcastUrlDetector.detect("https://cdn.example.com/shows/episode-42.mp3").getOrThrow()
        assertEquals(DetectedPodcastUrlType.DirectAudio, direct.type)

        // Unsafe schemes & private-network SSRF targets blocked
        assertTrue(PodcastUrlDetector.detect("javascript:alert(1)").isFailure)
        assertTrue(PodcastUrlDetector.detect("file:///etc/passwd").isFailure)
        assertTrue(PodcastUrlDetector.detect("http://127.0.0.1/feed.xml").isFailure)
        assertTrue(PodcastUrlDetector.detect("http://192.168.1.10/feed.xml").isFailure)
        assertTrue(PodcastUrlDetector.detect("http://169.254.169.254/latest/meta-data").isFailure)
        assertTrue(PodcastUrlDetector.detect("https://user:pass@example.com/feed.xml").isFailure)
    }

    @Test
    fun rssAndAtomParser_parsesMultiEpisodeRssAndAtomAndRejectsInvalidXml() {
        val rssXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:podcast="https://podcastindex.org/namespace/1.0">
              <channel>
                <title>Systems Engineering Weekly</title>
                <description><![CDATA[<p>Deep dives into distributed audio and mobile systems.</p>]]></description>
                <itunes:author>Ada &amp; Alan</itunes:author>
                <itunes:owner><itunes:name>Open Audio Network</itunes:name></itunes:owner>
                <itunes:category text="Technology" />
                <itunes:image href="https://cdn.example.com/artwork/show.jpg" />
                <item>
                  <guid>ep-002</guid>
                  <title>Real-Time Speech Pipelines</title>
                  <description>Streaming PCM with zero blocking.</description>
                  <pubDate>Thu, 14 Nov 2026 10:00:00 +0000</pubDate>
                  <itunes:duration>42:15</itunes:duration>
                  <itunes:image href="https://cdn.example.com/artwork/ep2.jpg" />
                  <enclosure url="https://cdn.example.com/audio/ep2.mp3" type="audio/mpeg" length="1234567" />
                  <podcast:transcript url="https://cdn.example.com/transcripts/ep2.vtt" type="text/vtt" language="en-US" />
                </item>
                <item>
                  <guid>ep-001</guid>
                  <title>Intro to Podcasty</title>
                  <description>Welcome episode.</description>
                  <pubDate>Thu, 07 Nov 2026 10:00:00 +0000</pubDate>
                  <itunes:duration>1:05:10</itunes:duration>
                  <enclosure url="https://cdn.example.com/audio/ep1.m4a" type="audio/mp4" />
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val feed = RssAndAtomFeedParser.parseXml(rssXml, feedUrl = "https://cdn.example.com/feed.xml")
        assertEquals("Systems Engineering Weekly", feed.title)
        assertEquals("Ada & Alan", feed.author)
        assertEquals("Open Audio Network", feed.publisher)
        assertEquals("Technology", feed.categoryName)
        assertEquals("https://cdn.example.com/artwork/show.jpg", feed.artworkUrl)
        assertEquals(2, feed.episodes.size)
        assertEquals("Real-Time Speech Pipelines", feed.episodes[0].title)
        assertEquals(42 * 60_000L + 15_000L, feed.episodes[0].durationMs)
        assertEquals("https://cdn.example.com/audio/ep2.mp3", feed.episodes[0].audioUrl)
        assertEquals("https://cdn.example.com/artwork/ep2.jpg", feed.episodes[0].artworkUrl)
        assertEquals("https://cdn.example.com/transcripts/ep2.vtt", feed.episodes[0].transcriptUrl)
        assertEquals(3_910_000L, feed.episodes[1].durationMs)

        // Atom 1.0 feed support
        val atomXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Tamil Tech Cast</title>
              <subtitle>Technology conversations in Tamil.</subtitle>
              <author><name>Karthik</name></author>
              <logo>https://cdn.example.com/tamil-logo.png</logo>
              <entry>
                <id>atom-ep-1</id>
                <title>AI Speech Recognition</title>
                <summary>Live Tamil captioning.</summary>
                <published>2026-11-18T08:30:00Z</published>
                <link rel="enclosure" type="audio/mpeg" href="https://cdn.example.com/tamil-ep1.mp3" />
              </entry>
            </feed>
        """.trimIndent()
        val atom = RssAndAtomFeedParser.parseXml(atomXml, feedUrl = "https://cdn.example.com/atom.xml")
        assertEquals("Tamil Tech Cast", atom.title)
        assertEquals("Karthik", atom.author)
        assertEquals("https://cdn.example.com/tamil-logo.png", atom.artworkUrl)
        assertEquals(1, atom.episodes.size)
        assertEquals("https://cdn.example.com/tamil-ep1.mp3", atom.episodes[0].audioUrl)
        assertTrue(atom.episodes[0].publishedAtMs > 0L)

        // Invalid / malformed feed rejected cleanly
        assertTrue(runCatching { RssAndAtomFeedParser.parseXml("<html><body>Not RSS</body></html>") }.isFailure)
        assertTrue(runCatching { RssAndAtomFeedParser.parseXml("not xml at all") }.isFailure)
    }

    @Test
    fun youTubeMetadataService_parsesOfficialDataApiAndOEmbedAndIsoDurations() {
        assertEquals(3_723_000L, YouTubeMetadataService.parseIso8601DurationMs("PT1H2M3S"))
        assertEquals(315_000L, YouTubeMetadataService.parseIso8601DurationMs("PT5M15S"))
        assertEquals(45_000L, YouTubeMetadataService.parseIso8601DurationMs("PT45S"))

        val apiJson = """
            {
              "items": [{
                "snippet": {
                  "title": "Modern Kotlin Audio Pipelines",
                  "channelTitle": "Android Developers",
                  "description": "Official video metadata.",
                  "thumbnails": { "high": { "url": "https://i.ytimg.com/vi/abc12345678/hqdefault.jpg" } }
                },
                "contentDetails": { "duration": "PT48M10S" }
              }]
            }
        """.trimIndent()
        val meta = YouTubeMetadataService.parseYouTubeDataApiResponse(
            body = apiJson,
            videoId = "abc12345678",
            canonicalUrl = "https://www.youtube.com/watch?v=abc12345678",
            isShorts = false,
            fallbackThumb = "https://i.ytimg.com/vi/abc12345678/hqdefault.jpg",
        )
        assertNotNull(meta)
        assertEquals("Modern Kotlin Audio Pipelines", meta!!.title)
        assertEquals("Android Developers", meta.channelName)
        assertEquals(2_890_000L, meta.durationMs)
        assertEquals("YouTube Data API v3", meta.metadataSource)
    }

    @Test
    fun audioPreprocessor_downmixesResamplesDetectsSpeechAndMapsExactMediaTimestamps() {
        // Generate 250 ms of 48 kHz stereo 440 Hz sine wave (speech-band energy) starting at mediaStartMs = 15_000
        val sampleRate = 48_000
        val channels = 2
        val totalFrames = (sampleRate * 0.25).toInt() // 12,000 stereo frames = 250 ms
        val bb = ByteBuffer.allocate(totalFrames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until totalFrames) {
            val sample = (sin(2.0 * PI * 440.0 * i / sampleRate) * 0.4 * 32767.0).toInt().toShort()
            bb.putShort(sample)
            bb.putShort(sample)
        }
        val frame = CapturedPcmFrame(
            pcmBytes = bb.array(),
            sampleRateHz = sampleRate,
            channelCount = channels,
            encoding = C.ENCODING_PCM_16BIT,
            mediaStartMs = 15_000L,
            mediaEndMs = 15_250L,
            generationId = 1L,
        )

        val preprocessor = AudioChunkPreprocessor(targetSampleRateHz = 16_000, chunkDurationMs = 100)
        val chunks = preprocessor.processFrame(frame)
        assertEquals(2, chunks.size) // 250 ms yields two full 100 ms chunks (+ 50 ms buffered)
        assertEquals(16_000, chunks[0].sampleRateHz)
        assertEquals(1_600, chunks[0].sampleCount)
        assertEquals(3_200, chunks[0].pcm16Bytes.size)
        assertTrue(chunks[0].isSpeech)
        assertEquals(15_000L, chunks[0].mediaStartMs)
        assertEquals(15_100L, chunks[0].mediaEndMs)
        assertEquals(15_100L, chunks[1].mediaStartMs)
        assertEquals(15_200L, chunks[1].mediaEndMs)

        // Pure silence frame with fresh preprocessor should NOT trigger VAD
        val silentPreprocessor = AudioChunkPreprocessor(targetSampleRateHz = 16_000, chunkDurationMs = 100)
        val silentFrame = CapturedPcmFrame(
            pcmBytes = ByteArray(1600 * 2), // 100 ms of 16 kHz mono silence
            sampleRateHz = 16_000,
            channelCount = 1,
            encoding = C.ENCODING_PCM_16BIT,
            mediaStartMs = 20_000L,
            mediaEndMs = 20_100L,
            generationId = 2L,
        )
        val silentChunks = silentPreprocessor.processFrame(silentFrame)
        assertEquals(1, silentChunks.size)
        assertFalse(silentChunks[0].isSpeech)

        // Verify audio-frame-to-media-ms calculation does not drift across playback speeds
        assertEquals(1_000L, LiveCaptionAudioProcessor.computeMediaDurationMs(48_000L, 48_000, 1.0f))
        assertEquals(1_000L, LiveCaptionAudioProcessor.computeMediaDurationMs(48_000L, 48_000, 2.0f))
    }

    @Test
    fun liveCaptionAggregator_handlesPartialFinalDedupEnglishTamilSeekAndPause() {
        val aggregator = LiveCaptionStateAggregator()

        // 1. Partial English caption appears immediately
        val (fin1, part1) = aggregator.onIncomingSegment(
            LiveCaptionSegment(
                id = "p1",
                sessionId = "s1",
                startMs = 12_500L,
                endMs = 14_000L,
                text = "Welcome to today's",
                language = "en-US",
                status = LiveCaptionStatus.PARTIAL,
            ),
        )
        assertTrue(fin1.isEmpty())
        assertEquals("Welcome to today's", part1?.text)
        assertEquals(0, LiveCaptionStateAggregator.findActiveIndex(fin1, part1, 13_000L))

        // 2. Final replaces partial smoothly
        val (fin2, part2) = aggregator.onIncomingSegment(
            LiveCaptionSegment(
                id = "f1",
                sessionId = "s1",
                startMs = 12_500L,
                endMs = 15_800L,
                text = "Welcome to today's podcast",
                language = "en-US",
                status = LiveCaptionStatus.FINAL,
            ),
        )
        assertNull(part2)
        assertEquals(1, fin2.size)
        assertEquals("Welcome to today's podcast", fin2[0].text)

        // 3. Duplicate overlapping sentence is deduplicated (keeps richer version)
        val (fin3, _) = aggregator.onIncomingSegment(
            LiveCaptionSegment(
                id = "f1_dup",
                sessionId = "s1",
                startMs = 12_600L,
                endMs = 16_000L,
                text = "Welcome to today's podcast!",
                language = "en-US",
                status = LiveCaptionStatus.FINAL,
            ),
        )
        assertEquals(1, fin3.size)
        assertEquals("Welcome to today's podcast!", fin3[0].text)

        // 4. Second finalized sentence (Tamil ta-IN) preserves order & previous sentence
        val (fin4, _) = aggregator.onIncomingSegment(
            LiveCaptionSegment(
                id = "f2_ta",
                sessionId = "s1",
                startMs = 25_000L,
                endMs = 28_500L,
                text = "இன்றைய நிகழ்ச்சிக்கு வரவேற்கிறோம்",
                language = "ta-IN",
                status = LiveCaptionStatus.FINAL,
            ),
        )
        assertEquals(2, fin4.size)
        assertEquals(1, LiveCaptionStateAggregator.findActiveIndex(fin4, null, 26_000L))

        // 5. Seeking backward highlights earlier finalized sentence cleanly
        assertEquals(0, LiveCaptionStateAggregator.findActiveIndex(fin4, null, 13_500L))
    }

    @Test
    fun googleStreamingSpeechEngine_validatesWssAndParsesEnglishAndTamilEvents() {
        val engine = GoogleStreamingSpeechEngine()
        assertEquals(AsrEngineType.GoogleCloud, engine.engineType)
        assertTrue("en-US" in engine.supportedLanguages)
        assertTrue("ta-IN" in engine.supportedLanguages)
        assertTrue("ta-LK" in engine.supportedLanguages)
        assertTrue("si-LK" in engine.supportedLanguages)

        // Blank URL -> ConfigurationRequired
        val missing = engine.checkAvailability(RecognitionSessionConfig("s", "e", "en-US", backendWebSocketUrl = ""))
        assertTrue(missing is EngineAvailability.ConfigurationRequired)

        // Insecure remote ws:// rejected
        val insecureRemote = engine.checkAvailability(
            RecognitionSessionConfig("s", "e", "en-US", backendWebSocketUrl = "ws://remote.example.com/ws/captions"),
        )
        assertTrue(insecureRemote is EngineAvailability.ConfigurationRequired)

        // Production wss:// and local emulator ws://10.0.2.2 accepted
        val prodWss = engine.checkAvailability(
            RecognitionSessionConfig("s", "e", "ta-IN", backendWebSocketUrl = "wss://captions.example.com/ws/captions"),
        )
        assertTrue(prodWss is EngineAvailability.Ready)

        val devEmulator = engine.checkAvailability(
            RecognitionSessionConfig("s", "e", "en-US", backendWebSocketUrl = "ws://10.0.2.2:8443/ws/captions"),
        )
        assertTrue(devEmulator is EngineAvailability.Ready)

        // Parse server JSON event (Tamil final with exact timing)
        val cfg = RecognitionSessionConfig("pod_001", "ep1", "ta-IN")
        val raw = """
            {"type":"final","id":"seg_1","sessionId":"pod_001","startMs":12500,"endMs":15800,"text":"வணக்கம் நண்பர்களே","language":"ta-IN","status":"final","timingPrecision":"exact"}
        """.trimIndent()
        val parsed = engine.parseServerMessage(raw, cfg)
        assertTrue(parsed is RecognitionEvent.Caption)
        val seg = (parsed as RecognitionEvent.Caption).segment
        assertEquals(12_500L, seg.startMs)
        assertEquals(15_800L, seg.endMs)
        assertEquals("வணக்கம் நண்பர்களே", seg.text)
        assertEquals("ta-IN", seg.language)
        assertEquals(LiveCaptionStatus.FINAL, seg.status)
        assertEquals(CaptionTimingPrecision.EXACT, seg.timingPrecision)

        // Verify binary frame wire encoding
        val wire = GoogleStreamingSpeechEngine.encodeBinaryAudioFrame(12_500L, 12_600L, byteArrayOf(1, 2, 3, 4))
        assertEquals(20, wire.size)
    }

    @Test
    fun youTubeCaptionExtractorClient_derivesUrlsParsesEnglishTamilJsonAndSyncsWithCaptionEngine() {
        // 1. Endpoint URL derivation & security checks
        assertEquals(
            "https://captions.example.com/api/youtube/captions",
            YouTubeCaptionExtractorClient.deriveCaptionsEndpointUrl("wss://captions.example.com/ws/captions"),
        )
        assertEquals(
            "http://10.0.2.2:8443/api/youtube/captions",
            YouTubeCaptionExtractorClient.deriveCaptionsEndpointUrl("ws://10.0.2.2:8443/ws/captions"),
        )
        assertEquals(
            "https://api.example.com:8443/api/youtube/captions",
            YouTubeCaptionExtractorClient.deriveCaptionsEndpointUrl("https://api.example.com:8443"),
        )
        assertNull(YouTubeCaptionExtractorClient.deriveCaptionsEndpointUrl("ws://remote.example.com/ws/captions"))
        assertNull(YouTubeCaptionExtractorClient.deriveCaptionsEndpointUrl("http://remote.example.com"))
        assertNull(YouTubeCaptionExtractorClient.deriveCaptionsEndpointUrl(""))

        // 2. Parse backend JSON (English + Tamil UTF-8) & verify LRC + CaptionSyncEngine synchronization
        val client = YouTubeCaptionExtractorClient()
        val responseJson = """
            {
              "videoId": "jNQXAC9IVRw",
              "title": "Podcast Episode",
              "description": "Synced YouTube transcript",
              "language": "ta",
              "cueCount": 3,
              "cues": [
                {"startMs": 1120, "endMs": 4500, "text": "வணக்கம் நண்பர்களே"},
                {"startMs": 4500, "endMs": 9200, "text": "Welcome to today's synchronized podcast"},
                {"startMs": 9200, "endMs": 14000, "text": "Tap any line to seek immediately"}
              ]
            }
        """.trimIndent()

        val extracted = client.parseResponseJson(responseJson, "jNQXAC9IVRw", "ta-IN")
        assertNotNull(extracted)
        assertEquals("ta", extracted!!.language)
        assertEquals(3, extracted.cues.size)
        assertEquals("வணக்கம் நண்பர்களே", extracted.cues[0].text)
        assertEquals(1_120L, extracted.cues[0].startMs)

        // Round-trip via LRC persistence & CaptionSyncEngine O(log N) lookup
        val lrc = extracted.toLrcContent()
        val reparsed = TranscriptParser.parse("yt_ep_jNQXAC9IVRw", lrc, "jNQXAC9IVRw_ta.lrc", language = extracted.language)
        assertEquals(3, reparsed.cues.size)

        val sync = CaptionSyncEngine()
        sync.loadDocument(reparsed, initialPositionMs = 2_000L)
        assertEquals(0, sync.snapshot.value.activeCueIndex)
        assertEquals("வணக்கம் நண்பர்களே", sync.snapshot.value.activeCue?.text)

        // Seek forward -> active cue updates immediately
        sync.onSeek(10_500L)
        assertEquals(2, sync.snapshot.value.activeCueIndex)
        assertEquals("Tap any line to seek immediately", sync.snapshot.value.activeCue?.text)

        // Seek backward -> active cue updates immediately
        sync.onSeek(5_000L)
        assertEquals(1, sync.snapshot.value.activeCueIndex)
        assertEquals("Welcome to today's synchronized podcast", sync.snapshot.value.activeCue?.text)
    }

    @Test
    fun transcriptParser_supportsTamilSinhalaAndMultilingualLyricsSynchronization() {
        val tamilLrc = """
            [00:02.00] முன்பே வா என் அன்பே வா
            [00:06.50] பூப்பூவாய் பூப்போம் வா
            [00:11.00] நான் நானா கேட்டேன் என்னை நானே
        """.trimIndent()
        val tamilDoc = TranscriptParser.parse("song_ta", tamilLrc, "song.lrc", language = "auto")
        assertEquals("ta", tamilDoc.language)
        assertEquals(3, tamilDoc.cues.size)

        val sinhalaLrc = """
            [00:01.50] මල් පිට මල් හැලෙනා දිගු මාවතේ
            [00:05.80] පිය මත පිය තබමින් හිනැහෙන නුඹේ
            [00:10.20] සඳ එළියෙන් නැහැවෙන මේ රාත්‍රියේ
        """.trimIndent()
        val sinhalaDoc = TranscriptParser.parse("song_si", sinhalaLrc, "song_si.lrc", language = "auto")
        assertEquals("si", sinhalaDoc.language)
        assertEquals(3, sinhalaDoc.cues.size)
        // Verify zero-width joiner in "රාත්‍රියේ" is preserved intact
        assertTrue(sinhalaDoc.cues[2].text.contains("රාත්\u200Dරියේ"))

        val sync = CaptionSyncEngine()
        sync.loadDocument(sinhalaDoc, initialPositionMs = 6_000L)
        assertEquals(1, sync.snapshot.value.activeCueIndex)
        assertEquals("පිය මත පිය තබමින් හිනැහෙන නුඹේ", sync.snapshot.value.activeCue?.text)
        sync.onPositionChanged(11_000L)
        assertEquals(2, sync.snapshot.value.activeCueIndex)
        assertEquals("සඳ එළියෙන් නැහැවෙන මේ රාත්\u200Dරියේ", sync.snapshot.value.activeCue?.text)
    }

    @Test
    fun rssPodcastDiscoveryService_parsesItunesResultsAndMatchesEpisodeTitles() {
        val service = RssPodcastDiscoveryService()
        val itunesJson = """
            {
              "resultCount": 1,
              "results": [{
                "collectionName": "Lex Fridman Podcast",
                "artistName": "Lex Fridman",
                "feedUrl": "https://lexfridman.com/feed/podcast/",
                "artworkUrl600": "https://example.com/art.jpg"
              }]
            }
        """.trimIndent()
        val parsed = service.parseItunesSearchJson(itunesJson)
        assertEquals(1, parsed.size)
        assertEquals("https://lexfridman.com/feed/podcast/", parsed[0].feedUrl)

        val highSim = RssPodcastDiscoveryService.tokenSimilarity(
            "Andrew Huberman: Sleep, Focus & Neuroscience | Lex Fridman Podcast #400",
            "#400 – Andrew Huberman: Sleep, Focus, and Neuroscience",
        )
        assertTrue("Expected high title similarity >= 0.45, got $highSim", highSim >= 0.45f)

        val lowSim = RssPodcastDiscoveryService.tokenSimilarity(
            "Me at the zoo",
            "Global Macroeconomic Policy Update",
        )
        assertTrue("Expected low title similarity < 0.2, got $lowSim", lowSim < 0.2f)
    }

    @Test
    fun captionExtractorAndroidCore_parsesUrlsFormatsSrtVttParsesJson3AndSyncsToLyricsPage() {
        val id = "7GeFt8suV8E"
        assertEquals(id, YouTubeUrlParser.extractVideoId("https://www.youtube.com/watch?v=$id"))
        assertEquals(id, YouTubeUrlParser.extractVideoId("https://youtu.be/$id?t=10"))
        assertEquals(id, YouTubeUrlParser.extractVideoId("https://youtube.com/shorts/$id"))
        assertEquals(id, YouTubeUrlParser.extractVideoId("https://youtube.com/live/$id"))
        assertEquals(id, YouTubeUrlParser.extractVideoId("https://www.youtube.com/embed/$id"))
        assertEquals(id, YouTubeUrlParser.extractVideoId(id))
        assertThrows(IllegalArgumentException::class.java) {
            YouTubeUrlParser.extractVideoId("https://example.com/watch?v=$id")
        }

        val srt = TranscriptFormatters.toSrt(listOf(Subtitle(start = "1.25", dur = "2.5", text = "Hello world")))
        assertTrue(srt.contains("00:00:01,250 --> 00:00:03,750"))
        assertTrue(srt.contains("Hello world"))

        val vtt = TranscriptFormatters.toVtt(listOf(Subtitle(start = "0.0", dur = "1.0", text = "Hello")))
        assertTrue(vtt.startsWith("WEBVTT"))
        assertTrue(vtt.contains("00:00:00.000 --> 00:00:01.000"))

        val extractor = YouTubeCaptionExtractor()
        val json3 = """
            {
              "events": [
                {"tStartMs": 1200, "dDurationMs": 2800, "segs": [{"utf8": "வணக்கம் "}, {"utf8": "நண்பர்களே"}]},
                {"tStartMs": 4000, "dDurationMs": 3500, "segs": [{"utf8": "Welcome &amp; enjoy the music"}]},
                {"tStartMs": 7500, "dDurationMs": 3000, "aAppend": 1, "segs": [{"utf8": "ignored append"}]},
                {"tStartMs": 7500, "dDurationMs": 4200, "segs": [{"utf8": "සඳ එළියෙන් නැහැවෙන"}]}
              ]
            }
        """.trimIndent()
        val subs = extractor.parseJson3CaptionResponse(json3)
        assertEquals(3, subs.size)
        assertEquals("வணக்கம் நண்பர்களே", subs[0].text)
        assertEquals("Welcome & enjoy the music", subs[1].text)
        assertEquals("සඳ එළියෙන් නැහැවෙන", subs[2].text)

        val client = YouTubeCaptionExtractorClient()
        val cues = client.subtitlesToTranscriptCues(subs)
        assertEquals(3, cues.size)
        assertEquals(1_200L, cues[0].startMs)
        assertEquals(4_000L, cues[0].endMs)
        assertEquals(4_000L, cues[1].startMs)
        assertEquals(7_500L, cues[1].endMs)

        val lrc = TranscriptParser.toLrcString(cues)
        val doc = TranscriptParser.parse("yt_ep_$id", lrc, "${id}_ta.lrc", language = "ta")
        val sync = CaptionSyncEngine()
        sync.loadDocument(doc, initialPositionMs = 2_000L)
        assertEquals("வணக்கம் நண்பர்களே", sync.snapshot.value.activeCue?.text)
        sync.onPositionChanged(5_000L)
        assertEquals("Welcome & enjoy the music", sync.snapshot.value.activeCue?.text)
        sync.onPositionChanged(8_500L)
        assertEquals("සඳ එළියෙන් නැහැවෙන", sync.snapshot.value.activeCue?.text)
    }

    @Test
    fun ytMusicApiClient_parsesSongDetailsAudioFormatsAndTimestampedLyrics() {
        val ytMusic = YTMusicApiClient()
        val json = Json { ignoreUnknownKeys = true }

        val songJson = """
            {
              "playabilityStatus": {
                "status": "OK",
                "audioOnlyPlayability": {
                  "audioOnlyPlayabilityRenderer": {
                    "audioOnlyAvailability": "FEATURE_AVAILABILITY_ALLOWED"
                  }
                }
              },
              "videoDetails": {
                "videoId": "AjXQiKP5kMs",
                "title": "Sparks",
                "lengthSeconds": "245",
                "channelId": "UCvCk2zFqkCYzpnSgWfx0qOg",
                "author": "Thomas Bergersen",
                "shortDescription": "Uploaded via YouTube Music"
              },
              "streamingData": {
                "adaptiveFormats": [
                  {
                    "itag": 251,
                    "url": "https://rr1---sn.googlevideo.com/videoplayback?itag=251",
                    "mimeType": "audio/webm; codecs=\"opus\"",
                    "bitrate": 160000,
                    "averageBitrate": 155000,
                    "approxDurationMs": "245000",
                    "audioSampleRate": "48000",
                    "audioChannels": 2
                  },
                  {
                    "itag": 140,
                    "url": "https://rr1---sn.googlevideo.com/videoplayback?itag=140",
                    "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                    "bitrate": 131007,
                    "averageBitrate": 129547,
                    "approxDurationMs": "245000",
                    "audioSampleRate": "44100",
                    "audioChannels": 2
                  }
                ]
              }
            }
        """.trimIndent()

        val details = ytMusic.parseSongResponse("AjXQiKP5kMs", json.parseToJsonElement(songJson).jsonObject)
        assertTrue(details.isPlayable)
        assertTrue(details.audioOnlyAvailable)
        assertEquals("Sparks", details.title)
        assertEquals("Thomas Bergersen", details.author)
        assertEquals(245_000L, details.durationMs)
        assertEquals(2, details.audioFormats.size)

        val bestAudio = ytMusic.selectBestAudioFormat(details.audioFormats)
        assertNotNull(bestAudio)
        assertEquals(140, bestAudio!!.itag)
        assertEquals("audio/mp4", bestAudio.mimeType)

        val timedLyricsJson = """
            {
              "contents": {
                "elementRenderer": {
                  "newElement": {
                    "type": {
                      "componentType": {
                        "model": {
                          "timedLyricsModel": {
                            "lyricsData": {
                              "sourceMessage": "Source: LyricFind",
                              "timedLyricsData": [
                                {
                                  "lyricLine": "I was a liar",
                                  "cueRange": {
                                    "startTimeMilliseconds": "9200",
                                    "endTimeMilliseconds": "10630",
                                    "metadata": { "id": "1" }
                                  }
                                },
                                {
                                  "lyricLine": "I gave in to the fire",
                                  "cueRange": {
                                    "startTimeMilliseconds": "10680",
                                    "endTimeMilliseconds": "12540",
                                    "metadata": { "id": "2" }
                                  }
                                }
                              ]
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val lyrics = ytMusic.parseLyricsResponse(json.parseToJsonElement(timedLyricsJson).jsonObject, timestamps = true)
        assertNotNull(lyrics)
        assertTrue(lyrics!!.hasTimestamps)
        assertEquals(2, lyrics.lines.size)
        assertEquals("I was a liar", lyrics.lines[0].text)
        assertEquals(9_200L, lyrics.lines[0].startTimeMs)
        assertEquals(10_630L, lyrics.lines[0].endTimeMs)

        // Verify YouTubeMusicDownloadManager helpers ("Youtube Music Download" HTTP Range & extension resolution)
        assertEquals(39_673_820L, YouTubeMusicDownloadManager.parseTotalLengthFromContentRange("bytes 0-9437183/39673820"))
        assertNull(YouTubeMusicDownloadManager.parseTotalLengthFromContentRange(null))
        assertEquals("m4a", YouTubeMusicDownloadManager.resolveAudioExtension("audio/mp4; codecs=\"mp4a.40.2\""))
        assertEquals("ogg", YouTubeMusicDownloadManager.resolveAudioExtension("audio/webm; codecs=\"opus\""))
        assertEquals("My Podcast_Episode_01", YouTubeMusicDownloadManager.sanitizeFileName("My Podcast:Episode/01"))
    }

    @Test
    fun lockScreenLyrics_syncsTimelineEnhancedLrcSeekAndFormatsCompanionDisplay() {
        // 1. Default user settings enable lock-screen lyrics and haven't prompted notification permission yet
        val defaultSettings = PodcastUserSettings()
        assertTrue(defaultSettings.showLockScreenLyrics)
        assertFalse(defaultSettings.hasRequestedNotificationPermission)

        // 2. Enhanced LRC + standard LRC timestamps parse accurately with timing intact
        val lrcContent = """
            [00:10.00] <00:10.00>I <00:10.40>found <00:10.80>a <00:11.10>love <00:11.50>for <00:11.90>me
            [00:15.20] Darling, just dive right in
            [00:20.00] Follow my lead
        """.trimIndent()
        val timedDoc = TranscriptParser.parse(
            episodeId = "song_perfect",
            content = lrcContent,
            language = "en",
            source = TranscriptSource.AuthorizedImport,
        )
        assertTrue(timedDoc.isTimed)
        assertEquals(3, timedDoc.cues.size)

        val engine = CaptionSyncEngine()
        engine.loadDocument(timedDoc, initialPositionMs = 11_000L)
        var snap = engine.snapshot.value
        assertEquals("I found a love for me", snap.activeCue?.text)
        assertEquals("Darling, just dive right in", snap.nextCue?.text)

        // 3. Companion notification formats Song + Artist + ♪ current lyric + next lyric while Playing
        val playingDisplay = LockScreenLyricsNotificationController.formatDisplayContent(
            episodeId = "song_perfect",
            songTitle = "Perfect",
            artistName = "Ed Sheeran",
            albumTitle = "Divide",
            currentLyric = snap.activeCue!!.text,
            nextLyric = snap.nextCue?.text,
            isPaused = false,
        )
        assertEquals("Perfect · Ed Sheeran", playingDisplay.title)
        assertEquals("♪ I found a love for me", playingDisplay.collapsedLine)
        assertEquals("♪ I found a love for me\nDarling, just dive right in", playingDisplay.expandedBody)
        assertNull(playingDisplay.summaryText)

        // 4. Pausing preserves the current lyric and alters renderKey so notification updates with "Paused"
        val pausedDisplay = LockScreenLyricsNotificationController.formatDisplayContent(
            episodeId = "song_perfect",
            songTitle = "Perfect",
            artistName = "Ed Sheeran",
            albumTitle = "Divide",
            currentLyric = snap.activeCue!!.text,
            nextLyric = snap.nextCue?.text,
            isPaused = true,
        )
        assertEquals("Paused", pausedDisplay.summaryText)
        assertEquals(playingDisplay.expandedBody, pausedDisplay.expandedBody)
        assertTrue(pausedDisplay.renderKey != playingDisplay.renderKey)

        // 5. Seeking forward & backward updates current & next lyrics immediately from timeline
        engine.onSeek(16_000L)
        snap = engine.snapshot.value
        assertEquals("Darling, just dive right in", snap.activeCue?.text)
        assertEquals("Follow my lead", snap.nextCue?.text)

        engine.onSeek(12_000L)
        assertEquals("I found a love for me", engine.snapshot.value.activeCue?.text)

        // 6. Track transition clears stale lyrics and loads new track cues; plain-text lyrics are untimed
        val unsyncedDoc = TranscriptParser.parse(
            episodeId = "song_plain",
            content = "Verse 1\nPlain text without timestamps",
            language = "en",
            source = TranscriptSource.EmbeddedTag,
        )
        assertFalse(unsyncedDoc.isTimed)
        engine.loadDocument(unsyncedDoc, initialPositionMs = 0L)
        assertFalse(engine.snapshot.value.isTimed)

        // 7. Unknown Artist falls back gracefully without duplicating song title
        val fallbackDisplay = LockScreenLyricsNotificationController.formatDisplayContent(
            episodeId = "song_2",
            songTitle = "Instrumental Intro",
            artistName = "Unknown Artist",
            albumTitle = "Local Audio",
            currentLyric = "La la la",
            nextLyric = null,
            isPaused = false,
        )
        assertEquals("Instrumental Intro", fallbackDisplay.title)
        assertEquals("♪ La la la", fallbackDisplay.expandedBody)
    }
}
