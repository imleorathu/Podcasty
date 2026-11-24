/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import android.util.Size
import com.kyant.taglib.Picture
import com.kyant.taglib.TagLib
import com.podcasty.app.storage.core.StorageLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Reliable, OOM-safe, persistent local artwork resolver & cache.
 *
 * Extraction priority (strictly per-track / same-directory — never borrows unrelated album art):
 * 1. Embedded pictures via TagLib.getPictures() across MP3/FLAC/M4A/AAC/OGG/OPUS/WAV/AIFF,
 *    inspecting ALL picture types (Front Cover, Other/0x00, Cover, etc.) and skipping corrupt entries.
 * 2. MediaMetadataRetriever.getEmbeddedPicture() fallback (always released in finally).
 * 3. Valid Android MediaStore thumbnail API (ContentResolver.loadThumbnail) for MediaStore URIs.
 * 4. Same-directory folder cover image (cover.jpg, folder.jpg, front.jpg, cover.png, etc.)
 *    from SAF-granted folders, SD cards, USB OTG, or local directories.
 * 5. Returns null when no usable artwork exists so Podcasty renders its existing placeholder.
 */
class LocalArtworkResolver(private val context: Context) {

    /**
     * Persistent artwork cache directory under filesDir so covers survive app restarts and
     * Android low-storage cacheDir purges.
     */
    val persistentArtworkDir: File by lazy {
        File(context.filesDir, "local_artwork").apply { mkdirs() }
    }

    /**
     * Resolves and caches a verified, downsampled artwork file for a local audio track.
     *
     * @param audioUri Content or file URI of the audio track.
     * @param existingFileDescriptor Optional already-open read-only PFD (rewound safely via Os.lseek).
     * @param folderCoverUri Optional pre-discovered cover image URI in the exact same directory (SAF O(1)).
     * @param folderCoverMemo Optional per-scan memo mapping folderCoverUri string -> cached artwork path.
     * @param mediaStoreAudioUri Optional MediaStore content://media/... URI for API 29+ thumbnail fallback.
     * @param directFilePath Optional readable filesystem path for sibling folder-cover lookup.
     */
    fun resolveAndCacheArtwork(
        audioUri: Uri,
        existingFileDescriptor: ParcelFileDescriptor? = null,
        folderCoverUri: Uri? = null,
        folderCoverMemo: MutableMap<String, String?>? = null,
        mediaStoreAudioUri: Uri? = null,
        directFilePath: String? = null,
        preExtractedPictures: Array<Picture>? = null,
    ): String? {
        // 1. TagLib ALL embedded pictures (Front Cover, Other/0x00, FLAC METADATA_BLOCK_PICTURE, M4A covr, OGG/OPUS)
        extractViaTagLibPictures(audioUri, existingFileDescriptor, preExtractedPictures)?.let { return it }

        // 2. Pure-Kotlin container metadata parser (FLAC Block type 6 & VORBIS_COMMENT, Ogg/Opus METADATA_BLOCK_PICTURE, ID3v2 APIC/PIC)
        extractViaDirectContainerParser(audioUri, existingFileDescriptor)?.let { return it }

        // 3. Android MediaMetadataRetriever.getEmbeddedPicture() fallback
        extractViaMediaMetadataRetriever(audioUri)?.let { return it }

        // 3. Android MediaStore thumbnail API (for content://media/... URIs)
        val msUri = mediaStoreAudioUri
            ?: audioUri.takeIf { it.authority == MediaStore.AUTHORITY || it.toString().startsWith("content://media/") }
        if (msUri != null) {
            extractViaMediaStoreThumbnail(msUri)?.let { return it }
        }

        // 4. Accessible same-directory folder cover image (cover.jpg, folder.jpg, front.jpg, cover.png, ...)
        if (folderCoverUri != null) {
            val memoKey = folderCoverUri.toString()
            if (folderCoverMemo != null && folderCoverMemo.containsKey(memoKey)) {
                val cached = folderCoverMemo[memoKey]
                if (cached != null && isUsableCachedArtworkFile(cached)) return cached
            }
            val resolved = extractFromImageUri(folderCoverUri)
            if (folderCoverMemo != null) {
                folderCoverMemo[memoKey] = resolved
            }
            if (resolved != null) return resolved
        }

        // 4b. Direct filesystem sibling cover image (for MediaStore / file:// tracks with readable parent dir)
        val pathForDir = directFilePath
            ?: audioUri.takeIf { it.scheme.equals("file", ignoreCase = true) }?.path
            ?: resolveMediaStoreDataPath(msUri)
        if (!pathForDir.isNullOrBlank()) {
            val parentDir = File(pathForDir).parentFile
            if (parentDir != null && parentDir.isDirectory && parentDir.canRead()) {
                val memoKey = "dir:${parentDir.absolutePath}"
                if (folderCoverMemo != null && folderCoverMemo.containsKey(memoKey)) {
                    val cached = folderCoverMemo[memoKey]
                    if (cached != null && isUsableCachedArtworkFile(cached)) return cached
                }
                val coverFile = findBestFolderCoverFile(parentDir)
                val resolved = coverFile?.let { extractFromImageFile(it) }
                if (folderCoverMemo != null) {
                    folderCoverMemo[memoKey] = resolved
                }
                if (resolved != null) return resolved
            }
        }

        return null
    }

