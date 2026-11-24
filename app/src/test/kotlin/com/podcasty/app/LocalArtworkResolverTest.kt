package com.podcasty.app

import com.kyant.taglib.Picture
import com.podcasty.app.storage.EmbeddedArtworkParser
import com.podcasty.app.storage.LocalArtworkResolver
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.io.path.createTempDirectory

class LocalArtworkResolverTest {

    @Test
    fun selectOrderedPictures_prioritizesFrontCoverThenOtherType0AndSkipsEmpty() {
        val emptyPic = Picture(ByteArray(0), "Empty", "Front Cover", "image/jpeg")
        val backCover = Picture(byteArrayOf(1, 2, 3), "Back", "Back Cover", "image/jpeg")
        val otherType0 = Picture(byteArrayOf(4, 5, 6), "", "Other", "image/jpeg")
        val frontCover = Picture(byteArrayOf(7, 8, 9), "Cover", "Front Cover", "image/png")

        val ordered = LocalArtworkResolver.selectOrderedPictures(
            arrayOf(emptyPic, backCover, otherType0, frontCover),
        )

        assertEquals(3, ordered.size)
        assertEquals("Front Cover", ordered[0].pictureType)
        assertEquals("Other", ordered[1].pictureType)
        assertEquals("Back Cover", ordered[2].pictureType)

        // When only APIC type 0 ("Other") exists (common in MassTamilan / LAME / FFmpeg MP3s),
        // it is still selected instead of returning null like TagLib.getFrontCover()
        val onlyOther = LocalArtworkResolver.selectOrderedPictures(arrayOf(otherType0))
        assertEquals(1, onlyOther.size)
        assertEquals("Other", onlyOther[0].pictureType)
    }

    @Test
    fun folderCoverPriority_matchesKnownCoverImagesAndRejectsUnrelatedFiles() {
        assertTrue(LocalArtworkResolver.folderCoverPriority("cover.jpg") >= 0)
        assertTrue(LocalArtworkResolver.folderCoverPriority("FOLDER.JPG") >= 0)
        assertTrue(LocalArtworkResolver.folderCoverPriority("Front.png") >= 0)
        assertTrue(LocalArtworkResolver.folderCoverPriority("cover.png") >= 0)
        assertTrue(LocalArtworkResolver.folderCoverPriority("AlbumArtSmall.jpg") >= 0)

        // Priority order: cover.jpg < folder.jpg < front.jpg < albumartsmall.jpg
        assertTrue(
            LocalArtworkResolver.folderCoverPriority("cover.jpg") <
                LocalArtworkResolver.folderCoverPriority("folder.jpg"),
        )
        assertTrue(
            LocalArtworkResolver.folderCoverPriority("folder.jpg") <
                LocalArtworkResolver.folderCoverPriority("front.jpg"),
        )
        assertTrue(
            LocalArtworkResolver.folderCoverPriority("front.jpg") <
                LocalArtworkResolver.folderCoverPriority("AlbumArtSmall.jpg"),
        )

        // Never matches unrelated image files or audio files
        assertEquals(-1, LocalArtworkResolver.folderCoverPriority("IMG_20260101.jpg"))
        assertEquals(-1, LocalArtworkResolver.folderCoverPriority("screenshot.png"))
        assertEquals(-1, LocalArtworkResolver.folderCoverPriority("song.mp3"))
    }

