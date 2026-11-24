/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private val artworkCacheBudgetKb: Int by lazy {
    val maxHeapKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt().coerceAtLeast(32 * 1024)
    (maxHeapKb / 16).coerceIn(4 * 1024, 16 * 1024)
}

private val artworkCache = object : LruCache<String, ImageBitmap>(artworkCacheBudgetKb) {
    override fun sizeOf(key: String, value: ImageBitmap): Int =
        (value.asAndroidBitmap().allocationByteCount / 1024).coerceAtLeast(1)
}

private val failedArtworkKeys = LruCache<String, Long>(512)
private const val FailedArtworkRetryTtlMs = 30_000L

@Volatile
internal var onArtworkCacheInvalidated: ((String?) -> Unit)? = null

fun clearArtworkMemoryCache() {
    artworkCache.evictAll()
    failedArtworkKeys.evictAll()
    onArtworkCacheInvalidated?.invoke(null)
}

/**
 * Evicts all cached thumbnail sizes and negative-cache entries for a specific artwork path
 * (e.g., after tag editing, artwork replacement, file rename/move, or rescan repair).
 */
fun invalidateArtworkCache(artworkPath: String?) {
    val normalized = artworkPath?.trim()?.takeIf { it.isNotEmpty() } ?: return
    val prefix = "$normalized:"
    artworkCache.snapshot().keys.forEach { key ->
        if (key == normalized || key.startsWith(prefix)) {
            artworkCache.remove(key)
        }
    }
    failedArtworkKeys.snapshot().keys.forEach { key ->
        if (key == normalized || key.startsWith(prefix)) {
            failedArtworkKeys.remove(key)
        }
    }
    onArtworkCacheInvalidated?.invoke(normalized)
}

private const val MaxRemoteArtworkBytes = 2 * 1024 * 1024 // 2 MB cap for remote thumbnails/covers
private const val MaxDecodeTargetPx = 768
private const val MaxDiskCacheBytes = 48L * 1024L * 1024L // 48 MB total disk cache cap
private val inFlightArtworkLoads = ConcurrentHashMap<String, CompletableDeferred<ImageBitmap?>>()

internal fun decodeArtworkBitmap(
    context: Context,
    artworkPath: String,
    targetPx: Int,
    config: Bitmap.Config = Bitmap.Config.RGB_565,
): Bitmap? {
    val safeTarget = targetPx.coerceIn(48, MaxDecodeTargetPx)
    if (artworkPath.startsWith("https://", ignoreCase = true) || artworkPath.startsWith("http://", ignoreCase = true)) {
        return decodeRemoteArtworkBitmap(context, artworkPath, safeTarget, config)
    }
    if (artworkPath.startsWith("content://", ignoreCase = true)) {
        return decodeContentUriSampled(context, Uri.parse(artworkPath), safeTarget, config)
    }
    val resolvedLocalPath = if (artworkPath.startsWith("file://", ignoreCase = true)) {
        runCatching { Uri.parse(artworkPath).path }.getOrNull().orEmpty().ifBlank { artworkPath.removePrefix("file://") }
    } else {
        artworkPath
    }
    val file = File(resolvedLocalPath)
    if (file.isAbsolute && file.isFile) {
        return decodeFileSampled(file, safeTarget, config)
    }
    val localFile = File(context.filesDir, artworkPath)
    if (localFile.isFile) {
        return decodeFileSampled(localFile, safeTarget, config)
    }
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.assets.open(artworkPath).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, safeTarget)
        context.assets.open(artworkPath).use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = config
                },
            )
        }
    }.getOrNull()
}

private fun decodeContentUriSampled(
    context: Context,
    uri: Uri,
    targetPx: Int,
    config: Bitmap.Config,
): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, targetPx)
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(
            it,
            null,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = config
            },
        )
    }
}.getOrNull()

