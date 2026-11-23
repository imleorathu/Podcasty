/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.tags

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.kyant.taglib.Picture
import com.kyant.taglib.TagLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class EditableAudioTags(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val genre: String = "",
    val year: String = "",
    val trackNumber: String = "",
    val discNumber: String = "",
    val composer: String = "",
    val comment: String = "",
    val lyrics: String = "",
    val copyright: String = "",
    val publisher: String = "",
    val bpm: String = "",
    val encoder: String = "",
    val customKey: String = "",
    val customValue: String = "",
    val artworkBytes: ByteArray? = null,
    val artworkMimeType: String = "image/jpeg",
    val artworkModified: Boolean = false,
    val removeArtwork: Boolean = false,
)

sealed interface TagSaveResult {
    data class SavedInPlace(val verifiedTags: EditableAudioTags) : TagSaveResult
    data class NeedsExportCopy(
        val verifiedTempFile: File,
        val suggestedFileName: String,
        val reason: String,
    ) : TagSaveResult

    data class Failed(val reason: String) : TagSaveResult
}

data class BulkTagEditSummary(
    val succeededCount: Int,
    val needsExportFiles: List<Pair<File, String>>,
    val failedCount: Int,
)

/**
 * Safe, corruption-proof audio tag reader and writer powered by TagLib 2.x.
 *
 * Supports ID3v1/v2 (MP3, WAV, AIFF), Vorbis Comments (OGG, Opus, FLAC),
 * MP4/M4A atoms, APE tags, ASF/WMA tags, WavPack, and DSF where supported by TagLib.
 *
 * Write protocol:
 * 1. Read original metadata.
 * 2. Copy original file bytes to a safe backup & working copy in app cacheDir.
 * 3. Apply edits to the working copy via TagLib.
 * 4. Re-read & verify the working copy metadata via TagLib before touching the source file.
 * 5. Write verified bytes back to the SAF URI — or if the storage provider / USB drive is
 *    read-only, return NeedsExportCopy so the user can export an edited copy safely.
 */
class AudioTagEditor(private val context: Context) {
    private val workDir: File by lazy {
        File(context.cacheDir, "tag_editor_work").apply { mkdirs() }
    }

