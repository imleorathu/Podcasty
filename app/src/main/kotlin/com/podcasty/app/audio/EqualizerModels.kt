/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.audio

import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Hardware-reported equalizer band metadata queried dynamically from
 * [android.media.audiofx.Equalizer] (never hardcoded).
 */
data class EqualizerBandInfo(
    val index: Int,
    val centerFreqHz: Int,
    val minFreqHz: Int,
    val maxFreqHz: Int,
) {
    val shortLabel: String
        get() = formatFrequencyLabel(centerFreqHz)

    companion object {
        fun formatFrequencyLabel(hz: Int): String = when {
            hz >= 10_000 -> "${(hz / 1000.0).roundToInt()}k"
            hz >= 1_000 -> {
                val khz = hz / 1000.0
                if (khz % 1.0 == 0.0) "${khz.toInt()}k" else String.format(java.util.Locale.US, "%.1fk", khz)
            }
            else -> "$hz"
        }

        fun formatFrequencyWithUnit(hz: Int): String = when {
            hz >= 1_000 -> {
                val khz = hz / 1000.0
                if (khz % 1.0 == 0.0) "${khz.toInt()} kHz" else String.format(java.util.Locale.US, "%.1f kHz", khz)
            }
            else -> "$hz Hz"
        }
    }
}

/**
 * Runtime capabilities reported by the active Android device's AudioEffect framework.
 */
data class EqualizerCapabilities(
    val isSupported: Boolean = false,
    val isAttached: Boolean = false,
    val audioSessionId: Int = 0,
    val bands: List<EqualizerBandInfo> = emptyList(),
    val minGainMb: Int = -1500,
    val maxGainMb: Int = 1500,
    val bassBoostSupported: Boolean = false,
    val virtualizerSupported: Boolean = false,
    val equalizerImplementor: String? = null,
    val unavailableReason: String? = null,
) {
    val bandCount: Int get() = bands.size
    val minGainDb: Float get() = minGainMb / 100f
    val maxGainDb: Float get() = maxGainMb / 100f
}

/**
 * Represents either a built-in preset or a Room-persisted user custom preset.
 */