    @Test
    fun findBestFolderCoverFile_picksHighestPriorityValidFileInSameDirectoryOnly() {
        val dir = createTempDirectory("podcasty_cover_test").toFile()
        try {
            val unrelated = File(dir, "vacation.jpg").apply { writeBytes(ByteArray(256) { 1 }) }
            assertNull(LocalArtworkResolver.findBestFolderCoverFile(dir))
            assertTrue(unrelated.exists())

            val small = File(dir, "AlbumArtSmall.jpg").apply { writeBytes(ByteArray(256) { 2 }) }
            val folder = File(dir, "Folder.jpg").apply { writeBytes(ByteArray(256) { 3 }) }
            val tinyCorruptCover = File(dir, "cover.jpg").apply { writeBytes(ByteArray(8)) } // <= 32 bytes skipped

            val picked1 = LocalArtworkResolver.findBestFolderCoverFile(dir)
            assertNotNull(picked1)
            assertEquals("Folder.jpg", picked1!!.name)
            assertTrue(small.exists() && folder.exists() && tinyCorruptCover.exists())

            // Valid cover.png takes precedence over Folder.jpg
            File(dir, "cover.png").writeBytes(ByteArray(256) { 4 })
            val picked2 = LocalArtworkResolver.findBestFolderCoverFile(dir)
            assertNotNull(picked2)
            assertEquals("cover.png", picked2!!.name)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun computeInSampleSize_downsamplesHighResCoversSafely() {
        assertEquals(1, LocalArtworkResolver.computeInSampleSize(500, 500, 600))
        assertEquals(2, LocalArtworkResolver.computeInSampleSize(1200, 1200, 600))
        assertEquals(4, LocalArtworkResolver.computeInSampleSize(3000, 3000, 600))
        assertEquals(8, LocalArtworkResolver.computeInSampleSize(4800, 4800, 600))
    }

    @Test
    fun isUsableCachedArtworkFile_rejectsMissingAndTinyFiles() {
        assertFalse(LocalArtworkResolver.isUsableCachedArtworkFile(null))
        assertFalse(LocalArtworkResolver.isUsableCachedArtworkFile(""))
        assertFalse(LocalArtworkResolver.isUsableCachedArtworkFile("/non/existent/art_123.jpg"))

        val tmp = createTempDirectory("podcasty_art_usable").toFile()
        try {
            val emptyFile = File(tmp, "empty.jpg").apply { writeBytes(ByteArray(0)) }
            assertFalse(LocalArtworkResolver.isUsableCachedArtworkFile(emptyFile.absolutePath))
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun embeddedArtworkParser_extractsFlacPictureAfterVorbisComment() {
        val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(64) { 0x42 }
        val picBlock = buildFlacPictureBlock(picType = 3, mime = "image/jpeg", img = fakeJpeg)
        val vorbis = buildVorbisComment(listOf("TITLE=Putaapu Gang", "ARTIST=Anirudh"))
        val flac = ByteArrayOutputStream().apply {
            write("fLaC".toByteArray(Charsets.ISO_8859_1))
            writeFlacBlock(0, ByteArray(34), isLast = false)
            writeFlacBlock(4, vorbis, isLast = false)
            writeFlacBlock(6, picBlock, isLast = false)
            writeFlacBlock(1, ByteArray(128), isLast = true)
        }.toByteArray()

        val pics = EmbeddedArtworkParser.extractPictures(ByteArrayInputStream(flac))
        assertEquals(1, pics.size)
        assertEquals(3, pics[0].pictureType)
        assertEquals("image/jpeg", pics[0].mimeType)
        assertArrayEquals(fakeJpeg, pics[0].bytes)
    }

    @Test
    fun embeddedArtworkParser_extractsOggOpusMultiPageMetadataBlockPicture() {
        val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(512) { it.toByte() }
        val picBlock = buildFlacPictureBlock(picType = 3, mime = "image/jpeg", img = fakeJpeg)
        val b64 = Base64.getEncoder().encodeToString(picBlock)
        val opusHead = "OpusHead".toByteArray(Charsets.ISO_8859_1) + ByteArray(11)
        val opusTags = "OpusTags".toByteArray(Charsets.ISO_8859_1) +
            buildVorbisComment(listOf("TITLE=Yumabaibesa", "METADATA_BLOCK_PICTURE=$b64"))

        // Split OpusTags across 2 Ogg pages (exact multi-page structure of real Opus covers!)
        val ogg = ByteArrayOutputStream().apply {
            writeOggPage(opusHead, continued = false, complete = true)
            val split = 255
            writeOggPage(opusTags.copyOfRange(0, split), continued = false, complete = false)
            writeOggPage(opusTags.copyOfRange(split, opusTags.size), continued = true, complete = true)
        }.toByteArray()

        val pics = EmbeddedArtworkParser.extractPictures(ByteArrayInputStream(ogg))
        assertEquals(1, pics.size)
        assertEquals(3, pics[0].pictureType)
        assertEquals("image/jpeg", pics[0].mimeType)
        assertArrayEquals(fakeJpeg, pics[0].bytes)
    }

    @Test
    fun embeddedArtworkParser_extractsId3v23ApicType0AndHandlesCorruptInputSafely() {
        val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(80) { 0x33 }
        val apicPayload = ByteArrayOutputStream().apply {
            write(0) // ISO-8859-1
            write("image/jpeg".toByteArray(Charsets.ISO_8859_1))
            write(0)
            write(0) // pic_type = 0 ("Other")
            write(0) // empty desc
            write(fakeJpeg)
        }.toByteArray()
        val frame = ByteArrayOutputStream().apply {
            write("APIC".toByteArray(Charsets.ISO_8859_1))
            write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(apicPayload.size).array())
            write(byteArrayOf(0, 0))
            write(apicPayload)
        }.toByteArray()
        val sz = frame.size
        val id3 = ByteArrayOutputStream().apply {
            write("ID3".toByteArray(Charsets.ISO_8859_1))
            write(byteArrayOf(3, 0, 0))
            write(byteArrayOf(((sz shr 21) and 0x7F).toByte(), ((sz shr 14) and 0x7F).toByte(), ((sz shr 7) and 0x7F).toByte(), (sz and 0x7F).toByte()))
            write(frame)
        }.toByteArray()

        val pics = EmbeddedArtworkParser.extractPictures(ByteArrayInputStream(id3))
        assertEquals(1, pics.size)
        assertEquals(0, pics[0].pictureType)
        assertArrayEquals(fakeJpeg, pics[0].bytes)

        // Corrupt/truncated inputs return empty without throwing
        assertTrue(EmbeddedArtworkParser.extractPictures(ByteArrayInputStream("fLaC\u0006\u00FF\u00FF\u00FFbad".toByteArray())).isEmpty())
        assertTrue(EmbeddedArtworkParser.extractPictures(ByteArrayInputStream(ByteArray(4))).isEmpty())
    }

    private fun buildFlacPictureBlock(picType: Int, mime: String, img: ByteArray): ByteArray {
        val mimeBytes = mime.toByteArray(Charsets.ISO_8859_1)
        val buf = ByteBuffer.allocate(4 + 4 + mimeBytes.size + 4 + 16 + 4 + img.size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(picType)
        buf.putInt(mimeBytes.size)
        buf.put(mimeBytes)
        buf.putInt(0) // desc len
        buf.putInt(300).putInt(300).putInt(24).putInt(0)
        buf.putInt(img.size)
        buf.put(img)
        return buf.array()
    }

    private fun buildVorbisComment(comments: List<String>): ByteArray {
        val vendor = "PodcastyTest".toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(vendor.size).array())
        out.write(vendor)
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(comments.size).array())
        for (c in comments) {
            val cb = c.toByteArray(Charsets.UTF_8)
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(cb.size).array())
            out.write(cb)
        }
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeFlacBlock(type: Int, payload: ByteArray, isLast: Boolean) {
        write((if (isLast) 0x80 else 0x00) or (type and 0x7F))
        write((payload.size shr 16) and 0xFF)
        write((payload.size shr 8) and 0xFF)
        write(payload.size and 0xFF)
        write(payload)
    }

    private fun ByteArrayOutputStream.writeOggPage(payload: ByteArray, continued: Boolean, complete: Boolean) {
        write("OggS".toByteArray(Charsets.ISO_8859_1))
        write(0) // version
        write(if (continued) 1 else 0)
        write(ByteArray(20)) // granule, serial, seq, crc
        val segs = ArrayList<Int>()
        var rem = payload.size
        while (rem >= 255) {
            segs += 255
            rem -= 255
        }
        if (complete) {
            segs += rem
        } else if (rem > 0) {
            segs += rem
        }
        write(segs.size)
        for (s in segs) write(s)
        write(payload)
    }
}
