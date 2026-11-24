/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.storage

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64

/**
 * Zero-dependency, bounded-header binary embedded artwork parser for formats where
 * Android's MediaMetadataRetriever and TagLib FD-mode occasionally return empty results:
 *
 * - FLAC native `METADATA_BLOCK_PICTURE` (block type 6) regardless of block order or size,
 *   plus `VORBIS_COMMENT` Base64 `METADATA_BLOCK_PICTURE` tags.
 * - Ogg Vorbis & Ogg Opus multi-page `OpusTags` / `\x03vorbis` `METADATA_BLOCK_PICTURE` (RFC 7845)
 *   and legacy `COVERART` tags.
 * - ID3v2.2 (`PIC`), ID3v2.3 (`APIC`), and ID3v2.4 (`APIC`) frames across ALL picture types
 *   (including `0x00` Other, `0x03` Front Cover, etc.), plus ID3-wrapped FLAC streams.
 *
 * Never reads audio frame payloads; strictly bounded to metadata headers so 100 MB+ lossless
 * files parse in a few milliseconds without memory spikes.
 */
object EmbeddedArtworkParser {

    data class ExtractedPicture(
        val pictureType: Int,
        val mimeType: String,
        val bytes: ByteArray,
    )

    private const val MaxSinglePictureBytes = 16 * 1024 * 1024
    private const val MaxHeaderReadBytes = 20 * 1024 * 1024
    private const val MaxOggCommentBytes = 12 * 1024 * 1024
    private const val MaxOggPagesToScan = 256

    /**
     * Parses embedded cover pictures from an audio stream in priority order
     * (Front Cover 0x03 -> Other 0x00 -> Cover/Media -> other valid images).
     */
    fun extractPictures(rawStream: InputStream): List<ExtractedPicture> {
        val stream = if (rawStream.markSupported()) rawStream else BufferedInputStream(rawStream, 16 * 1024)
        stream.mark(16)
        val magic = ByteArray(4)
        val n = readFullyOrEof(stream, magic, 0, 4)
        if (n < 4) return emptyList()
        stream.reset()

        val pictures = when {
            magic[0] == 'f'.code.toByte() && magic[1] == 'L'.code.toByte() &&
                magic[2] == 'a'.code.toByte() && magic[3] == 'C'.code.toByte() ->
                parseFlacPictures(stream)

            magic[0] == 'O'.code.toByte() && magic[1] == 'g'.code.toByte() &&
                magic[2] == 'g'.code.toByte() && magic[3] == 'S'.code.toByte() ->
                parseOggPictures(stream)

            magic[0] == 'I'.code.toByte() && magic[1] == 'D'.code.toByte() &&
                magic[2] == '3'.code.toByte() ->
                parseId3v2AndTrailingFlac(stream)

            else -> emptyList()
        }

        return pictures
            .filter { it.bytes.size > 32 && it.bytes.size <= MaxSinglePictureBytes }
            .sortedBy { pictureTypeRank(it.pictureType) }
    }

    internal fun pictureTypeRank(type: Int): Int = when (type) {
        3 -> 0 // Front Cover
        0 -> 1 // Other (common on LAME/FFmpeg/regional MP3s)
        4, 5, 6, 17, 18 -> 2 // Back cover, Leaflet, Media, Illustration, Logo
        else -> 3
    }