    /**
     * Validates, downsamples, and stores raw image bytes in the persistent artwork cache.
     * Rejects empty, oversized, or corrupted payloads so invalid bytes never poison the DB.
     */
    fun validateAndCacheImageBytes(rawBytes: ByteArray): String? {
        if (rawBytes.isEmpty() || rawBytes.size > MaxRawArtworkBytes) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return@runCatching null
            }

            val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, MaxCachedArtworkPx)
            val decoded = BitmapFactory.decodeByteArray(
                rawBytes,
                0,
                rawBytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            ) ?: return@runCatching null

            if (decoded.width <= 0 || decoded.height <= 0) {
                decoded.recycle()
                return@runCatching null
            }

            try {
                // Scale cleanly if still noticeably larger than MaxCachedArtworkPx after power-of-2 sampling
                val finalBitmap = if (decoded.width > MaxCachedArtworkPx * 1.4f || decoded.height > MaxCachedArtworkPx * 1.4f) {
                    val scale = MaxCachedArtworkPx.toFloat() / maxOf(decoded.width, decoded.height).toFloat()
                    val targetW = (decoded.width * scale).toInt().coerceAtLeast(1)
                    val targetH = (decoded.height * scale).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
                } else {
                    decoded
                }
                try {
                    encodeAndPersistBitmap(finalBitmap)
                } finally {
                    if (finalBitmap !== decoded) finalBitmap.recycle()
                }
            } finally {
                decoded.recycle()
            }
        }.getOrNull()
    }

    private fun encodeAndPersistBitmap(bitmap: Bitmap): String? {
        val preserveAlpha = bitmap.hasAlpha() && hasActualTransparency(bitmap)
        val useWebpLossy = !preserveAlpha && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val ext = when {
            preserveAlpha -> "png"
            useWebpLossy -> "webp"
            else -> "jpg"
        }
        val encoded = ByteArrayOutputStream().use { out ->
            val ok = when {
                preserveAlpha -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                useWebpLossy -> bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 82, out)
                else -> bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
            }
            if (!ok) return null
            out.toByteArray()
        }
        if (encoded.isEmpty()) return null
        val hash = shortHashBytes(encoded)
        val target = File(persistentArtworkDir, "art_$hash.$ext")
        if (!target.exists() || target.length() <= 32L) {
            val tmp = File(persistentArtworkDir, "art_${hash}_${System.nanoTime()}.tmp")
            tmp.writeBytes(encoded)
            if (!tmp.renameTo(target)) {
                target.writeBytes(encoded)
                tmp.delete()
            }
        }
        return target.absolutePath
    }

    /**
     * Checks a small grid of pixels so opaque PNGs (decoded as ARGB_8888 with alpha=255)
     * are stored as compact lossy WebP/JPEG instead of 5-10x larger PNG files.
     */
    private fun hasActualTransparency(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return false
        val stepX = (w / 8).coerceAtLeast(1)
        val stepY = (h / 8).coerceAtLeast(1)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                if ((bitmap.getPixel(x, y) ushr 24) < 250) return true
                x += stepX
            }
            y += stepY
        }
        return false
    }

    /**
     * Fast O(1) migration for already-valid artwork files sitting in volatile cacheDir/local_artwork
     * into persistent filesDir/local_artwork without re-reading the audio file over SAF.
     */
    fun migrateVolatileCachedArtwork(
        oldPath: String?,
        migrationMemo: MutableMap<String, String?>? = null,
    ): String? {
        if (oldPath.isNullOrBlank()) return null
        if (migrationMemo != null && migrationMemo.containsKey(oldPath)) {
            return migrationMemo[oldPath]
        }
        val migrated = runCatching {
            val src = File(oldPath)
            if (!src.isFile || !src.canRead() || src.length() <= 32L || src.length() > MaxRawArtworkBytes) {
                return@runCatching null
            }
            if (!isUsableCachedArtworkFile(oldPath)) return@runCatching null
            val bytes = src.readBytes()
            if (bytes.isEmpty()) return@runCatching null
            val ext = src.extension.lowercase().takeIf { it == "png" || it == "jpg" || it == "jpeg" || it == "webp" } ?: "jpg"
            val hash = shortHashBytes(bytes)
            val target = File(persistentArtworkDir, "art_$hash.$ext")
            if (!target.exists() || target.length() <= 32L) {
                val tmp = File(persistentArtworkDir, "art_${hash}_${System.nanoTime()}.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) {
                    target.writeBytes(bytes)
                    tmp.delete()
                }
            }
            target.absolutePath
        }.getOrNull()
        if (migrationMemo != null) {
            migrationMemo[oldPath] = migrated
        }
        return migrated
    }

    private fun extractViaTagLibPictures(
        audioUri: Uri,
        existingFileDescriptor: ParcelFileDescriptor?,
        preExtractedPictures: Array<Picture>? = null,
    ): String? {
        val pictures = preExtractedPictures ?: runCatching {
            if (existingFileDescriptor != null) {
                rewindFd(existingFileDescriptor)
                TagLib.getPictures(existingFileDescriptor.dup().detachFd())
            } else {
                context.contentResolver.openFileDescriptor(audioUri, "r")?.use { pfd ->
                    TagLib.getPictures(pfd.dup().detachFd())
                }
            }
        }.getOrNull()

        if (pictures != null && pictures.isNotEmpty()) {
            for (pic in selectOrderedPictures(pictures)) {
                val bytes = runCatching { @Suppress("USELESS_ELVIS") (pic.data ?: ByteArray(0)) }.getOrNull()
                if (bytes != null && bytes.isNotEmpty()) {
                    validateAndCacheImageBytes(bytes)?.let { return it }
                }
            }
        }
        return null
    }

    private fun extractViaDirectContainerParser(
        audioUri: Uri,
        existingFileDescriptor: ParcelFileDescriptor?,
    ): String? {
        val pictures = runCatching {
            if (existingFileDescriptor != null) {
                rewindFd(existingFileDescriptor)
                // Wrap a dup'd FileInputStream so closing it does NOT close the caller's PFD
                ParcelFileDescriptor.AutoCloseInputStream(existingFileDescriptor.dup()).use { input ->
                    EmbeddedArtworkParser.extractPictures(input)
                }
            } else {
                context.contentResolver.openInputStream(audioUri)?.use { input ->
                    EmbeddedArtworkParser.extractPictures(input)
                }
            }
        }.getOrNull().orEmpty()

        for (pic in pictures) {
            validateAndCacheImageBytes(pic.bytes)?.let { return it }
        }
        return null
    }

    private fun extractViaMediaMetadataRetriever(audioUri: Uri): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            runCatching {
                retriever.setDataSource(context, audioUri)
                val bytes = retriever.embeddedPicture
                if (bytes != null && bytes.isNotEmpty()) {
                    validateAndCacheImageBytes(bytes)
                } else {
                    null
                }
            }.getOrNull()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun extractViaMediaStoreThumbnail(mediaStoreUri: Uri): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val bmp = context.contentResolver.loadThumbnail(
                mediaStoreUri,
                Size(MaxCachedArtworkPx, MaxCachedArtworkPx),
                null,
            )
            try {
                if (bmp.width > 0 && bmp.height > 0) {
                    encodeAndPersistBitmap(bmp)
                } else {
                    null
                }
            } finally {
                bmp.recycle()
            }
        }.getOrNull()
    }

    private fun extractFromImageUri(imageUri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(imageUri)?.use { input ->
            val bytes = readBoundedBytes(input, MaxRawArtworkBytes) ?: return@use null
            validateAndCacheImageBytes(bytes)
        }
    }.getOrNull()

    private fun extractFromImageFile(imageFile: File): String? = runCatching {
        if (!imageFile.isFile || !imageFile.canRead() || imageFile.length() <= 32L || imageFile.length() > MaxRawArtworkBytes) {
            return@runCatching null
        }
        validateAndCacheImageBytes(imageFile.readBytes())
    }.getOrNull()

    private fun resolveMediaStoreDataPath(mediaStoreUri: Uri?): String? {
        if (mediaStoreUri == null) return null
        if (mediaStoreUri.authority != MediaStore.AUTHORITY && !mediaStoreUri.toString().startsWith("content://media/")) {
            return null
        }
        return runCatching {
            @Suppress("DEPRECATION")
            context.contentResolver.query(
                mediaStoreUri,
                arrayOf(MediaStore.Audio.Media.DATA),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    /**
     * Garbage-collects ONLY orphaned artwork files in persistentArtworkDir that are no longer
     * referenced by any episode in Room (e.g., deleted tracks or replaced covers), plus leftover
     * volatile cacheDir/local_artwork files. Never evicts an active track's artwork during a scan.
     */
    fun pruneUnreferencedArtwork(referencedPaths: Set<String>) {
        runCatching {
            val nowMs = System.currentTimeMillis()
            val files = persistentArtworkDir.listFiles() ?: return
            var removedCount = 0
            for (f in files) {
                if (!f.isFile) continue
                if (f.name.endsWith(".tmp")) {
                    if (nowMs - f.lastModified() > 60_000L) f.delete()
                    continue
                }
                if (f.absolutePath !in referencedPaths) {
                    if (f.delete()) removedCount++
                }
            }
            // Clean up legacy volatile cacheDir/local_artwork files once no longer referenced
            val legacyDir = File(context.cacheDir, "local_artwork")
            if (legacyDir.isDirectory) {
                legacyDir.listFiles()?.forEach { f ->
                    if (f.isFile && f.absolutePath !in referencedPaths) {
                        f.delete()
                    }
                }
            }
            if (removedCount > 0) {
                StorageLog.info("ArtworkResolver", "Pruned $removedCount unreferenced artwork cache files")
            }
        }.onFailure {
            StorageLog.warn("ArtworkResolver", "Orphan artwork GC skipped", it)
        }
    }

    companion object {
        const val CurrentArtworkResolverVersion = 5
        private const val MaxCachedArtworkPx = 512
        private const val MaxRawArtworkBytes = 16 * 1024 * 1024 // 16 MB safety cap against OOM

        private val FolderCoverNamesOrdered = listOf(
            "cover.jpg",
            "cover.jpeg",
            "cover.png",
            "cover.webp",
            "folder.jpg",
            "folder.jpeg",
            "folder.png",
            "folder.webp",
            "front.jpg",
            "front.jpeg",
            "front.png",
            "front.webp",
            "albumart.jpg",
            "albumart.jpeg",
            "albumart.png",
            "albumartlarge.jpg",
            "album.jpg",
            "album.jpeg",
            "album.png",
            "artwork.jpg",
            "artwork.png",
            "albumartsmall.jpg",
        )

        /**
         * Returns 0..N priority for recognized folder cover filenames (lower = higher priority),
         * or -1 if the filename is not a recognized album/folder cover image.
         */
        fun folderCoverPriority(displayName: String): Int {
            val lower = displayName.trim().lowercase()
            return FolderCoverNamesOrdered.indexOf(lower)
        }

        fun findBestFolderCoverFile(directory: File): File? {
            val files = directory.listFiles() ?: return null
            var bestPriority = Int.MAX_VALUE
            var bestFile: File? = null
            for (file in files) {
                if (!file.isFile || !file.canRead() || file.length() <= 32L) continue
                val prio = folderCoverPriority(file.name)
                if (prio in 0 until bestPriority) {
                    bestPriority = prio
                    bestFile = file
                    if (prio == 0) break
                }
            }
            return bestFile
        }

        /**
         * Orders TagLib Picture entries so Front Cover is tried first, then Other (type 0x00,
         * common on LAME/FFmpeg/Tamil MP3s), then Cover/Media/Artist, then any other valid image.
         * Guards defensively against null elements or null JNI fields from malformed tags.
         */
        fun selectOrderedPictures(pictures: Array<Picture>): List<Picture> =
            pictures.asSequence()
                .filterNotNull()
                .filter { pic ->
                    val data: ByteArray? = runCatching { pic.data }.getOrNull()
                    data != null && data.isNotEmpty()
                }
                .sortedBy { pic ->
                    val pType: String? = runCatching { pic.pictureType }.getOrNull()
                    pictureTypePriority(pType)
                }
                .toList()

        internal fun pictureTypePriority(pictureType: String?): Int {
            val norm = pictureType?.trim()?.lowercase().orEmpty()
            return when {
                norm == "front cover" || norm.contains("front") -> 0
                norm.isEmpty() || norm == "other" || norm == "0" -> 1
                norm.contains("cover") || norm == "media" || norm == "illustration" -> 2
                else -> 3
            }
        }

        fun isUsableCachedArtworkFile(path: String?): Boolean {
            if (path.isNullOrBlank()) return false
            if (path.startsWith("http://", ignoreCase = true) ||
                path.startsWith("https://", ignoreCase = true) ||
                path.startsWith("content://", ignoreCase = true)
            ) {
                return true
            }
            val file = File(path)
            if (!file.isFile || file.length() <= 32L) return false
            return runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                bounds.outWidth > 0 && bounds.outHeight > 0
            }.getOrDefault(false)
        }

        fun rewindFd(pfd: ParcelFileDescriptor) {
            runCatching { Os.lseek(pfd.fileDescriptor, 0L, OsConstants.SEEK_SET) }
        }

        private fun readBoundedBytes(input: java.io.InputStream, maxBytes: Int): ByteArray? {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                total += read
                if (total > maxBytes) return null
                out.write(buf, 0, read)
            }
            return out.toByteArray().takeIf { it.isNotEmpty() }
        }

        internal fun computeInSampleSize(width: Int, height: Int, targetPx: Int): Int {
            if (width <= 0 || height <= 0 || targetPx <= 0) return 1
            var sampleSize = 1
            while (width / (sampleSize * 2) >= targetPx && height / (sampleSize * 2) >= targetPx) {
                sampleSize *= 2
            }
            return sampleSize
        }

        private fun shortHashBytes(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .take(10)
                .joinToString("") { "%02x".format(it) }
    }
}
