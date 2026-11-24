package com.podcasty.app

import com.podcasty.app.data.PodcastUserSettings
import com.podcasty.app.player.LockScreenLyricsNotificationController
import com.podcasty.app.player.computeBackgroundTickerDelayMs
import com.podcasty.app.transcript.CaptionSyncEngine
import com.podcasty.app.transcript.Id3SyltParser
import com.podcasty.app.transcript.TranscriptParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class LockScreenLyricsAndSyncUpgradeTest {

    @Test
    fun captionSyncEngine_handlesPreIntroBeforeFirstCueAndTracksNextCueDeadline() {
        val lrc = """
            [00:10.00] முன்பே வா என் அன்பே வா
            [00:15.50] பூப்பூவாய் பூப்போம் வா
            [00:21.00] நான் நானா கேட்டேன் என்னை நானே
        """.trimIndent()
        val doc = TranscriptParser.parse("song_intro", lrc, "song_intro.lrc", language = "auto")
        assertEquals("ta", doc.language)

        val engine = CaptionSyncEngine()
        // At 0:02 (before first lyric at 0:10), activeCueIndex must be -1 (intro), nextCue = cues[0]
        engine.loadDocument(doc, initialPositionMs = 2_000L)
        val introSnap = engine.snapshot.value
        assertTrue(introSnap.isTimed)
        assertEquals(-1, introSnap.activeCueIndex)
        assertNull(introSnap.activeCue)
        assertEquals("முன்பே வா என் அன்பே வா", introSnap.nextCue?.text)
        assertEquals(10_000L, introSnap.nextCueStartMs)

        // Crossing into first lyric activates index 0 and sets nextCueStartMs to 15_500L
        val firstSnap = engine.onPositionChanged(10_050L)
        assertEquals(0, firstSnap.activeCueIndex)
        assertEquals("முன்பே வா என் அன்பே வா", firstSnap.activeCue?.text)
        assertNull(firstSnap.previousCue)
        assertEquals(15_500L, firstSnap.nextCueStartMs)

        // Crossing into second lyric populates previous, active, and next
        val secondSnap = engine.onPositionChanged(16_000L)
        assertEquals(1, secondSnap.activeCueIndex)
        assertEquals("முன்பே வா என் அன்பே வா", secondSnap.previousCue?.text)
        assertEquals("பூப்பூவாய் பூப்போம் வா", secondSnap.activeCue?.text)
        assertEquals("நான் நானா கேட்டேன் என்னை நானே", secondSnap.nextCue?.text)
        assertEquals(21_000L, secondSnap.nextCueStartMs)

        // Seeking back into intro clears activeCue cleanly
        val backToIntro = engine.onSeek(4_000L)
        assertEquals(-1, backToIntro.activeCueIndex)
        assertNull(backToIntro.activeCue)
        assertEquals(10_000L, backToIntro.nextCueStartMs)
    }

    @Test
    fun transcriptParser_pairsBilingualLrcTranslationsAndRoundTripsLosslessly() {
        val bilingualLrc = """
            [00:05.00] அன்பே அன்பே கொல்லாதே
            [00:05.00] My love, my love, don't tease me
            [00:12.40] සඳ එළියෙන් නැහැවෙන මේ රාත්‍රියේ
            [00:12.40] In this night bathed in moonlight
        """.trimIndent()

        val doc = TranscriptParser.parse("song_bilingual", bilingualLrc, "bilingual.lrc", language = "auto")
        assertTrue(doc.isTimed)
        assertEquals(2, doc.cues.size)
        assertEquals("அன்பே அன்பே கொல்லாதே", doc.cues[0].text)
        assertEquals("My love, my love, don't tease me", doc.cues[0].translation)
        assertEquals("සඳ එළියෙන් නැහැවෙන මේ රාත්\u200Dරියේ", doc.cues[1].text)
        assertEquals("In this night bathed in moonlight", doc.cues[1].translation)

        // Round-trip through toLrcString preserves both original and translation lines
        val serialized = TranscriptParser.toLrcString(doc.cues)
        val reparsed = TranscriptParser.parse("song_bilingual", serialized, "roundtrip.lrc")
        assertEquals(2, reparsed.cues.size)
        assertEquals(doc.cues[0].translation, reparsed.cues[0].translation)
        assertEquals(doc.cues[1].translation, reparsed.cues[1].translation)
    }

    @Test
    fun id3SyltParser_extractsEmbeddedUtf8SynchronizedLyricsWithMillisecondTimestamps() {
        // Build a synthetic ID3v2.3 header containing a valid UTF-8 SYLT frame (timestampFormat = 2 ms)
        val framePayload = ByteArrayOutputStream().apply {
            write(3) // UTF-8 encoding
            write("tam".toByteArray(Charsets.ISO_8859_1)) // language
            write(2) // timestamp format = milliseconds
            write(1) // content type = lyrics
            write("Synced".toByteArray(Charsets.UTF_8))
            write(0) // description null terminator
            // Cue 1: 4500 ms
            write("வணக்கம் பாடல்".toByteArray(Charsets.UTF_8))
            write(0)
            write(byteArrayOf(0x00, 0x00, 0x11, 0x94.toByte())) // 4500
            // Cue 2: 12000 ms
            write("இசை தொடர்கிறது".toByteArray(Charsets.UTF_8))
            write(0)
            write(byteArrayOf(0x00, 0x00, 0x2E, 0xE0.toByte())) // 12000
        }.toByteArray()

        val frameHeader = ByteArrayOutputStream().apply {
            write("SYLT".toByteArray(Charsets.ISO_8859_1))
            val sz = framePayload.size
            write((sz shr 24) and 0xFF)
            write((sz shr 16) and 0xFF)
            write((sz shr 8) and 0xFF)
            write(sz and 0xFF)
            write(0)
            write(0)
        }.toByteArray()

        val totalBodySize = frameHeader.size + framePayload.size
        val id3Bytes = ByteArrayOutputStream().apply {
            write("ID3".toByteArray(Charsets.ISO_8859_1))
            write(3) // v2.3
            write(0)
            write(0) // flags
            // Syncsafe size
            write((totalBodySize shr 21) and 0x7F)
            write((totalBodySize shr 14) and 0x7F)
            write((totalBodySize shr 7) and 0x7F)
            write(totalBodySize and 0x7F)
            write(frameHeader)
            write(framePayload)
        }.toByteArray()

        val parsed = Id3SyltParser.extractFromBytes(id3Bytes)
        assertNotNull(parsed)
        assertEquals("ta", parsed!!.language)
        assertEquals(2, parsed.cues.size)
        assertEquals(4_500L, parsed.cues[0].startMs)
        assertEquals("வணக்கம் பாடல்", parsed.cues[0].text)
        assertEquals(12_000L, parsed.cues[1].startMs)
        assertEquals("இசை தொடர்கிறது", parsed.cues[1].text)
    }

    @Test
    fun lockScreenLyricsFormatter_supportsCompactAndExpandedWithPrevCurrentTranslationNext() {
        val expanded = LockScreenLyricsNotificationController.formatDisplayContent(
            episodeId = "ep1",
            songTitle = "Anbae Anbae",
            artistName = "Hariharan",
            albumTitle = "Jeans",
            currentLyric = "அன்பே அன்பே கொல்லாதே",
            nextLyric = "கண்ணே கண்ணே செல்லாதே",
            isPaused = false,
            previousLyric = "முதல் வரி",
            translationLyric = "My love, don't tease me",
            expandedMode = true,
        )
        assertEquals("Anbae Anbae · Hariharan", expanded.title)
        assertEquals("♪ அன்பே அன்பே கொல்லாதே", expanded.collapsedLine)
        assertEquals(
            "முதல் வரி\n♪ அன்பே அன்பே கொல்லாதே\nMy love, don't tease me\nகண்ணே கண்ணே செல்லாதே",
            expanded.expandedBody,
        )

        val compact = LockScreenLyricsNotificationController.formatDisplayContent(
            episodeId = "ep1",
            songTitle = "Anbae Anbae",
            artistName = "Hariharan",
            albumTitle = "Jeans",
            currentLyric = "அன்பே அன்பே கொல்லாதே",
            nextLyric = "கண்ணே கண்ணே செல்லாதே",
            isPaused = false,
            previousLyric = "முதல் வரி",
            translationLyric = "My love, don't tease me",
            expandedMode = false,
        )
        assertEquals("♪ அன்பே அன்பே கொல்லாதே", compact.expandedBody)
        assertTrue(compact.renderKey != expanded.renderKey)

        // Default user preferences enable mini player lyrics, expanded lock screen lyrics, and translations
        val defaults = PodcastUserSettings()
        assertTrue(defaults.showLockScreenLyrics)
        assertTrue(defaults.lockScreenLyricsExpanded)
        assertTrue(defaults.showMiniPlayerLyrics)
        assertTrue(defaults.showLyricsTranslation)
    }

    @Test
    fun backgroundTickerDelay_usesCueDeadlineWhenScreenOnAndSleepsWhenScreenOff() {
        // 1. Foreground UI active -> fast responsive cadence scaled by playback speed
        assertEquals(
            180L,
            computeBackgroundTickerDelayMs(
                hasUiCollectors = true,
                isScreenInteractive = true,
                lockScreenLyricsActive = true,
                currentPositionMs = 5_000L,
                nextCueStartMs = 10_000L,
                playbackSpeed = 1.0f,
            ),
        )
        assertEquals(
            90L,
            computeBackgroundTickerDelayMs(
                hasUiCollectors = true,
                isScreenInteractive = true,
                lockScreenLyricsActive = true,
                currentPositionMs = 5_000L,
                nextCueStartMs = 10_000L,
                playbackSpeed = 2.0f,
            ),
        )

        // 2. Background + screen ON + timed lock-screen lyrics -> sleeps directly to next cue boundary
        assertEquals(
            2_400L,
            computeBackgroundTickerDelayMs(
                hasUiCollectors = false,
                isScreenInteractive = true,
                lockScreenLyricsActive = true,
                currentPositionMs = 10_000L,
                nextCueStartMs = 12_400L,
                playbackSpeed = 1.0f,
            ),
        )
        // At 2.0x speed, 2400ms of track time takes 1200ms wall-clock time
        assertEquals(
            1_200L,
            computeBackgroundTickerDelayMs(
                hasUiCollectors = false,
                isScreenInteractive = true,
                lockScreenLyricsActive = true,
                currentPositionMs = 10_000L,
                nextCueStartMs = 12_400L,
                playbackSpeed = 2.0f,
            ),
        )

        // 3. Screen OFF -> long 15s battery-saving sleep (zero lyric polling!)
        assertEquals(
            15_000L,
            computeBackgroundTickerDelayMs(
                hasUiCollectors = false,
                isScreenInteractive = false,
                lockScreenLyricsActive = true,
                currentPositionMs = 10_000L,
                nextCueStartMs = 10_500L,
                playbackSpeed = 1.0f,
            ),
        )
        // 4. Screen ON but no timed lock-screen lyrics active -> long 15s idle sleep
        assertEquals(
            15_000L,
            computeBackgroundTickerDelayMs(
                hasUiCollectors = false,
                isScreenInteractive = true,
                lockScreenLyricsActive = false,
                currentPositionMs = 10_000L,
                nextCueStartMs = null,
                playbackSpeed = 1.0f,
            ),
        )
    }
}
