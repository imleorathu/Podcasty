/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.transcript

import java.io.InputStream
import java.nio.charset.Charset

/**
 * Pure-Kotlin ID3v2.3 / ID3v2.4 SYLT (Synchronised Lyrics/Text) binary frame parser.
 *
 * Extracts authentic embedded synchronized lyrics from MP3 / ID3v2 audio headers when TagLib's
 * string property map does not expose binary SYLT frames. Only timestampFormat == 2 (milliseconds)
 * is converted into timed cues; timestamps are never fabricated.
 */
object Id3SyltParser {
    private const val MaxHeaderReadBytes = 512 * 1024

    data class SyltTrack(
        val language: String,
        val description: String,
        val cues: List<TranscriptCue>,
    )

    fun extractFromStream(input: InputStream): SyltTrack? {
        val header = ByteArray(10)
        if (!readFully(input, header, 0, 10)) return null
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) {
            return null
        }
        val majorVersion = header[3].toInt() and 0xFF
        if (majorVersion !in 3..4) return null
        val flags = header[5].toInt() and 0xFF
        val tagSize = decodeSyncSafeInt(header, 6)
        if (tagSize <= 0 || tagSize > MaxHeaderReadBytes) return null

        val body = ByteArray(tagSize)
        if (!readFully(input, body, 0, tagSize)) return null
        return extractFromId3Body(body, majorVersion, flags)
    }

    fun extractFromBytes(bytes: ByteArray): SyltTrack? {
        if (bytes.size < 10) return null
        if (bytes[0] != 'I'.code.toByte() || bytes[1] != 'D'.code.toByte() || bytes[2] != '3'.code.toByte()) {
            return null
        }
        val majorVersion = bytes[3].toInt() and 0xFF
        if (majorVersion !in 3..4) return null
        val flags = bytes[5].toInt() and 0xFF
        val tagSize = decodeSyncSafeInt(bytes, 6).coerceAtMost(bytes.size - 10)
        if (tagSize <= 0) return null
        return extractFromId3Body(bytes.copyOfRange(10, 10 + tagSize), majorVersion, flags)
    }

    private fun extractFromId3Body(body: ByteArray, majorVersion: Int, headerFlags: Int): SyltTrack? {
        var offset = 0
        // Skip optional extended header (bit 6 = 0x40)
        if ((headerFlags and 0x40) != 0) {
            if (body.size < 4) return null
            val extSize = if (majorVersion == 4) decodeSyncSafeInt(body, 0) else decodeBigEndianInt(body, 0)
            offset += if (majorVersion == 4) extSize.coerceAtLeast(4) else (extSize + 4).coerceAtLeast(4)
        }

        while (offset + 10 <= body.size) {
            // Padding reached
            if (body[offset].toInt() == 0 && body[offset + 1].toInt() == 0 &&
                body[offset + 2].toInt() == 0 && body[offset + 3].toInt() == 0
            ) {
                break
            }
            val frameId = String(body, offset, 4, Charsets.ISO_8859_1)
            val frameSize = if (majorVersion == 4) {
                decodeSyncSafeInt(body, offset + 4)
            } else {
                decodeBigEndianInt(body, offset + 4)
            }
            if (frameSize <= 0 || offset + 10 + frameSize > body.size) break
            val frameStart = offset + 10
            if (frameId == "SYLT") {
                parseSyltFrame(body, frameStart, frameSize)?.let { track ->
                    if (track.cues.isNotEmpty()) return track
                }
            }
            offset += 10 + frameSize
        }
        return null
    }

    private fun parseSyltFrame(data: ByteArray, start: Int, length: Int): SyltTrack? {
        val end = start + length
        if (length < 7) return null
        val encoding = data[start].toInt() and 0xFF
        val language = String(data, start + 1, 3, Charsets.ISO_8859_1)
            .lowercase()
            .filter { it in 'a'..'z' }
            .ifBlank { "en" }
        val timestampFormat = data[start + 4].toInt() and 0xFF
        // Only millisecond timestamps (format 2) map reliably to playback positionMs
        if (timestampFormat != 2) return null
        val contentType = data[start + 5].toInt() and 0xFF
        // Accept Other (0), Lyrics (1), Text transcription (2)
        if (contentType !in 0..2) return null

        val isDoubleNull = encoding == 1 || encoding == 2
        var pos = start + 6
        val descEnd = findStringTerminator(data, pos, end, isDoubleNull)
        if (descEnd < 0) return null
        val description = decodeEncodedString(data, pos, descEnd - pos, encoding).trim()
        pos = descEnd + if (isDoubleNull) 2 else 1

        data class RawEntry(val startMs: Long, val text: String)
        val raw = mutableListOf<RawEntry>()
        while (pos < end) {
            val textEnd = findStringTerminator(data, pos, end, isDoubleNull)
            if (textEnd < 0 || textEnd + (if (isDoubleNull) 2 else 1) + 4 > end) break
            val text = decodeEncodedString(data, pos, textEnd - pos, encoding)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\n', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
            val tsOffset = textEnd + if (isDoubleNull) 2 else 1
            val timestampMs = decodeUnsignedBigEndianInt(data, tsOffset)
            pos = tsOffset + 4
            if (text.isNotEmpty()) {
                raw += RawEntry(startMs = timestampMs, text = text)
            }
        }
        if (raw.isEmpty()) return null

        val sorted = raw.sortedBy { it.startMs }
        val merged = mutableListOf<Pair<Long, Pair<String, String?>>>()
        for (entry in sorted) {
            val prev = merged.lastOrNull()
            if (prev != null && kotlin.math.abs(entry.startMs - prev.first) <= 50L &&
                prev.second.second == null && !prev.second.first.equals(entry.text, ignoreCase = false)
            ) {
                merged[merged.lastIndex] = prev.first to (prev.second.first to entry.text)
            } else {
                merged += entry.startMs to (entry.text to null)
            }
        }
        val cues = merged.mapIndexed { idx, (startMs, pair) ->
            val nextStart = merged.getOrNull(idx + 1)?.first
            val endMs = if (nextStart != null && nextStart > startMs) nextStart else startMs + 6_000L
            TranscriptCue(
                id = idx.toLong() + 1L,
                startMs = startMs,
                endMs = endMs,
                text = pair.first,
                translation = pair.second,
            )
        }
        val isoLang = mapIso6392ToIso6391(language)
        return SyltTrack(language = isoLang, description = description, cues = cues)
    }

    private fun findStringTerminator(data: ByteArray, from: Int, limit: Int, doubleNull: Boolean): Int {
        if (!doubleNull) {
            for (i in from until limit) {
                if (data[i].toInt() == 0) return i
            }
            return -1
        }
        var i = from
        while (i + 1 < limit) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) return i
            i += 2
        }
        return -1
    }

    private fun decodeEncodedString(data: ByteArray, offset: Int, length: Int, encoding: Int): String {
        if (length <= 0) return ""
        return when (encoding) {
            0 -> String(data, offset, length, Charsets.ISO_8859_1)
            1 -> {
                if (length >= 2) {
                    val b0 = data[offset].toInt() and 0xFF
                    val b1 = data[offset + 1].toInt() and 0xFF
                    when {
                        b0 == 0xFF && b1 == 0xFE -> String(data, offset + 2, length - 2, Charsets.UTF_16LE)
                        b0 == 0xFE && b1 == 0xFF -> String(data, offset + 2, length - 2, Charsets.UTF_16BE)
                        else -> String(data, offset, length, Charset.forName("UTF-16"))
                    }
                } else {
                    ""
                }
            }
            2 -> String(data, offset, length, Charsets.UTF_16BE)
            3 -> String(data, offset, length, Charsets.UTF_8)
            else -> String(data, offset, length, Charsets.UTF_8)
        }
    }

    private fun decodeSyncSafeInt(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0x7F) shl 21) or
            ((data[offset + 1].toInt() and 0x7F) shl 14) or
            ((data[offset + 2].toInt() and 0x7F) shl 7) or
            (data[offset + 3].toInt() and 0x7F)

    private fun decodeBigEndianInt(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    private fun decodeUnsignedBigEndianInt(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFFL) shl 24) or
            ((data[offset + 1].toLong() and 0xFFL) shl 16) or
            ((data[offset + 2].toLong() and 0xFFL) shl 8) or
            (data[offset + 3].toLong() and 0xFFL)

    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int): Boolean {
        var total = 0
        while (total < length) {
            val read = input.read(buffer, offset + total, length - total)
            if (read < 0) return false
            total += read
        }
        return true
    }

    private fun mapIso6392ToIso6391(code: String): String = when (code.lowercase()) {
        "tam" -> "ta"
        "sin" -> "si"
        "eng" -> "en"
        "hin" -> "hi"
        "mal" -> "ml"
        "tel" -> "te"
        "kan" -> "kn"
        "ben" -> "bn"
        "ara" -> "ar"
        "jpn" -> "ja"
        "kor" -> "ko"
        "zho", "chi" -> "zh"
        "spa" -> "es"
        "fra", "fre" -> "fr"
        "deu", "ger" -> "de"
        else -> if (code.length == 2) code else "en"
    }
}