    private fun parseFlacPictures(stream: InputStream): List<ExtractedPicture> {
        val magic = ByteArray(4)
        if (readFullyOrEof(stream, magic, 0, 4) != 4) return emptyList()
        if (magic[0] != 'f'.code.toByte() || magic[1] != 'L'.code.toByte() ||
            magic[2] != 'a'.code.toByte() || magic[3] != 'C'.code.toByte()
        ) {
            return emptyList()
        }

        val out = ArrayList<ExtractedPicture>(2)
        val blockHeader = ByteArray(4)
        var totalRead = 4L

        while (totalRead < MaxHeaderReadBytes) {
            if (readFullyOrEof(stream, blockHeader, 0, 4) != 4) break
            totalRead += 4
            val firstByte = blockHeader[0].toInt() and 0xFF
            val isLast = (firstByte and 0x80) != 0
            val blockType = firstByte and 0x7F
            val blockLen = ((blockHeader[1].toInt() and 0xFF) shl 16) or
                ((blockHeader[2].toInt() and 0xFF) shl 8) or
                (blockHeader[3].toInt() and 0xFF)

            if (blockLen < 0 || blockLen > MaxSinglePictureBytes + 65536) break
            totalRead += blockLen

            when (blockType) {
                6 -> { // METADATA_BLOCK_PICTURE
                    val payload = readExactBytes(stream, blockLen) ?: break
                    parseFlacPictureBlock(payload)?.let { out += it }
                }
                4 -> { // VORBIS_COMMENT (may contain Base64 METADATA_BLOCK_PICTURE)
                    if (blockLen <= MaxOggCommentBytes) {
                        val payload = readExactBytes(stream, blockLen) ?: break
                        parseVorbisCommentBlock(payload, offset = 0, out = out)
                    } else {
                        if (!skipExact(stream, blockLen.toLong())) break
                    }
                }
                else -> {
                    if (!skipExact(stream, blockLen.toLong())) break
                }
            }
            if (isLast) break
        }
        return out
    }

    internal fun parseFlacPictureBlock(data: ByteArray): ExtractedPicture? {
        if (data.size < 32) return null
        var off = 0
        val picType = readIntBE(data, off); off += 4
        val mimeLen = readIntBE(data, off); off += 4
        if (mimeLen < 0 || mimeLen > 1024 || off + mimeLen + 4 > data.size) return null
        val mime = String(data, off, mimeLen, Charsets.ISO_8859_1); off += mimeLen
        if (mime == "-->") return null // External URL link, not embedded image bytes
        val descLen = readIntBE(data, off); off += 4
        if (descLen < 0 || off + descLen + 20 > data.size) return null
        off += descLen
        // width(4), height(4), depth(4), colors(4), dataLen(4)
        off += 16
        val imgLen = readIntBE(data, off); off += 4
        if (imgLen <= 32 || imgLen > MaxSinglePictureBytes || off + imgLen > data.size) return null
        val imgBytes = data.copyOfRange(off, off + imgLen)
        return ExtractedPicture(pictureType = picType, mimeType = mime, bytes = imgBytes)
    }

    private fun parseOggPictures(stream: InputStream): List<ExtractedPicture> {
        val out = ArrayList<ExtractedPicture>(2)
        val pageHeader = ByteArray(27)
        val packetBuf = ByteArrayOutputStream(4096)
        var packetsCompleted = 0
        var pagesScanned = 0

        while (pagesScanned < MaxOggPagesToScan && packetsCompleted < 2) {
            if (readFullyOrEof(stream, pageHeader, 0, 27) != 27) break
            if (pageHeader[0] != 'O'.code.toByte() || pageHeader[1] != 'g'.code.toByte() ||
                pageHeader[2] != 'g'.code.toByte() || pageHeader[3] != 'S'.code.toByte()
            ) {
                break
            }
            pagesScanned++
            val numSegments = pageHeader[26].toInt() and 0xFF
            val segTable = readExactBytes(stream, numSegments) ?: break
            var bodyLen = 0
            for (s in segTable) bodyLen += (s.toInt() and 0xFF)
            if (bodyLen < 0 || packetBuf.size() + bodyLen > MaxOggCommentBytes) break
            val body = readExactBytes(stream, bodyLen) ?: break

            var bodyOff = 0
            for (seg in segTable) {
                val segLen = seg.toInt() and 0xFF
                if (segLen > 0) {
                    packetBuf.write(body, bodyOff, segLen)
                    bodyOff += segLen
                }
                if (segLen < 255) {
                    val pkt = packetBuf.toByteArray()
                    packetBuf.reset()
                    packetsCompleted++
                    extractPicturesFromOggCommentPacket(pkt, out)
                    if (packetsCompleted >= 2) break
                }
            }
        }
        return out
    }

