/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.asr

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure, off-playback-thread PCM preprocessing pipeline for streaming speech recognition:
 * 1. Decodes incoming PCM (16-bit / 24-bit / 32-bit / float) into normalized mono float samples.
 * 2. Downmixes stereo/multi-channel audio to mono.
 * 3. Resamples cleanly to target 16,000 Hz (required by Google Cloud STT, Sherpa-ONNX, Whisper.cpp).
 * 4. Applies gentle RMS normalization (capped gain to prevent boosting background noise).
 * 5. Runs lightweight Energy + Zero-Crossing-Rate Voice Activity Detection (VAD) with hangover frames.
 * 6. Slices audio into fixed-duration chunks (default 100 ms = 1,600 samples = 3,200 bytes) tagged
 *    with exact original playback media timestamps (`mediaStartMs`, `mediaEndMs`).
 *
 * Zero disk writes — operates purely in bounded in-memory buffers and releases buffers immediately.
 */
class AudioChunkPreprocessor(
    private val targetSampleRateHz: Int = 16_000,
    chunkDurationMs: Int = 100,
    private val vadEnergyThreshold: Float = 0.008f,
    private val vadHangoverChunks: Int = 6,
) {
    private val targetChunkSamples: Int = ((targetSampleRateHz * chunkDurationMs) / 1000).coerceAtLeast(320)
    private val sampleBuffer = FloatArray(targetChunkSamples * 4)
    private var bufferedCount: Int = 0
    private var bufferStartMediaMs: Long = 0L
    private var currentGenerationId: Long = -1L
    private var hangoverRemaining: Int = 0

    fun reset() {
        bufferedCount = 0
        bufferStartMediaMs = 0L
        currentGenerationId = -1L
        hangoverRemaining = 0
    }

    fun processFrame(frame: CapturedPcmFrame): List<ProcessedAudioChunk> {
        if (frame.pcmBytes.isEmpty() || frame.sampleRateHz <= 0 || frame.channelCount <= 0) {
            return emptyList()
        }
        if (currentGenerationId != -1L && frame.generationId != currentGenerationId) {
            reset()
        }
        currentGenerationId = frame.generationId

        val monoInput = decodeAndDownmixToMono(frame.pcmBytes, frame.channelCount, frame.encoding)
        if (monoInput.isEmpty()) return emptyList()

        val resampled = if (frame.sampleRateHz == targetSampleRateHz) {
            monoInput
        } else {
            resampleLinear(monoInput, frame.sampleRateHz, targetSampleRateHz)
        }
        if (resampled.isEmpty()) return emptyList()

        val totalMediaSpanMs = (frame.mediaEndMs - frame.mediaStartMs).coerceAtLeast(
            (resampled.size * 1_000L) / targetSampleRateHz,
        )
        val msPerSample = totalMediaSpanMs.toDouble() / resampled.size.toDouble()

        val emitted = mutableListOf<ProcessedAudioChunk>()
        var readIdx = 0
        while (readIdx < resampled.size) {
            if (bufferedCount == 0) {
                bufferStartMediaMs = frame.mediaStartMs + (readIdx * msPerSample).toLong()
            }
            val spaceLeft = sampleBuffer.size - bufferedCount
            val toCopy = min(spaceLeft, resampled.size - readIdx)
            System.arraycopy(resampled, readIdx, sampleBuffer, bufferedCount, toCopy)
            bufferedCount += toCopy
            readIdx += toCopy

            while (bufferedCount >= targetChunkSamples) {
                val chunkSamples = sampleBuffer.copyOfRange(0, targetChunkSamples)
                val remaining = bufferedCount - targetChunkSamples
                if (remaining > 0) {
                    System.arraycopy(sampleBuffer, targetChunkSamples, sampleBuffer, 0, remaining)
                }
                bufferedCount = remaining

                val chunkDurationRealMs = ((targetChunkSamples * msPerSample).toLong()).coerceAtLeast(1L)
                val mediaStart = bufferStartMediaMs
                val mediaEnd = mediaStart + chunkDurationRealMs
                bufferStartMediaMs = mediaEnd

                emitted += finalizeChunk(chunkSamples, mediaStart, mediaEnd)
            }
        }
        return emitted
    }

    private fun finalizeChunk(
        samples: FloatArray,
        mediaStartMs: Long,
        mediaEndMs: Long,
    ): ProcessedAudioChunk {
        val rms = computeRms(samples)
        val zcr = computeZeroCrossingRate(samples)
        val rawSpeech = rms >= vadEnergyThreshold && zcr <= 0.45f
        val isSpeech = if (rawSpeech) {
            hangoverRemaining = vadHangoverChunks
            true
        } else if (hangoverRemaining > 0) {
            hangoverRemaining -= 1
            true
        } else {
            false
        }

        // Gentle speech RMS normalization targeting ~0.12 RMS with gain capped at 4x
        val normalized = if (rms > 0.002f) {
            val gain = (0.12f / rms).coerceIn(0.5f, 4.0f)
            FloatArray(samples.size) { idx -> (samples[idx] * gain).coerceIn(-0.98f, 0.98f) }
        } else {
            samples
        }

        val pcmBytes = floatToPcm16LeBytes(normalized)
        return ProcessedAudioChunk(
            pcm16Bytes = pcmBytes,
            sampleRateHz = targetSampleRateHz,
            sampleCount = normalized.size,
            mediaStartMs = mediaStartMs,
            mediaEndMs = mediaEndMs,
            isSpeech = isSpeech,
            rmsEnergy = rms,
        )
    }

    companion object {
        fun decodeAndDownmixToMono(
            bytes: ByteArray,
            channelCount: Int,
            encoding: Int = C.ENCODING_PCM_16BIT,
        ): FloatArray {
            if (bytes.isEmpty() || channelCount <= 0) return FloatArray(0)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return when (encoding) {
                C.ENCODING_PCM_16BIT -> {
                    val totalFrames = bytes.size / (2 * channelCount)
                    val out = FloatArray(totalFrames)
                    val invScale = 1f / 32768f
                    for (f in 0 until totalFrames) {
                        var sum = 0f
                        for (c in 0 until channelCount) {
                            sum += bb.short.toFloat() * invScale
                        }
                        out[f] = sum / channelCount
                    }
                    out
                }

                C.ENCODING_PCM_FLOAT -> {
                    val totalFrames = bytes.size / (4 * channelCount)
                    val out = FloatArray(totalFrames)
                    for (f in 0 until totalFrames) {
                        var sum = 0f
                        for (c in 0 until channelCount) {
                            sum += bb.float.coerceIn(-1f, 1f)
                        }
                        out[f] = sum / channelCount
                    }
                    out
                }

                C.ENCODING_PCM_32BIT -> {
                    val totalFrames = bytes.size / (4 * channelCount)
                    val out = FloatArray(totalFrames)
                    val invScale = 1f / 2147483648f
                    for (f in 0 until totalFrames) {
                        var sum = 0f
                        for (c in 0 until channelCount) {
                            sum += bb.int.toFloat() * invScale
                        }
                        out[f] = sum / channelCount
                    }
                    out
                }

                C.ENCODING_PCM_24BIT -> {
                    val totalFrames = bytes.size / (3 * channelCount)
                    val out = FloatArray(totalFrames)
                    val invScale = 1f / 8388608f
                    var pos = 0
                    for (f in 0 until totalFrames) {
                        var sum = 0f
                        for (c in 0 until channelCount) {
                            val b0 = bytes[pos].toInt() and 0xFF
                            val b1 = bytes[pos + 1].toInt() and 0xFF
                            val b2 = bytes[pos + 2].toInt()
                            val sample24 = (b2 shl 16) or (b1 shl 8) or b0
                            sum += sample24.toFloat() * invScale
                            pos += 3
                        }
                        out[f] = sum / channelCount
                    }
                    out
                }

                else -> FloatArray(0)
            }
        }

        fun resampleLinear(input: FloatArray, fromRateHz: Int, toRateHz: Int): FloatArray {
            if (input.isEmpty() || fromRateHz <= 0 || toRateHz <= 0) return FloatArray(0)
            if (fromRateHz == toRateHz) return input
            val ratio = fromRateHz.toDouble() / toRateHz.toDouble()
            val outLength = (input.size.toDouble() / ratio).roundToInt().coerceAtLeast(1)
            val output = FloatArray(outLength)
            for (i in 0 until outLength) {
                val srcPos = i * ratio
                val idx0 = srcPos.toInt().coerceIn(0, input.lastIndex)
                val idx1 = (idx0 + 1).coerceAtMost(input.lastIndex)
                val frac = (srcPos - idx0).toFloat()
                output[i] = input[idx0] * (1f - frac) + input[idx1] * frac
            }
            return output
        }

        fun floatToPcm16LeBytes(samples: FloatArray): ByteArray {
            val out = ByteArray(samples.size * 2)
            var offset = 0
            for (sample in samples) {
                val clamped = (sample.coerceIn(-1f, 1f) * 32767f).roundToInt().coerceIn(-32768, 32767)
                out[offset] = (clamped and 0xFF).toByte()
                out[offset + 1] = ((clamped shr 8) and 0xFF).toByte()
                offset += 2
            }
            return out
        }

        internal fun computeRms(samples: FloatArray): Float {
            if (samples.isEmpty()) return 0f
            var sumSq = 0.0
            for (s in samples) {
                sumSq += (s * s).toDouble()
            }
            return sqrt(sumSq / samples.size).toFloat()
        }

        internal fun computeZeroCrossingRate(samples: FloatArray): Float {
            if (samples.size < 2) return 0f
            var crossings = 0
            for (i in 1 until samples.size) {
                if ((samples[i - 1] >= 0f && samples[i] < 0f) || (samples[i - 1] < 0f && samples[i] >= 0f)) {
                    if (abs(samples[i] - samples[i - 1]) > 0.001f) {
                        crossings += 1
                    }
                }
            }
            return crossings.toFloat() / samples.size.toFloat()
        }
    }
}
