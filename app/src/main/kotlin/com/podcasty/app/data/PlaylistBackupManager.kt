package com.podcasty.app.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.podcasty.app.database.CollectionEntity
import com.podcasty.app.database.PodcastyDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream

@Serializable
data class PlaylistBackup(val format: String = "podcasty-playlists", val version: Int = 1, val playlists: List<PlaylistBackupEntry>)
@Serializable
data class PlaylistBackupEntry(val id: String, val name: String, val episodeIds: List<String>)
data class PlaylistRestoreResult(val playlists: Int, val missingTracks: Int)

/** Bounded, versioned SAF backup. Imports only playlist references, never music or file paths. */
class PlaylistBackupManager(private val context: Context, private val database: PodcastyDatabase) {
    private val json = Json { ignoreUnknownKeys = false }

    suspend fun exportTo(uri: Uri) = withContext(Dispatchers.IO) {
        val dao = database.dao()
        val payload = database.withTransaction {
            PlaylistBackup(playlists = dao.observeCollections().first().filterNot { it.isSystem }.map {
                PlaylistBackupEntry(it.id, it.name, dao.getCollectionEpisodeIds(it.id).distinct())
            })
        }
        validate(payload)
        val bytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MaxBytes) { "Playlist backup exceeds 2 MB." }
        (context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open backup destination.")).use { it.write(bytes) }
    }

    suspend fun restoreFrom(uri: Uri): PlaylistRestoreResult = withContext(Dispatchers.IO) {
        val bytes = (context.contentResolver.openInputStream(uri) ?: error("Cannot read backup.")).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MaxBytes) { "Backup is too large (maximum 2 MB)." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val payload = json.decodeFromString<PlaylistBackup>(bytes.toString(Charsets.UTF_8))
        validate(payload) // Complete validation precedes every database mutation.
        val dao = database.dao()
        database.withTransaction {
            val existing = dao.observeCollections().first().associateBy { it.id }
            val uniqueIds = payload.playlists.flatMap { it.episodeIds }.distinct()
            val available = uniqueIds.chunked(400).flatMap { dao.getEpisodesByIds(it) }.mapTo(hashSetOf()) { it.id }
            val missing = uniqueIds.count { it !in available }
            var restored = 0
            val now = System.currentTimeMillis()
            for (playlist in payload.playlists) {
                val previous = existing[playlist.id]
                require(previous?.isSystem != true) { "Backup conflicts with a system collection." }
                val imported = playlist.episodeIds.distinct().filter { it in available }
                val combined = ((previous?.let { dao.getCollectionEpisodeIds(it.id) } ?: emptyList()) + imported).distinct()
                dao.upsertCollection(previous?.copy(updatedAt = now) ?: CollectionEntity(playlist.id, playlist.name.trim(), false, null, now, now))
                dao.replaceCollectionOrder(playlist.id, combined, now)
                restored++
            }
            PlaylistRestoreResult(restored, missing)
        }
    }

    companion object {
        const val MaxBytes = 2 * 1024 * 1024
        fun validate(backup: PlaylistBackup) {
            require(backup.format == "podcasty-playlists" && backup.version == 1) { "Unsupported playlist backup format." }
            require(backup.playlists.size <= 1000) { "Too many playlists." }
            require(backup.playlists.map { it.id }.distinct().size == backup.playlists.size) { "Duplicate playlist IDs." }
            require(backup.playlists.sumOf { it.episodeIds.size.toLong() } <= 50_000) { "Too many playlist entries." }
            backup.playlists.forEach { playlist ->
                require(playlist.id.isNotBlank() && playlist.id.length <= 256 && !playlist.id.startsWith("system_")) { "Invalid playlist ID." }
                require(playlist.name.isNotBlank() && playlist.name.length <= 200) { "Invalid playlist name." }
                require(playlist.episodeIds.all { it.isNotBlank() && it.length <= 256 }) { "Invalid track ID." }
            }
        }
    }
}