    private fun extractPicturesFromOggCommentPacket(packet: ByteArray, out: MutableList<ExtractedPicture>) {
        val commentOffset = when {
            packet.size > 16 &&
                packet[0] == 'O'.code.toByte() && packet[1] == 'p'.code.toByte() &&
                packet[2] == 'u'.code.toByte() && packet[3] == 's'.code.toByte() &&
                packet[4] == 'T'.code.toByte() && packet[5] == 'a'.code.toByte() &&
                packet[6] == 'g'.code.toByte() && packet[7] == 's'.code.toByte() -> 8

            packet.size > 15 &&
                packet[0] == 3.toByte() &&
                packet[1] == 'v'.code.toByte() && packet[2] == 'o'.code.toByte() &&
                packet[3] == 'r'.code.toByte() && packet[4] == 'b'.code.toByte() &&
                packet[5] == 'i'.code.toByte() && packet[6] == 's'.code.toByte() -> 7

            else -> return
        }
        parseVorbisCommentBlock(packet, commentOffset, out)
    }

    private fun parseVorbisCommentBlock(data: ByteArray, offset: Int, out: MutableList<ExtractedPicture>) {
        var off = offset
        if (off + 4 > data.size) return
        val vendorLen = readIntLE(data, off); off += 4
        if (vendorLen < 0 || off + vendorLen + 4 > data.size) return
        off += vendorLen
        val count = readIntLE(data, off); off += 4
        if (count <= 0 || count > 4096) return

        for (i in 0 until count) {
            if (off + 4 > data.size) break
            val clen = readIntLE(data, off); off += 4
            if (clen < 0 || off + clen > data.size) break
            val start = off
            off += clen

            val eqIdx = indexOfByte(data, start, start + clen, '='.code.toByte())
            if (eqIdx <= start) continue
            val keyLen = eqIdx - start
            if (keyLen == 22 && asciiEqualsIgnoreCase(data, start, "METADATA_BLOCK_PICTURE")) {
                val valStart = eqIdx + 1
                val valLen = (start + clen) - valStart
                if (valLen > 32) {
                    val decoded = decodeBase64Lenient(data, valStart, valLen)
                    if (decoded != null) {
                        parseFlacPictureBlock(decoded)?.let { out += it }
                    }
                }
            } else if (keyLen == 8 && asciiEqualsIgnoreCase(data, start, "COVERART")) {
                val valStart = eqIdx + 1
                val valLen = (start + clen) - valStart
                if (valLen > 32) {
                    val decoded = decodeBase64Lenient(data, valStart, valLen)
                    if (decoded != null && decoded.size > 32) {
                        out += ExtractedPicture(pictureType = 3, mimeType = "image/*", bytes = decoded)
                    }
                }
            }
        }
    }

    private fun parseId3v2AndTrailingFlac(stream: InputStream): List<ExtractedPicture> {
        val header = ByteArray(10)
        if (readFullyOrEof(stream, header, 0, 10) != 10) return emptyList()
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) {
            return emptyList()
        }
        val majorVersion = header[3].toInt() and 0xFF
        val flags = header[5].toInt() and 0xFF
        val tagSize = readSynchsafeInt(header, 6)
        if (tagSize <= 0 || tagSize > MaxHeaderReadBytes) return emptyList()

        val tagPayload = readExactBytes(stream, tagSize) ?: return emptyList()
        val out = ArrayList<ExtractedPicture>(2)
        val tagUnsync = (flags and 0x80) != 0
        val hasExtHeader = (flags and 0x40) != 0

