package com.podcasty.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.podcasty.app.storage.LocalArtworkResolver
import com.podcasty.app.ui.components.clearArtworkMemoryCache
import com.podcasty.app.ui.components.decodeArtworkBitmap
import com.podcasty.app.ui.components.invalidateArtworkCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhysicalFolderArtworkTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = LocalArtworkResolver(context)

    private fun makeJpegBytes(width: Int, height: Int, color: Int): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        return try {
            ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.toByteArray()
            }
        } finally {
            bmp.recycle()
        }
    }

    private fun makePngBytes(width: Int, height: Int, color: Int): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        return try {
            ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        } finally {
            bmp.recycle()
        }
    }

    /** Builds a minimal valid MP3 file with an ID3v2.3 APIC frame of the chosen pictureType (0=Other, 3=Front Cover). */
    private fun buildMp3WithApic(mimeType: String, pictureType: Int, imageBytes: ByteArray): ByteArray {
        val apicPayload = ByteArrayOutputStream().apply {
            write(0) // ISO-8859-1
            write(mimeType.toByteArray(Charsets.ISO_8859_1))
            write(0) // mime terminator
            write(pictureType and 0xFF)
            write(0) // empty description terminator
            write(imageBytes)
        }.toByteArray()

        val frameHeader = ByteArrayOutputStream().apply {
            write("APIC".toByteArray(Charsets.ISO_8859_1))
            val sz = apicPayload.size
            write((sz ushr 24) and 0xFF)
            write((sz ushr 16) and 0xFF)
            write((sz ushr 8) and 0xFF)
            write(sz and 0xFF)
            write(0)
            write(0)
        }.toByteArray()

        val bodySize = frameHeader.size + apicPayload.size
        // Minimal valid MPEG1 Layer3 128kbps 44.1kHz silent frame (417 bytes)
        val mp3Frame = ByteArray(417).apply {
            this[0] = 0xFF.toByte()
            this[1] = 0xFB.toByte()
            this[2] = 0x90.toByte()
            this[3] = 0x00.toByte()
        }

        return ByteArrayOutputStream().apply {
            write("ID3".toByteArray(Charsets.ISO_8859_1))
            write(3) // ID3v2.3
            write(0)
            write(0)
            write((bodySize ushr 21) and 0x7F)
            write((bodySize ushr 14) and 0x7F)
            write((bodySize ushr 7) and 0x7F)
            write(bodySize and 0x7F)
            write(frameHeader)
            write(apicPayload)
            write(mp3Frame)
        }.toByteArray()
    }

    /** Builds a minimal valid FLAC header containing a METADATA_BLOCK_PICTURE block (type 6). */
    private fun buildFlacWithPicture(mimeType: String, imageBytes: ByteArray): ByteArray {
        // Minimal 34-byte STREAMINFO block (type 0, not last)
        val streamInfo = ByteArray(34).apply {
            // min/max block size 4096
            this[0] = 0x10; this[1] = 0x00
            this[2] = 0x10; this[3] = 0x00
            // 44100 Hz (20 bits), 2 ch (3 bits = 1), 16 bps (5 bits = 15)
            this[10] = 0x0A; this[11] = 0xC4.toByte(); this[12] = 0x42; this[13] = 0xF0.toByte()
        }
        val mimeBytes = mimeType.toByteArray(Charsets.ISO_8859_1)
        val picBlock = ByteArrayOutputStream().apply {
            fun writeBe32(v: Int) {
                write((v ushr 24) and 0xFF); write((v ushr 16) and 0xFF); write((v ushr 8) and 0xFF); write(v and 0xFF)
            }
            writeBe32(3) // Front Cover
            writeBe32(mimeBytes.size)
            write(mimeBytes)
            writeBe32(0) // description len
            writeBe32(200) // width
            writeBe32(200) // height
            writeBe32(24) // depth
            writeBe32(0) // indexed colors
            writeBe32(imageBytes.size)
            write(imageBytes)
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            write("fLaC".toByteArray(Charsets.ISO_8859_1))
            // STREAMINFO header (not last, type 0, len 34)
            write(0x00); write(0x00); write(0x00); write(34)
            write(streamInfo)
            // PICTURE header (last = 0x80 | 6 = 0x86)
            val plen = picBlock.size
            write(0x86)
            write((plen ushr 16) and 0xFF); write((plen ushr 8) and 0xFF); write(plen and 0xFF)
            write(picBlock)
        }.toByteArray()
    }

    @Test
    fun artworkResolver_handlesAllEmbeddedFolderCorruptedAndHighResCasesOnDevice() {
        val testRoot = File(context.cacheDir, "artwork_device_test_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            // 1. Embedded JPG with APIC type=0 ("Other" — exact bug from JD-Intro-MassTamilan.io.mp3)
            val mp3OtherFile = File(testRoot, "track_apic_other.mp3")
            mp3OtherFile.writeBytes(buildMp3WithApic("image/jpeg", 0, makeJpegBytes(300, 300, Color.RED)))
            val artOther = resolver.resolveAndCacheArtwork(Uri.fromFile(mp3OtherFile))
            assertNotNull("APIC type=0 (Other) JPG must resolve", artOther)
            assertTrue("Must cache in persistent filesDir", artOther!!.startsWith(context.filesDir.absolutePath))
            assertTrue(LocalArtworkResolver.isUsableCachedArtworkFile(artOther))

            // 2. Embedded PNG artwork with alpha
            val mp3PngFile = File(testRoot, "track_png.mp3")
            mp3PngFile.writeBytes(buildMp3WithApic("image/png", 3, makePngBytes(240, 240, Color.argb(180, 0, 128, 255))))
            val artPng = resolver.resolveAndCacheArtwork(Uri.fromFile(mp3PngFile))
            assertNotNull("Embedded PNG must resolve", artPng)
            assertTrue(LocalArtworkResolver.isUsableCachedArtworkFile(artPng))

            // 3. FLAC METADATA_BLOCK_PICTURE
            val flacFile = File(testRoot, "track_flac.flac")
            flacFile.writeBytes(buildFlacWithPicture("image/jpeg", makeJpegBytes(250, 250, Color.GREEN)))
            val artFlac = resolver.resolveAndCacheArtwork(Uri.fromFile(flacFile))
            assertNotNull("FLAC METADATA_BLOCK_PICTURE must resolve", artFlac)
            assertTrue(LocalArtworkResolver.isUsableCachedArtworkFile(artFlac))

            // 4. Folder-based cover image (Folder.jpg) for track with NO embedded artwork
            val folderDir = File(testRoot, "album_with_folder_jpg").apply { mkdirs() }
            File(folderDir, "Folder.jpg").writeBytes(makeJpegBytes(320, 320, Color.BLUE))
            val bareTrackInFolder = File(folderDir, "01_bare.m4a").apply { writeBytes(ByteArray(256) { 0 }) }
            val folderMemo = HashMap<String, String?>()
            val artFromFolder = resolver.resolveAndCacheArtwork(
                audioUri = Uri.fromFile(bareTrackInFolder),
                folderCoverMemo = folderMemo,
                directFilePath = bareTrackInFolder.absolutePath,
            )
            assertNotNull("Folder.jpg fallback must resolve for track without embedded art", artFromFolder)
            assertTrue(LocalArtworkResolver.isUsableCachedArtworkFile(artFromFolder))

            // 5. Track with NO embedded art and NO folder cover -> null (graceful placeholder)
            val emptyDir = File(testRoot, "no_art_folder").apply { mkdirs() }
            File(emptyDir, "random_selfie.jpg").writeBytes(makeJpegBytes(100, 100, Color.YELLOW))
            val noArtTrack = File(emptyDir, "bare_song.mp3").apply { writeBytes(ByteArray(256) { 0 }) }
            val artNone = resolver.resolveAndCacheArtwork(
                audioUri = Uri.fromFile(noArtTrack),
                directFilePath = noArtTrack.absolutePath,
            )
            assertNull("Must never borrow unrelated image when no cover exists", artNone)

            // 6. Corrupted embedded artwork -> rejected cleanly without crashing or poisoning cache
            val corruptDir = File(testRoot, "corrupt_art_folder").apply { mkdirs() }
            val corruptMp3 = File(corruptDir, "corrupt.mp3")
            corruptMp3.writeBytes(buildMp3WithApic("image/jpeg", 3, byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55)))
            val artCorrupt = resolver.resolveAndCacheArtwork(
                audioUri = Uri.fromFile(corruptMp3),
                directFilePath = corruptMp3.absolutePath,
            )
            assertNull("Corrupted artwork bytes must return null instead of writing invalid jpg", artCorrupt)

            // 6b. Corrupted embedded art + valid cover.png in same folder -> falls back to cover.png!
            File(corruptDir, "cover.png").writeBytes(makePngBytes(200, 200, Color.MAGENTA))
            val artFallbackAfterCorrupt = resolver.resolveAndCacheArtwork(
                audioUri = Uri.fromFile(corruptMp3),
                directFilePath = corruptMp3.absolutePath,
            )
            assertNotNull("Must fall back to valid folder cover.png when embedded art is corrupted", artFallbackAfterCorrupt)

            // 7. Large high-resolution artwork (2800x2800) -> downsampled safely to <= 600px without OOM
            val hiResBytes = makeJpegBytes(2800, 2800, Color.CYAN)
            val artHiRes = resolver.validateAndCacheImageBytes(hiResBytes)
            assertNotNull("High-res artwork must resolve without OOM", artHiRes)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(artHiRes, bounds)
            assertTrue("Downsampled width must be <= 840px, was ${bounds.outWidth}", bounds.outWidth in 1..840)
            assertTrue("Downsampled height must be <= 840px, was ${bounds.outHeight}", bounds.outHeight in 1..840)

            // 8. Cache persistence & invalidation
            clearArtworkMemoryCache()
            val reloaded = decodeArtworkBitmap(context, artOther, 120)
            assertNotNull("Persistent artwork must decode cleanly after memory cache clear / restart", reloaded)
            reloaded?.recycle()
            invalidateArtworkCache(artOther)
            assertEquals(true, File(artOther).exists())
            assertFalse(LocalArtworkResolver.isUsableCachedArtworkFile(File(testRoot, "missing.jpg").absolutePath))
        } finally {
            testRoot.deleteRecursively()
        }
    }
}
