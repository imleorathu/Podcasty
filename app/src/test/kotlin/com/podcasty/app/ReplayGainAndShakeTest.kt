package com.podcasty.app

import com.podcasty.app.audio.ReplayGain
import com.podcasty.app.player.ShakeSkipDetector
import org.junit.Assert.*
import org.junit.Test

class ReplayGainAndShakeTest {
    @Test fun gainUsesAlbumOrTrackFallbackAndPreventsTaggedPeakClipping() {
        val tags = mapOf("REPLAYGAIN_TRACK_GAIN" to arrayOf("+6.0 dB"), "REPLAYGAIN_TRACK_PEAK" to arrayOf("2.0"), "REPLAYGAIN_ALBUM_GAIN" to arrayOf("-8.0 dB"))
        assertEquals(-6.0206f, ReplayGain.gainDb(tags, false), 0.001f)
        assertEquals(-8f, ReplayGain.gainDb(tags, true), 0.001f)
        assertEquals(-6.0206f, ReplayGain.gainDb(tags - "REPLAYGAIN_ALBUM_GAIN", true), 0.001f)
        assertEquals(0f, ReplayGain.gainDb(mapOf("REPLAYGAIN_TRACK_GAIN" to arrayOf("NaN")), false), 0f)
        assertEquals(0f, ReplayGain.gainDb(emptyMap(), false), 0f)
    }

    @Test fun shakeRequiresSeparatedPeaksAndCooldownAndDefaultsOff() {
        val detector = ShakeSkipDetector()
        assertFalse(detector.sample(40f, 0f, 0f, 1000, 0))
        assertFalse(detector.sample(9.8f, 0f, 0f, 1000, 3))
        assertFalse(detector.sample(40f, 0f, 0f, 1100, 2))
        assertFalse(detector.sample(40f, 0f, 0f, 1200, 2))
        assertFalse(detector.sample(9.8f, 0f, 0f, 1250, 2))
        assertTrue(detector.sample(40f, 0f, 0f, 1300, 2))
        detector.sample(9.8f, 0f, 0f, 1400, 2)
        assertFalse(detector.sample(40f, 0f, 0f, 1500, 2))
        detector.reset()
        assertFalse(detector.sample(40f, 0f, 0f, 4000, 2))
    }
}
