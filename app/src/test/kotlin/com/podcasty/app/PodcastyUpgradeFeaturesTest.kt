/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app

import com.podcasty.app.audio.AudioFormatCatalog
import com.podcasty.app.audio.FormatSupportStatus
import com.podcasty.app.audio.WrappedPcmDataSource
import com.podcasty.app.domain.SleepTimerPreset
import com.podcasty.app.domain.SleepTimerRequest
import com.podcasty.app.player.SleepTimerManager
import com.podcasty.app.podcast.YouTubeAndRssImporter
import com.podcasty.app.storage.LocalMusicScanner
import com.podcasty.app.transcript.CaptionSyncEngine
import com.podcasty.app.transcript.TranscriptCue
import com.podcasty.app.transcript.TranscriptDocument
import com.podcasty.app.transcript.TranscriptFormat
import com.podcasty.app.transcript.TranscriptParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastyUpgradeFeaturesTest {

    @Test
    fun transcriptParser_parsesLrcEnhancedLrcSrtWebVttAndPodcastJson() {
        val lrc = """
            [ti:Episode One]
            [lang:en]
            [00:02.50]Host: Welcome back to Podcasty.
            [00:08.00]<00:08.10>Guest: <00:08.40>Thanks for having me.
        """.trimIndent()
        val lrcDoc = TranscriptParser.parse("ep1", lrc, "ep1.lrc")
        assertEquals(TranscriptFormat.EnhancedLrc, lrcDoc.format)
        assertEquals("en", lrcDoc.language)
        assertEquals(2, lrcDoc.cues.size)
        assertEquals(2_500L, lrcDoc.cues[0].startMs)
        assertEquals("Host", lrcDoc.cues[0].speaker)
        assertEquals("Welcome back to Podcasty.", lrcDoc.cues[0].text)
        assertEquals("Guest", lrcDoc.cues[1].speaker)
        assertEquals("Thanks for having me.", lrcDoc.cues[1].text)

        val srt = """
            1
            00:00:01,250 --> 00:00:04,500
            <i>Elena:</i> Opening remark.

            2
            00:01:02,000 --> 00:01:06,750
            Second segment text.
        """.trimIndent()
        val srtDoc = TranscriptParser.parse("ep2", srt, "captions.srt")
        assertEquals(TranscriptFormat.Srt, srtDoc.format)
        assertEquals(2, srtDoc.cues.size)
        assertEquals(1_250L, srtDoc.cues[0].startMs)
        assertEquals(4_500L, srtDoc.cues[0].endMs)
        assertEquals("Elena", srtDoc.cues[0].speaker)
        assertEquals("Opening remark.", srtDoc.cues[0].text)
        assertEquals(62_000L, srtDoc.cues[1].startMs)

        val vtt = """
            WEBVTT
            Language: es

            00:03.000 --> 00:06.500
            <v Maria>Hola a todos.

            00:01:10.200 --> 00:01:14.000
            Segunda linea.
        """.trimIndent()
        val vttDoc = TranscriptParser.parse("ep3", vtt, "captions.vtt")
        assertEquals(TranscriptFormat.WebVtt, vttDoc.format)
        assertEquals("es", vttDoc.language)
        assertEquals(2, vttDoc.cues.size)
        assertEquals("Maria", vttDoc.cues[0].speaker)
        assertEquals("Hola a todos.", vttDoc.cues[0].text)
        assertEquals(70_200L, vttDoc.cues[1].startMs)

        val json = """
            {"version":"1.0.0","segments":[
                {"speaker":"Sam","startTime":1.5,"endTime":4.0,"body":"Segment one"},
                {"speaker":"Alex","startTime":4.0,"endTime":8.5,"body":"Segment two"}
            ]}
        """.trimIndent()
        val jsonDoc = TranscriptParser.parse("ep4", json, "transcript.json")
        assertEquals(TranscriptFormat.PodcastJson, jsonDoc.format)
        assertEquals(2, jsonDoc.cues.size)
        assertEquals(1_500L, jsonDoc.cues[0].startMs)
        assertEquals("Sam", jsonDoc.cues[0].speaker)
    }

    @Test
    fun captionSyncEngine_handlesLongTranscriptsAndSeeksEfficiently() {
        val cues = List(10_000) { idx ->
            TranscriptCue(
                id = idx.toLong() + 1L,
                startMs = idx * 3_000L,
                endMs = (idx + 1) * 3_000L,
                text = "Sentence number $idx",
            )
        }
        val engine = CaptionSyncEngine()
        engine.loadDocument(
            TranscriptDocument(episodeId = "long_ep", language = "en", format = TranscriptFormat.Srt, cues = cues),
            initialPositionMs = 0L,
        )
        assertEquals(0, engine.snapshot.value.activeCueIndex)

        // Seeking forward deep into an 8-hour transcript resolves accurately via binary search
        val snapForward = engine.onSeek(15_000_500L)
        assertEquals(5_000, snapForward.activeCueIndex)
        assertEquals("Sentence number 5000", snapForward.activeCue?.text)
        assertEquals("Sentence number 4999", snapForward.previousCue?.text)
        assertEquals("Sentence number 5001", snapForward.nextCue?.text)

        // Position tick within same cue preserves exact same snapshot instance (no redundant UI emission)
        val sameTick = engine.onPositionChanged(15_002_100L)
        assertEquals(5_000, sameTick.activeCueIndex)

        // Seeking backward updates active cue cleanly
        val snapBack = engine.onSeek(9_100L)
        assertEquals(3, snapBack.activeCueIndex)
        assertEquals("Sentence number 3", snapBack.activeCue?.text)
    }

    @Test
    fun audioFormatCatalog_sniffsRealContainersAndRejectsCdaAndProprietaryVoice() {
        // WAV vs CDA (both start with RIFF!)
        val wavHeader = "RIFF\u0024\u0000\u0000\u0000WAVEfmt ".toByteArray(Charsets.ISO_8859_1)
        val wav = AudioFormatCatalog.detectFromHeader(wavHeader, "track.wav")
        assertEquals("wav", wav.formatId)
        assertTrue(wav.isPlayable)

        val cdaHeader = "RIFF\u0024\u0000\u0000\u0000CDDAfmt ".toByteArray(Charsets.ISO_8859_1)
        val cda = AudioFormatCatalog.detectFromHeader(cdaHeader, "Track01.cda")
        assertEquals("cda", cda.formatId)
        assertFalse(cda.isPlayable)
        assertEquals(FormatSupportStatus.Unsupported, cda.status)
        assertTrue(cda.unsupportedReason.orEmpty().contains("optical-disc", ignoreCase = true))

        // FLAC, AIFF, Sun AU, DSD, APE, WavPack
        assertEquals("flac", AudioFormatCatalog.detectFromHeader("fLaC\u0000\u0000".toByteArray(Charsets.ISO_8859_1), "a.flac").formatId)
        assertEquals("aiff", AudioFormatCatalog.detectFromHeader("FORM\u0000\u0000\u0000\u0000AIFF".toByteArray(Charsets.ISO_8859_1), "a.aiff").formatId)
        assertEquals("au", AudioFormatCatalog.detectFromHeader(".snd\u0000\u0000\u0000\u0018".toByteArray(Charsets.ISO_8859_1), "a.au").formatId)
        assertFalse(AudioFormatCatalog.detectFromHeader("DSD \u001C\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1), "a.dsf").isPlayable)
        assertFalse(AudioFormatCatalog.detectFromHeader("MAC \u0000\u0000".toByteArray(Charsets.ISO_8859_1), "a.ape").isPlayable)
        assertFalse(AudioFormatCatalog.detectFromHeader("wvpk\u0000\u0000".toByteArray(Charsets.ISO_8859_1), "a.wv").isPlayable)

        // M4A ALAC vs AAC codec sniffing
        val alacHeader = "\u0000\u0000\u0000\u0018ftypM4A ....alac".toByteArray(Charsets.ISO_8859_1)
        assertEquals("alac", AudioFormatCatalog.detectFromHeader(alacHeader, "song.m4a").formatId)
        val aacHeader = "\u0000\u0000\u0000\u0018ftypM4A ....mp4a".toByteArray(Charsets.ISO_8859_1)
        assertEquals("aac", AudioFormatCatalog.detectFromHeader(aacHeader, "song.m4a").formatId)

        // Ogg Opus vs Vorbis
        assertEquals("opus", AudioFormatCatalog.detectFromHeader("OggS....OpusHead".toByteArray(Charsets.ISO_8859_1), "p.opus").formatId)
        assertEquals("ogg_vorbis", AudioFormatCatalog.detectFromHeader("OggS....vorbis".toByteArray(Charsets.ISO_8859_1), "p.ogg").formatId)

        // Raw PCM requires user parameters
        val pcm = AudioFormatCatalog.detectFromHeader(ByteArray(16), "raw_audio.pcm")
        assertEquals("pcm", pcm.formatId)
        assertTrue(pcm.requiresRawPcmParams)
        assertEquals(FormatSupportStatus.RequiresFormatParameters, pcm.status)

        // DSS / DVF marked unsupported honestly
        assertFalse(AudioFormatCatalog.detectFromHeader(ByteArray(8), "memo.dss").isPlayable)
        assertFalse(AudioFormatCatalog.detectFromHeader(ByteArray(8), "memo.dvf").isPlayable)
    }

    @Test
    fun wrappedPcmDataSource_buildsValidWavHeaderAndSwapsBigEndianSamples() {
        val header = WrappedPcmDataSource.buildWavHeader(
            dataLength = 4_000L,
            sampleRate = 48_000,
            channels = 2,
            bitsPerSample = 16,
        )
        assertEquals(44, header.size)
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(header, 36, 4, Charsets.US_ASCII))

        // 16-bit big-endian swap
        val pcm16 = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        WrappedPcmDataSource.swapEndianInPlace(pcm16, 0, pcm16.size, 2)
        assertEquals(0x34.toByte(), pcm16[0])
        assertEquals(0x12.toByte(), pcm16[1])
        assertEquals(0x78.toByte(), pcm16[2])
        assertEquals(0x56.toByte(), pcm16[3])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sleepTimerManager_supportsDurationFadeOutEndOfTrackAndNTracks() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        var nowMs = 1_000L
        var lastVolume = 1.0f
        var pausedCount = 0
        val manager = SleepTimerManager(
            scope = testScope,
            elapsedRealtimeMsProvider = { nowMs },
            onSetPlayerVolume = { lastVolume = it },
            onTimerTriggeredPause = { pausedCount++ },
        )

        // 1. Fade-out curve check
        assertEquals(1.0f, manager.computeFadeVolume(30_000L, 30), 0.001f)
        assertEquals(0.666f, manager.computeFadeVolume(20_000L, 30), 0.01f)
        assertEquals(0.333f, manager.computeFadeVolume(10_000L, 30), 0.01f)
        assertEquals(0.0f, manager.computeFadeVolume(0L, 30), 0.001f)

        // 2. Stop after N tracks (3 tracks)
        manager.configure(SleepTimerRequest(preset = SleepTimerPreset.AfterTracks, trackCount = 3))
        assertEquals(3, manager.state.value.remainingTracks)
        assertFalse(manager.onTrackCompleted())
        assertEquals(2, manager.state.value.remainingTracks)
        assertFalse(manager.onTrackCompleted())
        assertEquals(1, manager.state.value.remainingTracks)
        assertTrue(manager.onTrackCompleted())
        assertEquals(SleepTimerPreset.Off, manager.state.value.preset)

        // 3. Stop after current track
        manager.configure(SleepTimerRequest(preset = SleepTimerPreset.EndOfEpisode))
        assertTrue(manager.onTrackCompleted())

        // 4. Custom duration timer with fade-out and volume restoration
        manager.configure(SleepTimerRequest(preset = SleepTimerPreset.CustomDuration, customMinutes = 1, fadeOutSeconds = 30))
        nowMs += 45_000L // 15s remaining -> 50% fade volume
        testScope.advanceTimeBy(600L)
        assertTrue(manager.state.value.isFadingOut)
        assertTrue(lastVolume in 0.45f..0.55f)

        // Cancel restores player volume cleanly to 1.0f
        manager.cancel(restoreVolume = true)
        assertEquals(1.0f, lastVolume, 0.001f)
        assertEquals(0, pausedCount)
    }

    @Test
    fun youtubeAndRssImporter_validatesUrlsAndDurations() {
        assertEquals("dQw4w9WgXcQ", YouTubeAndRssImporter.extractYouTubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YouTubeAndRssImporter.extractYouTubeVideoId("https://youtu.be/dQw4w9WgXcQ?t=42"))
        assertEquals("dQw4w9WgXcQ", YouTubeAndRssImporter.extractYouTubeVideoId("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(YouTubeAndRssImporter.extractYouTubeVideoId("https://example.com/podcast.mp3"))

        assertEquals(3_723_000L, YouTubeAndRssImporter.parseItunesDurationMs("1:02:03"))
        assertEquals(754_000L, YouTubeAndRssImporter.parseItunesDurationMs("12:34"))
        assertEquals(180_000L, YouTubeAndRssImporter.parseItunesDurationMs("180"))

        val id1 = LocalMusicScanner.stableLocalEpisodeId("content://com.android.externalstorage/track.flac")
        val id2 = LocalMusicScanner.stableLocalEpisodeId("content://com.android.externalstorage/track.flac")
        assertNotNull(id1)
        assertEquals(id1, id2)
    }
}
