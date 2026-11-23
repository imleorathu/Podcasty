/*
 * Podcasty — Physical Device End-to-End Verification for Paused & Playing Progress Bar Seeking
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.podcasty.app.app.PodcastyApplication
import com.podcasty.app.domain.PlaybackState
import com.podcasty.app.domain.SongSortOption
import com.podcasty.app.domain.SortOrder
import com.podcasty.app.domain.durationMsOrZero
import com.podcasty.app.domain.itemOrNull
import com.podcasty.app.domain.positionMsOrZero
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class PhysicalPausedSeekBarTest {

    private val tag = "PausedSeekTest"

    private fun app(): PodcastyApplication =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PodcastyApplication

    private suspend fun waitForPlayingOrPaused(
        episodeId: String,
        wantPlaying: Boolean,
        timeoutMs: Long = 10_000L,
    ): PlaybackState {
        val controller = app().container.playbackController
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val st = controller.playbackState.value
            val matched = if (wantPlaying) {
                st is PlaybackState.Playing && st.item.episodeId == episodeId && st.durationMs > 0L
            } else {
                st is PlaybackState.Paused && st.item.episodeId == episodeId && st.durationMs > 0L
            }
            if (matched) return st
            delay(100)
        }
        return controller.playbackState.value
    }

    @Test
    fun test01_pausedSeekNeverFlashesPreparingNeverResumesAndPlayResumesFromTarget(): Unit = runBlocking {
        val container = app().container
        val repo = container.repository
        val playback = container.playbackController

        val songs = repo.observeLocalSongsPaged(
            query = "",
            sortOption = SongSortOption.Duration,
            sortOrder = SortOrder.Descending,
            limit = 20,
        ).first()
        val track = songs.firstOrNull { it.durationMs >= 60_000L } ?: songs.first()
        assertNotNull("Expected a local music track", track)

        // 1. Start playing local track
        withContext(Dispatchers.Main) {
            playback.playEpisode(track.id, listOf(track.id), false)
        }
        val playingState = waitForPlayingOrPaused(track.id, wantPlaying = true)
        assertTrue("Track should reach Playing state, got $playingState", playingState is PlaybackState.Playing)
        val durationMs = playingState.durationMsOrZero()
        assertTrue("Expected valid duration > 30s, got $durationMs", durationMs > 30_000L)

        // 2. Test seeking while PLAYING
        val playSeekTarget = (durationMs * 0.25).toLong()
        val playingFlashCount = java.util.concurrent.atomic.AtomicInteger(0)
        val collectorJob = launch {
            playback.playbackState.collect { st ->
                if (st is PlaybackState.Preparing) playingFlashCount.incrementAndGet()
            }
        }
        delay(50)
        withContext(Dispatchers.Main) { playback.seekTo(playSeekTarget) }
        delay(450)
        assertTrue("Must remain Playing after seek while playing", playback.playbackState.value is PlaybackState.Playing)
        val posAfterPlaySeek = playback.playbackPositionMs.value
        assertTrue(
            "Playing seek should advance near $playSeekTarget, got $posAfterPlaySeek",
            abs(posAfterPlaySeek - playSeekTarget) <= 2_500L,
        )
        assertEquals("Playing seek must not flash Preparing state", 0, playingFlashCount.get())

        // 3. PAUSE music
        withContext(Dispatchers.Main) { playback.pause() }
        val pausedState = waitForPlayingOrPaused(track.id, wantPlaying = false)
        assertTrue("Track should be Paused, got $pausedState", pausedState is PlaybackState.Paused)
        delay(200)

        // 4. Tap multiple positions while PAUSED (including rapid seeks, near beginning, near end)
        val pausedPreparingFlashes = java.util.concurrent.atomic.AtomicInteger(0)
        val pausedUnexpectedPlay = java.util.concurrent.atomic.AtomicInteger(0)
        val pausedJob = launch {
            playback.playbackState.collect { st ->
                if (st is PlaybackState.Preparing) pausedPreparingFlashes.incrementAndGet()
                if (st is PlaybackState.Playing) pausedUnexpectedPlay.incrementAndGet()
            }
        }
        delay(50)

        val targets = listOf(
            (durationMs * 0.60).toLong(),
            (durationMs * 0.15).toLong(),
            0L, // Beginning of track
            (durationMs - 1_500L).coerceAtLeast(0L), // Near end of track
            (durationMs * 0.72).toLong(), // Final paused target
        )
        for (target in targets) {
            withContext(Dispatchers.Main) { playback.seekTo(target) }
            // Immediate check right after seekTo (before async Media3 settles)
            assertEquals("Immediate playbackPositionMs must equal tapped target", target, playback.playbackPositionMs.value)
            assertEquals("Immediate Paused.positionMs must equal tapped target", target, playback.playbackState.value.positionMsOrZero())
            delay(180)
            // After Media3 settles, position must NOT revert and playback must stay PAUSED
            val settled = playback.playbackPositionMs.value
            assertTrue(
                "Settled paused position must stay near $target (no snap-back), got $settled",
                abs(settled - target) <= 1_500L,
            )
            assertTrue("Must remain Paused after seeking to $target, got ${playback.playbackState.value}", playback.playbackState.value is PlaybackState.Paused)
        }

        // Rapid repeated seeks while paused (no delay between calls)
        val rapidTargets = listOf(
            (durationMs * 0.20).toLong(),
            (durationMs * 0.80).toLong(),
            (durationMs * 0.45).toLong(),
        )
        withContext(Dispatchers.Main) {
            rapidTargets.forEach { playback.seekTo(it) }
        }
        val finalPausedTarget = rapidTargets.last()
        delay(600)
        assertTrue("Must remain Paused after rapid seeking", playback.playbackState.value is PlaybackState.Paused)
        val posAfterRapid = playback.playbackPositionMs.value
        assertTrue(
            "Position after rapid paused seeks must settle at final target $finalPausedTarget, got $posAfterRapid",
            abs(posAfterRapid - finalPausedTarget) <= 1_500L,
        )
        assertEquals("Paused seeking must NEVER flash Preparing state", 0, pausedPreparingFlashes.get())
        assertEquals("Paused seeking must NEVER auto-resume playback", 0, pausedUnexpectedPlay.get())

        pausedJob.cancel()
        collectorJob.cancel()

        // 5. Press PLAY and verify playback resumes from finalPausedTarget (NOT old pre-seek position!)
        withContext(Dispatchers.Main) { playback.resume() }
        val resumedState = waitForPlayingOrPaused(track.id, wantPlaying = true)
        assertTrue("Pressing Play must resume playback, got $resumedState", resumedState is PlaybackState.Playing)
        delay(500)
        val resumedPos = playback.playbackPositionMs.value
        assertTrue(
            "Playback must resume from $finalPausedTarget (+elapsed), got $resumedPos",
            resumedPos in (finalPausedTarget - 1_500L)..(finalPausedTarget + 4_000L),
        )

        // Pause cleanly
        withContext(Dispatchers.Main) { playback.pause() }
        waitForPlayingOrPaused(track.id, wantPlaying = false)
        Log.i(tag, "TEST01_PASS track=${track.title} dur=$durationMs finalPausedTarget=$finalPausedTarget resumedPos=$resumedPos")
    }

    @Test
    fun test02_shortTrackLongYouTubePodcastAndTrackSwitchingPausedSeek(): Unit = runBlocking {
        val container = app().container
        val repo = container.repository
        val playback = container.playbackController

        // Find a playable short local track (15s..90s) and the long downloaded YouTube podcast (> 30 min)
        val shortSongs = repo.observeLocalSongsPaged(
            query = "",
            sortOption = SongSortOption.Duration,
            sortOrder = SortOrder.Ascending,
            limit = 50,
            minDurationMs = 15_000L,
            maxDurationMs = 90_000L,
        ).first()
        val shortTrack = shortSongs.firstOrNull { ep ->
            val ext = ep.audioAsset.substringAfterLast('.', "").lowercase()
            ext in setOf("mp3", "m4a", "flac", "ogg", "wav")
        } ?: shortSongs.first()
        val ytPodcast = repo.observePodcastEpisodes(limit = 10).first().firstOrNull { it.isDownloaded }
        assertNotNull("Expected downloaded YouTube podcast for long-file seek test", ytPodcast)

        // A. Test short local track: play -> pause -> seek to 50% -> seek to exact end (must NOT auto-resume!) -> seek to 40% -> play
        withContext(Dispatchers.Main) {
            playback.playEpisode(shortTrack.id, listOf(shortTrack.id, ytPodcast!!.id), false)
        }
        val shortPlaying = waitForPlayingOrPaused(shortTrack.id, wantPlaying = true)
        assertTrue("Short track (${shortTrack.title}, ${shortTrack.audioAsset}) should play, got $shortPlaying", shortPlaying is PlaybackState.Playing)
        val shortDur = shortPlaying.durationMsOrZero()

        withContext(Dispatchers.Main) { playback.pause() }
        waitForPlayingOrPaused(shortTrack.id, wantPlaying = false)
        delay(150)

        // Seek to exact end while paused -> MUST NOT auto-advance or auto-resume!
        withContext(Dispatchers.Main) { playback.seekTo(shortDur) }
        delay(500)
        assertEquals("Seeking to end while paused must stay on same track", shortTrack.id, playback.playbackState.value.itemOrNull()?.episodeId)
        assertTrue("Seeking to end while paused must stay Paused", playback.playbackState.value is PlaybackState.Paused)

        // Seek back to 40% while paused -> press play -> resumes from 40%
        val shortTarget = (shortDur * 0.40).toLong()
        withContext(Dispatchers.Main) { playback.seekTo(shortTarget) }
        delay(350)
        assertTrue("Short track must stay Paused at 40%", playback.playbackState.value is PlaybackState.Paused)
        assertTrue(abs(playback.playbackPositionMs.value - shortTarget) <= 1_500L)

        // B. Switch to long downloaded YouTube podcast -> play -> pause -> seek to 50% -> verify immediate sync + stay paused -> resume
        withContext(Dispatchers.Main) {
            playback.playEpisode(ytPodcast!!.id, listOf(ytPodcast.id), false)
        }
        val ytPlaying = waitForPlayingOrPaused(ytPodcast!!.id, wantPlaying = true)
        assertTrue("YouTube podcast should play, got $ytPlaying", ytPlaying is PlaybackState.Playing)
        val ytDur = ytPlaying.durationMsOrZero()
        assertTrue("Expected long YouTube podcast duration > 10 min, got $ytDur", ytDur > 600_000L)

        withContext(Dispatchers.Main) { playback.pause() }
        waitForPlayingOrPaused(ytPodcast.id, wantPlaying = false)
        delay(150)

        val ytTarget = (ytDur * 0.50).toLong()
        withContext(Dispatchers.Main) { playback.seekTo(ytTarget) }
        assertEquals("Long podcast immediate seek position must match", ytTarget, playback.playbackPositionMs.value)
        delay(500)
        assertTrue("Long podcast must remain Paused after seek", playback.playbackState.value is PlaybackState.Paused)
        assertTrue(
            "Long podcast settled position must match $ytTarget, got ${playback.playbackPositionMs.value}",
            abs(playback.playbackPositionMs.value - ytTarget) <= 1_500L,
        )

        withContext(Dispatchers.Main) { playback.resume() }
        waitForPlayingOrPaused(ytPodcast.id, wantPlaying = true)
        delay(450)
        val ytResumedPos = playback.playbackPositionMs.value
        assertTrue(
            "Long podcast must resume near $ytTarget, got $ytResumedPos",
            ytResumedPos in (ytTarget - 1_500L)..(ytTarget + 4_000L),
        )

        withContext(Dispatchers.Main) { playback.pause() }
        waitForPlayingOrPaused(ytPodcast.id, wantPlaying = false)
        Log.i(tag, "TEST02_PASS short=${shortTrack.title} (${shortDur}ms) long=${ytPodcast.title} (${ytDur}ms) ytResumed=$ytResumedPos")
    }
}
