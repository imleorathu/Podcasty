/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import com.podcasty.app.features.player.PlayerSeekState
import com.podcasty.app.features.player.hasConfirmedSeekPosition
import com.podcasty.app.streaming.AudioCandidate
import com.podcasty.app.streaming.AudioTrackSelectionResult
import com.podcasty.app.streaming.YouTubeAudioStream
import com.podcasty.app.streaming.YouTubeAudioStreamResolver
import com.podcasty.app.ui.components.touchXToSliderValue
import com.podcasty.ytmusicapi.YTMusicApiClient
import com.podcasty.ytmusicapi.YouTubeAudioTrackKind
import com.podcasty.ytmusicapi.YtMusicAdaptiveAudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import java.util.Locale

class YouTubeAudioStreamResolverTest {
    @Test
    fun selectBestAudioStream_prefersProgressiveAacThenHighestBitrate() {
        val selected = YouTubeAudioStreamResolver.selectBestAudioStream(
            listOf(
                audioStream("https://cdn.example/opus-160", MediaFormat.WEBMA_OPUS, 160_000),
                audioStream("https://cdn.example/aac-48", MediaFormat.M4A, 48_000),
                audioStream("https://cdn.example/aac-128", MediaFormat.M4A, 128_000),
                audioStream(
                    "https://cdn.example/dash-aac-256",
                    MediaFormat.M4A,
                    256_000,
                    deliveryMethod = DeliveryMethod.DASH,
                ),
                audioStream(
                    "https://cdn.example/not-a-url",
                    MediaFormat.M4A,
                    300_000,
                    isUrl = false,
                ),
            ),
        )

        assertEquals("https://cdn.example/aac-128", selected?.content)
    }

    @Test
    fun selectBestAudioStream_fallsBackToOpusWhenAacIsUnavailable() {
        val selected = YouTubeAudioStreamResolver.selectBestAudioStream(
            listOf(
                audioStream("https://cdn.example/opus-64", MediaFormat.WEBMA_OPUS, 64_000),
                audioStream("https://cdn.example/opus-160", MediaFormat.WEBMA_OPUS, 160_000),
                audioStream(
                    "https://cdn.example/hls",
                    MediaFormat.M4A,
                    320_000,
                    deliveryMethod = DeliveryMethod.HLS,
                ),
            ),
        )

        assertEquals("https://cdn.example/opus-160", selected?.content)
    }

    @Test
    fun selectBestAudioStream_returnsNullWhenNoProgressiveStreamExists() {
        assertNull(YouTubeAudioStreamResolver.selectBestAudioStream(emptyList()))
        assertNull(
            YouTubeAudioStreamResolver.selectBestAudioStream(
                listOf(
                    audioStream(
                        "https://cdn.example/dash",
                        MediaFormat.M4A,
                        128_000,
                        deliveryMethod = DeliveryMethod.DASH,
                    ),
                ),
            ),
        )
    }

    @Test
    fun parseYouTubeXtags_decodesRealProtobufXtagsForB30KvHixUmY() {
        val enDub = YTMusicApiClient.parseYouTubeXtags("ChQKBWFjb250EgtkdWJiZWQtYXV0bwoNCgRsYW5nEgVlbi1VUw")
        assertEquals("dubbed-auto", enDub.audioContent)
        assertEquals("en-US", enDub.language)
        assertFalse(enDub.isDrc)

        val taOrigDrc = YTMusicApiClient.parseYouTubeXtags("ChEKBWFjb250EghvcmlnaW5hbAoICgNkcmMSATEKCgoEbGFuZxICdGE")
        assertEquals("original", taOrigDrc.audioContent)
        assertEquals("ta", taOrigDrc.language)
        assertTrue(taOrigDrc.isDrc)

        val taOrigClean = YTMusicApiClient.parseYouTubeXtags("ChEKBWFjb250EghvcmlnaW5hbAoKCgRsYW5nEgJ0YQ")
        assertEquals("original", taOrigClean.audioContent)
        assertEquals("ta", taOrigClean.language)
        assertFalse(taOrigClean.isDrc)
    }

