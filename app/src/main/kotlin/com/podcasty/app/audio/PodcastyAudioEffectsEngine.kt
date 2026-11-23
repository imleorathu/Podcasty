/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.DynamicsProcessing
import kotlin.math.log10
import kotlin.math.roundToInt
import android.media.audiofx.Virtualizer
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Single-instance, lifecycle-safe manager for Android's [Equalizer], optional [BassBoost],
 * optional [Virtualizer], and [PodcastyAudioDspProcessor].
 *
 * Guarantees:
 * - Never attaches to invalid session ID `0` (global output mix) or `C.AUDIO_SESSION_ID_UNSET`.
 * - Exactly one active effect chain per active ExoPlayer `audioSessionId` (zero duplicates/leaks).
 * - Dynamically queries device band count, center frequencies, and gain range (never hardcoded).
 * - Releases all native AudioEffect instances cleanly on session change or service destruction.
 * - Gracefully reports unsupported states without crashing on OEM devices lacking effects.
 */
@OptIn(UnstableApi::class)
class PodcastyAudioEffectsEngine(
    private val dspProcessor: PodcastyAudioDspProcessor,
) {
    private val lock = Any()

    private var attachedSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var equalizer: Equalizer? = null
    private var dynamics: DynamicsProcessing? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null

    private var trackGainDb = 0f

    fun setReplayGain(gainDb: Float) {
        synchronized(lock) {
            trackGainDb = if (gainDb.isFinite()) gainDb.coerceIn(-15f, 6f) else 0f
            applyAllLocked()
        }
    }

    private var currentEnabled: Boolean = false
    private var currentBandLevelsMb: List<Int> = emptyList()
    private var currentPreampDb: Float = 0f
    private var currentAutoHeadroom: Boolean = true
    private var currentBassBoostStrength: Int = 0
    private var currentVirtualizerStrength: Int = 0
    private var currentBalance: Float = 0f
    private var currentMonoAudio: Boolean = false

    private val _capabilities = MutableStateFlow(EqualizerCapabilities())
    val capabilities: StateFlow<EqualizerCapabilities> = _capabilities.asStateFlow()

    private val _diagnostics = MutableStateFlow(AudioOutputDiagnostics())
    val diagnostics: StateFlow<AudioOutputDiagnostics> = _diagnostics.asStateFlow()

    /**
     * Attaches (or re-attaches) AudioEffects to ExoPlayer's real audio session ID.
     * Safe to call repeatedly; no-ops if already attached to the same valid session.
     */
    fun attachToAudioSession(audioSessionId: Int) {
        synchronized(lock) {
            if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId <= 0) {
                return
            }
            if (attachedSessionId == audioSessionId && (equalizer != null || dynamics != null)) {
                return
            }
            releaseLocked(keepCapabilities = false)
            attachedSessionId = audioSessionId

            val eqImplementor = queryEffectImplementor(AudioEffect.EFFECT_TYPE_EQUALIZER)
            var createdEq: Equalizer? = null
            var unavailableReason: String? = null

            try {
                createdEq = Equalizer(0, audioSessionId)
                val bandCount = createdEq.numberOfBands.toInt().coerceAtLeast(0)
                val levelRange = createdEq.bandLevelRange
                val minMb = levelRange.getOrNull(0)?.toInt() ?: -1500
                val maxMb = levelRange.getOrNull(1)?.toInt() ?: 1500
                val bands = (0 until bandCount).map { idx ->
                    val bandShort = idx.toShort()
                    val centerHz = (createdEq.getCenterFreq(bandShort) / 1000).coerceAtLeast(1)
                    val freqRange = runCatching { createdEq.getBandFreqRange(bandShort) }.getOrNull()
                    val minHz = ((freqRange?.getOrNull(0) ?: 0) / 1000).coerceAtLeast(0)
                    val maxHz = ((freqRange?.getOrNull(1) ?: 0) / 1000).coerceAtLeast(centerHz)
                    EqualizerBandInfo(
                        index = idx,
                        centerFreqHz = centerHz,
                        minFreqHz = minHz,
                        maxFreqHz = maxHz,
                    )
                }

                // Query optional BassBoost & Virtualizer safely without crashing if OEM omits them
                var createdBass: BassBoost? = null
                val bassSupported = runCatching {
                    val bb = BassBoost(0, audioSessionId)
                    createdBass = bb
                    if (bb.strengthSupported) {
                        createdBass = bb
                        true
                    } else {
                        bb.release()
                        createdBass = null
                        false
                    }
                }.onFailure { runCatching { createdBass?.release() }; createdBass = null }.getOrDefault(false)

                var createdVirt: Virtualizer? = null
                val virtSupported = runCatching {
                    @Suppress("DEPRECATION")
                    val vz = Virtualizer(0, audioSessionId)
                    createdVirt = vz
                    @Suppress("DEPRECATION")
                    if (vz.strengthSupported) {
                        createdVirt = vz
                        true
                    } else {
                        vz.release()
                        createdVirt = null
                        false
                    }
                }.onFailure { runCatching { createdVirt?.release() }; createdVirt = null }.getOrDefault(false)

                // DynamicsProcessing exposes a real configurable filter bank, independent of
                // the legacy Equalizer's OEM band count, and remains active on float output.
                dynamics = runCatching {
                    val config = DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                        true, TenBandFrequencies.size, false, 0, false, 0, true,
                    ).build()
                    val effect = DynamicsProcessing(0, audioSessionId, config)
                    try {
                        check(effect.getPreEqByChannelIndex(0).bandCount == TenBandFrequencies.size)
                        effect
                    } catch (error: Throwable) {
                        effect.release()
                        throw error
                    }
                }.getOrNull()
                equalizer = createdEq
                bassBoost = createdBass
                virtualizer = createdVirt

                val activeBandCount = if (dynamics != null) TenBandFrequencies.size else bandCount
                val activeMinMb = if (dynamics != null) -1200 else minMb
                val activeMaxMb = if (dynamics != null) 1200 else maxMb
                val activeImpl = if (dynamics != null) "Android DynamicsProcessing (10 bands)" else eqImplementor

                _capabilities.value = EqualizerCapabilities(
                    isSupported = activeBandCount > 0,
                    isAttached = activeBandCount > 0,
                    audioSessionId = audioSessionId,
                    bands = if (dynamics != null) TenBandFrequencies.mapIndexed { index, hz ->
                        EqualizerBandInfo(index, hz, (hz / 1.4142f).toInt(), (hz * 1.4142f).toInt())
                    } else bands,
                    minGainMb = activeMinMb,
                    maxGainMb = activeMaxMb,
                    bassBoostSupported = bassSupported,
                    virtualizerSupported = virtSupported,
                    equalizerImplementor = activeImpl,
                    unavailableReason = if (activeBandCount == 0) "Device reported 0 equalizer bands." else null,
                )

                // Re-apply currently configured state immediately to the newly attached session
                applyAllLocked()
                Log.i(
                    Tag,
                    "Attached Equalizer ($activeBandCount bands, ${activeMinMb / 100f}..${activeMaxMb / 100f} dB, " +
                        "bass=$bassSupported, virt=$virtSupported, impl=$activeImpl) to session=$audioSessionId",
                )
            } catch (t: Throwable) {
                runCatching { createdEq?.release() }
                unavailableReason = t.localizedMessage ?: "AudioEqualizer unavailable on this device."
                Log.w(Tag, "Could not attach Equalizer to session=$audioSessionId: $unavailableReason")
                _capabilities.value = EqualizerCapabilities(
                    isSupported = false,
                    isAttached = false,
                    audioSessionId = audioSessionId,
                    equalizerImplementor = eqImplementor,
                    unavailableReason = unavailableReason,
                )
                applyDspOnlyLocked()
            }

            _diagnostics.update {
                it.copy(audioSessionId = audioSessionId)
            }
        }
    }

    /**
     * Applies full equalizer + preamp + optional effects state atomically to the active audio session.
     */
    fun applySettings(
        enabled: Boolean,
        bandLevelsMb: List<Int>,
        preampDb: Float,
        autoClippingProtection: Boolean,
        bassBoostStrength: Int,
        virtualizerStrength: Int,
        balance: Float,
        monoAudio: Boolean,
    ) {
        synchronized(lock) {
            currentEnabled = enabled
            currentBandLevelsMb = bandLevelsMb
            currentPreampDb = preampDb.coerceIn(-15.0f, 6.0f)
            currentAutoHeadroom = autoClippingProtection
            currentBassBoostStrength = bassBoostStrength.coerceIn(0, 1000)
            currentVirtualizerStrength = virtualizerStrength.coerceIn(0, 1000)
            currentBalance = balance.coerceIn(-1.0f, 1.0f)
            currentMonoAudio = monoAudio
            applyAllLocked()
        }
    }

    /**
     * Lightweight real-time update for a single equalizer band slider drag without touching
     * unrelated effects or triggering audio glitches.
     */
    fun updateSingleBand(bandIndex: Int, levelMb: Int) {
        synchronized(lock) {
            val caps = _capabilities.value
            if (bandIndex !in caps.bands.indices) return
            val clamped = levelMb.coerceIn(caps.minGainMb, caps.maxGainMb)
            val mutable = if (currentBandLevelsMb.size == caps.bandCount) {
                currentBandLevelsMb.toMutableList()
            } else {
                MutableList(caps.bandCount) { idx -> currentBandLevelsMb.getOrElse(idx) { 0 } }
            }
            mutable[bandIndex] = clamped
            currentBandLevelsMb = mutable

            if (currentEnabled) {
                runCatching {
                    if (dynamics != null) {
                        dynamics?.setPreEqBandAllChannelsTo(bandIndex, DynamicsProcessing.EqBand(
                            true, TenBandFrequencies[bandIndex] * 1.4142f, clamped / 100f,
                        ))
                    } else equalizer?.setBandLevel(bandIndex.toShort(), clamped.toShort())
                }.onFailure { err ->
                    Log.w(Tag, "setBandLevel($bandIndex, $clamped) failed: ${err.message}")
                }
            }
            applyDspOnlyLocked()
        }
    }

    fun updateTrackAudioDiagnostics(
        codecMimeType: String?,
        sampleRateHz: Int,
        channelCount: Int,
        pcmEncoding: Int,
        audioSessionId: Int,
    ) {
        _diagnostics.update {
            it.copy(
                codecMimeType = codecMimeType ?: it.codecMimeType,
                sampleRateHz = if (sampleRateHz > 0) sampleRateHz else it.sampleRateHz,
                channelCount = if (channelCount > 0) channelCount else it.channelCount,
                pcmEncoding = if (pcmEncoding != 0) pcmEncoding else it.pcmEncoding,
                audioSessionId = if (audioSessionId > 0) audioSessionId else it.audioSessionId,
                floatOutputEnabled = pcmEncoding == C.ENCODING_PCM_FLOAT,
                dspBypassed = dspProcessor.isBypassed(),
            )
        }
    }

    /**
     * Reads back the live hardware band levels directly from [android.media.audiofx.Equalizer]
     * for verification & instrumentation tests (proves real AudioEffect state, not UI state).
     */
    fun readHardwareStateSnapshot(): HardwareEqualizerSnapshot {
        synchronized(lock) {
            dynamics?.let { effect ->
                return HardwareEqualizerSnapshot(
                    attached = true, audioSessionId = attachedSessionId, enabled = effect.enabled,
                    bandLevelsMb = TenBandFrequencies.indices.map { (effect.getPreEqBandByChannelIndex(0, it).gain * 100f).roundToInt() },
                    bassBoostEnabled = bassBoost?.enabled == true, bassBoostStrength = bassBoost?.roundedStrength?.toInt() ?: 0,
                    virtualizerEnabled = virtualizer?.enabled == true, virtualizerStrength = virtualizer?.roundedStrength?.toInt() ?: 0,
                    dspBypassed = dspProcessor.isBypassed(),
                    nativeInputGainsDb = (0 until effect.channelCount).map { effect.getInputGainByChannelIndex(it) },
                )
            }
            val eq = equalizer ?: return HardwareEqualizerSnapshot(
                attached = false,
                audioSessionId = attachedSessionId,
                enabled = false,
                bandLevelsMb = emptyList(),
                bassBoostEnabled = false,
                bassBoostStrength = 0,
                virtualizerEnabled = false,
                virtualizerStrength = 0,
                dspBypassed = dspProcessor.isBypassed(),
            )
            val count = runCatching { eq.numberOfBands.toInt() }.getOrDefault(0)
            val levels = (0 until count).map { idx ->
                runCatching { eq.getBandLevel(idx.toShort()).toInt() }.getOrDefault(0)
            }
            return HardwareEqualizerSnapshot(
                attached = true,
                audioSessionId = attachedSessionId,
                enabled = runCatching { eq.enabled }.getOrDefault(false),
                bandLevelsMb = levels,
                bassBoostEnabled = runCatching { bassBoost?.enabled == true }.getOrDefault(false),
                bassBoostStrength = runCatching { bassBoost?.roundedStrength?.toInt() ?: 0 }.getOrDefault(0),
                virtualizerEnabled = runCatching { virtualizer?.enabled == true }.getOrDefault(false),
                virtualizerStrength = runCatching {
                    @Suppress("DEPRECATION")
                    virtualizer?.roundedStrength?.toInt() ?: 0
                }.getOrDefault(0),
                dspBypassed = dspProcessor.isBypassed(),
            )
        }
    }

    fun release() {
        synchronized(lock) {
            releaseLocked(keepCapabilities = false)
            dspProcessor.updateParameters(
                enabled = false,
                preampDb = 0f,
                autoHeadroomDb = 0f,
                balance = 0f,
                monoAudio = false,
            )
        }
    }

    private fun applyAllLocked() {
        val caps = _capabilities.value
        val eq = equalizer
        val dp = dynamics
        if (dp != null) {
            runCatching {
                eq?.enabled = false // Never run two equalizers in series.
                TenBandFrequencies.forEachIndexed { index, hz ->
                    dp.setPreEqBandAllChannelsTo(index, DynamicsProcessing.EqBand(
                        true, hz * 1.4142f, if (currentEnabled) (currentBandLevelsMb.getOrElse(index) { 0 }.coerceIn(-1200, 1200)) / 100f else 0f,
                    ))
                }
                dp.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(
                    true, currentAutoHeadroom, 0, 1f, 80f, 1000f, -0.1f, 0f,
                ))
                dp.enabled = currentEnabled || trackGainDb != 0f
            }.onFailure { Log.w(Tag, "Ten-band EQ update failed", it) }
        } else if (eq != null && caps.isSupported) {
            val resolvedLevels = if (currentBandLevelsMb.size == caps.bandCount) {
                currentBandLevelsMb
            } else {
                List(caps.bandCount) { idx -> currentBandLevelsMb.getOrElse(idx) { 0 } }
            }
            currentBandLevelsMb = resolvedLevels
            runCatching {
                if (currentEnabled) {
                    for (i in 0 until caps.bandCount) {
                        val level = resolvedLevels[i].coerceIn(caps.minGainMb, caps.maxGainMb)
                        eq.setBandLevel(i.toShort(), level.toShort())
                    }
                    if (!eq.enabled) eq.enabled = true
                } else {
                    if (eq.enabled) eq.enabled = false
                }
            }.onFailure { err ->
                Log.w(Tag, "Failed updating hardware Equalizer: ${err.message}")
            }
        }

        bassBoost?.let { bb ->
            runCatching {
                val shouldEnable = currentEnabled && currentBassBoostStrength > 0
                if (shouldEnable) {
                    bb.setStrength(currentBassBoostStrength.toShort())
                    if (!bb.enabled) bb.enabled = true
                } else {
                    if (bb.enabled) bb.enabled = false
                }
            }.onFailure { err ->
                Log.w(Tag, "Failed updating BassBoost: ${err.message}")
            }
        }

        virtualizer?.let { vz ->
            runCatching {
                val shouldEnable = currentEnabled && currentVirtualizerStrength > 0
                if (shouldEnable) {
                    @Suppress("DEPRECATION")
                    vz.setStrength(currentVirtualizerStrength.toShort())
                    if (!vz.enabled) vz.enabled = true
                } else {
                    if (vz.enabled) vz.enabled = false
                }
            }.onFailure { err ->
                Log.w(Tag, "Failed updating Virtualizer: ${err.message}")
            }
        }

        applyDspOnlyLocked()
    }

    private fun applyDspOnlyLocked() {
        val headroomDb = BuiltInEqualizerPresets.computeAutoHeadroomDb(
            eqEnabled = currentEnabled,
            autoClippingProtection = currentAutoHeadroom,
            bandLevelsMb = currentBandLevelsMb,
        )
        val dp = dynamics
        if (dp != null) {
            runCatching {
                val baseDb = (if (currentEnabled) currentPreampDb + headroomDb else 0f) + trackGainDb
                val balance = if (currentEnabled) currentBalance else 0f
                for (channel in 0 until dp.channelCount) {
                    val linear = when (channel) {
                        0 -> if (balance > 0) 1f - balance else 1f
                        1 -> if (balance < 0) 1f + balance else 1f
                        else -> 1f
                    }
                    val attenuation = if (linear <= 0f) -96f else (20 * log10(linear)).coerceAtLeast(-96f)
                    dp.setInputGainbyChannel(channel, baseDb + attenuation)
                }
            }.onFailure { Log.w(Tag, "Native gain/balance update failed", it) }
        }
        dspProcessor.updateParameters(
            enabled = currentEnabled || trackGainDb != 0f,
            preampDb = if (dp == null) (if (currentEnabled) currentPreampDb else 0f) + trackGainDb else 0f,
            autoHeadroomDb = if (dp == null && currentEnabled) headroomDb else 0f,
            balance = if (dp == null && currentEnabled) currentBalance else 0f,
            monoAudio = currentEnabled && currentMonoAudio,
        )
        _diagnostics.update {
            it.copy(
                dspBypassed = dspProcessor.isBypassed(),
                appliedHeadroomDb = headroomDb,
            )
        }
    }

    private fun releaseLocked(keepCapabilities: Boolean) {
        runCatching { dynamics?.enabled = false }
        runCatching { dynamics?.release() }
        dynamics = null
        runCatching { equalizer?.enabled = false }
        runCatching { equalizer?.release() }
        equalizer = null

        runCatching { bassBoost?.enabled = false }
        runCatching { bassBoost?.release() }
        bassBoost = null

        runCatching { virtualizer?.enabled = false }
        runCatching { virtualizer?.release() }
        virtualizer = null

        attachedSessionId = C.AUDIO_SESSION_ID_UNSET
        if (!keepCapabilities) {
            _capabilities.update { it.copy(isAttached = false, audioSessionId = 0) }
        }
    }

    private fun queryEffectImplementor(effectType: java.util.UUID): String? = runCatching {
        AudioEffect.queryEffects()
            ?.firstOrNull { it.type == effectType }
            ?.let { "${it.name} (${it.implementor})" }
    }.getOrNull()

    companion object {
        val TenBandFrequencies = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        private const val Tag = "PodcastyAudioEffects"
    }
}

data class HardwareEqualizerSnapshot(
    val attached: Boolean,
    val audioSessionId: Int,
    val enabled: Boolean,
    val bandLevelsMb: List<Int>,
    val bassBoostEnabled: Boolean,
    val bassBoostStrength: Int,
    val virtualizerEnabled: Boolean,
    val virtualizerStrength: Int,
    val dspBypassed: Boolean,
    val nativeInputGainsDb: List<Float> = emptyList(),
)
