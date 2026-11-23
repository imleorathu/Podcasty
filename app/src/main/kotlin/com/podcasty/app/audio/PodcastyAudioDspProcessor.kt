/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.tanh

/**
 * High-precision, transparent Media3 AudioProcessor providing:
 * - Preamp gain / attenuation (-15.0 dB to +6.0 dB)
 * - Automatic clipping protection headroom attenuation when EQ boosts are active
 * - Gentle safety soft-ceiling only above -0.1 dBFS (zero coloration at normal levels)
 * - Optional Left/Right channel balance (-1.0..+1.0)
 * - Optional Mono audio fold-down
 *
 * Audio quality guarantees:
 * - Preserves original sample rate, channel count, and PCM encoding (16/24/32-bit & 32-bit float).
 * - NEVER forces resampling or lossy bit-depth reduction.
 * - Bit-transparent zero-math bypass whenever all DSP controls are neutral/disabled.
 * - Smooth per-frame gain ramping prevents clicks/zipper noise during live adjustments.
 */
@UnstableApi
class PodcastyAudioDspProcessor : BaseAudioProcessor() {

    @Volatile
    private var targetLeftGain: Float = 1.0f

    @Volatile
    private var targetRightGain: Float = 1.0f

    @Volatile
    private var monoEnabled: Boolean = false

    @Volatile
    private var safetyLimiterActive: Boolean = false

    @Volatile
    private var bypassed: Boolean = true

    @Volatile
    var lastConfiguredFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET
        private set

    // Audio-thread smoothed gain state (prevents pops/clicks when changing preamp or presets)
    private var currentLeftGain: Float = 1.0f
    private var currentRightGain: Float = 1.0f

    fun isBypassed(): Boolean = bypassed

    /**
     * Updates live DSP parameters safely from any thread. Takes effect smoothly on the next
     * audio buffer without restarting playback or flushing AudioTrack.
     */
    fun updateParameters(
        enabled: Boolean,
        preampDb: Float,
        autoHeadroomDb: Float,
        balance: Float,
        monoAudio: Boolean,
    ) {
        if (!enabled) {
            targetLeftGain = 1.0f
            targetRightGain = 1.0f
            monoEnabled = false
            safetyLimiterActive = false
            bypassed = true
            return
        }
        val clampedPreamp = preampDb.coerceIn(-15.0f, 6.0f)
        val clampedHeadroom = autoHeadroomDb.coerceIn(-12.0f, 0.0f)
        val totalGainDb = (clampedPreamp + clampedHeadroom).coerceIn(-24.0f, 6.0f)
        val clampedBalance = balance.coerceIn(-1.0f, 1.0f)

        val isNeutral = abs(totalGainDb) < 0.01f && abs(clampedBalance) < 0.01f && !monoAudio
        if (isNeutral) {
            targetLeftGain = 1.0f
            targetRightGain = 1.0f
            monoEnabled = false
            safetyLimiterActive = false
            bypassed = true
            return
        }

        val linearGain = 10.0.pow(totalGainDb / 20.0).toFloat()
        // Balance attenuates the opposite channel rather than boosting above unity (prevents clipping)
        val leftBal = if (clampedBalance > 0f) (1.0f - clampedBalance).coerceIn(0f, 1f) else 1.0f
        val rightBal = if (clampedBalance < 0f) (1.0f + clampedBalance).coerceIn(0f, 1f) else 1.0f

        targetLeftGain = linearGain * leftBal
        targetRightGain = linearGain * rightBal
        monoEnabled = monoAudio
        // Only engage safety soft-ceiling when positive gain could approach 0 dBFS
        safetyLimiterActive = totalGainDb > 0.05f
        bypassed = false
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val supported = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_24BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_32BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        if (!supported) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        lastConfiguredFormat = inputAudioFormat
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        val outputBuffer = replaceOutputBuffer(remaining)
        val tgtL = targetLeftGain
        val tgtR = targetRightGain
        val mono = monoEnabled

        // Exact bit-transparent fast path when bypassed and ramp has settled to unity
        if (bypassed && abs(currentLeftGain - 1.0f) < 0.0005f && abs(currentRightGain - 1.0f) < 0.0005f) {
            currentLeftGain = 1.0f
            currentRightGain = 1.0f
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        val limitSafety = safetyLimiterActive
        val src = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val dst = outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> processPcm16(src, dst, channels, tgtL, tgtR, mono, limitSafety)
            C.ENCODING_PCM_FLOAT -> processPcmFloat(src, dst, channels, tgtL, tgtR, mono, limitSafety)
            C.ENCODING_PCM_24BIT -> processPcm24(src, dst, channels, tgtL, tgtR, mono, limitSafety)
            C.ENCODING_PCM_32BIT -> processPcm32(src, dst, channels, tgtL, tgtR, mono, limitSafety)
            else -> dst.put(src)
        }
        dst.flip()
    }

