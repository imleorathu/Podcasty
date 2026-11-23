/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.podcast

import android.content.Context
import android.net.Uri
import com.podcasty.app.storage.core.StorageLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class PodcastDownloadResult(
    val localFile: File,
    val localFileUri: String,
    val sizeBytes: Long,
)

/**
 * Original-quality resumable HTTP downloader for authorized RSS & direct audio podcast enclosures.
 *
 * - Preserves the source container and codec without transcoding or quality loss.
 * - Uses temporary `.part` files and supports HTTP `Range` resumption.
 * - Verifies downloaded byte size and readability before moving into place.
 */
class PodcastAudioDownloader(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build(),
) {
    private val downloadDir: File by lazy {
        File(context.filesDir, "podcast_downloads").apply { mkdirs() }
    }
    private val inFlightDownloads = ConcurrentHashMap<String, CompletableDeferred<Result<PodcastDownloadResult>>>()

    suspend fun downloadOriginalAudio(
        episodeId: String,
        sourceUrl: String,
        suggestedFileName: String? = null,
    ): Result<PodcastDownloadResult> {
        inFlightDownloads[episodeId]?.let { existing ->
            return existing.await()
        }
        val deferred = CompletableDeferred<Result<PodcastDownloadResult>>()
        val racer = inFlightDownloads.putIfAbsent(episodeId, deferred)
        if (racer != null) return racer.await()
        return try {
            val result = downloadOriginalAudioInternal(episodeId, sourceUrl, suggestedFileName)
            deferred.complete(result)
            result
        } catch (ce: CancellationException) {
            deferred.cancel(ce)
            throw ce
        } catch (t: Throwable) {
            val fail = Result.failure<PodcastDownloadResult>(t)
            deferred.complete(fail)
            fail
        } finally {
            inFlightDownloads.remove(episodeId, deferred)
        }
    }

    private suspend fun downloadOriginalAudioInternal(
        episodeId: String,
        sourceUrl: String,
        suggestedFileName: String? = null,
    ): Result<PodcastDownloadResult> = withContext(Dispatchers.IO) {
        runCatching {
            val safeId = episodeId.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val ext = resolveExtension(sourceUrl, suggestedFileName)
            val finalFile = File(downloadDir, "$safeId.$ext")
            val partFile = File(downloadDir, "$safeId.$ext.part")

            if (finalFile.isFile && finalFile.length() > 16_384L) {
                return@runCatching PodcastDownloadResult(
                    localFile = finalFile,
                    localFileUri = Uri.fromFile(finalFile).toString(),
                    sizeBytes = finalFile.length(),
                )
            }

            val existingPartBytes = if (partFile.isFile) partFile.length() else 0L
            val requestBuilder = Request.Builder()
                .url(sourceUrl)
                .get()
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "Podcasty/1.0 (Android)")
            if (existingPartBytes > 0L) {
                requestBuilder.header("Range", "bytes=$existingPartBytes-")
            }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    throw IllegalStateException("Download failed (HTTP ${response.code})")
                }
                val isResumed = response.code == 206 && existingPartBytes > 0L
                val startOffset = if (isResumed) existingPartBytes else 0L
                if (!isResumed && partFile.exists()) {
                    partFile.delete()
                }
                val expectedContentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
                val body = response.body ?: throw IllegalStateException("Empty audio response body")

                var writtenThisSession = 0L
                RandomAccessFile(partFile, "rw").use { raf ->
                    raf.seek(startOffset)
                    val buffer = ByteArray(32 * 1024)
                    body.byteStream().use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            raf.write(buffer, 0, read)
                            writtenThisSession += read
                        }
                    }
                }

                if (expectedContentLength > 0L && writtenThisSession < expectedContentLength) {
                    throw IllegalStateException("Incomplete audio download ($writtenThisSession / $expectedContentLength bytes)")
                }
            }

            if (!partFile.isFile || partFile.length() <= 0L) {
                partFile.delete()
                throw IllegalStateException("Downloaded audio file was empty.")
            }

            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }

            StorageLog.info("PodcastDownloader", "Downloaded original audio for $episodeId (${finalFile.length()} bytes)")
            PodcastDownloadResult(
                localFile = finalFile,
                localFileUri = Uri.fromFile(finalFile).toString(),
                sizeBytes = finalFile.length(),
            )
        }.onFailure { err ->
            if (err is CancellationException) throw err
            StorageLog.warn("PodcastDownloader", "Audio download failed for $episodeId", err)
        }
    }

    fun deleteDownloadedAudio(episodeId: String) {
        val safeId = episodeId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        downloadDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("$safeId.")) {
                file.delete()
            }
        }
    }

    private fun resolveExtension(url: String, suggestedFileName: String?): String {
        val fromName = suggestedFileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (fromName in setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav", "mp4")) return fromName
        val cleanUrl = url.substringBefore('?').substringBefore('#')
        val fromUrl = cleanUrl.substringAfterLast('.', "").lowercase()
        return if (fromUrl in setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav", "mp4")) fromUrl else "mp3"
    }
}
