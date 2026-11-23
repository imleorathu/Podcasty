/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app

import com.podcasty.app.streaming.CaptionParser
import com.podcasty.app.streaming.LyricsSyncEngine
import com.podcasty.app.streaming.PodcastSourceResolver
import com.podcasty.app.streaming.ResolvedPodcastSource
import com.podcasty.app.streaming.SourceResolutionException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastStreamingTest {
    private val resolver = PodcastSourceResolver()

    @Test
    fun sourceResolver_validatesStandardAndShortYouTubeUrlsAndRejectsInvalidOrInsecure() = runTest {
        // 1. Valid standard YouTube URL
        assertEquals(
            "dQw4w9WgXcQ",
            resolver.extractValidatedYouTubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=30s"),
        )
        // 2. Short youtu.be URL
        assertEquals(
            "jNQXAC9IVRw",
            resolver.extractValidatedYouTubeVideoId("https://youtu.be/jNQXAC9IVRw?si=abc"),
        )
        // Live & Music URLs
        assertEquals(
            "jfKfPfyJRdk",
            resolver.extractValidatedYouTubeVideoId("https://www.youtube.com/live/jfKfPfyJRdk"),
        )
        assertEquals(
            "dQw4w9WgXcQ",
            resolver.extractValidatedYouTubeVideoId("https://music.youtube.com/watch?v=dQw4w9WgXcQ"),
        )

        // 3. Invalid URLs & non-HTTPS URLs rejected securely
        assertNull(resolver.extractValidatedYouTubeVideoId("https://www.youtube.com/watch?v=short"))
        assertNull(resolver.extractValidatedYouTubeVideoId("not a url"))
        assertNull(resolver.extractValidatedYouTubeVideoId("http://www.youtube.com/watch?v=dQw4w9WgXcQ"))

        val insecureRes = resolver.resolve("http://example.com/episode.mp3")
        assertTrue(insecureRes.isFailure)
        assertTrue(insecureRes.exceptionOrNull() is SourceResolutionException.InsecureUrl)

        val emptyRes = resolver.resolve("   ")
        assertTrue(emptyRes.isFailure)
        assertTrue(emptyRes.exceptionOrNull() is SourceResolutionException.InvalidUrl)

        // Direct HTTPS audio URL & publisher VTT/SRT URL classification
        val directMp3 = resolver.resolve("https://cdn.example.com/podcasts/ep01-intro.mp3").getOrThrow()
        assertTrue(directMp3 is ResolvedPodcastSource.DirectAudioStream)
        assertEquals("ep01 intro", (directMp3 as ResolvedPodcastSource.DirectAudioStream).title)

        val vttRes = resolver.resolve("https://cdn.example.com/transcripts/ep01.vtt").getOrThrow()
        assertTrue(vttRes is ResolvedPodcastSource.PublisherTranscriptUrl)
        assertEquals("vtt", (vttRes as ResolvedPodcastSource.PublisherTranscriptUrl).formatHint)
    }

    @Test
    fun captionParserAndLyricsSyncEngine_handleVttSrtPauseResumeAndSeeking() {
        // Publisher WebVTT transcript
        val vtt = """
            WEBVTT

            00:00.000 --> 00:04.000
            <v Host>Welcome to today's episode.

            00:04.000 --> 00:09.500
            <v Guest>Glad to be here discussing audio engineering.

            00:09.500 --> 00:15.000
            <v Host>Let's dive right into synchronized captions.
        """.trimIndent()
        val vttDoc = CaptionParser.parseTranscript("ep_vtt", vtt, "ep.vtt").getOrThrow()
        assertEquals(3, vttDoc.cues.size)
        assertEquals("Host", vttDoc.cues[0].speaker)

        // Publisher SRT transcript
        val srt = """
            1
            00:00:01,000 --> 00:00:05,000
            First SRT caption sentence.

            2
            00:00:05,000 --> 00:00:10,000
            Second SRT caption sentence.
        """.trimIndent()
        val srtDoc = CaptionParser.parseTranscript("ep_srt", srt, "ep.srt").getOrThrow()
        assertEquals(2, srtDoc.cues.size)

        // Reject empty transcript cleanly
        assertTrue(CaptionParser.parseTranscript("empty", "   ", "empty.vtt").isFailure)

        // Pause-aware LyricsSyncEngine test
        val sync = LyricsSyncEngine()
        sync.load(vttDoc, initialPositionMs = 0L)
        assertEquals(0, sync.snapshot.value.activeCueIndex)

        // While paused, background position ticks must NOT emit active cue changes
        sync.onPlaybackStateChanged(isPlaying = false, positionMs = 0L)
        val frozen = sync.onPositionTick(6_000L)
        assertEquals(0, frozen.activeCueIndex)

        // Seeking while paused immediately updates active cue
        val sought = sync.onSeek(10_200L)
        assertEquals(2, sought.activeCueIndex)
        assertEquals("Let's dive right into synchronized captions.", sought.activeCue?.text)

        // Resuming playback enables live synchronization again
        sync.onPlaybackStateChanged(isPlaying = true, positionMs = 5_000L)
        val resumed = sync.onPositionTick(5_500L)
        assertEquals(1, resumed.activeCueIndex)
        assertEquals("Guest", resumed.activeCue?.speaker)
    }
}