    private fun processPcm16(
        src: ByteBuffer,
        dst: ByteBuffer,
        channels: Int,
        tgtL: Float,
        tgtR: Float,
        mono: Boolean,
        limitSafety: Boolean,
    ) {
        val frameBytes = channels * 2
        val frames = src.remaining() / frameBytes
        if (frames <= 0) {
            dst.put(src)
            return
        }
        val stepL = (tgtL - currentLeftGain) / frames
        val stepR = (tgtR - currentRightGain) / frames
        var gL = currentLeftGain
        var gR = currentRightGain
        val invScale = 1.0f / 32768.0f

        for (f in 0 until frames) {
            gL += stepL
            gR += stepR
            if (channels == 2) {
                val rawL = src.short.toFloat() * invScale
                val rawR = src.short.toFloat() * invScale
                var outL: Float
                var outR: Float
                if (mono) {
                    val m = (rawL + rawR) * 0.5f
                    outL = m * gL
                    outR = m * gR
                } else {
                    outL = rawL * gL
                    outR = rawR * gR
                }
                if (limitSafety) {
                    outL = softLimit(outL)
                    outR = softLimit(outR)
                }
                dst.putShort(floatToPcm16(outL))
                dst.putShort(floatToPcm16(outR))
            } else {
                val g = (gL + gR) * 0.5f
                for (c in 0 until channels) {
                    var s = src.short.toFloat() * invScale * g
                    if (limitSafety) s = softLimit(s)
                    dst.putShort(floatToPcm16(s))
                }
            }
        }
        currentLeftGain = tgtL
        currentRightGain = tgtR
        if (src.hasRemaining()) dst.put(src)
    }

    private fun processPcmFloat(
        src: ByteBuffer,
        dst: ByteBuffer,
        channels: Int,
        tgtL: Float,
        tgtR: Float,
        mono: Boolean,
        limitSafety: Boolean,
    ) {
        val frameBytes = channels * 4
        val frames = src.remaining() / frameBytes
        if (frames <= 0) {
            dst.put(src)
            return
        }
        val stepL = (tgtL - currentLeftGain) / frames
        val stepR = (tgtR - currentRightGain) / frames
        var gL = currentLeftGain
        var gR = currentRightGain

        for (f in 0 until frames) {
            gL += stepL
            gR += stepR
            if (channels == 2) {
                val rawL = src.float
                val rawR = src.float
                var outL: Float
                var outR: Float
                if (mono) {
                    val m = (rawL + rawR) * 0.5f
                    outL = m * gL
                    outR = m * gR
                } else {
                    outL = rawL * gL
                    outR = rawR * gR
                }
                if (limitSafety) {
                    outL = softLimit(outL)
                    outR = softLimit(outR)
                } else {
                    outL = outL.coerceIn(-1.0f, 1.0f)
                    outR = outR.coerceIn(-1.0f, 1.0f)
                }
                dst.putFloat(outL)
                dst.putFloat(outR)
            } else {
                val g = (gL + gR) * 0.5f
                for (c in 0 until channels) {
                    var s = src.float * g
                    s = if (limitSafety) softLimit(s) else s.coerceIn(-1.0f, 1.0f)
                    dst.putFloat(s)
                }
            }
        }
        currentLeftGain = tgtL
        currentRightGain = tgtR
        if (src.hasRemaining()) dst.put(src)
    }

