/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.storage.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.podcasty.app.database.PodcastyDao
import com.podcasty.app.domain.StorageProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Validates persisted SAF folder grants and updates `music_folders.isAvailable` safely
 * without ever deleting existing indexed tracks when a USB/SD card disconnects or a grant is revoked.
 */
class StoragePermissionMonitor(
    private val context: Context,
    private val dao: PodcastyDao,
) {
    /**
     * Checks persisted SAF grants on startup or storage reconnection and updates availability flags.
     * Returns the list of folder URIs that are currently readable.
     */
    suspend fun validatePersistedFolders(): List<String> = withContext(Dispatchers.IO) {
        val folders = dao.getAllMusicFolders()
        if (folders.isEmpty()) return@withContext emptyList()

        val persistedReadUris = runCatching {
            context.contentResolver.persistedUriPermissions
                .filter { it.isReadPermission }
                .mapTo(HashSet()) { it.uri.toString() }
        }.getOrDefault(emptySet())

        val availableTrees = mutableListOf<String>()
        for (folder in folders) {
            if (folder.treeUri == StorageProviderType.MediaStore.key) {
                if (!folder.isAvailable) dao.setMusicFolderAvailable(folder.treeUri, true)
                availableTrees += folder.treeUri
                continue
            }
            val uri = runCatching { Uri.parse(folder.treeUri) }.getOrNull()
            val hasGrant = folder.treeUri in persistedReadUris
            val rootReachable = hasGrant && uri != null && isTreeRootReachable(uri)

            if (folder.isAvailable != rootReachable) {
                dao.setMusicFolderAvailable(folder.treeUri, rootReachable)
                if (!rootReachable) {
                    StorageLog.warn("PermissionMonitor", "Folder unavailable or permission revoked (tracks preserved): ${folder.displayName}")
                } else {
                    StorageLog.info("PermissionMonitor", "Folder became available: ${folder.displayName}")
                }
            }
            if (rootReachable) {
                availableTrees += folder.treeUri
            }
        }
        availableTrees
    }

    /**
     * Marks removable (USB OTG / SD card) folders unavailable if their document tree is no longer reachable,
     * preserving all Room metadata & playback history.
     */
    suspend fun refreshRemovableAvailability() = withContext(Dispatchers.IO) {
        val folders = dao.getAllMusicFolders()
        for (folder in folders) {
            if (folder.storageType == StorageProviderType.Internal.key || folder.storageType == StorageProviderType.MediaStore.key) {
                continue
            }
            val uri = runCatching { Uri.parse(folder.treeUri) }.getOrNull()
            val reachable = uri != null && isTreeRootReachable(uri)
            if (folder.isAvailable != reachable) {
                dao.setMusicFolderAvailable(folder.treeUri, reachable)
                StorageLog.info("PermissionMonitor", "Removable storage '${folder.displayName}' available=$reachable")
            }
        }
    }

    private fun isTreeRootReachable(treeUri: Uri): Boolean = runCatching {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        context.contentResolver.query(
            rootDocUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null,
            null,
            null,
        )?.use { cursor ->
            cursor.moveToFirst()
        } ?: false
    }.getOrDefault(false)
}
