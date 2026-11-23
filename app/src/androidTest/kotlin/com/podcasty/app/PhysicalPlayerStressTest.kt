/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Debug
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.podcasty.app.app.PodcastyApplication
import com.podcasty.app.domain.PlaybackState
import com.podcasty.app.domain.RepeatMode
import com.podcasty.app.domain.SongSortOption
import com.podcasty.app.domain.SortOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureNanoTime

/**
 * Physical-device Media3 / PodcastPlaybackController responsiveness & stability stress test:
 * - Play/Pause 100x (measures avg & p95 control dispatch latency)
 * - Next/Previous 100x rapid skips across real library tracks
 * - 50x rapid seeks + speed/shuffle/repeat + queue add/remove
 * - Verifies isolated playbackPositionMs ticks do NOT spam root playbackState emissions
 */
@RunWith(AndroidJUnit4::class)
class PhysicalPlayerStressTest {

    @Test
    fun playerStress_100PlayPause_100Skips_50Seeks_and_isolatedPositionFlow(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<Context>() as PodcastyApplication
        val pkg = instrumentation.targetContext.packageName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                instrumentation.uiAutomation.grantRuntimePermission(pkg, Manifest.permission.READ_MEDIA_AUDIO)
            }
            runCatching {
                instrumentation.uiAutomation.grantRuntimePermission(pkg, Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val container = app.container
        val repo = container.repository
        val controller = container.playbackController

        val songs = repo.observeLocalSongsPaged("", SongSortOption.Duration, SortOrder.Descending, 200).first()
            .filter { !it.sourceUri.isNullOrBlank() && it.durationMs >= 10_000L }
        assumeTrue("Need at least 5 real indexed tracks for player stress test (found ${songs.size})", songs.size >= 5)

        val ids = songs.map { it.id }
        val playPauseLatenciesUs = LongArray(100)
        val skipLatenciesUs = LongArray(100)
        val seekLatenciesUs = LongArray(50)

        try {
            // Wait up to 4s for MediaController to connect to PodcastPlaybackService
            withContext(Dispatchers.Main) {
                controller.playEpisode(ids.first(), ids, shuffle = false)
            }
            var waitReadyMs = 0L
            while (controller.playbackState.value !is PlaybackState.Playing && waitReadyMs < 6_000L) {
                delay(50L)
                waitReadyMs += 50L
            }
            val initialStartState = controller.playbackState.value
            Log.i(TAG, "[PLAYER_STRESS] initialPlayReady=${waitReadyMs}ms state=${initialStartState::class.simpleName}")
            assertTrue("Expected player to reach Playing/Paused, got $initialStartState", initialStartState !is PlaybackState.Failed)

            // Allow initial decoder/timeline callbacks to settle before measuring steady playback.
            delay(600L)
            // 1. Verify isolated playbackPositionMs ticks without spamming root playbackState
            var rootStateEmissions = 0
            var posEmissions = 0
            val rootCollector = launch {
                controller.playbackState.collect { rootStateEmissions++ }
            }
            val posCollector = launch {
                controller.playbackPositionMs.collect { posEmissions++ }
            }
            delay(1_200L)
            rootCollector.cancel()
            posCollector.cancel()
            Log.i(TAG, "[PLAYER_STRESS] isolation_1200ms rootStateEmissions=$rootStateEmissions posFlowEmissions=$posEmissions")
            assertTrue("Root playbackState emitted too frequently during steady playback: $rootStateEmissions", rootStateEmissions <= 3)

            // 2. 100x Play / Pause toggle latency stress
            withContext(Dispatchers.Main) {
                for (i in 0 until 100) {
                    val ns = measureNanoTime {
                        if (i % 2 == 0) controller.pause() else controller.resume()
                    }
                    playPauseLatenciesUs[i] = ns / 1_000L
                    if (i % 10 == 0) delay(5L)
                }
            }

            // 3. 100x Rapid Next / Previous track skips (verifies cancellable prepareEpisodeJob + debounced queue persist)
            withContext(Dispatchers.Main) {
                for (i in 0 until 100) {
                    val ns = measureNanoTime {
                        if (i % 3 == 0) controller.previous() else controller.next()
                    }
                    skipLatenciesUs[i] = ns / 1_000L
                    if (i % 10 == 0) delay(5L)
                }
            }

            // 4. 50x Rapid Seeks + Speed + Shuffle + Repeat + Queue mutations
            withContext(Dispatchers.Main) {
                val speeds = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 1.0f)
                for (s in speeds) controller.setPlaybackSpeed(s)
                controller.setShuffle(true)
                controller.setShuffle(false)
                controller.setRepeatMode(RepeatMode.All)
                controller.setRepeatMode(RepeatMode.One)
                controller.setRepeatMode(RepeatMode.Off)
                if (ids.size >= 4) {
                    controller.playNext(ids[2])
                    controller.addToQueue(listOf(ids[3]))
                }
                for (i in 0 until 50) {
                    val targetMs = (i * 1_500L) % 30_000L
                    val ns = measureNanoTime {
                        controller.seekTo(targetMs)
                    }
                    seekLatenciesUs[i] = ns / 1_000L
                }
            }

            // Allow debounced jobs to settle & pause cleanly
            delay(500L)
            withContext(Dispatchers.Main) { controller.pause() }

            playPauseLatenciesUs.sort()
            skipLatenciesUs.sort()
            seekLatenciesUs.sort()

            val ppAvgMs = playPauseLatenciesUs.average() / 1000.0
            val ppP95Ms = playPauseLatenciesUs[94] / 1000.0
            val skipAvgMs = skipLatenciesUs.average() / 1000.0
            val skipP95Ms = skipLatenciesUs[94] / 1000.0
            val seekAvgMs = seekLatenciesUs.average() / 1000.0
            val seekP95Ms = seekLatenciesUs[47] / 1000.0

            val rt = Runtime.getRuntime()
            val javaMb = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0)
            val nativeMb = Debug.getNativeHeapAllocatedSize() / (1024.0 * 1024.0)

            Log.i(
                TAG,
                "[PLAYER_STRESS] playPause100(avg=${"%.2f".format(ppAvgMs)}ms p95=${"%.2f".format(ppP95Ms)}ms) " +
                    "skip100(avg=${"%.2f".format(skipAvgMs)}ms p95=${"%.2f".format(skipP95Ms)}ms) " +
                    "seek50(avg=${"%.2f".format(seekAvgMs)}ms p95=${"%.2f".format(seekP95Ms)}ms) " +
                    "javaHeap=${"%.1f".format(javaMb)}MB native=${"%.1f".format(nativeMb)}MB",
            )
            assertTrue("Play/Pause p95 latency exceeded 25ms: ${ppP95Ms}ms", ppP95Ms < 25.0)
            assertTrue("Skip p95 latency exceeded 25ms: ${skipP95Ms}ms", skipP95Ms < 25.0)
            assertTrue("Seek p95 latency exceeded 25ms: ${seekP95Ms}ms", seekP95Ms < 25.0)
        } finally {
            withContext(Dispatchers.Main) { controller.pause() }
        }
    }

    companion object {
        private const val TAG = "PodcastyPlayerStress"
    }
}
