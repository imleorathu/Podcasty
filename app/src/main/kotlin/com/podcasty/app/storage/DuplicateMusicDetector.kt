package com.podcasty.app.storage

import android.content.Context
import android.net.Uri
import com.podcasty.app.database.PodcastyDao
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.PodcastRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest

 data class DuplicateMusicResult(val groups: List<List<Episode>>, val unreadableFiles: Int, val truncated: Boolean)

/** Expensive content comparison runs only after an explicit user request, on metadata candidates. */
class DuplicateMusicDetector(private val context: Context, private val dao: PodcastyDao, private val repository: PodcastRepository) {
    suspend fun find(): DuplicateMusicResult = withContext(Dispatchers.IO) {
        val ids = dao.getDuplicateMusicCandidateIds()
        val tracks = ids.take(1000).chunked(400).flatMap { repository.getEpisodesByIds(it) }.distinctBy { it.sourceUri }
        val fingerprints = linkedMapOf<String, MutableList<Episode>>()
        val buffer = ByteArray(64 * 1024)
        var unreadable = 0
        for (track in tracks) {
            currentCoroutineContext().ensureActive()
            val uri = track.sourceUri ?: continue
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                (context.contentResolver.openInputStream(Uri.parse(uri)) ?: error("File unavailable")).use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                val key = digest.digest().joinToString("") { "%02x".format(it) }
                fingerprints.getOrPut(key) { mutableListOf() }.add(track)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                unreadable++
            }
        }
        DuplicateMusicResult(fingerprints.values.filter { it.size > 1 }, unreadable, ids.size > 1000)
    }
}
