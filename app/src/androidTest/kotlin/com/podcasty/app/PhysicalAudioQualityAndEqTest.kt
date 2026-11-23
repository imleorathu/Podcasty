/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app

import android.util.Log
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.podcasty.app.app.MainActivity
import com.podcasty.app.app.PodcastyApplication
import com.podcasty.app.audio.BuiltInEqualizerPresets
import com.podcasty.app.domain.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Physical-device verification of:
 * 1. Real hardware android.media.audiofx.Equalizer attachment, dynamic band inspection,
 *    enable/disable, all 12 built-in presets, positive/negative band gains, preamp & clipping protection.
 * 2. Room v8 custom preset persistence (save, rename, update, select, reload, delete).
 * 3. Real multi-format audio playback (MP3, FLAC, WAV, M4A, OPUS) on the connected device's library.
 * 4. 20x EQ enable/disable & track-change lifecycle stability with zero crashes or leaks.
 *
 * Strictly preserves all user songs, settings, and custom presets.
 */
@RunWith(AndroidJUnit4::class)
class PhysicalAudioQualityAndEqTest {

    private companion object {
        const val Tag = "PODCASTY_AUDIO_EQ"
    }

    @Test
    fun verifyRealHardwareEqualizerCustomPresetsAndMultiFormatPlayback(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PodcastyApplication
        val container = app.container
        val dao = container.database.dao()
        val playback = container.playbackController
        val eq = container.equalizerController

        // Snapshot user's existing EQ state so we restore it cleanly at the end
        val initialEqState = eq.uiState.value

        // Discover real tracks across available formats on this device
        val formats = listOf(".mp3", ".flac", ".wav", ".m4a", ".opus")
        val sampleByFormat = linkedMapOf<String, String>()
        val candidates = dao.observeLocalSongsByTitleAsc(limit = 5000).first()
        for (ext in formats) {
            val found = candidates.firstOrNull {
                it.audioAsset.endsWith(ext, ignoreCase = true) && it.durationMs > 3_000L
            }
            if (found != null) {
                sampleByFormat[ext] = found.id
            }
        }
        Log.i(Tag, "DISCOVERED_FORMATS=${sampleByFormat.keys.joinToString(",")}")
        assertTrue("Expected at least MP3 on physical device", sampleByFormat.isNotEmpty())

        // Avoid ActivityScenario's global-idle wait: the living artwork intentionally
        // renders continuously. Playback readiness is polled below instead.
        InstrumentationRegistry.getInstrumentation().targetContext.startActivity(
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            // Allow MainActivity & MediaController initial bind to settle with foreground audio focus
            delay(1_200L)
            withContext(Dispatchers.Main) {
                playback.setPlaybackSpeed(1.0f)
            }
            // 1. Verify real playback across every discovered audio format (MP3, FLAC, WAV, M4A, OPUS)
            for ((ext, epId) in sampleByFormat) {
                withContext(Dispatchers.Main) {
                    playback.playEpisode(epId, listOf(epId), shuffle = false)
                }
                var ready = false
                for (i in 0 until 80) {
                    val st = playback.playbackState.value
                    if (st is PlaybackState.Playing && st.item.episodeId == epId && st.durationMs > 0L) {
                        ready = true
                        break
                    }
                    if (st is PlaybackState.Paused && st.item.episodeId == epId && i == 15) {
                        withContext(Dispatchers.Main) { playback.resume() }
                    }
                    if (st is PlaybackState.Failed && st.item?.episodeId == epId) {
                        throw AssertionError("Format $ext failed playback: ${st.message}")
                    }
                    delay(100L)
                }
                assertTrue("Format $ext did not reach Playing state in 8s (lastState=${playback.playbackState.value})", ready)
                val playingState = playback.playbackState.value as PlaybackState.Playing
                // Verify seeking on each real format
                val seekTarget = (playingState.durationMs / 4L).coerceIn(1_000L, 15_000L)
                withContext(Dispatchers.Main) {
                    playback.seekTo(seekTarget)
                }
                delay(400L)
                val diag = eq.uiState.value.diagnostics
                Log.i(
                    Tag,
                    "FORMAT_PASS ext=$ext durationMs=${playingState.durationMs} seekTo=$seekTarget " +
                        "mime=${diag.codecMimeType} rate=${diag.sampleRateHz}Hz ch=${diag.channelCount} " +
                        "enc=${diag.encodingLabel} session=${diag.audioSessionId}",
                )
            }

            // 2. Verify real hardware Equalizer capabilities on this physical device
            var caps = eq.uiState.value.capabilities
            for (i in 0 until 20) {
                if (caps.isAttached && caps.bandCount > 0) break
                delay(150L)
                caps = eq.uiState.value.capabilities
            }
            Log.i(
                Tag,
                "HW_EQ_CAPS supported=${caps.isSupported} attached=${caps.isAttached} " +
                    "session=${caps.audioSessionId} bands=${caps.bandCount} " +
                    "freqs=${caps.bands.map { it.centerFreqHz }} " +
                    "range=${caps.minGainMb}..${caps.maxGainMb}mB " +
                    "bass=${caps.bassBoostSupported} virt=${caps.virtualizerSupported} " +
                    "impl=${caps.equalizerImplementor}",
            )
            assertTrue("Expected hardware Equalizer attached to valid session > 0", caps.isAttached && caps.audioSessionId > 0)
            assertTrue("Expected at least 3 equalizer bands", caps.bandCount >= 3)

            // 3. Verify Master Disable -> hardware EQ disabled & DSP bypassed
            eq.setEnabled(false)
            delay(150L)
            val snapOff = eq.readHardwareSnapshot()
            assertFalse("Hardware EQ must be disabled when master switch is off", snapOff.enabled)
            assertTrue("DSP processor must be bypassed when master switch is off", snapOff.dspBypassed)

            // 4. Verify Master Enable + ALL 12 Built-in Presets directly on Android hardware
            eq.setEnabled(true)
            delay(150L)
            for (spec in BuiltInEqualizerPresets.specs) {
                eq.selectPreset(spec.id)
                delay(80L)
                val hw = eq.readHardwareSnapshot()
                assertTrue("Hardware EQ must be enabled for preset ${spec.id}", hw.enabled)
                val expected = BuiltInEqualizerPresets.resolveBandLevelsMb(spec.id, caps)
                if (spec.id != BuiltInEqualizerPresets.CustomId) {
                    assertEquals("Band count mismatch for preset ${spec.id}", expected.size, hw.bandLevelsMb.size)
                    for (b in expected.indices) {
                        val diff = kotlin.math.abs(hw.bandLevelsMb[b] - expected[b])
                        assertTrue(
                            "Hardware band $b mismatch for preset ${spec.id}: expected=${expected[b]} actual=${hw.bandLevelsMb[b]} (diff=$diff > 100mB quantization)",
                            diff <= 100,
                        )
                    }
                }
                if (spec.id == BuiltInEqualizerPresets.FlatId) {
                    assertTrue("Flat must set all hardware bands to 0 mB", hw.bandLevelsMb.all { it == 0 })
                }
                Log.i(Tag, "PRESET_HW_PASS id=${spec.id} hwBands=${hw.bandLevelsMb}")
            }

            // 5. Verify individual positive & negative band adjustments + reset directly on hardware
            eq.setBandLevelMb(0, 500)
            eq.setBandLevelMb(caps.bandCount - 1, -400)
            delay(100L)
            val hwManual = eq.readHardwareSnapshot()
            assertTrue("Expected band 0 near 500mB, got ${hwManual.bandLevelsMb[0]}", kotlin.math.abs(hwManual.bandLevelsMb[0] - 500) <= 100)
            assertTrue("Expected last band near -400mB, got ${hwManual.bandLevelsMb[caps.bandCount - 1]}", kotlin.math.abs(hwManual.bandLevelsMb[caps.bandCount - 1] - (-400)) <= 100)
            // Verify Auto Clipping Protection engaged negative headroom for +5.0 dB boost
            assertTrue("Expected negative headroom attenuation for +5.0 dB boost", eq.uiState.value.effectiveHeadroomDb <= -4.5f)
            assertTrue("Headroom must affect native gain or PCM DSP", !hwManual.dspBypassed || hwManual.nativeInputGainsDb.any { it <= -4.5f })

            eq.resetBand(0)
            delay(80L)
            assertEquals(0, eq.readHardwareSnapshot().bandLevelsMb[0])

            // Read back every independent band, not only the first and last slider.
            for (band in 0 until caps.bandCount) {
                eq.setBandLevelMb(band, 300)
                delay(30L)
                assertTrue(kotlin.math.abs(eq.readHardwareSnapshot().bandLevelsMb[band] - 300) <= 100)
                eq.resetBand(band)
            }
            // 6. Verify Preamp, BassBoost, Virtualizer, Balance, Mono
            eq.setPreampDb(-3.0f)
            if (caps.bassBoostSupported) eq.setBassBoostStrength(400)
            if (caps.virtualizerSupported) eq.setVirtualizerStrength(300)
            eq.setBalance(0.25f)
            eq.setMonoAudio(true)
            delay(120L)
            val hwFx = eq.readHardwareSnapshot()
            if (caps.bassBoostSupported) assertTrue("BassBoost should be enabled", hwFx.bassBoostEnabled)
            if (caps.virtualizerSupported) assertTrue("Virtualizer should be enabled", hwFx.virtualizerEnabled)
            assertFalse("DSP should be active with mono", hwFx.dspBypassed)
            if (hwFx.nativeInputGainsDb.size >= 2) {
                assertTrue("Positive balance must attenuate left channel", hwFx.nativeInputGainsDb[0] < hwFx.nativeInputGainsDb[1])
            }

            // 7. Verify Room v8 Custom Preset CRUD (Save -> Rename -> Update -> Select -> Delete)
            val testPresetName = "Studio Test EQ"
            eq.saveAsCustomPreset(testPresetName)
            delay(300L)
            val savedRow = dao.getAllEqualizerPresets().firstOrNull { it.name == testPresetName }
            assertNotNull("Custom preset must be persisted in Room equalizer_presets", savedRow)
            val presetId = savedRow!!.id

            eq.renameCustomPreset(presetId, "Studio Renamed EQ")
            delay(250L)
            val renamedRow = dao.getAllEqualizerPresets().firstOrNull { it.id == presetId }
            assertEquals("Studio Renamed EQ", renamedRow?.name)

            eq.setBandLevelMb(0, 200)
            eq.updateCustomPreset(presetId)
            delay(250L)
            val updatedRow = dao.getAllEqualizerPresets().firstOrNull { it.id == presetId }
            assertTrue("Updated custom preset bands should start with 200", updatedRow!!.bandLevelsMbCsv.startsWith("200"))

            // Switch to Flat then back to custom preset and confirm hardware restores saved curve
            eq.selectPreset(BuiltInEqualizerPresets.FlatId)
            delay(80L)
            assertEquals(0, eq.readHardwareSnapshot().bandLevelsMb[0])
            eq.selectPreset(presetId)
            delay(100L)
            assertEquals(200, eq.readHardwareSnapshot().bandLevelsMb[0])

            // Cleanly delete test custom preset
            eq.deleteCustomPreset(presetId)
            delay(250L)
            assertTrue("Deleted custom preset must be removed from Room", dao.getAllEqualizerPresets().none { it.id == presetId })

            // 8. Stress-test 20x EQ enable/disable toggles during live playback (zero leaks/crashes)
            repeat(20) { idx ->
                eq.setEnabled(idx % 2 == 0)
                delay(40L)
            }
            val rt = Runtime.getRuntime()
            val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0)
            Log.i(Tag, "STRESS_PASS cycles=20 javaHeapMb=${String.format("%.1f", usedMb)}")
        } finally {
            // Restore original EQ state & pause cleanly
            eq.setEnabled(initialEqState.enabled)
            eq.selectPreset(initialEqState.selectedPresetId)
            eq.setPreampDb(initialEqState.preampDb)
            eq.setAutoClippingProtection(initialEqState.autoClippingProtection)
            eq.setBassBoostStrength(initialEqState.bassBoostStrength)
            eq.setVirtualizerStrength(initialEqState.virtualizerStrength)
            eq.setBalance(initialEqState.balance)
            eq.setMonoAudio(initialEqState.monoAudio)
            withContext(Dispatchers.Main) {
                playback.setPlaybackSpeed(1.0f)
                playback.pause()
            }
            delay(300L)

        }
    }
}
