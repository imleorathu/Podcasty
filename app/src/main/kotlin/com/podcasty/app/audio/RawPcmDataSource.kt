/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.podcasty.app.domain.RawPcmConfig
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streaming Media3 DataSource that wraps:
 * - Headerless Raw PCM (.pcm / .raw) using user-supplied RawPcmConfig
 * - Uncompressed AIFF (.aiff / .aif) big-endian PCM chunks
 * - Uncompressed Sun AU (.au / .snd) big-endian PCM streams
 *
 * Prepends a 44-byte RIFF/WAVE header and byte-swaps big-endian samples on the fly
 * using a small streaming buffer (never loads the audio file into RAM).
 */
@UnstableApi
class WrappedPcmDataSource(
    private val context: Context,
    private val rawPcmConfig: RawPcmConfig? = null,
    private val mode: Mode = Mode.RawPcm,
) : BaseDataSource(/* isNetwork = */ false) {

    enum class Mode { RawPcm, Aiff, SunAu }

    private var uri: Uri? = null
    private var inputStream: InputStream? = null
    private var wavHeader: ByteArray = ByteArray(0)
    private var virtualPos: Long = 0L
    private var pcmDataOffsetInSource: Long = 0L
    private var pcmDataBytes: Long = 0L
    private var bytesPerSample: Int = 2
    private var swapEndian: Boolean = false
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val resolver = context.contentResolver

        val stream = resolver.openInputStream(dataSpec.uri)
            ?: throw IOException("Cannot open audio URI: ${dataSpec.uri}")

        val params = when (mode) {
            Mode.RawPcm -> {
                val cfg = rawPcmConfig ?: RawPcmConfig(sourceUri = dataSpec.uri.toString())
                val totalFileSize = resolver.openAssetFileDescriptor(dataSpec.uri, "r")?.use { it.length }
                    ?.takeIf { it > 0L } ?: 0L
                pcmDataOffsetInSource = 0L
                pcmDataBytes = totalFileSize
                PcmStreamParams(cfg.sampleRateHz, cfg.bitDepth, cfg.channelCount, cfg.bigEndian)
            }
            Mode.Aiff -> parseAiffHeader(stream)
            Mode.SunAu -> parseSunAuHeader(stream)
        }

        bytesPerSample = (params.bitDepth / 8).coerceIn(1, 4)
        swapEndian = params.bigEndian && bytesPerSample > 1
        wavHeader = buildWavHeader(
            dataLength = pcmDataBytes.coerceAtLeast(0L),
            sampleRate = params.sampleRateHz,
            channels = params.channels,
            bitsPerSample = params.bitDepth,
        )

        // Re-open fresh stream and skip cleanly to requested offset
        stream.close()
        val playbackStream = resolver.openInputStream(dataSpec.uri)
            ?: throw IOException("Cannot open audio URI for streaming: ${dataSpec.uri}")
        inputStream = playbackStream
        virtualPos = dataSpec.position.coerceAtLeast(0L)

        val pcmSkip = (virtualPos - wavHeader.size).coerceAtLeast(0L)
        skipFully(playbackStream, pcmDataOffsetInSource + pcmSkip)

        opened = true
        transferStarted(dataSpec)

        val totalVirtualLength = if (pcmDataBytes > 0L) wavHeader.size.toLong() + pcmDataBytes else C.LENGTH_UNSET.toLong()
        return if (totalVirtualLength == C.LENGTH_UNSET.toLong()) {
            C.LENGTH_UNSET.toLong()
        } else {
            (totalVirtualLength - virtualPos).coerceAtLeast(0L)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (virtualPos < wavHeader.size) {
            val headerRemaining = wavHeader.size - virtualPos.toInt()
            val toCopy = minOf(length, headerRemaining)
            System.arraycopy(wavHeader, virtualPos.toInt(), buffer, offset, toCopy)
            virtualPos += toCopy
            bytesTransferred(toCopy)
            return toCopy
        }
        val stream = inputStream ?: return C.RESULT_END_OF_INPUT
        val maxRead = if (pcmDataBytes > 0L) {
            val remainingPcm = (wavHeader.size.toLong() + pcmDataBytes) - virtualPos
            if (remainingPcm <= 0L) return C.RESULT_END_OF_INPUT
            minOf(length.toLong(), remainingPcm).toInt()
        } else {
            length
        }
        val alignedMaxRead = if (swapEndian && maxRead >= bytesPerSample) {
            maxRead - (maxRead % bytesPerSample)
        } else {
            maxRead
        }
        val bytesRead = stream.read(buffer, offset, alignedMaxRead)
        if (bytesRead <= 0) return C.RESULT_END_OF_INPUT
        if (swapEndian) {
            swapEndianInPlace(buffer, offset, bytesRead, bytesPerSample)
        }
        virtualPos += bytesRead
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            inputStream?.close()
        } finally {
            inputStream = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    private data class PcmStreamParams(
        val sampleRateHz: Int,
        val bitDepth: Int,
        val channels: Int,
        val bigEndian: Boolean,
    )

    private fun parseAiffHeader(stream: InputStream): PcmStreamParams {
        val header = readBytesUpTo(stream, 4096)
        if (header.size < 12 || String(header, 0, 4, Charsets.US_ASCII) != "FORM") {
            throw IOException("Invalid AIFF header")
        }
        var pos = 12
        var channels = 2
        var bitDepth = 16
        var sampleRate = 44_100
        var ssndOffset = 54L
        var ssndSize = 0L

        while (pos + 8 <= header.size) {
            val chunkId = String(header, pos, 4, Charsets.US_ASCII)
            val chunkSize = readIntBE(header, pos + 4).toLong() and 0xFFFFFFFFL
            val chunkDataStart = pos + 8
            if (chunkId == "COMM" && chunkDataStart + 18 <= header.size) {
                channels = readShortBE(header, chunkDataStart).coerceIn(1, 8)
                bitDepth = readShortBE(header, chunkDataStart + 6).coerceIn(8, 32)
                sampleRate = decodeIeeeExtendedSampleRate(header, chunkDataStart + 8).coerceIn(8_000, 384_000)
            } else if (chunkId == "SSND" && chunkDataStart + 8 <= header.size) {
                val blockOffset = readIntBE(header, chunkDataStart).toLong() and 0xFFFFFFFFL
                ssndOffset = chunkDataStart.toLong() + 8L + blockOffset
                ssndSize = (chunkSize - 8L - blockOffset).coerceAtLeast(0L)
                break
            }
            val padded = chunkSize + (chunkSize and 1L)
            pos = (chunkDataStart + padded).toInt()
            if (pos <= chunkDataStart) break
        }
        pcmDataOffsetInSource = ssndOffset
        pcmDataBytes = ssndSize
        return PcmStreamParams(sampleRate, bitDepth, channels, bigEndian = true)
    }

    private fun parseSunAuHeader(stream: InputStream): PcmStreamParams {
        val header = readBytesUpTo(stream, 24)
        if (header.size < 24 || String(header, 0, 4, Charsets.US_ASCII) != ".snd") {
            throw IOException("Invalid Sun AU (.snd) header")
        }
        val dataOffset = readIntBE(header, 4).toLong() and 0xFFFFFFFFL
        val dataSize = readIntBE(header, 8).toLong() and 0xFFFFFFFFL
        val encoding = readIntBE(header, 12)
        val sampleRate = readIntBE(header, 16).coerceIn(8_000, 384_000)
        val channels = readIntBE(header, 20).coerceIn(1, 8)
        val bitDepth = when (encoding) {
            2 -> 8
            3 -> 16
            4 -> 24
            5 -> 32
            else -> throw IOException("Unsupported Sun AU encoding code: $encoding (only linear PCM 8/16/24/32-bit is supported)")
        }
        pcmDataOffsetInSource = dataOffset.coerceAtLeast(24L)
        pcmDataBytes = if (dataSize == 0xFFFFFFFFL) 0L else dataSize
        return PcmStreamParams(sampleRate, bitDepth, channels, bigEndian = true)
    }

    class Factory(
        private val context: Context,
        private val rawPcmConfig: RawPcmConfig? = null,
        private val mode: Mode = Mode.RawPcm,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = WrappedPcmDataSource(context, rawPcmConfig, mode)
    }

    companion object {
        fun buildWavHeader(
            dataLength: Long,
            sampleRate: Int,
            channels: Int,
            bitsPerSample: Int,
        ): ByteArray {
            val safeDataLen = dataLength.coerceIn(0L, Int.MAX_VALUE.toLong() - 44L).toInt()
            val byteRate = sampleRate * channels * (bitsPerSample / 8)
            val blockAlign = (channels * (bitsPerSample / 8)).toShort()
            return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(36 + safeDataLen)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16) // PCM fmt chunk size
                putShort(1) // PCM format tag
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(byteRate)
                putShort(blockAlign)
                putShort(bitsPerSample.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(safeDataLen)
            }.array()
        }

        internal fun swapEndianInPlace(buf: ByteArray, offset: Int, length: Int, bytesPerSample: Int) {
            var i = offset
            val end = offset + length
            while (i + bytesPerSample <= end) {
                var lo = i
                var hi = i + bytesPerSample - 1
                while (lo < hi) {
                    val tmp = buf[lo]
                    buf[lo] = buf[hi]
                    buf[hi] = tmp
                    lo++
                    hi--
                }
                i += bytesPerSample
            }
        }

        private fun readBytesUpTo(stream: InputStream, max: Int): ByteArray {
            val buf = ByteArray(max)
            var total = 0
            while (total < max) {
                val n = stream.read(buf, total, max - total)
                if (n <= 0) break
                total += n
            }
            return if (total == max) buf else buf.copyOf(total)
        }

        private fun skipFully(stream: InputStream, bytesToSkip: Long) {
            var remaining = bytesToSkip
            val scratch = ByteArray(4096)
            while (remaining > 0L) {
                val skipped = stream.skip(remaining)
                if (skipped > 0L) {
                    remaining -= skipped
                } else {
                    val read = stream.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
                    if (read <= 0) break
                    remaining -= read
                }
            }
        }

        private fun readShortBE(b: ByteArray, o: Int): Int =
            ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

        private fun readIntBE(b: ByteArray, o: Int): Int =
            ((b[o].toInt() and 0xFF) shl 24) or
                ((b[o + 1].toInt() and 0xFF) shl 16) or
                ((b[o + 2].toInt() and 0xFF) shl 8) or
                (b[o + 3].toInt() and 0xFF)

        /** Decodes 80-bit IEEE 754 extended float used in AIFF COMM sampleRate field. */
        private fun decodeIeeeExtendedSampleRate(b: ByteArray, o: Int): Int {
            val exponent = (((b[o].toInt() and 0x7F) shl 8) or (b[o + 1].toInt() and 0xFF)) - 16383
            val hiMantissa = readIntBE(b, o + 2).toLong() and 0xFFFFFFFFL
            if (exponent < 0 || exponent > 31) return 44_100
            return (hiMantissa ushr (31 - exponent)).toInt()
        }
    }
}
