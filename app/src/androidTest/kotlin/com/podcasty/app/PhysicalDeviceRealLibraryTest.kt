/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.podcasty.app.app.PodcastyApplication
import com.podcasty.app.domain.SongSortOption
import com.podcasty.app.domain.SortOrder
import com.podcasty.app.domain.StorageProviderType
import com.podcasty.app.ui.components.clearArtworkMemoryCache
import com.podcasty.app.ui.components.decodeArtworkBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.system.measureTimeMillis

/**
 * Physical-device end-to-end real-file stress test.
 * Skips automatically on empty emulators (< 100 real MediaStore audio files).
 * Never modifies or deletes any pre-existing user audio files; cleans up only its own
 * temporary WAV files under Music/PodcastySyncDeltaTmp and Music/Podcasty10kRealTmp.
 */
@RunWith(AndroidJUnit4::class)
class PhysicalDeviceRealLibraryTest {

    @Test
    fun physicalDevice_realLibrary_10kRealFiles_incrementalSync_playbackAndArtwork(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<Context>() as PodcastyApplication
        val pkg = instrumentation.targetContext.packageName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                instrumentation.uiAutomation.grantRuntimePermission(pkg, Manifest.permission.READ_MEDIA_AUDIO)
            }
            runCatching {
                instrumentation.uiAutomation.grantRuntimePermission(pkg, Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val mediaStoreTotal = countMediaStoreAudio(app)
        Log.i(TAG, "[PHYS_ENV] MediaStore pre-existing audio count=$mediaStoreTotal device=${Build.MANUFACTURER} ${Build.MODEL} Android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        assumeTrue("Requires physical device with >= 100 real audio files (found $mediaStoreTotal)", mediaStoreTotal >= 100)

        val container = app.container
        val dao = container.database.dao()
        val repo = container.repository
        val scanner = container.localMusicScanner
        val coordinator = container.scanCoordinator
        val createdTempUris = mutableListOf<Uri>()
        val wavSmallBytes = generatePcmWavBytes(durationMs = 150, sampleRate = 22_050) // ~6.6 KB valid WAV

        try {
            // =========================================================================
            // PHASE 1: Initial real-file indexing of pre-existing user music library
            // =========================================================================
            val initialScanState: com.podcasty.app.storage.LibraryScanState
            val initialScanMs = measureTimeMillis {
                initialScanState = scanner.scanMediaStoreAudioInternal(sinceGeneration = 0L, silentIncremental = false)
            }
            val initialRoomCount = repo.observeLocalMusicCount().first()
            val (initJavaMb, maxJavaMb, initNativeMb) = snapshotMemoryMb()
            Log.i(
                TAG,
                "[PHYS_INITIAL] files=$initialRoomCount scanned=${initialScanState.scannedFiles} added=${initialScanState.addedCount} " +
                    "time=${initialScanMs}ms javaHeap=${"%.1f".format(initJavaMb)}/${"%.0f".format(maxJavaMb)}MB native=${"%.1f".format(initNativeMb)}MB err=${initialScanState.lastError}",
            )
            assertTrue("Expected at least 100 indexed real tracks, got $initialRoomCount", initialRoomCount >= 100)
            assertEquals("Initial real-library scan error: ${initialScanState.lastError}", null, initialScanState.lastError)

            // Verify Room summary observation stayed bounded despite thousands of real tracks
            val initialSummaries = repo.episodes.first()
            assertTrue("Expected bounded repo.episodes <= 500, got ${initialSummaries.size}", initialSummaries.size <= 500)

            // =========================================================================
            // PHASE 2: Repeated launches without full reindexing (5x)
            // =========================================================================
            val msFolder = dao.getAllMusicFolders().firstOrNull { it.treeUri == StorageProviderType.MediaStore.key }
            assertTrue("MediaStore folder must be marked initialIndexingCompleted=true", msFolder?.initialIndexingCompleted == true)
            val genBeforeRepeats = msFolder?.lastMediaStoreGeneration ?: 0L
            assertTrue("Expected persisted MediaStore generation > 0, got $genBeforeRepeats", genBeforeRepeats > 0L)

            val repeatMs = measureTimeMillis {
                repeat(5) {
                    coordinator.syncOnAppLaunch(autoLibraryRefresh = true, includeSubfolders = true)
                    delay(150L)
                    assertFalse("App launch triggered unexpected full scan on cycle ${it + 1}", scanner.scanState.value.isScanning)
                }
            }
            Log.i(TAG, "[PHYS_REPEATED_LAUNCH] 5x syncOnAppLaunch time=${repeatMs}ms isScanning=${scanner.scanState.value.isScanning} persistedGen=$genBeforeRepeats")

            // =========================================================================
            // PHASE 3: Incremental synchronization after adding 3 brand-new real WAVs
            // =========================================================================
            val deltaUris = mutableListOf<Uri>()
            for (i in 1..3) {
                val uri = insertTempMediaStoreWav(
                    context = app,
                    relativePath = "Music/PodcastySyncDeltaTmp/",
                    displayName = "podcasty_delta_sync_$i.wav",
                    wavBytes = wavSmallBytes,
                )
                if (uri != null) {
                    deltaUris += uri
                    createdTempUris += uri
                }
            }
            assertEquals("Failed to insert 3 temp WAVs into MediaStore", 3, deltaUris.size)
            val genAfterAdd = MediaStore.getGeneration(app, MediaStore.VOLUME_EXTERNAL)
            val deltaScanState: com.podcasty.app.storage.LibraryScanState
            val deltaMs = measureTimeMillis {
                deltaScanState = scanner.scanMediaStoreAudioInternal(
                    sinceGeneration = genBeforeRepeats,
                    silentIncremental = true,
                )
            }
            val countAfterDelta = repo.observeLocalMusicCount().first()
            Log.i(
                TAG,
                "[PHYS_DELTA_SYNC] genBefore=$genBeforeRepeats genAfter=$genAfterAdd deltaAdded=${deltaScanState.addedCount} " +
                    "roomBefore=$initialRoomCount roomAfter=$countAfterDelta time=${deltaMs}ms",
            )
            assertTrue(
                "Expected Room count to increase by at least 3 after delta sync (before=$initialRoomCount after=$countAfterDelta)",
                countAfterDelta >= initialRoomCount + 3,
            )

            // =========================================================================
            // PHASE 4: Scale real MediaStore library to 10,000+ ACTUAL audio files
            // =========================================================================
            val targetRealFiles = 10_050
            val toCreate = (targetRealFiles - countAfterDelta).coerceAtLeast(0)
            val createRealMs = measureTimeMillis {
                for (i in 0 until toCreate) {
                    val sub = i % 20
                    val uri = insertTempMediaStoreWav(
                        context = app,
                        relativePath = "Music/Podcasty10kRealTmp/Batch_$sub/",
                        displayName = "real_stress_%05d.wav".format(i),
                        wavBytes = wavSmallBytes,
                    )
                    if (uri != null) createdTempUris += uri
                }
            }
            val mediaStoreAt10k = countMediaStoreAudio(app)
            val genBefore10k = dao.getAllMusicFolders()
                .firstOrNull { it.treeUri == StorageProviderType.MediaStore.key }
                ?.lastMediaStoreGeneration ?: 0L

            val scan10kState: com.podcasty.app.storage.LibraryScanState
            val scan10kMs = measureTimeMillis {
                scan10kState = scanner.scanMediaStoreAudioInternal(
                    sinceGeneration = genBefore10k,
                    silentIncremental = false,
                )
            }
            val roomCountAt10k = repo.observeLocalMusicCount().first()
            val (java10kMb, _, native10kMb) = snapshotMemoryMb()

            // Measure live queries at 10,000+ REAL indexed tracks
            var summaries10kSize = 0
            val summaries10kMs = measureTimeMillis { summaries10kSize = repo.episodes.first().size }
            var songs10kSize = 0
            val songs10kMs = measureTimeMillis {
                songs10kSize = repo.observeLocalSongsPaged("", SongSortOption.Title, SortOrder.Ascending, 200).first().size
            }
            var albums10kSize = 0
            val albums10kMs = measureTimeMillis { albums10kSize = repo.observeLocalAlbums("", 200).first().size }
            var artists10kSize = 0
            val artists10kMs = measureTimeMillis { artists10kSize = repo.observeLocalArtists("", 200).first().size }
            var folders10kSize = 0
            val folders10kMs = measureTimeMillis { folders10kSize = repo.observeLinearFolderSummaries().first().size }
            var search10kSize = 0
            val search10kMs = measureTimeMillis {
                search10kSize = repo.observeLocalSongsPaged("real_stress_00500", SongSortOption.Title, SortOrder.Ascending, 200).first().size
            }

            Log.i(
                TAG,
                "[PHYS_10K_REAL] created=$toCreate(${createRealMs}ms) mediaStore=$mediaStoreAt10k roomCount=$roomCountAt10k " +
                    "scanTime=${scan10kMs}ms summaries($summaries10kSize)=${summaries10kMs}ms songs($songs10kSize)=${songs10kMs}ms " +
                    "albums($albums10kSize)=${albums10kMs}ms artists($artists10kSize)=${artists10kMs}ms folders($folders10kSize)=${folders10kMs}ms " +
                    "search($search10kSize)=${search10kMs}ms javaHeap=${"%.1f".format(java10kMb)}/${"%.0f".format(maxJavaMb)}MB native=${"%.1f".format(native10kMb)}MB",
            )
            assertTrue("Expected >= 10,000 real indexed tracks in Room, got $roomCountAt10k", roomCountAt10k >= 10_000)
            assertTrue("Expected bounded summaries <= 500 at 10k+ real files, got $summaries10kSize", summaries10kSize <= 500)
            assertTrue("Java heap exceeded 200 MB ceiling at 10k+ real files: $java10kMb MB", java10kMb < 200.0)

            // =========================================================================
            // PHASE 5: Real Media3 Playback WHILE Browsing 10,000+ Real Songs + Artwork
            // =========================================================================
            val realSampleSongs = repo.observeLocalSongsPaged("", SongSortOption.Duration, SortOrder.Descending, 50).first()
            val playableTrack = realSampleSongs.firstOrNull { !it.sourceUri.isNullOrBlank() && it.durationMs >= 5_000L }
                ?: realSampleSongs.firstOrNull { !it.sourceUri.isNullOrBlank() }
            assertTrue("Expected at least one playable real track in 10k+ library", playableTrack != null)

            var playbackError: Throwable? = null
            var reachedReady = false
            var browsedPages = 0
            var decodedCovers = 0

            val browseAndArtJob = launch(Dispatchers.Default) {
                // Page through 2,000 real songs + albums + artists + real embedded album covers
                for (limit in listOf(200, 400, 800, 1200, 2000)) {
                    val page = repo.observeLocalSongsPaged("", SongSortOption.Title, SortOrder.Ascending, limit).first()
                    browsedPages += page.size
                }
                repo.observeLocalAlbums("", 400).first()
                repo.observeLocalArtists("", 400).first()

                val realArtworkPaths = repo.observeLocalAlbums("", 200).first()
                    .mapNotNull { it.artworkAsset }
                    .distinct()
                    .take(100)
                clearArtworkMemoryCache()
                for (artPath in realArtworkPaths) {
                    val bmp = decodeArtworkBitmap(app, artPath, targetPx = 128)
                    if (bmp != null) {
                        decodedCovers++
                        bmp.recycle()
                    }
                }
                clearArtworkMemoryCache()
            }

            withContext(Dispatchers.Main) {
                val player = ExoPlayer.Builder(app).build()
                try {
                    player.addListener(
                        object : Player.Listener {
                            override fun onPlayerError(error: PlaybackException) {
                                playbackError = error
                            }
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_READY) reachedReady = true
                            }
                        },
                    )
                    player.setMediaItem(MediaItem.fromUri(Uri.parse(playableTrack!!.sourceUri!!)))
                    player.prepare()
                    player.playWhenReady = true
                    var waited = 0L
                    while (!reachedReady && playbackError == null && waited < 8_000L) {
                        delay(50L)
                        waited += 50L
                    }
                    assertTrue("Media3 failed to reach STATE_READY for '${playableTrack.title}': err=$playbackError", reachedReady)
                    // Seek twice during active browsing & artwork decoding
                    player.seekTo(2_000L)
                    delay(200L)
                    player.seekTo(5_000L)
                    delay(200L)
                    assertEquals("Media3 playback error during 10k browsing", null, playbackError)
                } finally {
                    player.release()
                }
            }
            browseAndArtJob.join()
            val (playJavaMb, _, playNativeMb) = snapshotMemoryMb()
            Log.i(
                TAG,
                "[PHYS_PLAYBACK_BROWSE] track='${playableTrack?.title}' browsedItems=$browsedPages realCoversDecoded=$decodedCovers " +
                    "javaHeap=${"%.1f".format(playJavaMb)}/${"%.0f".format(maxJavaMb)}MB native=${"%.1f".format(playNativeMb)}MB",
            )
            assertTrue("Heap grew excessively during playback+browsing+artwork: $playJavaMb MB", playJavaMb < 200.0)
        } finally {
            // =========================================================================
            // PHASE 6: Cleanly remove ONLY our temporary stress WAV files & reconcile
            // =========================================================================
            var deletedTemp = 0
            for (uri in createdTempUris) {
                val rows = runCatching { app.contentResolver.delete(uri, null, null) }.getOrDefault(0)
                if (rows > 0) deletedTemp++
            }
            if (createdTempUris.isNotEmpty()) {
                val gen = dao.getAllMusicFolders()
                    .firstOrNull { it.treeUri == StorageProviderType.MediaStore.key }
                    ?.lastMediaStoreGeneration ?: 0L
                runCatching {
                    scanner.scanMediaStoreAudioInternal(sinceGeneration = gen, silentIncremental = true)
                }
            }
            val finalRoomCount = runCatching { repo.observeLocalMusicCount().first() }.getOrDefault(-1)
            Log.i(TAG, "[PHYS_CLEANUP] deletedTempWavs=$deletedTemp/${createdTempUris.size} restoredRoomCount=$finalRoomCount")
        }
    }

