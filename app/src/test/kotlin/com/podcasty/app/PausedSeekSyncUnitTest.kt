/*
 * Podcasty — Unit Tests for Paused & Playing Progress Bar Seek Synchronization
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.Player
import com.podcasty.app.domain.PlaybackItem
import com.podcasty.app.domain.PlaybackState
import com.podcasty.app.features.player.PlayerSeekState
import com.podcasty.app.features.player.hasConfirmedSeekPosition
import com.podcasty.app.player.resolveGuardedSeekPosition
import com.podcasty.app.player.shouldSuppressSeekRebuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PausedSeekSyncUnitTest {

    private fun sampleItem(id: String = "track_1", durationMs: Long = 240_000L) = PlaybackItem(
        episodeId = id,
        podcastId = "album_1",
        hostId = "artist_1",
        title = "Sample Track",
        podcastTitle = "Sample Album",
        hostName = "Sample Artist",
        artworkAsset = null,
        audioAsset = "sample.mp3",
        durationMs = durationMs,
    )

    @Test
    fun `shouldSuppressSeekRebuffer suppresses STATE_BUFFERING for BOTH Paused and Playing states`() {
        val item = sampleItem()
        val paused = PlaybackState.Paused(item, positionMs = 30_000L, durationMs = 240_000L, playbackSpeed = 1f)
        val playing = PlaybackState.Playing(item, positionMs = 30_000L, durationMs = 240_000L, playbackSpeed = 1f)
        val preparing = PlaybackState.Preparing(item)

        assertTrue(
            shouldSuppressSeekRebuffer(
                playbackState = Player.STATE_BUFFERING,
                nowElapsedMs = 1_000L,
                suppressUntilElapsedMs = 1_800L,
                currentState = paused,
                currentItemId = item.episodeId,
            ),
        )
        assertTrue(
            shouldSuppressSeekRebuffer(
                playbackState = Player.STATE_BUFFERING,
                nowElapsedMs = 1_000L,
                suppressUntilElapsedMs = 1_800L,
                currentState = playing,
                currentItemId = item.episodeId,
            ),
        )
        // Must NOT suppress after timeout expires, for a different track, or during initial Preparing
        assertFalse(
            shouldSuppressSeekRebuffer(
                playbackState = Player.STATE_BUFFERING,
                nowElapsedMs = 2_000L,
                suppressUntilElapsedMs = 1_800L,
                currentState = paused,
                currentItemId = item.episodeId,
            ),
        )
        assertFalse(
            shouldSuppressSeekRebuffer(
                playbackState = Player.STATE_BUFFERING,
                nowElapsedMs = 1_000L,
                suppressUntilElapsedMs = 1_800L,
                currentState = paused,
                currentItemId = "other_track",
            ),
        )
        assertFalse(
            shouldSuppressSeekRebuffer(
                playbackState = Player.STATE_BUFFERING,
                nowElapsedMs = 1_000L,
                suppressUntilElapsedMs = 1_800L,
                currentState = preparing,
                currentItemId = item.episodeId,
            ),
        )
    }

    @Test
    fun `resolveGuardedSeekPosition blocks stale pre-seek position during guard window and confirms on match`() {
        // User tapped 120_000ms while paused at 10_000ms; Media3 briefly still reports 10_000ms
        val staleResult = resolveGuardedSeekPosition(
            rawPositionMs = 10_000L,
            durationMs = 240_000L,
            guardTargetMs = 120_000L,
            nowElapsedMs = 5_100L,
            guardUntilElapsedMs = 5_750L,
        )
        assertEquals(120_000L, staleResult.positionMs)
        assertFalse(staleResult.matchedTarget)
        assertFalse(staleResult.guardCleared)

        // Rapid second tap to 180_000ms while late callback for 120_000ms arrives -> stays at 180_000ms
        val supersededResult = resolveGuardedSeekPosition(
            rawPositionMs = 120_000L,
            durationMs = 240_000L,
            guardTargetMs = 180_000L,
            nowElapsedMs = 5_200L,
            guardUntilElapsedMs = 5_950L,
        )
        assertEquals(180_000L, supersededResult.positionMs)
        assertFalse(supersededResult.guardCleared)

        // Media3 finishes seeking to 180_120ms -> confirmed & guard cleared
        val confirmedResult = resolveGuardedSeekPosition(
            rawPositionMs = 180_120L,
            durationMs = 240_000L,
            guardTargetMs = 180_000L,
            nowElapsedMs = 5_250L,
            guardUntilElapsedMs = 5_950L,
        )
        assertEquals(180_120L, confirmedResult.positionMs)
        assertTrue(confirmedResult.matchedTarget)
        assertTrue(confirmedResult.guardCleared)

        // After guard expires, Media3's actual position is accepted
        val expiredResult = resolveGuardedSeekPosition(
            rawPositionMs = 175_000L,
            durationMs = 240_000L,
            guardTargetMs = 180_000L,
            nowElapsedMs = 6_200L,
            guardUntilElapsedMs = 5_950L,
        )
        assertEquals(175_000L, expiredResult.positionMs)
        assertTrue(expiredResult.guardCleared)
    }

    @Test
    fun `short track confirmation tolerance scales properly and unknown duration is handled safely`() {
        // 4-second short track: seeking from 0ms to 2000ms must NOT prematurely confirm at 0ms
        assertFalse(hasConfirmedSeekPosition(seekFraction = 0.5f, playbackPositionMs = 0L, durationMs = 4_000L, explicitTargetMs = 2_000L))
        assertTrue(hasConfirmedSeekPosition(seekFraction = 0.5f, playbackPositionMs = 1_900L, durationMs = 4_000L, explicitTargetMs = 2_000L))

        // Long 2-hour YouTube podcast
        assertTrue(
            hasConfirmedSeekPosition(
                seekFraction = 0.5f,
                playbackPositionMs = 3_600_400L,
                durationMs = 7_200_000L,
                explicitTargetMs = 3_600_000L,
            ),
        )

        // Unknown / zero duration never produces NaN or crashes
        assertFalse(hasConfirmedSeekPosition(seekFraction = 0.5f, playbackPositionMs = 0L, durationMs = 0L))
        val seekState = PlayerSeekState(
            isScrubbing = mutableStateOf(false),
            pendingFraction = mutableStateOf(null),
            pendingTargetMs = mutableStateOf(null),
            pendingGeneration = mutableLongStateOf(0L),
            awaitingConfirmation = mutableStateOf(false),
        )
        seekState.updateScrub(Float.NaN)
        assertEquals(0f, seekState.pendingFraction.value)
        assertNull(seekState.commitScrub(0L))
        assertFalse(seekState.isScrubbing.value)
    }
}