    @Test
    fun selectAudioCandidate_prefersTamilOriginalNonDrcOverDefaultAutoDubbedEnglish() {
        val candidates = listOf(
            // Mirrors real B30KvHixUmY response: English auto-dub is first, marked default, and has slightly higher bitrate
            AudioCandidate(
                url = "https://cdn.example/en-autodub-140",
                mimeType = "audio/mp4",
                formatRank = 4,
                averageBitrate = 129_472,
                bitrate = 135_966,
                sampleRateHz = 44_100,
                audioChannels = 2,
                codec = "AAC",
                itag = 140,
                approxDurationMs = 3_600_000L,
                trackId = "en-US.10",
                languageCode = "en-US",
                displayName = "English (US)",
                kind = YouTubeAudioTrackKind.AUTO_DUBBED,
                isDefault = true,
                isAutoDubbed = true,
                isDrc = false,
            ),
            AudioCandidate(
                url = "https://cdn.example/ta-orig-drc-140",
                mimeType = "audio/mp4",
                formatRank = 4,
                averageBitrate = 129_472,
                bitrate = 135_910,
                sampleRateHz = 44_100,
                audioChannels = 2,
                codec = "AAC",
                itag = 140,
                approxDurationMs = 3_600_000L,
                trackId = "ta.4",
                languageCode = "ta",
                displayName = "Tamil original",
                kind = YouTubeAudioTrackKind.ORIGINAL,
                isDefault = false,
                isAutoDubbed = false,
                isDrc = true,
            ),
            AudioCandidate(
                url = "https://cdn.example/ta-orig-clean-140",
                mimeType = "audio/mp4",
                formatRank = 4,
                averageBitrate = 129_472,
                bitrate = 135_904,
                sampleRateHz = 44_100,
                audioChannels = 2,
                codec = "AAC",
                itag = 140,
                approxDurationMs = 3_600_000L,
                trackId = "ta.4",
                languageCode = "ta",
                displayName = "Tamil original",
                kind = YouTubeAudioTrackKind.ORIGINAL,
                isDefault = false,
                isAutoDubbed = false,
                isDrc = false,
            ),
        )

        val result = YouTubeAudioStreamResolver.selectAudioCandidate(candidates)
        assertTrue(result is AudioTrackSelectionResult.Selected)
        val selected = result as AudioTrackSelectionResult.Selected
        assertEquals("https://cdn.example/ta-orig-clean-140", selected.candidate.url)
        assertEquals("ta", selected.candidate.languageCode)
        assertEquals("ta.4", selected.candidate.trackId)
        assertTrue(selected.isOriginalConfirmed)
        assertFalse(selected.candidate.isAutoDubbed)
        assertFalse(selected.candidate.isDrc)
    }

    @Test
    fun selectBestAudioStream_newPipeSelectsOriginalTamilOverHigherBitrateEnglishDub() {
        val enDub = AudioStream.Builder()
            .setId("en-140")
            .setContent("https://cdn.example/en-dub?xtags=acont%3Ddubbed-auto%3Alang%3Den-US", true)
            .setMediaFormat(MediaFormat.M4A)
            .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
            .setAverageBitrate(135_000)
            .setAudioTrackId("en-US.10")
            .setAudioTrackName("English (US)")
            .setAudioLocale(Locale.US)
            .setAudioTrackType(AudioTrackType.DUBBED)
            .build()

        val taOrig = AudioStream.Builder()
            .setId("ta-140")
            .setContent("https://cdn.example/ta-orig?xtags=acont%3Doriginal%3Alang%3Dta", true)
            .setMediaFormat(MediaFormat.M4A)
            .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
            .setAverageBitrate(129_000)
            .setAudioTrackId("ta.4")
            .setAudioTrackName("Tamil original")
            .setAudioLocale(Locale.forLanguageTag("ta"))
            .setAudioTrackType(AudioTrackType.ORIGINAL)
            .build()

        val chosen = YouTubeAudioStreamResolver.selectBestAudioStream(listOf(enDub, taOrig))
        assertEquals(taOrig.content, chosen?.content)
        assertEquals("ta.4", chosen?.audioTrackId)
    }