        val body = if (tagUnsync && majorVersion < 4) removeUnsynchronization(tagPayload) else tagPayload
        var off = 0
        if (hasExtHeader && body.size >= 4) {
            val extSize = if (majorVersion >= 4) readSynchsafeInt(body, 0) else readIntBE(body, 0) + 4
            if (extSize in 4..body.size) off = extSize
        }

        if (majorVersion == 2) {
            while (off + 6 <= body.size) {
                if (body[off] == 0.toByte()) break
                val id = String(body, off, 3, Charsets.ISO_8859_1)
                val frameSize = ((body[off + 3].toInt() and 0xFF) shl 16) or
                    ((body[off + 4].toInt() and 0xFF) shl 8) or
                    (body[off + 5].toInt() and 0xFF)
                off += 6
                if (frameSize <= 0 || off + frameSize > body.size) break
                if (id == "PIC") {
                    parseId3v22PicFrame(body, off, frameSize)?.let { out += it }
                }
                off += frameSize
            }
        } else if (majorVersion == 3 || majorVersion == 4) {
            while (off + 10 <= body.size) {
                if (body[off] == 0.toByte()) break
                val id = String(body, off, 4, Charsets.ISO_8859_1)
                val frameSize = if (majorVersion == 4) {
                    val ss = readSynchsafeInt(body, off + 4)
                    // Some v2.4 writers mistakenly write regular big-endian sizes; fall back if synchsafe overflows
                    if (off + 10 + ss <= body.size) ss else readIntBE(body, off + 4)
                } else {
                    readIntBE(body, off + 4)
                }
                val frameFlags2 = body[off + 9].toInt() and 0xFF
                off += 10
                if (frameSize <= 0 || off + frameSize > body.size) break
                if (id == "APIC") {
                    var frameData = body.copyOfRange(off, off + frameSize)
                    if (majorVersion == 4) {
                        var trimStart = 0
                        if ((frameFlags2 and 0x01) != 0 && frameData.size > 4) trimStart += 4 // Data Length Indicator
                        if (trimStart > 0) frameData = frameData.copyOfRange(trimStart, frameData.size)
                        if (tagUnsync || (frameFlags2 and 0x02) != 0) {
                            frameData = removeUnsynchronization(frameData)
                        }
                    }
                    parseId3v23ApicFrame(frameData)?.let { out += it }
                }
                off += frameSize
            }
        }

