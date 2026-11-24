/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.storage

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.MediaSourceType
import com.podcasty.app.domain.PodcastRepository
import com.podcasty.app.storage.core.StorageLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class LocalAudioFileDetails(
    val episode: Episode,
    val fileName: String,
    val uriString: String,
    val folderPath: String,
    val storageLabel: String,
    val mimeType: String,
    val sizeBytes: Long,
    val isWritable: Boolean,
    val isSafDocument: Boolean,
    val isMediaStoreItem: Boolean,
)

enum class PendingConsentOperation {
    Rename,
    Delete,
    MoveSourceDelete,
}

data class PendingMediaStoreFileAction(
    val operation: PendingConsentOperation,
    val targetEpisodes: List<Episode>,
    val newName: String? = null,
    val copiedDestinationUri: String? = null,
    val copiedDisplayName: String? = null,
    val copiedFolderUri: String? = null,
    val copiedRelativePath: String? = null,
)

sealed interface FileOperationResult {
    data class Success(val message: String, val affectedEpisodeIds: List<String>) : FileOperationResult
    data class RequiresUserConsent(
        val intentSender: IntentSender,
        val pendingAction: PendingMediaStoreFileAction,
    ) : FileOperationResult
    data class ReadOnlyOrUnsupported(val reason: String) : FileOperationResult
    data class Failed(val reason: String) : FileOperationResult
}

/**
 * Offline Scoped-Storage & SAF Music File Manager.
 *
 * Uses strictly supported Android APIs (ContentResolver, DocumentsContract, MediaStore consent
 * requests on API 31+, and 64 KB buffered streaming) — zero MANAGE_EXTERNAL_STORAGE required.
 */