    @Test
    fun selectAudioCandidate_rejectsDubbedOnlyStreamsInsteadOfPretendingOriginal() {
        val dubbedOnly = listOf(
            AudioCandidate(
                url = "https://cdn.example/en-autodub-140",
                mimeType = "audio/mp4",
                formatRank = 4,
                averageBitrate = 128_000,
                bitrate = 130_000,
                sampleRateHz = 44_100,
                audioChannels = 2,
                codec = "AAC",
                itag = 140,
                approxDurationMs = 60_000L,
                trackId = "en-US.10",
                languageCode = "en-US",
                displayName = "English (US)",
                kind = YouTubeAudioTrackKind.AUTO_DUBBED,
                isDefault = true,
                isAutoDubbed = true,
                isDrc = false,
            ),
        )

        val result = YouTubeAudioStreamResolver.selectAudioCandidate(dubbedOnly)
        assertTrue(result is AudioTrackSelectionResult.OriginalUnavailable)
    }

    @Test
    fun selectAudioCandidate_requestsLanguageSelectionWhenMultipleUnmarkedLanguagesExist() {
        val ambiguous = listOf(
            AudioCandidate(
                url = "https://cdn.example/en",
                mimeType = "audio/mp4",
                formatRank = 4,
                averageBitrate = 128_000,
                bitrate = 128_000,
                sampleRateHz = 44_100,
                audioChannels = 2,
                codec = "AAC",
                itag = 140,
                approxDurationMs = 60_000L,
                trackId = "en",
                languageCode = "en",
                displayName = "English",
                kind = YouTubeAudioTrackKind.UNKNOWN,
                isDefault = true,
                isAutoDubbed = false,
                isDrc = false,
            ),
            AudioCandidate(
                url = "https://cdn.example/ta",
                mimeType = "audio/mp4",
                formatRank = 4,
                averageBitrate = 128_000,
                bitrate = 128_000,
                sampleRateHz = 44_100,
                audioChannels = 2,
                codec = "AAC",
                itag = 140,
                approxDurationMs = 60_000L,
                trackId = "ta",
                languageCode = "ta",
                displayName = "Tamil",
                kind = YouTubeAudioTrackKind.UNKNOWN,
                isDefault = false,
                isAutoDubbed = false,
                isDrc = false,
            ),
        )

        val result = YouTubeAudioStreamResolver.selectAudioCandidate(ambiguous)
        assertTrue(result is AudioTrackSelectionResult.LanguageSelectionRequired)
        val req = result as AudioTrackSelectionResult.LanguageSelectionRequired
        assertEquals(2, req.availableTracks.size)

        // Explicit selection picks the user's chosen track
        val explicit = YouTubeAudioStreamResolver.selectAudioCandidate(ambiguous, preferredTrackId = "ta")
        assertTrue(explicit is AudioTrackSelectionResult.Selected)
        assertEquals("https://cdn.example/ta", (explicit as AudioTrackSelectionResult.Selected).candidate.url)
    }

    @Test
    fun ytMusicClient_selectBestAudioFormat_picksOriginalTamilNotDefaultAutoDub() {
        val client = YTMusicApiClient()
        val chosen = client.selectBestAudioFormat(
            listOf(
                YtMusicAdaptiveAudioFormat(
                    itag = 140,
                    url = "https://cdn.example/en-dub",
                    mimeType = "audio/mp4",
                    bitrate = 135_966,
                    averageBitrate = 129_472,
                    approxDurationMs = 3_600_000L,
                    audioSampleRate = 44_100,
                    audioChannels = 2,
                    audioQuality = "AUDIO_QUALITY_MEDIUM",
                    audioTrackId = "en-US.10",
                    audioTrackDisplayName = "English (US)",
                    audioLanguageCode = "en-US",
                    audioIsDefault = true,
                    isAutoDubbed = true,
                    isDrc = false,
                    audioTrackKind = YouTubeAudioTrackKind.AUTO_DUBBED,
                ),
                YtMusicAdaptiveAudioFormat(
                    itag = 140,
                    url = "https://cdn.example/ta-orig",
                    mimeType = "audio/mp4",
                    bitrate = 135_904,
                    averageBitrate = 129_472,
                    approxDurationMs = 3_600_000L,
                    audioSampleRate = 44_100,
                    audioChannels = 2,
                    audioQuality = "AUDIO_QUALITY_MEDIUM",
                    audioTrackId = "ta.4",
                    audioTrackDisplayName = "Tamil original",
                    audioLanguageCode = "ta",
                    audioIsDefault = false,
                    isAutoDubbed = false,
                    isDrc = false,
                    audioTrackKind = YouTubeAudioTrackKind.ORIGINAL,
                ),
            ),
        )
        assertEquals("https://cdn.example/ta-orig", chosen?.url)
    }