        if (out.isEmpty()) {
            // Some FLAC files have an ID3v2 header prepended before `fLaC`
            out += parseFlacPictures(stream)
        }
        return out
    }

    private fun parseId3v22PicFrame(data: ByteArray, start: Int, len: Int): ExtractedPicture? {
        if (len < 8) return null
        val encoding = data[start].toInt() and 0xFF
        val fmt = String(data, start + 1, 3, Charsets.ISO_8859_1)
        val picType = data[start + 4].toInt() and 0xFF
        val descEnd = skipNullTerminatedString(data, start + 5, start + len, encoding)
        if (descEnd >= start + len) return null
        val imgBytes = data.copyOfRange(descEnd, start + len)
        if (imgBytes.size <= 32) return null
        val mime = when (fmt.uppercase()) {
            "PNG" -> "image/png"
            "JPG" -> "image/jpeg"
            else -> "image/*"
        }
        return ExtractedPicture(picType, mime, imgBytes)
    }

    private fun parseId3v23ApicFrame(frame: ByteArray): ExtractedPicture? {
        if (frame.size < 16) return null
        val encoding = frame[0].toInt() and 0xFF
        val mimeEnd = indexOfByte(frame, 1, frame.size, 0)
        if (mimeEnd < 1 || mimeEnd + 2 >= frame.size) return null
        val mime = String(frame, 1, mimeEnd - 1, Charsets.ISO_8859_1)
        if (mime == "-->") return null
        val picType = frame[mimeEnd + 1].toInt() and 0xFF
        val imgStart = skipNullTerminatedString(frame, mimeEnd + 2, frame.size, encoding)
        if (imgStart >= frame.size) return null
        val imgBytes = frame.copyOfRange(imgStart, frame.size)
        if (imgBytes.size <= 32) return null
        return ExtractedPicture(picType, mime.ifBlank { "image/jpeg" }, imgBytes)
    }

    private fun skipNullTerminatedString(data: ByteArray, start: Int, end: Int, encoding: Int): Int {
        var i = start
        if (encoding == 1 || encoding == 2) {
            // UTF-16: 2-byte null terminator aligned on 2-byte steps
            while (i + 1 < end) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) return i + 2
                i += 2
            }
            return end
        }
        while (i < end) {
            if (data[i] == 0.toByte()) return i + 1
            i++
        }
        return end
    }

    private fun removeUnsynchronization(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(input.size)
        var i = 0
        while (i < input.size) {
            val b = input[i]
            out.write(b.toInt() and 0xFF)
            if (b == 0xFF.toByte() && i + 1 < input.size && input[i + 1] == 0x00.toByte()) {
                i += 2
            } else {
                i++
            }
        }
        return out.toByteArray()
    }

    private fun decodeBase64Lenient(data: ByteArray, start: Int, len: Int): ByteArray? = runCatching {
        val slice = String(data, start, len, Charsets.ISO_8859_1).trim()
        Base64.getMimeDecoder().decode(slice)
    }.getOrNull()

    private fun asciiEqualsIgnoreCase(data: ByteArray, start: Int, target: String): Boolean {
        for (i in target.indices) {
            val a = data[start + i].toInt() and 0xFF
            val b = target[i].code
            val aUp = if (a in 'a'.code..'z'.code) a - 32 else a
            val bUp = if (b in 'a'.code..'z'.code) b - 32 else b
            if (aUp != bUp) return false
        }
        return true
    }

    private fun indexOfByte(data: ByteArray, start: Int, end: Int, value: Byte): Int {
        for (i in start until end) {
            if (data[i] == value) return i
        }
        return -1
    }

    private fun readIntBE(data: ByteArray, off: Int): Int =
        ((data[off].toInt() and 0xFF) shl 24) or
            ((data[off + 1].toInt() and 0xFF) shl 16) or
            ((data[off + 2].toInt() and 0xFF) shl 8) or
            (data[off + 3].toInt() and 0xFF)

    private fun readIntLE(data: ByteArray, off: Int): Int =
        (data[off].toInt() and 0xFF) or
            ((data[off + 1].toInt() and 0xFF) shl 8) or
            ((data[off + 2].toInt() and 0xFF) shl 16) or
            ((data[off + 3].toInt() and 0xFF) shl 24)

    private fun readSynchsafeInt(data: ByteArray, off: Int): Int =
        ((data[off].toInt() and 0x7F) shl 21) or
            ((data[off + 1].toInt() and 0x7F) shl 14) or
            ((data[off + 2].toInt() and 0x7F) shl 7) or
            (data[off + 3].toInt() and 0x7F)

    private fun readFullyOrEof(stream: InputStream, buf: ByteArray, off: Int, len: Int): Int {
        var readTotal = 0
        while (readTotal < len) {
            val n = stream.read(buf, off + readTotal, len - readTotal)
            if (n < 0) break
            readTotal += n
        }
        return readTotal
    }

    private fun readExactBytes(stream: InputStream, len: Int): ByteArray? {
        if (len < 0) return null
        val buf = ByteArray(len)
        return if (readFullyOrEof(stream, buf, 0, len) == len) buf else null
    }

    private fun skipExact(stream: InputStream, count: Long): Boolean {
        var remaining = count
        val scratch = ByteArray(4096)
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val n = stream.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
                if (n <= 0) return false
                remaining -= n
            }
        }
        return true
    }
}