class MusicFileManager(
    private val context: Context,
    private val repository: PodcastRepository,
    private val scanner: LocalMusicScanner,
) {
    private val fileOpMutex = Mutex()
    suspend fun inspectFileDetails(episode: Episode): LocalAudioFileDetails = withContext(Dispatchers.IO) {
        val uriStr = episode.editableAudioUri ?: episode.sourceUri.orEmpty()
        val uri = uriStr.takeIf { it.isNotBlank() }?.let(Uri::parse)
        val fileName = episode.audioAsset.substringAfterLast('/').ifBlank {
            uri?.let(::resolveDisplayName) ?: "${episode.title}.mp3"
        }
        val relFolder = episode.audioAsset.substringBeforeLast('/', "").takeIf { episode.audioAsset.contains('/') }.orEmpty()
        val isSaf = uri != null && runCatching { DocumentsContract.isDocumentUri(context, uri) }.getOrDefault(false)
        val isMediaStore = uri?.authority == MediaStore.AUTHORITY || uriStr.startsWith("content://media/")
        val storageLabel = when {
            isMediaStore -> "Device MediaStore"
            uriStr.startsWith("file://") -> "App Storage"
            else -> scanner.detectStorageType(Uri.parse(episode.folderUri ?: uriStr.ifBlank { "content://unknown" })).label
        }
        val mime = uri?.let { runCatching { context.contentResolver.getType(it) }.getOrNull() }
            ?: episode.containerFormat
            ?: "audio/*"
        var writable = false
        if (uri != null) {
            writable = if (isSaf) {
                hasSafWriteFlag(uri)
            } else {
                uriStr.startsWith("file://") && File(uri.path.orEmpty()).canWrite()
            }
        }
        LocalAudioFileDetails(
            episode = episode,
            fileName = fileName,
            uriString = uriStr,
            folderPath = relFolder.ifBlank { "Root folder" },
            storageLabel = storageLabel,
            mimeType = mime,
            sizeBytes = episode.fileSizeBytes,
            isWritable = writable || isMediaStore,
            isSafDocument = isSaf,
            isMediaStoreItem = isMediaStore,
        )
    }

    suspend fun renameFile(episode: Episode, rawNewName: String): FileOperationResult = withContext(Dispatchers.IO) {
        fileOpMutex.withLock {
            renameFileInternal(episode, rawNewName)
        }
    }

    private suspend fun renameFileInternal(episode: Episode, rawNewName: String): FileOperationResult {
        val uriStr = episode.editableAudioUri ?: episode.sourceUri
            ?: return FileOperationResult.Failed("No editable file URI for this track.")
        val uri = Uri.parse(uriStr)
        val currentName = episode.audioAsset.substringAfterLast('/').ifBlank {
            resolveDisplayName(uri) ?: episode.title
        }
        val currentExt = currentName.substringAfterLast('.', "")
        val cleanBase = rawNewName.trim().replace('/', '_').replace('\\', '_')
        if (cleanBase.isEmpty()) {
            return FileOperationResult.Failed("File name cannot be empty.")
        }
        val targetFileName = if (currentExt.isNotEmpty() && !cleanBase.endsWith(".$currentExt", ignoreCase = true)) {
            "$cleanBase.$currentExt"
        } else {
            cleanBase
        }
        val newTitle = targetFileName.substringBeforeLast('.')

        // 1. Direct file:// path
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val file = File(uri.path.orEmpty())
            if (!file.exists()) return FileOperationResult.Failed("Original file no longer exists.")
            val dest = File(file.parentFile, resolveUniqueFileName(targetFileName) { candidate ->
                File(file.parentFile, candidate).exists()
            })
            if (!file.renameTo(dest)) {
                return FileOperationResult.ReadOnlyOrUnsupported("Storage prevented renaming this file.")
            }
            val newUri = Uri.fromFile(dest).toString()
            val relPath = episode.audioAsset.substringBeforeLast('/', "").takeIf { episode.audioAsset.contains('/') }.orEmpty()
            val newAsset = if (relPath.isNotEmpty()) "$relPath/${dest.name}" else dest.name
            repository.updateLocalEpisodeFileLocation(
                episodeId = episode.id,
                sourceUri = newUri,
                audioAsset = newAsset,
                folderUri = episode.folderUri,
                relativeFolderPath = relPath,
                title = newTitle,
                lastModifiedMs = dest.lastModified(),
            )
            return FileOperationResult.Success("Renamed to ${dest.name}", listOf(episode.id))
        }

        // 2. SAF Document URI
        if (DocumentsContract.isDocumentUri(context, uri)) {
            return runCatching {
                val renamedUri = DocumentsContract.renameDocument(context.contentResolver, uri, targetFileName)
                    ?: return@runCatching FileOperationResult.ReadOnlyOrUnsupported("Document provider did not return a renamed URI.")
                val relPath = episode.audioAsset.substringBeforeLast('/', "").takeIf { episode.audioAsset.contains('/') }.orEmpty()
                val actualName = resolveDisplayName(renamedUri) ?: targetFileName
                val newAsset = if (relPath.isNotEmpty()) "$relPath/$actualName" else actualName
                repository.updateLocalEpisodeFileLocation(
                    episodeId = episode.id,
                    sourceUri = renamedUri.toString(),
                    audioAsset = newAsset,
                    folderUri = episode.folderUri,
                    relativeFolderPath = relPath,
                    title = actualName.substringBeforeLast('.'),
                    lastModifiedMs = System.currentTimeMillis(),
                )
                FileOperationResult.Success("Renamed to $actualName", listOf(episode.id))
            }.getOrElse { err ->
                FileOperationResult.ReadOnlyOrUnsupported(
                    err.localizedMessage ?: "Permission denied or storage is read-only.",
                )
            }
        }

        // 3. MediaStore URI
        if (uri.authority == MediaStore.AUTHORITY || uriStr.startsWith("content://media/")) {
            return try {
                renameMediaStoreItem(episode, uri, targetFileName)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                StorageLog.warn("FileManager", "Rename failed for ${episode.id}", t)
                FileOperationResult.ReadOnlyOrUnsupported("This storage provider does not support renaming.")
            }
        }

        return FileOperationResult.ReadOnlyOrUnsupported("This storage provider does not support renaming.")
    }

    private suspend fun renameMediaStoreItem(
        episode: Episode,
        uri: Uri,
        targetFileName: String,
    ): FileOperationResult {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, targetFileName)
            }
            val updatedRows = context.contentResolver.update(uri, values, null, null)
            if (updatedRows > 0) {
                val actualName = resolveDisplayName(uri) ?: targetFileName
                val relPath = episode.audioAsset.substringBeforeLast('/', "").takeIf { episode.audioAsset.contains('/') }.orEmpty()
                val newAsset = if (relPath.isNotEmpty()) "$relPath/$actualName" else actualName
                repository.updateLocalEpisodeFileLocation(
                    episodeId = episode.id,
                    sourceUri = uri.toString(),
                    audioAsset = newAsset,
                    folderUri = episode.folderUri,
                    relativeFolderPath = relPath,
                    title = actualName.substringBeforeLast('.'),
                    lastModifiedMs = System.currentTimeMillis(),
                )
                FileOperationResult.Success("Renamed to $actualName", listOf(episode.id))
            } else {
                val writePi = MediaStore.createWriteRequest(context.contentResolver, listOf(uri))
                FileOperationResult.RequiresUserConsent(
                    intentSender = writePi.intentSender,
                    pendingAction = PendingMediaStoreFileAction(
                        operation = PendingConsentOperation.Rename,
                        targetEpisodes = listOf(episode),
                        newName = targetFileName,
                    ),
                )
            }
        } catch (security: SecurityException) {
            val intentSender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                MediaStore.createWriteRequest(context.contentResolver, listOf(uri)).intentSender
            } else {
                (security as? RecoverableSecurityException)?.userAction?.actionIntent?.intentSender
            }
            if (intentSender != null) {
                FileOperationResult.RequiresUserConsent(
                    intentSender = intentSender,
                    pendingAction = PendingMediaStoreFileAction(
                        operation = PendingConsentOperation.Rename,
                        targetEpisodes = listOf(episode),
                        newName = targetFileName,
                    ),
                )
            } else {
                FileOperationResult.ReadOnlyOrUnsupported("Storage permission denied for rename.")
            }
        }
    }

    /**
     * Copies or moves an audio file into a target SAF directory tree (or subfolder inside it),
     * streaming via bounded 64 KB buffers, verifying copied byte size before deleting source on Move,
     * and rolling back any partial target document if cancelled or failed.
     */
    suspend fun copyOrMoveToSafFolder(
        episode: Episode,
        destTreeUri: Uri,
        destSubfolderRelativePath: String = "",
        move: Boolean,
    ): FileOperationResult = withContext(Dispatchers.IO) {
        val sourceUriStr = episode.editableAudioUri ?: episode.sourceUri
            ?: return@withContext FileOperationResult.Failed("Source audio file URI is missing.")
        val sourceUri = Uri.parse(sourceUriStr)
        val sourceName = episode.audioAsset.substringAfterLast('/').ifBlank {
            resolveDisplayName(sourceUri) ?: "${episode.title}.mp3"
        }
        val cleanSubpath = destSubfolderRelativePath.trim('/')
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(destTreeUri) }.getOrNull()
            ?: return@withContext FileOperationResult.ReadOnlyOrUnsupported("Destination folder is unavailable or access was revoked.")

        val targetDirDocId = resolveOrCreateSubfolderDocId(destTreeUri, rootDocId, cleanSubpath)
            ?: return@withContext FileOperationResult.ReadOnlyOrUnsupported("Cannot access or create destination subfolder.")
        val targetDirUri = DocumentsContract.buildDocumentUriUsingTree(destTreeUri, targetDirDocId)

        val existingNames = listChildDisplayNames(destTreeUri, targetDirDocId)
        val finalDisplayName = resolveUniqueFileName(sourceName) { candidate ->
            candidate.lowercase() in existingNames
        }
        val mime = context.contentResolver.getType(sourceUri) ?: "audio/*"

        var createdDestUri: Uri? = null
        try {
            createdDestUri = DocumentsContract.createDocument(
                context.contentResolver,
                targetDirUri,
                mime,
                finalDisplayName,
            ) ?: return@withContext FileOperationResult.ReadOnlyOrUnsupported("Destination storage is read-only.")

            var bytesCopied = 0L
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                context.contentResolver.openOutputStream(createdDestUri, "w")?.use { output ->
                    val buffer = ByteArray(CopyBufferSize)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        bytesCopied += read
                    }
                    output.flush()
                } ?: throw IllegalStateException("Unable to open destination stream for writing.")
            } ?: throw IllegalStateException("Unable to read source audio file.")

            // Verify destination integrity before considering transfer successful
            val expectedBytes = episode.fileSizeBytes
            if (bytesCopied <= 0L || (expectedBytes > 0L && bytesCopied != expectedBytes)) {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, createdDestUri) }
                return@withContext FileOperationResult.Failed("File copy verification failed ($bytesCopied/$expectedBytes bytes). Original file was preserved.")
            }

            if (move) {
                val sourceDeleted = deleteSinglePhysicalFile(sourceUri)
                if (sourceDeleted.isSuccess) {
                    val newAsset = if (cleanSubpath.isNotEmpty()) "$cleanSubpath/$finalDisplayName" else finalDisplayName
                    repository.updateLocalEpisodeFileLocation(
                        episodeId = episode.id,
                        sourceUri = createdDestUri.toString(),
                        audioAsset = newAsset,
                        folderUri = destTreeUri.toString(),
                        relativeFolderPath = cleanSubpath,
                        title = finalDisplayName.substringBeforeLast('.'),
                        lastModifiedMs = System.currentTimeMillis(),
                    )
                    com.podcasty.app.ui.components.invalidateArtworkCache(episode.artworkAsset)
                    scanner.startBackgroundScanFolder(destTreeUri)
                    return@withContext FileOperationResult.Success("Moved to $finalDisplayName", listOf(episode.id))
                }
                val consentSender = sourceDeleted.consentSender
                if (consentSender != null) {
                    return@withContext FileOperationResult.RequiresUserConsent(
                        intentSender = consentSender,
                        pendingAction = PendingMediaStoreFileAction(
                            operation = PendingConsentOperation.MoveSourceDelete,
                            targetEpisodes = listOf(episode),
                            copiedDestinationUri = createdDestUri.toString(),
                            copiedDisplayName = finalDisplayName,
                            copiedFolderUri = destTreeUri.toString(),
                            copiedRelativePath = cleanSubpath,
                        ),
                    )
                }
                // Source couldn't be removed cleanly -> keep destination as safe copy & notify
                scanner.startBackgroundScanFolder(destTreeUri)
                return@withContext FileOperationResult.Success(
                    "Copied to $finalDisplayName (original file could not be deleted from read-only storage).",
                    listOf(episode.id),
                )
            } else {
                scanner.startBackgroundScanFolder(destTreeUri)
                return@withContext FileOperationResult.Success("Copied as $finalDisplayName", listOf(episode.id))
            }
        } catch (c: CancellationException) {
            createdDestUri?.let { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it) } }
            throw c
        } catch (t: Throwable) {
            createdDestUri?.let { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it) } }
            return@withContext FileOperationResult.Failed(t.localizedMessage ?: "File transfer failed safely.")
        }
    }

    suspend fun deleteFiles(episodes: List<Episode>): FileOperationResult = withContext(Dispatchers.IO) {
        if (episodes.isEmpty()) return@withContext FileOperationResult.Failed("No files selected.")
        val deletedIds = mutableListOf<String>()
        val mediaStoreConsentUris = mutableListOf<Uri>()
        val consentEpisodes = mutableListOf<Episode>()
        var lastErr: String? = null

        for (ep in episodes) {
            val uriStr = ep.editableAudioUri ?: ep.sourceUri ?: continue
            val uri = Uri.parse(uriStr)
            val outcome = deleteSinglePhysicalFile(uri)
            if (outcome.isSuccess) {
                deletedIds += ep.id
            } else if (outcome.consentUri != null) {
                mediaStoreConsentUris += outcome.consentUri
                consentEpisodes += ep
            } else {
                lastErr = outcome.error
            }
        }

        if (deletedIds.isNotEmpty()) {
            repository.deleteEpisodesCascade(deletedIds)
        }

        if (mediaStoreConsentUris.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, mediaStoreConsentUris)
            return@withContext FileOperationResult.RequiresUserConsent(
                intentSender = pendingIntent.intentSender,
                pendingAction = PendingMediaStoreFileAction(
                    operation = PendingConsentOperation.Delete,
                    targetEpisodes = consentEpisodes,
                ),
            )
        }

        if (deletedIds.isEmpty()) {
            return@withContext FileOperationResult.ReadOnlyOrUnsupported(
                lastErr ?: "Could not delete selected files (permission revoked or read-only storage).",
            )
        }
        FileOperationResult.Success("Deleted ${deletedIds.size} file(s)", deletedIds)
    }

    suspend fun completePendingMediaStoreConsent(
        action: PendingMediaStoreFileAction,
        granted: Boolean,
    ): FileOperationResult = withContext(Dispatchers.IO) {
        if (!granted) {
            return@withContext FileOperationResult.Failed("Storage confirmation cancelled; files left unchanged.")
        }
        when (action.operation) {
            PendingConsentOperation.Rename -> {
                val ep = action.targetEpisodes.firstOrNull() ?: return@withContext FileOperationResult.Failed("Missing track.")
                val newName = action.newName ?: return@withContext FileOperationResult.Failed("Missing new file name.")
                renameFile(ep, newName)
            }
            PendingConsentOperation.Delete -> {
                val ids = action.targetEpisodes.map { it.id }
                repository.deleteEpisodesCascade(ids)
                FileOperationResult.Success("Deleted ${ids.size} file(s)", ids)
            }
            PendingConsentOperation.MoveSourceDelete -> {
                val ep = action.targetEpisodes.firstOrNull() ?: return@withContext FileOperationResult.Failed("Missing track.")
                val destUri = action.copiedDestinationUri ?: return@withContext FileOperationResult.Failed("Missing destination.")
                val finalName = action.copiedDisplayName ?: ep.audioAsset.substringAfterLast('/')
                val relPath = action.copiedRelativePath.orEmpty()
                val newAsset = if (relPath.isNotEmpty()) "$relPath/$finalName" else finalName
                repository.updateLocalEpisodeFileLocation(
                    episodeId = ep.id,
                    sourceUri = destUri,
                    audioAsset = newAsset,
                    folderUri = action.copiedFolderUri,
                    relativeFolderPath = relPath,
                    title = finalName.substringBeforeLast('.'),
                    lastModifiedMs = System.currentTimeMillis(),
                )
                FileOperationResult.Success("Moved to $finalName", listOf(ep.id))
            }
        }
    }

    fun buildShareIntent(episode: Episode): Intent? {
        val rawUri = episode.effectivePlaybackUri ?: episode.editableAudioUri ?: return null
        val shareUri = when {
            rawUri.startsWith("content://", ignoreCase = true) -> Uri.parse(rawUri)
            rawUri.startsWith("file://", ignoreCase = true) -> {
                val file = File(Uri.parse(rawUri).path.orEmpty())
                if (!file.exists()) return null
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }
            else -> return null
        }
        val mime = context.contentResolver.getType(shareUri) ?: "audio/*"
        return Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, shareUri)
            putExtra(Intent.EXTRA_SUBJECT, episode.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private data class DeleteOutcome(
        val isSuccess: Boolean,
        val consentSender: IntentSender? = null,
        val consentUri: Uri? = null,
        val error: String? = null,
    )

    private fun deleteSinglePhysicalFile(uri: Uri): DeleteOutcome {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val file = File(uri.path.orEmpty())
            val deleted = !file.exists() || file.delete()
            return if (deleted) DeleteOutcome(true) else DeleteOutcome(false, error = "File is on read-only storage.")
        }
        if (DocumentsContract.isDocumentUri(context, uri)) {
            return runCatching {
                val deleted = DocumentsContract.deleteDocument(context.contentResolver, uri)
                if (deleted) DeleteOutcome(true) else DeleteOutcome(false, error = "Provider refused delete.")
            }.getOrElse { DeleteOutcome(false, error = it.localizedMessage) }
        }
        if (uri.authority == MediaStore.AUTHORITY || uri.toString().startsWith("content://media/")) {
            return try {
                val rows = context.contentResolver.delete(uri, null, null)
                if (rows > 0) {
                    DeleteOutcome(true)
                } else {
                    DeleteOutcome(false, consentUri = uri)
                }
            } catch (_: SecurityException) {
                val sender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    MediaStore.createDeleteRequest(context.contentResolver, listOf(uri)).intentSender
                } else {
                    null
                }
                DeleteOutcome(false, consentSender = sender, consentUri = uri)
            }
        }
        return DeleteOutcome(false, error = "Unsupported URI scheme for deletion.")
    }

    private fun resolveOrCreateSubfolderDocId(
        treeUri: Uri,
        rootDocId: String,
        relativePath: String,
    ): String? {
        if (relativePath.isEmpty()) return rootDocId
        var currentDocId = rootDocId
        for (segment in relativePath.split('/').filter { it.isNotBlank() }) {
            val existingChildId = findChildDirDocId(treeUri, currentDocId, segment)
            currentDocId = if (existingChildId != null) {
                existingChildId
            } else {
                val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, currentDocId)
                val created = runCatching {
                    DocumentsContract.createDocument(
                        context.contentResolver,
                        parentUri,
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        segment,
                    )
                }.getOrNull() ?: return null
                DocumentsContract.getDocumentId(created)
            }
        }
        return currentDocId
    }

    private fun findChildDirDocId(treeUri: Uri, parentDocId: String, targetName: String): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        return runCatching {
            context.contentResolver.query(childrenUri, proj, null, null, null)?.use { c ->
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (c.moveToNext()) {
                    val mime = if (mimeIdx >= 0) c.getString(mimeIdx) else null
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        val name = if (nameIdx >= 0) c.getString(nameIdx) else null
                        if (name.equals(targetName, ignoreCase = true) && idIdx >= 0) {
                            return@use c.getString(idIdx)
                        }
                    }
                }
                null
            }
        }.getOrNull()
    }

    private fun listChildDisplayNames(treeUri: Uri, parentDocId: String): Set<String> {
        val out = HashSet<String>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val proj = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        runCatching {
            context.contentResolver.query(childrenUri, proj, null, null, null)?.use { c ->
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (c.moveToNext()) {
                    if (nameIdx >= 0) {
                        c.getString(nameIdx)?.let { out += it.lowercase() }
                    }
                }
            }
        }
        return out
    }

    private fun resolveDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun hasSafWriteFlag(uri: Uri): Boolean = runCatching {
        val proj = arrayOf(DocumentsContract.Document.COLUMN_FLAGS)
        context.contentResolver.query(uri, proj, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val flags = c.getInt(0)
                (flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0 ||
                    (flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME) != 0
            } else false
        } ?: false
    }.getOrDefault(false)

    private inline fun resolveUniqueFileName(desiredName: String, existsPredicate: (String) -> Boolean): String {
        if (!existsPredicate(desiredName)) return desiredName
        val base = desiredName.substringBeforeLast('.')
        val ext = desiredName.substringAfterLast('.', "")
        var attempt = 1
        while (attempt < 500) {
            val candidate = if (ext.isNotEmpty()) "$base ($attempt).$ext" else "$base ($attempt)"
            if (!existsPredicate(candidate)) return candidate
            attempt++
        }
        return "${base}_${System.currentTimeMillis()}" + if (ext.isNotEmpty()) ".$ext" else ""
    }

    companion object {
        private const val CopyBufferSize = 64 * 1024
    }
}