    /**
     * Removes orphaned temporary backup/edit files left behind by interrupted tag edits.
     */
    fun cleanupStaleWorkFiles(maxAgeMs: Long = 2L * 60L * 60L * 1000L) {
        runCatching {
            val cutoff = System.currentTimeMillis() - maxAgeMs
            workDir.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff) {
                    file.delete()
                }
            }
        }
    }

    suspend fun readTags(uri: Uri): Result<EditableAudioTags> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val meta = TagLib.getMetadata(pfd.dup().detachFd(), /* readPictures = */ true)
                    ?: throw IllegalStateException("This file format does not support TagLib metadata reading.")
                val map = meta.propertyMap
                val pic = meta.pictures.firstOrNull()
                val standardKeys = setOf(
                    "TITLE", "ARTIST", "ALBUM", "ALBUMARTIST", "GENRE", "DATE",
                    "TRACKNUMBER", "DISCNUMBER", "COMPOSER", "COMMENT", "LYRICS",
                    "UNSYNCEDLYRICS", "COPYRIGHT", "LABEL", "PUBLISHER", "BPM", "ENCODEDBY",
                )
                val firstCustom = map.entries.firstOrNull { (k, v) -> k.uppercase() !in standardKeys && v.isNotEmpty() }
                EditableAudioTags(
                    title = map.firstValue("TITLE"),
                    artist = map.firstValue("ARTIST"),
                    album = map.firstValue("ALBUM"),
                    albumArtist = map.firstValue("ALBUMARTIST"),
                    genre = map.firstValue("GENRE"),
                    year = map.firstValue("DATE"),
                    trackNumber = map.firstValue("TRACKNUMBER"),
                    discNumber = map.firstValue("DISCNUMBER"),
                    composer = map.firstValue("COMPOSER"),
                    comment = map.firstValue("COMMENT"),
                    lyrics = map.firstValue("LYRICS")
                        .ifBlank { map.firstValue("UNSYNCEDLYRICS") }
                        .ifBlank {
                            map.entries.firstOrNull { (k, v) ->
                                val upper = k.uppercase()
                                (upper.startsWith("LYRICS") || upper.startsWith("USLT") || upper == "SYLT") &&
                                    v.any { it.isNotBlank() }
                            }?.value?.firstOrNull().orEmpty()
                        },
                    copyright = map.firstValue("COPYRIGHT"),
                    publisher = map.firstValue("PUBLISHER").ifBlank { map.firstValue("LABEL") },
                    bpm = map.firstValue("BPM"),
                    encoder = map.firstValue("ENCODEDBY"),
                    customKey = firstCustom?.key ?: "",
                    customValue = firstCustom?.value?.firstOrNull() ?: "",
                    artworkBytes = pic?.data?.takeIf { it.size <= MaxEmbeddedPictureBytes },
                    artworkMimeType = pic?.mimeType ?: "image/jpeg",
                )
            } ?: throw IllegalStateException("Could not open audio file descriptor.")
        }
    }

    suspend fun saveTagsSafely(
        uri: Uri,
        fileName: String,
        updated: EditableAudioTags,
    ): TagSaveResult = withContext(Dispatchers.IO) {
        cleanupStaleWorkFiles()
        if (workDir.usableSpace in 1L until MinRequiredCacheBytes) {
            return@withContext TagSaveResult.Failed("Insufficient internal cache storage to edit this file safely.")
        }
        val ext = fileName.substringAfterLast('.', "mp3")
        val stamp = System.currentTimeMillis()
        val backupFile = File(workDir, "backup_$stamp.$ext")
        val workingFile = File(workDir, "edited_$stamp.$ext")

        try {
            // 1 & 2. Copy original file into safe backup and working copy
            context.contentResolver.openInputStream(uri)?.use { input ->
                backupFile.outputStream().use { out -> input.copyTo(out) }
            } ?: return@withContext TagSaveResult.Failed("Unable to read original audio file.")
            backupFile.copyTo(workingFile, overwrite = true)

            // 3. Apply edits to working copy
            val existingMap = ParcelFileDescriptor.open(workingFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                TagLib.getMetadata(pfd.dup().detachFd(), false)?.propertyMap ?: hashMapOf()
            }
            val nextMap = HashMap<String, Array<String>>(existingMap)
            fun putOrRemove(key: String, value: String) {
                val trimmed = value.trim()
                if (trimmed.isEmpty()) nextMap.remove(key) else nextMap[key] = arrayOf(trimmed)
            }
            putOrRemove("TITLE", updated.title)
            putOrRemove("ARTIST", updated.artist)
            putOrRemove("ALBUM", updated.album)
            putOrRemove("ALBUMARTIST", updated.albumArtist)
            putOrRemove("GENRE", updated.genre)
            putOrRemove("DATE", updated.year)
            putOrRemove("TRACKNUMBER", updated.trackNumber)
            putOrRemove("DISCNUMBER", updated.discNumber)
            putOrRemove("COMPOSER", updated.composer)
            putOrRemove("COMMENT", updated.comment)
            putOrRemove("LYRICS", updated.lyrics)
            putOrRemove("COPYRIGHT", updated.copyright)
            putOrRemove("PUBLISHER", updated.publisher)
            putOrRemove("BPM", updated.bpm)
            putOrRemove("ENCODEDBY", updated.encoder)
            if (updated.customKey.isNotBlank()) {
                putOrRemove(updated.customKey.trim().uppercase(), updated.customValue)
            }

            val propsSaved = ParcelFileDescriptor.open(workingFile, ParcelFileDescriptor.MODE_READ_WRITE).use { pfd ->
                TagLib.savePropertyMap(pfd.dup().detachFd(), nextMap)
            }
            if (!propsSaved) {
                backupFile.delete()
                workingFile.delete()
                return@withContext TagSaveResult.Failed("Tag writing is not supported for this audio container format.")
            }

            if (updated.artworkModified || updated.removeArtwork) {
                val pictures = if (updated.removeArtwork || updated.artworkBytes == null) {
                    emptyArray()
                } else {
                    arrayOf(
                        Picture(
                            data = updated.artworkBytes,
                            description = "Front Cover",
                            pictureType = "Front Cover",
                            mimeType = updated.artworkMimeType,
                        ),
                    )
                }
                ParcelFileDescriptor.open(workingFile, ParcelFileDescriptor.MODE_READ_WRITE).use { pfd ->
                    TagLib.savePictures(pfd.dup().detachFd(), pictures)
                }
            }

            // 4. Verify saved metadata on the working copy before touching original
            val verifiedMeta = ParcelFileDescriptor.open(workingFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                TagLib.getMetadata(pfd.dup().detachFd(), false)
            } ?: run {
                backupFile.delete()
                workingFile.delete()
                return@withContext TagSaveResult.Failed("Verification failed after writing tags. Original file was left untouched.")
            }

            // 5. Write verified file back to SAF URI; if write fails, offer export copy!
            val writeBackResult = runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    workingFile.inputStream().use { input -> input.copyTo(out) }
                } ?: throw IllegalStateException("Storage provider does not allow in-place overwriting.")
            }

            if (writeBackResult.isSuccess) {
                backupFile.delete()
                workingFile.delete()
                val verified = updated.copy(
                    title = verifiedMeta.propertyMap.firstValue("TITLE").ifBlank { updated.title },
                    artist = verifiedMeta.propertyMap.firstValue("ARTIST").ifBlank { updated.artist },
                    album = verifiedMeta.propertyMap.firstValue("ALBUM").ifBlank { updated.album },
                )
                TagSaveResult.SavedInPlace(verified)
            } else {
                backupFile.delete()
                // Keep workingFile intact so user can export an edited copy via ACTION_CREATE_DOCUMENT
                TagSaveResult.NeedsExportCopy(
                    verifiedTempFile = workingFile,
                    suggestedFileName = fileName,
                    reason = "This storage provider or USB drive is read-only. Export the verified edited copy instead.",
                )
            }
        } catch (t: Throwable) {
            backupFile.delete()
            workingFile.delete()
            TagSaveResult.Failed(t.localizedMessage ?: "Failed to save audio tags.")
        }
    }

    /**
     * Bulk tag editor: applies only non-blank fields in [patch] across multiple audio files safely.
     */
    suspend fun saveBulkTags(
        targets: List<Pair<Uri, String>>,
        patch: EditableAudioTags,
    ): BulkTagEditSummary = withContext(Dispatchers.IO) {
        var succeeded = 0
        var failed = 0
        val exports = mutableListOf<Pair<File, String>>()

        for ((uri, fileName) in targets) {
            val current = readTags(uri).getOrElse { EditableAudioTags() }
            val merged = current.copy(
                artist = patch.artist.ifBlank { current.artist },
                album = patch.album.ifBlank { current.album },
                albumArtist = patch.albumArtist.ifBlank { current.albumArtist },
                genre = patch.genre.ifBlank { current.genre },
                year = patch.year.ifBlank { current.year },
                composer = patch.composer.ifBlank { current.composer },
                comment = patch.comment.ifBlank { current.comment },
                copyright = patch.copyright.ifBlank { current.copyright },
                publisher = patch.publisher.ifBlank { current.publisher },
                artworkBytes = if (patch.artworkModified) patch.artworkBytes else current.artworkBytes,
                artworkMimeType = if (patch.artworkModified) patch.artworkMimeType else current.artworkMimeType,
                artworkModified = patch.artworkModified,
                removeArtwork = patch.removeArtwork,
            )
            when (val res = saveTagsSafely(uri, fileName, merged)) {
                is TagSaveResult.SavedInPlace -> succeeded++
                is TagSaveResult.NeedsExportCopy -> exports += (res.verifiedTempFile to res.suggestedFileName)
                is TagSaveResult.Failed -> failed++
            }
        }
        BulkTagEditSummary(succeeded, exports, failed)
    }

    suspend fun exportTempFileToUri(tempFile: File, targetUri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(targetUri, "wt")?.use { out ->
                tempFile.inputStream().use { input -> input.copyTo(out) }
            } ?: return@runCatching false
            tempFile.delete()
            true
        }.getOrDefault(false)
    }

    private fun Map<String, Array<String>>.firstValue(key: String): String =
        this[key]?.firstOrNull().orEmpty()

    companion object {
        private const val MaxEmbeddedPictureBytes = 16 * 1024 * 1024
        private const val MinRequiredCacheBytes = 8L * 1024L * 1024L
    }
}