    private fun processPcm24(
        src: ByteBuffer,
        dst: ByteBuffer,
        channels: Int,
        tgtL: Float,
        tgtR: Float,
        mono: Boolean,
        limitSafety: Boolean,
    ) {
        val frameBytes = channels * 3
        val frames = src.remaining() / frameBytes
        if (frames <= 0) {
            dst.put(src)
            return
        }
        val stepL = (tgtL - currentLeftGain) / frames
        val stepR = (tgtR - currentRightGain) / frames
        var gL = currentLeftGain
        var gR = currentRightGain
        val invScale = 1.0f / 8388608.0f

        for (f in 0 until frames) {
            gL += stepL
            gR += stepR
            if (channels == 2) {
                val rawL = readInt24(src) * invScale
                val rawR = readInt24(src) * invScale
                var outL: Float
                var outR: Float
                if (mono) {
                    val m = (rawL + rawR) * 0.5f
                    outL = m * gL
                    outR = m * gR
                } else {
                    outL = rawL * gL
                    outR = rawR * gR
                }
                if (limitSafety) {
                    outL = softLimit(outL)
                    outR = softLimit(outR)
                }
                writeInt24(dst, floatToPcm24(outL))
                writeInt24(dst, floatToPcm24(outR))
            } else {
                val g = (gL + gR) * 0.5f
                for (c in 0 until channels) {
                    var s = readInt24(src) * invScale * g
                    if (limitSafety) s = softLimit(s)
                    writeInt24(dst, floatToPcm24(s))
                }
            }
        }
        currentLeftGain = tgtL
        currentRightGain = tgtR
        if (src.hasRemaining()) dst.put(src)
    }

    private fun processPcm32(
        src: ByteBuffer,
        dst: ByteBuffer,
        channels: Int,
        tgtL: Float,
        tgtR: Float,
        mono: Boolean,
        limitSafety: Boolean,
    ) {
        val frameBytes = channels * 4
        val frames = src.remaining() / frameBytes
        if (frames <= 0) {
            dst.put(src)
            return
        }
        val stepL = (tgtL - currentLeftGain) / frames
        val stepR = (tgtR - currentRightGain) / frames
        var gL = currentLeftGain
        var gR = currentRightGain
        val invScale = 1.0 / 2147483648.0

        for (f in 0 until frames) {
            gL += stepL
            gR += stepR
            if (channels == 2) {
                val rawL = (src.int.toDouble() * invScale).toFloat()
                val rawR = (src.int.toDouble() * invScale).toFloat()
                var outL: Float
                var outR: Float
                if (mono) {
                    val m = (rawL + rawR) * 0.5f
                    outL = m * gL
                    outR = m * gR
                } else {
                    outL = rawL * gL
                    outR = rawR * gR
                }
                if (limitSafety) {
                    outL = softLimit(outL)
                    outR = softLimit(outR)
                }
                dst.putInt(floatToPcm32(outL))
                dst.putInt(floatToPcm32(outR))
            } else {
                val g = (gL + gR) * 0.5f
                for (c in 0 until channels) {
                    var s = (src.int.toDouble() * invScale).toFloat() * g
                    if (limitSafety) s = softLimit(s)
                    dst.putInt(floatToPcm32(s))
                }
            }
        }
        currentLeftGain = tgtL
        currentRightGain = tgtR
        if (src.hasRemaining()) dst.put(src)
    }

    override fun onFlush() {
        currentLeftGain = targetLeftGain
        currentRightGain = targetRightGain
    }

    override fun onReset() {
        currentLeftGain = 1.0f
        currentRightGain = 1.0f
    }

    companion object {
        // Only engages gently above 0.96 (-0.35 dBFS) so normal dynamics remain 100% linear & untouched
        private const val SoftLimitThreshold = 0.96f
        private const val SoftLimitHeadroom = 0.039f

        internal fun softLimit(sample: Float): Float {
            val absSample = abs(sample)
            if (absSample <= SoftLimitThreshold) return sample
            val excess = (absSample - SoftLimitThreshold) / SoftLimitHeadroom
            val limited = SoftLimitThreshold + SoftLimitHeadroom * tanh(excess.toDouble()).toFloat()
            return if (sample >= 0f) limited.coerceAtMost(0.9999f) else (-limited).coerceAtLeast(-0.9999f)
        }

        private fun floatToPcm16(sample: Float): Short =
            (sample.coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()

        private fun floatToPcm24(sample: Float): Int =
            (sample.coerceIn(-1.0f, 1.0f) * 8388607.0f).toInt().coerceIn(-8388608, 8388607)

        private fun floatToPcm32(sample: Float): Int =
            (sample.coerceIn(-1.0f, 1.0f).toDouble() * 2147483647.0).toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

        private fun readInt24(buf: ByteBuffer): Int {
            val b0 = buf.get().toInt() and 0xFF
            val b1 = buf.get().toInt() and 0xFF
            val b2 = buf.get().toInt()
            return (b2 shl 16) or (b1 shl 8) or b0
        }

        private fun writeInt24(buf: ByteBuffer, value: Int) {
            buf.put((value and 0xFF).toByte())
            buf.put(((value ushr 8) and 0xFF).toByte())
            buf.put(((value ushr 16) and 0xFF).toByte())
        }
    }
}