    private fun countMediaStoreAudio(context: Context): Int = runCatching {
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID),
            null,
            null,
            null,
        )?.use { it.count } ?: 0
    }.getOrDefault(0)

    private fun insertTempMediaStoreWav(
        context: Context,
        relativePath: String,
        displayName: String,
        wavBytes: ByteArray,
    ): Uri? = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(wavBytes)
        }
        val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
        context.contentResolver.update(uri, done, null, null)
        uri
    }.getOrNull()

    private fun snapshotMemoryMb(): Triple<Double, Double, Double> {
        val rt = Runtime.getRuntime()
        val javaUsed = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0)
        val javaMax = rt.maxMemory() / (1024.0 * 1024.0)
        val nativeUsed = Debug.getNativeHeapAllocatedSize() / (1024.0 * 1024.0)
        return Triple(javaUsed, javaMax, nativeUsed)
    }

    private fun generatePcmWavBytes(durationMs: Int, sampleRate: Int): ByteArray {
        val numSamples = (sampleRate * durationMs) / 1000
        val dataSize = numSamples * 2
        val out = ByteArrayOutputStream(44 + dataSize)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataSize)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(dataSize)
        }
        out.write(header.array())
        val pcm = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until numSamples) {
            val angle = 2.0 * PI * 440.0 * i / sampleRate
            pcm.putShort((sin(angle) * 12_000).toInt().toShort())
        }
        out.write(pcm.array())
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "PodcastyPhysical"
    }
}