    @Test
    fun touchXToSliderValue_and_playerSeekState_mapAndClampAccurately() {
        assertEquals(0.10f, touchXToSliderValue(100f, 1000f), 0.0001f)
        assertEquals(0.25f, touchXToSliderValue(250f, 1000f), 0.0001f)
        assertEquals(0.50f, touchXToSliderValue(500f, 1000f), 0.0001f)
        assertEquals(0.75f, touchXToSliderValue(750f, 1000f), 0.0001f)
        assertEquals(0.90f, touchXToSliderValue(900f, 1000f), 0.0001f)
        assertEquals(0f, touchXToSliderValue(-50f, 1000f), 0.0001f)
        assertEquals(1f, touchXToSliderValue(1200f, 1000f), 0.0001f)
        assertEquals(0f, touchXToSliderValue(100f, 0f), 0.0001f)

        val seekState = PlayerSeekState(
            isScrubbing = mutableStateOf(false),
            pendingFraction = mutableStateOf(null),
            pendingTargetMs = mutableStateOf(null),
            pendingGeneration = mutableLongStateOf(0L),
            awaitingConfirmation = mutableStateOf(false),
        )

        // Start scrub -> confirmation must NOT wipe active scrub
        seekState.updateScrub(0.50f)
        assertTrue(seekState.isScrubbing.value)
        assertFalse(seekState.awaitingConfirmation.value)
        seekState.confirmSeek()
        assertEquals(0.50f, seekState.pendingFraction.value)

        // Commit scrub on 240s track -> 120_000 ms
        val target = seekState.commitScrub(240_000L)
        assertEquals(120_000L, target)
        assertFalse(seekState.isScrubbing.value)
        assertTrue(seekState.awaitingConfirmation.value)
        assertEquals(1L, seekState.pendingGeneration.value)

        // Confirm within tolerance (e.g. keyframe snapped by 800ms)
        assertTrue(hasConfirmedSeekPosition(0.50f, 120_800L, 240_000L, target))
        seekState.confirmSeek()
        assertNull(seekState.pendingFraction.value)
        assertFalse(seekState.awaitingConfirmation.value)

        // Zero-duration track safely returns null and resets
        seekState.updateScrub(0.4f)
        assertNull(seekState.commitScrub(0L))
        assertNull(seekState.pendingFraction.value)
    }

    @Test
    fun resolvedStream_expiresAfterTtl() {
        val resolvedAt = 1_000L
        val stream = YouTubeAudioStream(
            videoId = "dQw4w9WgXcQ",
            url = "https://cdn.example/audio",
            mimeType = "audio/mp4",
            bitrateKbps = 128,
            durationMs = 60_000L,
            title = "Example",
            channelName = "Channel",
            itag = 140,
            resolvedAtEpochMs = resolvedAt,
        )

        assertFalse(stream.isExpired(resolvedAt + YouTubeAudioStream.StreamTtlMs - 1L))
        assertTrue(stream.isExpired(resolvedAt + YouTubeAudioStream.StreamTtlMs))
    }

    private fun audioStream(
        content: String,
        format: MediaFormat?,
        averageBitrate: Int,
        deliveryMethod: DeliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP,
        isUrl: Boolean = true,
    ): AudioStream = AudioStream.Builder()
        .setId("itag-${content.hashCode()}")
        .setContent(content, isUrl)
        .setMediaFormat(format)
        .setDeliveryMethod(deliveryMethod)
        .setAverageBitrate(averageBitrate)
        .build()
}