private fun decodeRemoteArtworkBitmap(
    context: Context,
    urlString: String,
    targetPx: Int,
    config: Bitmap.Config,
): Bitmap? {
    val cacheDir = File(context.cacheDir, "remote_artwork").apply { mkdirs() }
    val cacheFile = File(cacheDir, "${hashArtworkKey(urlString)}.img")
    if (cacheFile.isFile && cacheFile.length() > 0L) {
        decodeFileSampled(cacheFile, targetPx, config)?.let { return it }
    }
    return runCatching {
        val parsed = URL(urlString)
        val scheme = parsed.protocol.lowercase()
        if (scheme != "https" && scheme != "http") return@runCatching null
        val host = parsed.host.orEmpty().lowercase()
        if (host.isBlank() || host == "localhost" || host == "127.0.0.1" || host == "::1") return@runCatching null

        val conn = (parsed.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Podcasty/1.0 (Android)")
        }
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            val bytes = conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    total += read
                    if (total > MaxRemoteArtworkBytes) return@runCatching null
                    out.write(buf, 0, read)
                }
                out.toByteArray()
            }
            if (bytes.isEmpty()) return@runCatching null
            runCatching {
                cacheFile.writeBytes(bytes)
                pruneDiskCacheIfNeeded(cacheDir, MaxDiskCacheBytes)
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, targetPx)
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = config
                },
            )
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}

private fun decodeFileSampled(file: File, targetPx: Int, config: Bitmap.Config): Bitmap? =
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, targetPx)
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = config
            },
        )
    }.getOrNull()

private fun hashArtworkKey(input: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray())
        .take(12)
        .joinToString("") { "%02x".format(it) }

private fun computeInSampleSize(width: Int, height: Int, targetPx: Int): Int {
    if (width <= 0 || height <= 0 || targetPx <= 0) return 1
    var sampleSize = 1
    while (width / (sampleSize * 2) >= targetPx && height / (sampleSize * 2) >= targetPx) {
        sampleSize *= 2
    }
    return sampleSize
}

private fun pruneDiskCacheIfNeeded(dir: File, maxBytes: Long) {
    val files = dir.listFiles() ?: return
    var total = 0L
    for (f in files) {
        if (f.isFile) total += f.length()
    }
    if (total <= maxBytes) return
    val sorted = files.filter { it.isFile }.sortedBy { it.lastModified() }
    for (f in sorted) {
        if (total <= maxBytes * 3 / 4) break
        val len = f.length()
        if (f.delete()) {
            total -= len
        }
    }
}

@Composable
fun rememberArtworkThumbnail(
    artworkPath: String?,
    targetPx: Int = 120,
): ImageBitmap? {
    val context = LocalContext.current
    val normalizedPath = artworkPath?.trim()?.takeIf { it.isNotEmpty() }
    val cacheKey = normalizedPath?.let { "$it:$targetPx" }
    // Reset thumbnail state deterministically whenever cacheKey changes so recycled LazyColumn rows
    // never display a previous item's artwork while loading or when the new item has no artwork.
    var bitmap by remember(cacheKey) {
        mutableStateOf(if (cacheKey != null) artworkCache.get(cacheKey) else null)
    }

    LaunchedEffect(cacheKey) {
        if (cacheKey == null) {
            bitmap = null
            return@LaunchedEffect
        }
        val path = normalizedPath ?: return@LaunchedEffect
        if (bitmap != null) return@LaunchedEffect
        val failedAt = failedArtworkKeys.get(cacheKey)
        if (failedAt != null && android.os.SystemClock.elapsedRealtime() - failedAt < FailedArtworkRetryTtlMs) {
            return@LaunchedEffect
        }
        val existing = inFlightArtworkLoads[cacheKey]
        val loaded = if (existing != null) {
            existing.await()
        } else {
            val created = CompletableDeferred<ImageBitmap?>()
            val racer = inFlightArtworkLoads.putIfAbsent(cacheKey, created)
            if (racer != null) {
                racer.await()
            } else {
                try {
                    val decoded = withContext(Dispatchers.IO) {
                        decodeArtworkBitmap(context, path, targetPx)?.asImageBitmap()
                    }
                    if (decoded != null) {
                        failedArtworkKeys.remove(cacheKey)
                        artworkCache.put(cacheKey, decoded)
                    } else {
                        failedArtworkKeys.put(cacheKey, android.os.SystemClock.elapsedRealtime())
                    }
                    created.complete(decoded)
                    decoded
                } catch (t: Throwable) {
                    created.complete(null)
                    null
                } finally {
                    inFlightArtworkLoads.remove(cacheKey, created)
                }
            }
        }
        bitmap = loaded
    }
    return bitmap
}