data class EqualizerPreset(
    val id: String,
    val name: String,
    val isBuiltIn: Boolean,
    val bandLevelsMb: List<Int> = emptyList(),
    val bandFrequenciesHz: List<Int> = emptyList(),
    val preampDb: Float = 0f,
    val bassBoostStrength: Int = 0,
    val virtualizerStrength: Int = 0,
    val balance: Float = 0f,
    val monoAudio: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/**
 * Live audio output pipeline diagnostics reported directly from Media3 / AudioSink.
 */
data class AudioOutputDiagnostics(
    val codecMimeType: String? = null,
    val sampleRateHz: Int = 0,
    val channelCount: Int = 0,
    val pcmEncoding: Int = 0,
    val audioSessionId: Int = 0,
    val floatOutputEnabled: Boolean = true,
    val dspBypassed: Boolean = true,
    val appliedHeadroomDb: Float = 0f,
) {
    val encodingLabel: String
        get() = when (pcmEncoding) {
            androidx.media3.common.C.ENCODING_PCM_FLOAT -> "32-bit Float PCM"
            androidx.media3.common.C.ENCODING_PCM_32BIT -> "32-bit PCM"
            androidx.media3.common.C.ENCODING_PCM_24BIT -> "24-bit PCM"
            androidx.media3.common.C.ENCODING_PCM_16BIT -> "16-bit PCM"
            else -> "Standard PCM"
        }
}

/**
 * Complete reactive UI state for the Equalizer & Audio Controls sheet.
 */
data class EqualizerUiState(
    val enabled: Boolean = false,
    val selectedPresetId: String = BuiltInEqualizerPresets.FlatId,
    val bandLevelsMb: List<Int> = emptyList(),
    val preampDb: Float = 0f,
    val autoClippingProtection: Boolean = true,
    val effectiveHeadroomDb: Float = 0f,
    val bassBoostStrength: Int = 0,
    val virtualizerStrength: Int = 0,
    val balance: Float = 0f,
    val monoAudio: Boolean = false,
    val capabilities: EqualizerCapabilities = EqualizerCapabilities(),
    val customPresets: List<EqualizerPreset> = emptyList(),
    val diagnostics: AudioOutputDiagnostics = AudioOutputDiagnostics(),
) {
    val allPresets: List<EqualizerPreset>
        get() = BuiltInEqualizerPresets.allBuiltIn(capabilities) + customPresets
}

/**
 * 12 musically calibrated built-in presets mapped dynamically to ANY device's reported
 * equalizer band count, center frequencies, and gain limits via log-frequency interpolation.
 */
object BuiltInEqualizerPresets {
    const val FlatId = "flat"
    const val BassBoostId = "bass_boost"
    const val TrebleBoostId = "treble_boost"
    const val VocalId = "vocal"
    const val ClassicalId = "classical"
    const val JazzId = "jazz"
    const val RockId = "rock"
    const val PopId = "pop"
    const val AcousticId = "acoustic"
    const val ElectronicId = "electronic"
    const val HipHopId = "hip_hop"
    const val CustomId = "custom"

    // Reference anchor frequencies (Hz): 60, 150, 400, 1000, 2400, 6000, 15000
    private val anchorFreqsHz = intArrayOf(60, 150, 400, 1_000, 2_400, 6_000, 15_000)

    data class BuiltInSpec(
        val id: String,
        val name: String,
        val anchorGainsDb: FloatArray,
    )

    val specs: List<BuiltInSpec> = listOf(
        BuiltInSpec(FlatId, "Flat", floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f)),
        BuiltInSpec(BassBoostId, "Bass Boost", floatArrayOf(5.5f, 4.0f, 1.5f, 0.0f, 0.0f, 0.5f, 0.5f)),
        BuiltInSpec(TrebleBoostId, "Treble Boost", floatArrayOf(0.0f, 0.0f, 0.0f, 0.5f, 2.5f, 4.5f, 5.5f)),
        BuiltInSpec(VocalId, "Vocal", floatArrayOf(-2.0f, -1.0f, 1.5f, 3.5f, 4.0f, 1.5f, -0.5f)),
        BuiltInSpec(ClassicalId, "Classical", floatArrayOf(3.0f, 2.0f, 0.0f, -0.5f, 1.0f, 2.5f, 3.5f)),
        BuiltInSpec(JazzId, "Jazz", floatArrayOf(2.5f, 1.5f, -1.0f, 1.5f, 2.5f, 2.0f, 1.5f)),
        BuiltInSpec(RockId, "Rock", floatArrayOf(4.5f, 3.0f, -1.0f, -1.5f, 2.0f, 3.5f, 4.0f)),
        BuiltInSpec(PopId, "Pop", floatArrayOf(-1.0f, 1.5f, 3.0f, 3.5f, 2.0f, 0.5f, -0.5f)),
        BuiltInSpec(AcousticId, "Acoustic", floatArrayOf(2.5f, 2.0f, 1.0f, 1.5f, 2.5f, 2.0f, 1.5f)),
        BuiltInSpec(ElectronicId, "Electronic", floatArrayOf(5.0f, 3.5f, 0.5f, -1.5f, 1.5f, 3.5f, 4.5f)),
        BuiltInSpec(HipHopId, "Hip-Hop", floatArrayOf(5.5f, 4.5f, 1.0f, -1.0f, 1.0f, 2.5f, 2.0f)),
        BuiltInSpec(CustomId, "Custom", floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f)),
    )

    fun isBuiltInId(id: String): Boolean = specs.any { it.id == id }

    fun specById(id: String): BuiltInSpec? = specs.firstOrNull { it.id == id }

    fun allBuiltIn(capabilities: EqualizerCapabilities): List<EqualizerPreset> =
        specs.map { spec ->
            EqualizerPreset(
                id = spec.id,
                name = spec.name,
                isBuiltIn = true,
                bandLevelsMb = resolveBandLevelsMb(spec.id, capabilities),
                bandFrequenciesHz = capabilities.bands.map { it.centerFreqHz },
            )
        }

    /**
     * Maps a built-in preset curve onto the device's actual bands via log2-frequency interpolation,
     * clamped strictly within `[capabilities.minGainMb, capabilities.maxGainMb]`.
     */
    fun resolveBandLevelsMb(presetId: String, capabilities: EqualizerCapabilities): List<Int> {
        val bands = capabilities.bands
        if (bands.isEmpty()) return emptyList()
        val spec = specById(presetId) ?: return List(bands.size) { 0 }
        if (presetId == FlatId) return List(bands.size) { 0 }
        return bands.map { band ->
            val db = interpolateDb(band.centerFreqHz, anchorFreqsHz, spec.anchorGainsDb)
            (db * 100f).roundToInt().coerceIn(capabilities.minGainMb, capabilities.maxGainMb)
        }
    }

    /**
     * Remaps a saved custom preset onto a target device's band layout if the saved band count
     * or center frequencies differ from the active hardware.
     */
    fun remapBandsToDevice(
        savedLevelsMb: List<Int>,
        savedFreqsHz: List<Int>,
        capabilities: EqualizerCapabilities,
    ): List<Int> {
        val targetBands = capabilities.bands
        if (targetBands.isEmpty()) return emptyList()
        if (savedLevelsMb.isEmpty()) return List(targetBands.size) { 0 }
        if (savedLevelsMb.size == targetBands.size &&
            (savedFreqsHz.isEmpty() || savedFreqsHz == targetBands.map { it.centerFreqHz })
        ) {
            return savedLevelsMb.map { it.coerceIn(capabilities.minGainMb, capabilities.maxGainMb) }
        }
        if (savedFreqsHz.size == savedLevelsMb.size && savedFreqsHz.all { it > 0 }) {
            val pairs = savedFreqsHz.zip(savedLevelsMb).sortedBy { it.first }
            val freqs = pairs.map { it.first }.toIntArray()
            val dbs = pairs.map { it.second / 100f }.toFloatArray()
            return targetBands.map { band ->
                val db = interpolateDb(band.centerFreqHz, freqs, dbs)
                (db * 100f).roundToInt().coerceIn(capabilities.minGainMb, capabilities.maxGainMb)
            }
        }
        return targetBands.indices.map { idx ->
            val srcIdx = ((idx.toFloat() / targetBands.size) * savedLevelsMb.size)
                .toInt()
                .coerceIn(0, savedLevelsMb.lastIndex)
            savedLevelsMb[srcIdx].coerceIn(capabilities.minGainMb, capabilities.maxGainMb)
        }
    }

    internal fun interpolateDb(targetFreqHz: Int, freqsHz: IntArray, gainsDb: FloatArray): Float {
        if (freqsHz.isEmpty() || gainsDb.isEmpty()) return 0f
        if (freqsHz.size == 1 || targetFreqHz <= freqsHz.first()) return gainsDb.first()
        if (targetFreqHz >= freqsHz.last()) return gainsDb.last()
        val logTarget = ln(targetFreqHz.coerceAtLeast(1).toDouble())
        for (i in 0 until freqsHz.lastIndex) {
            val f0 = freqsHz[i]
            val f1 = freqsHz[i + 1]
            if (targetFreqHz in f0..f1) {
                val log0 = ln(f0.coerceAtLeast(1).toDouble())
                val log1 = ln(f1.coerceAtLeast(1).toDouble())
                val denom = (log1 - log0).takeIf { it > 1e-6 } ?: return gainsDb[i]
                val t = ((logTarget - log0) / denom).toFloat().coerceIn(0f, 1f)
                return gainsDb[i] + (gainsDb[i + 1] - gainsDb[i]) * t
            }
        }
        return gainsDb.last()
    }

    /**
     * Computes safe automatic headroom attenuation (in dB, <= 0f) to prevent digital clipping
     * when positive equalizer boosts are active.
     */
    fun computeAutoHeadroomDb(
        eqEnabled: Boolean,
        autoClippingProtection: Boolean,
        bandLevelsMb: List<Int>,
    ): Float {
        if (!eqEnabled || !autoClippingProtection || bandLevelsMb.isEmpty()) return 0f
        val maxBoostMb = (bandLevelsMb.maxOrNull() ?: 0).coerceAtLeast(0)
        if (maxBoostMb <= 0) return 0f
        // Attenuate by the peak band boost (capped at -12 dB) so positive EQ peaks stay below 0 dBFS
        return -(maxBoostMb / 100f).coerceAtMost(12.0f)
    }

    fun parseCsvInts(csv: String): List<Int> =
        csv.split(',')
            .mapNotNull { it.trim().toIntOrNull() }

    fun encodeCsvInts(values: List<Int>): String =
        values.joinToString(",")
}
