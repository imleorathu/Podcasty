/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.podcasty.app.audio.BuiltInEqualizerPresets
import com.podcasty.app.audio.EqualizerBandInfo
import com.podcasty.app.audio.EqualizerCapabilities
import com.podcasty.app.audio.PodcastyAudioDspProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class EqualizerAndDspUnitTest {

    private val fiveBandCaps = EqualizerCapabilities(
        isSupported = true,
        isAttached = true,
        audioSessionId = 42,
        bands = listOf(
            EqualizerBandInfo(0, 60, 30, 120),
            EqualizerBandInfo(1, 230, 120, 460),
            EqualizerBandInfo(2, 910, 460, 1800),
            EqualizerBandInfo(3, 3600, 1800, 7000),
            EqualizerBandInfo(4, 14000, 7000, 20000),
        ),
        minGainMb = -1500,
        maxGainMb = 1500,
    )

    private val sevenBandCaps = EqualizerCapabilities(
        isSupported = true,
        isAttached = true,
        audioSessionId = 43,
        bands = listOf(
            EqualizerBandInfo(0, 60, 30, 100),
            EqualizerBandInfo(1, 150, 100, 250),
            EqualizerBandInfo(2, 400, 250, 700),
            EqualizerBandInfo(3, 1000, 700, 1600),
            EqualizerBandInfo(4, 2400, 1600, 4000),
            EqualizerBandInfo(5, 6000, 4000, 10000),
            EqualizerBandInfo(6, 15000, 10000, 20000),
        ),
        minGainMb = -1200,
        maxGainMb = 1200,
    )

    @Test
    fun allTwelveBuiltInPresetsExistAndMapToDynamicDeviceBands() {
        assertEquals(12, BuiltInEqualizerPresets.specs.size)

        val flat5 = BuiltInEqualizerPresets.resolveBandLevelsMb(BuiltInEqualizerPresets.FlatId, fiveBandCaps)
        assertEquals(listOf(0, 0, 0, 0, 0), flat5)

        val bass5 = BuiltInEqualizerPresets.resolveBandLevelsMb(BuiltInEqualizerPresets.BassBoostId, fiveBandCaps)
        assertEquals(5, bass5.size)
        assertTrue("Low band should be boosted for Bass Boost: $bass5", bass5[0] > 400)
        assertTrue("Mid band should stay near neutral for Bass Boost: $bass5", abs(bass5[2]) <= 100)

        val treble7 = BuiltInEqualizerPresets.resolveBandLevelsMb(BuiltInEqualizerPresets.TrebleBoostId, sevenBandCaps)
        assertEquals(7, treble7.size)
        assertEquals(0, treble7[0])
        assertTrue("High band should be boosted for Treble Boost: $treble7", treble7[6] > 400)

        // All preset bands must strictly obey hardware min/max gain range
        BuiltInEqualizerPresets.specs.forEach { spec ->
            val levels = BuiltInEqualizerPresets.resolveBandLevelsMb(spec.id, sevenBandCaps)
            assertEquals(7, levels.size)
            assertTrue(levels.all { it in sevenBandCaps.minGainMb..sevenBandCaps.maxGainMb })
        }
    }

    @Test
    fun customPresetRemapsCleanlyAcrossDifferentBandLayouts() {
        val saved5Levels = listOf(600, 300, 0, -200, 400)
        val saved5Freqs = fiveBandCaps.bands.map { it.centerFreqHz }
        val remapped7 = BuiltInEqualizerPresets.remapBandsToDevice(saved5Levels, saved5Freqs, sevenBandCaps)
        assertEquals(7, remapped7.size)
        assertEquals(600, remapped7.first())
        assertTrue(remapped7.last() >= 350)
        assertTrue(remapped7.all { it in sevenBandCaps.minGainMb..sevenBandCaps.maxGainMb })
    }

    @Test
    fun autoHeadroomComputesSafeNegativeAttenuationOnlyWhenBoosted() {
        assertEquals(0f, BuiltInEqualizerPresets.computeAutoHeadroomDb(false, true, listOf(600, 400)), 0.001f)
        assertEquals(0f, BuiltInEqualizerPresets.computeAutoHeadroomDb(true, false, listOf(600, 400)), 0.001f)
        assertEquals(0f, BuiltInEqualizerPresets.computeAutoHeadroomDb(true, true, listOf(-300, 0, -100)), 0.001f)
        assertEquals(-5.5f, BuiltInEqualizerPresets.computeAutoHeadroomDb(true, true, listOf(550, 200, -100)), 0.01f)
        assertEquals(-12.0f, BuiltInEqualizerPresets.computeAutoHeadroomDb(true, true, listOf(1500)), 0.01f)
    }

    @OptIn(UnstableApi::class)
    @Test
    fun dspProcessorIsBitTransparentWhenDisabledAndAttenuatesSafelyWhenEnabled() {
        val processor = PodcastyAudioDspProcessor()
        val format = AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)
        val configured = processor.configure(format)
        assertEquals(48_000, configured.sampleRate)
        assertEquals(2, configured.channelCount)
        assertEquals(C.ENCODING_PCM_16BIT, configured.encoding)
        processor.flush()

        // 1. Disabled -> bit-exact passthrough
        processor.updateParameters(enabled = false, preampDb = 0f, autoHeadroomDb = 0f, balance = 0f, monoAudio = false)
        assertTrue(processor.isBypassed())

        val inputShorts = shortArrayOf(10000, -10000, 20000, -20000, 5000, 15000, -5000, -15000)
        val inputBytes = ByteBuffer.allocate(inputShorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        inputShorts.forEach { inputBytes.putShort(it) }
        inputBytes.flip()

        processor.queueInput(inputBytes)
        val outBypass = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        val bypassedShorts = ShortArray(inputShorts.size) { outBypass.short }
        assertArrayEquals(inputShorts, bypassedShorts)

        // 2. -6 dB attenuation + Mono mixdown
        processor.updateParameters(enabled = true, preampDb = -6f, autoHeadroomDb = 0f, balance = 0f, monoAudio = true)
        processor.flush()
        assertFalse(processor.isBypassed())

        val stereoIn = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        stereoIn.putShort(20000).putShort(0) // mono avg = 10000 -> -6dB ≈ 5012
        stereoIn.putShort(20000).putShort(0)
        stereoIn.flip()

        processor.queueInput(stereoIn)
        val outMono = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        val l1 = outMono.short.toInt()
        val r1 = outMono.short.toInt()
        assertEquals(l1, r1)
        assertTrue("Expected attenuated mono around ~5012, got $l1", l1 in 4500..5500)

        // 3. Soft safety ceiling prevents digital clipping on +6 dB hot input
        assertTrue(PodcastyAudioDspProcessor.softLimit(1.8f) < 1.0f)
        assertTrue(PodcastyAudioDspProcessor.softLimit(-1.8f) > -1.0f)
        assertEquals(0.5f, PodcastyAudioDspProcessor.softLimit(0.5f), 0.0001f)
    }
}
