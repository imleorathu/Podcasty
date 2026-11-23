package com.podcasty.app

import com.podcasty.app.domain.SleepTimerPreset
import com.podcasty.app.domain.SleepTimerRequest
import com.podcasty.app.player.PlaybackVolumeFader
import com.podcasty.app.player.SleepTimerManager
import com.podcasty.app.features.player.nextSleepClockDeadline
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackEnvelopeAndTimerTest {
    @Test fun rapidFadeReversalCancelsPendingPauseAndNeverJumps() = runTest {
        val volumes = mutableListOf<Float>()
        var pauses = 0
        val fader = PlaybackVolumeFader(this, { testScheduler.currentTime }, { volumes += it })
        fader.fadeTo(0f, 400) { pauses++ }
        advanceTimeBy(200)
        val before = fader.gain
        fader.fadeTo(1f, 400)
        runCurrent()
        assertEquals(before, fader.gain, 0.001f)
        advanceTimeBy(500)
        assertEquals(0, pauses)
        assertEquals(1f, fader.gain, 0.001f)
        assertTrue(volumes.all { it in 0f..1f })
    }

    @Test fun replacementAndCancellationNeverFireOldTimer() = runTest {
        var pauses = 0
        var volume = 1f
        val manager = SleepTimerManager(this, { testScheduler.currentTime }, { volume = it }, { pauses++ }, { 1_000_000L })
        manager.configure(SleepTimerRequest(SleepTimerPreset.ClockTime, deadlineEpochMs = 1_002_000L, fadeOutSeconds = 1))
        advanceTimeBy(1000)
        manager.configure(SleepTimerRequest(SleepTimerPreset.EndOfEpisode))
        advanceTimeBy(3000)
        assertEquals(0, pauses)
        manager.onPlaybackProgress(95_000, 100_000, 1f)
        assertTrue(volume < 1f)
        manager.cancel()
        advanceTimeBy(60_000)
        assertEquals(0, pauses)
        assertEquals(1f, volume, 0.001f)
        assertFalse(manager.onTrackCompleted())
    }

    @Test fun restoredDeadlineUsesRemainingTimeAndExpiresOnce() = runTest {
        var pauses = 0
        val manager = SleepTimerManager(this, { testScheduler.currentTime }, {}, { pauses++ }, { 100_000L })
        manager.configure(SleepTimerRequest(SleepTimerPreset.ThirtyMin, deadlineEpochMs = 102_000L))
        assertEquals(2, manager.state.value.remainingSeconds)
        advanceTimeBy(3000)
        assertEquals(1, pauses)
        assertFalse(manager.state.value.isActive)
        assertNull(manager.deadlineEpochMs)
        manager.configure(SleepTimerRequest(SleepTimerPreset.ClockTime, deadlineEpochMs = 99_000L))
        runCurrent()
        assertFalse(manager.state.value.isActive)
        assertEquals(1, pauses)
    }

    @Test fun trackTimerFadesAtPlaybackSpeedAndSeekRestoresVolume() = runTest {
        var volume = 1f
        val manager = SleepTimerManager(this, { 0L }, { volume = it }, {})
        manager.configure(SleepTimerRequest(SleepTimerPreset.AfterTracks, trackCount = 2, fadeOutSeconds = 10))
        manager.onPlaybackProgress(99_000, 100_000, 1f)
        assertEquals(1f, volume, 0f)
        assertFalse(manager.onTrackCompleted())
        manager.onPlaybackProgress(90_000, 100_000, 2f)
        assertEquals(0.5f, volume, 0.001f)
        manager.onPlaybackProgress(10_000, 100_000, 1f)
        assertEquals(1f, volume, 0.001f)
        assertTrue(manager.onTrackCompleted())
        assertFalse(manager.onTrackCompleted())
    }

    @Test fun clockTimeRejectsInvalidInputAndSelectsNextOccurrence() {
        assertNull(nextSleepClockDeadline("25:00"))
        assertNull(nextSleepClockDeadline("23:60"))
        assertNull(nextSleepClockDeadline("abc"))
        val now = System.currentTimeMillis()
        val next = nextSleepClockDeadline("23:30", now)!!
        assertTrue(next > now)
        assertTrue(next - now <= 25 * 60 * 60 * 1000L)
    }
}
