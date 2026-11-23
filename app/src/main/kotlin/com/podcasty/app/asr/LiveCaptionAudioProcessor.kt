/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.asr

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToLong

/**
 * Immutable snapshot of PCM audio captured from ExoPlayer's audio pipeline with exact
 * media playback timestamps computed from audio frame offsets (never wall-clock arrival time).
 */
data class CapturedPcmFrame(
    val pcmBytes: ByteArray,
    val sampleRateHz: Int,
    val channelCount: Int,
    val encoding: Int,
    val mediaStartMs: Long,
    val mediaEndMs: Long,
    val generationId: Long,
)

/**
 * Non-blocking Media3 AudioProcessor installed inside PodcastPlaybackService's DefaultAudioSink.
 *
 * Guarantees:
 * - Zero modification to audio output (transparent pass-through).
 * - Zero disk I/O and never saves raw audio recordings.
 * - Zero blocking on ExoPlayer's audio thread: uses a bounded DROP_OLDEST Channel (capacity 32).
 * - Maintains an exact audio-frame-to-playback-position anchor across seeks, pauses, and speed changes.
 */
@UnstableApi
class LiveCaptionAudioProcessor(
    queueCapacity: Int = 32,
) : BaseAudioProcessor() {

    private data class TimelineAnchor(
        val mediaPositionMs: Long,
        val playbackSpeed: Float,
        val generationId: Long,
    )

    private val captureEnabled = AtomicBoolean(false)
    private val anchorRef = AtomicReference(TimelineAnchor(0L, 1.0f, 0L))

    @Volatile
    private var framesSinceAnchor: Long = 0L

    @Volatile
    private var lastGenerationSeen: Long = 0L

    private val frameChannel = Channel<CapturedPcmFrame>(
        capacity = queueCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val capturedFrames: ReceiveChannel<CapturedPcmFrame> = frameChannel

    fun setCaptureEnabled(enabled: Boolean) {
        captureEnabled.set(enabled)
        if (!enabled) {
            drainPendingFrames()
        }
    }

    fun isCaptureEnabled(): Boolean = captureEnabled.get()

    /**
     * Re-anchors the PCM frame timeline whenever playback starts, seeks, resumes, switches episodes,
     * or changes playback speed.
     */
    fun updatePlaybackAnchor(mediaPositionMs: Long, playbackSpeed: Float) {
        val nextGen = anchorRef.get().generationId + 1L
        anchorRef.set(
            TimelineAnchor(
                mediaPositionMs = mediaPositionMs.coerceAtLeast(0L),
                playbackSpeed = playbackSpeed.coerceIn(0.5f, 2.5f),
                generationId = nextGen,
            ),
        )
        framesSinceAnchor = 0L
        lastGenerationSeen = nextGen
        drainPendingFrames()
    }

    fun currentEstimatedMediaPositionMs(): Long {
        val anchor = anchorRef.get()
        val rate = inputAudioFormat.sampleRate
        if (rate <= 0) return anchor.mediaPositionMs
        val elapsedMediaMs = computeMediaDurationMs(framesSinceAnchor, rate, anchor.playbackSpeed)
        return anchor.mediaPositionMs + elapsedMediaMs
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val supported = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_24BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_32BIT
        if (!supported) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        val outputBuffer = replaceOutputBuffer(remaining)
        if (captureEnabled.get() && inputAudioFormat.sampleRate > 0 && inputAudioFormat.channelCount > 0) {
            val snapshot = ByteArray(remaining)
            val dup = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            dup.get(snapshot)

            val bytesPerSample = bytesPerSample(inputAudioFormat.encoding)
            val bytesPerFrame = (bytesPerSample * inputAudioFormat.channelCount).coerceAtLeast(1)
            val frameCount = (remaining / bytesPerFrame).toLong()

            val anchor = anchorRef.get()
            if (anchor.generationId != lastGenerationSeen) {
                framesSinceAnchor = 0L
                lastGenerationSeen = anchor.generationId
            }

            val startOffsetMs = computeMediaDurationMs(framesSinceAnchor, inputAudioFormat.sampleRate, anchor.playbackSpeed)
            framesSinceAnchor += frameCount
            val endOffsetMs = computeMediaDurationMs(framesSinceAnchor, inputAudioFormat.sampleRate, anchor.playbackSpeed)

            val mediaStartMs = anchor.mediaPositionMs + startOffsetMs
            val mediaEndMs = (anchor.mediaPositionMs + endOffsetMs).coerceAtLeast(mediaStartMs)

            // Non-blocking trySend on bounded DROP_OLDEST channel: never blocks audio playback thread
            frameChannel.trySend(
                CapturedPcmFrame(
                    pcmBytes = snapshot,
                    sampleRateHz = inputAudioFormat.sampleRate,
                    channelCount = inputAudioFormat.channelCount,
                    encoding = inputAudioFormat.encoding,
                    mediaStartMs = mediaStartMs,
                    mediaEndMs = mediaEndMs,
                    generationId = anchor.generationId,
                ),
            )
        }

        // Transparent pass-through into Media3 audio sink
        outputBuffer.put(inputBuffer)
        outputBuffer.flip()
    }

    override fun onFlush() {
        // ExoPlayer flushes AudioProcessors on seeks and track transitions.
        // We drain stale queued frames; PodcastPlaybackController updates the exact anchor position.
        drainPendingFrames()
    }

    override fun onReset() {
        captureEnabled.set(false)
        framesSinceAnchor = 0L
        drainPendingFrames()
    }

    private fun drainPendingFrames() {
        while (frameChannel.tryReceive().isSuccess) {
            // discard stale frames
        }
    }

    companion object {
        /**
         * Because [LiveCaptionAudioProcessor] sits ahead of Media3's `SonicAudioProcessor` in
         * `DefaultAudioProcessorChain`, decoded PCM frames advance at 1:1 original media duration
         * regardless of playback speed (0.5x..2.5x). Keeping `playbackSpeed` recorded on the anchor
         * preserves diagnostics while preventing speed-induced caption drift.
         */
        internal fun computeMediaDurationMs(
            frameCount: Long,
            sampleRateHz: Int,
            @Suppress("UNUSED_PARAMETER") playbackSpeed: Float = 1.0f,
        ): Long {
            if (frameCount <= 0L || sampleRateHz <= 0) return 0L
            val rawSeconds = frameCount.toDouble() / sampleRateHz.toDouble()
            return (rawSeconds * 1_000.0).roundToLong()
        }

        private fun bytesPerSample(encoding: Int): Int = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> 2
        }
    }
}
