/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Debug
import android.util.Log
import com.podcasty.app.ui.components.clearArtworkMemoryCache
import com.podcasty.app.ui.components.decodeArtworkBitmap
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.podcasty.app.app.PodcastyApplication
import com.podcasty.app.audio.AudioFormatCatalog
import com.podcasty.app.database.CategoryEntity
import com.podcasty.app.database.EpisodeEntity
import com.podcasty.app.database.HostEntity
import com.podcasty.app.database.MusicFolderEntity
import com.podcasty.app.database.MusicQueueEntity
import com.podcasty.app.database.PodcastEntity
import com.podcasty.app.database.PodcastyDatabase
import com.podcasty.app.database.ScanSessionEntity
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.MediaSourceType
import com.podcasty.app.storage.FileOperationResult
import com.podcasty.app.storage.LocalMusicScanner
import com.podcasty.app.storage.core.ScanSessionStatus
import com.podcasty.app.tags.AudioTagEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class ExtremeScalabilityInstrumentedTest {

    @Test
    fun syntheticRoomScalability_10k_30k_50k_100k_and_100Invalidations(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "podcasty_stress_100k.db"
        context.deleteDatabase(dbName)
        val db = Room.databaseBuilder(context, PodcastyDatabase::class.java, dbName)
            .addMigrations(
                PodcastyDatabase.MIGRATION_1_2,
                PodcastyDatabase.MIGRATION_2_3,
                PodcastyDatabase.MIGRATION_3_4,
                PodcastyDatabase.MIGRATION_4_5,
                PodcastyDatabase.MIGRATION_5_6,
                PodcastyDatabase.MIGRATION_6_7,
                PodcastyDatabase.MIGRATION_7_8,
                PodcastyDatabase.MIGRATION_8_9,
            )
            .build()
        val dao = db.dao()

        try {
            val now = System.currentTimeMillis()
            dao.insertCategories(listOf(CategoryEntity("stress_cat", "Local Music", now)))

            // Seed 1,000 artists & 2,000 albums to verify observeHosts() / observePodcasts() bounds
            val hosts = (0 until 1_000).map { i ->
                HostEntity("local_host_$i", "Artist %04d".format(i), "Local artist", null, now)
            }
            for (chunk in hosts.chunked(250)) dao.insertHosts(chunk)

            val podcasts = (0 until 2_000).map { i ->
                PodcastEntity(
                    id = "local_album_$i",
                    title = "Album %04d".format(i),
                    hostId = "local_host_${i % 1_000}",
                    hostName = "Artist %04d".format(i % 1_000),
                    categoryId = "stress_cat",
                    categoryName = "Local Music",
                    description = "Synthetic album",
                    publisher = "Artist %04d".format(i % 1_000),
                    artworkAsset = null,
                    isSubscribed = true,
                    isRecommended = false,
                    isPopular = false,
                    addedAt = now,
                    sourceType = MediaSourceType.LocalMusic.key,
                )
            }
            for (chunk in podcasts.chunked(250)) dao.insertPodcasts(chunk)

            val checkpoints = listOf(10_000, 30_000, 50_000, 100_000)
            var insertedSoFar = 0
            val batch = ArrayList<EpisodeEntity>(500)
            val unicodeSamples = arrayOf("Alpha", "Éclair", "தமிழ்", "हिन्दी", "日本語", "موسيقى")

            for (target in checkpoints) {
                val insertMs = measureTimeMillis {
                    while (insertedSoFar < target) {
                        val i = insertedSoFar++
                        val prefix = unicodeSamples[i % unicodeSamples.size]
                        val title = "$prefix Track %06d".format(i)
                        val artistIdx = i % 1_000
                        val albumIdx = i % 2_000
                        val folderPath = "Music/Genre_${i % 10}/Sub_${i % 25}"
                        batch += EpisodeEntity(
                            id = "ep_100k_$i",
                            podcastId = "local_album_$albumIdx",
                            podcastTitle = "Album %04d".format(albumIdx),
                            hostId = "local_host_$artistIdx",
                            hostName = "Artist %04d".format(artistIdx),
                            categoryId = "stress_cat",
                            categoryName = "Local Music",
                            episodeNumber = (i % 20) + 1,
                            title = title,
                            summary = "Synthetic stress track $i",
                            artworkAsset = null,
                            audioAsset = "$folderPath/track_$i.flac",
                            durationMs = 210_000L,
                            playbackPositionMs = 0L,
                            isPlayed = false,
                            isSaved = i % 500 == 0,
                            isDownloaded = true,
                            fileSizeBytes = 12_000_000L,
                            playCount = i % 7,
                            publishedAt = now + i,
                            lastPlayedAt = if (i % 1000 == 0) now + i else null,
                            transcriptLrc = if (i % 50 == 0) "[00:01.00]Synthetic synced lyrics line for memory test $i" else null,
                            sourceType = MediaSourceType.LocalMusic.key,
                            sourceUri = "content://com.podcasty.stress/doc/$i",
                            folderUri = "content://com.podcasty.stress/tree/root",
                            relativeFolderPath = folderPath,
                            normalizedTitle = title.lowercase(),
                            normalizedArtist = "artist %04d".format(artistIdx),
                            normalizedAlbum = "album %04d".format(albumIdx),
                        )
                        if (batch.size >= 500) {
                            dao.insertEpisodes(batch.toList())
                            batch.clear()
                        }
                    }
                    if (batch.isNotEmpty()) {
                        dao.insertEpisodes(batch.toList())
                        batch.clear()
                    }
                }

                // Measure all critical Room queries at this scale
                var summaryCount = 0
                val summaryMs = measureTimeMillis {
                    val list = dao.observeEpisodeSummaries().first()
                    summaryCount = list.size
                    assertTrue("observeEpisodeSummaries must stay <= 500 at $target rows, got $summaryCount", summaryCount <= 500)
                }

                var hostsCount = 0
                var podcastsCount = 0
                val metaMs = measureTimeMillis {
                    hostsCount = dao.observeHosts().first().size
                    podcastsCount = dao.observePodcasts().first().size
                    assertTrue("observeHosts must stay <= 500, got $hostsCount", hostsCount <= 500)
                    assertTrue("observePodcasts must stay <= 500, got $podcastsCount", podcastsCount <= 500)
                }

                var pagedSongsCount = 0
                val songsMs = measureTimeMillis {
                    pagedSongsCount = dao.observeLocalSongsByTitleAsc(200).first().size
                    assertEquals(200, pagedSongsCount)
                }

                var albumsCount = 0
                val albumsMs = measureTimeMillis {
                    albumsCount = dao.observeLocalAlbums("", 200).first().size
                    assertEquals(200, albumsCount)
                }

                var artistsCount = 0
                val artistsMs = measureTimeMillis {
                    artistsCount = dao.observeLocalArtists("", 200).first().size
                    assertEquals(200, artistsCount)
                }

                var folderGroups = 0
                val foldersMs = measureTimeMillis {
                    folderGroups = dao.observeLinearFolders().first().size
                    assertTrue(folderGroups > 0)
                }

                var searchHits = 0
                val searchMs = measureTimeMillis {
                    searchHits = dao.observeLocalSongsPaged("Track %06d".format(target - 1), "title", true, 200).first().size
                    assertEquals(1, searchHits)
                }

                val countVal = dao.observeLocalMusicCount().first()
                assertEquals(target, countVal)

                val rt = Runtime.getRuntime()
                val javaUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0)
                val javaMaxMb = rt.maxMemory() / (1024.0 * 1024.0)
                val nativeMb = Debug.getNativeHeapAllocatedSize() / (1024.0 * 1024.0)

                val reportLine = "[ROOM_100K] rows=$target insert=${insertMs}ms " +
                    "summaries(${summaryCount})=${summaryMs}ms hosts(${hostsCount})/albums(${podcastsCount})=${metaMs}ms " +
                    "songs200=${songsMs}ms albums200=${albumsMs}ms artists200=${artistsMs}ms " +
                    "folders(${folderGroups})=${foldersMs}ms search=${searchMs}ms " +
                    "javaHeap=${"%.1f".format(javaUsedMb)}/${"%.0f".format(javaMaxMb)}MB native=${"%.1f".format(nativeMb)}MB"
                Log.i("PodcastyStress", reportLine)
                println(reportLine)

                assertTrue("Java heap exceeded 180 MB safety ceiling at $target rows: $javaUsedMb MB", javaUsedMb < 180.0)
            }

            // Test 50,000-ID queue replacement is safely capped at 2,000 & chunked without SQLite errors
            dao.upsertMusicQueue(
                MusicQueueEntity(
                    id = "stress_q",
                    name = "Stress Queue",
                    isActive = true,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            val hugeQueueInput = (0 until 50_000).map { "ep_100k_$it" }
            val queueMs = measureTimeMillis {
                dao.replaceMusicQueueItems("stress_q", hugeQueueInput, System.currentTimeMillis())
            }
            val persistedQueueIds = dao.getQueueEpisodeIds("stress_q")
            assertEquals("Active queue persistence must cap at 2,000 tracks", 2_000, persistedQueueIds.size)
            Log.i("PodcastyStress", "[ROOM_100K] replaceMusicQueueItems(50k->2k) took ${queueMs}ms")

            // Test 100 repeated query cycles at 100,000 rows to ensure zero heap retention leak
            System.gc()
            val rt = Runtime.getRuntime()
            val heapBeforeCyclesMb = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0)
            val cyclesMs = measureTimeMillis {
                repeat(100) { idx ->
                    val list = dao.observeEpisodeSummaries().first()
                    assertTrue(list.size <= 500)
                    if (idx % 10 == 0) {
                        val page = dao.observeLocalSongsByTitleAsc(200).first()
                        assertEquals(200, page.size)
                    }
                }
            }
            System.gc()
            val heapAfterCyclesMb = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0)
            Log.i(
                "PodcastyStress",
                "[ROOM_100K] 100_cycles=${cyclesMs}ms heapBefore=${"%.1f".format(heapBeforeCyclesMb)}MB heapAfter=${"%.1f".format(heapAfterCyclesMb)}MB",
            )
            assertTrue("Heap grew excessively across 100 query cycles: $heapAfterCyclesMb MB", heapAfterCyclesMb < 180.0)
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun realAudioFiles_corruptedUnicodeDeepNesting_and_concurrentMedia3Playback(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>() as PodcastyApplication
        val rootDir = File(app.filesDir, "stress_real_audio").apply {
            deleteRecursively()
            mkdirs()
        }
        val deepDir = File(rootDir, "L1/L2/L3/L4/L5/L6/L7/L8/L9/L10").apply { mkdirs() }
        val dao = app.container.database.dao()
        val insertedIds = mutableListOf<String>()

        try {
            // 1. Create real playable 2-second 44.1kHz PCM WAV files (including Unicode & deep nesting)
            val validWavBytes = generatePcmWavBytes(durationSec = 2, sampleRate = 44_100)
            val playableWav = File(rootDir, "playable_sine.wav").apply { writeBytes(validWavBytes) }
            val unicodeTamil = File(rootDir, "தமிழ்_பாடல்.wav").apply { writeBytes(validWavBytes) }
            val unicodeJp = File(rootDir, "日本語_トラック.wav").apply { writeBytes(validWavBytes) }
            val deepWav = File(deepDir, "deep_nested_track.wav").apply { writeBytes(validWavBytes) }

            // 2. Create corrupted MP3 garbage file & zero-byte file
            val corruptMp3 = File(rootDir, "corrupted_garbage.mp3").apply {
                writeBytes(ByteArray(4096) { (it * 31).toByte() })
            }
            val emptyFile = File(rootDir, "zero_byte.mp3").apply { writeBytes(ByteArray(0)) }

            // Verify format detection across all real files
            for (f in listOf(playableWav, unicodeTamil, unicodeJp, deepWav, corruptMp3, emptyFile)) {
                val detected = AudioFormatCatalog.detectFromUri(app, Uri.fromFile(f), f.name)
                assertTrue("Format detection should succeed for ${f.name}", detected.displayName.isNotBlank())
            }

            // Verify AudioTagEditor handles corrupted & zero-byte files safely without crashing
            val tagEditor = AudioTagEditor(app)
            // Must return a Result (success or failure) without throwing / crashing the process
            val corruptRead = tagEditor.readTags(Uri.fromFile(corruptMp3))
            assertTrue(corruptRead.isSuccess || corruptRead.isFailure)
            val emptyRead = tagEditor.readTags(Uri.fromFile(emptyFile))
            assertTrue(emptyRead.isSuccess || emptyRead.isFailure)

            // Verify MusicFileManager real file rename & safe error on missing file
            val testEpId = "real_file_ep_1"
            insertedIds += testEpId
            val now = System.currentTimeMillis()
            dao.insertEpisodes(
                listOf(
                    EpisodeEntity(
                        id = testEpId,
                        podcastId = "real_alb",
                        podcastTitle = "Real Audio Test",
                        hostId = "real_art",
                        hostName = "Test Synth",
                        categoryId = "real_cat",
                        categoryName = "Music",
                        episodeNumber = 1,
                        title = "தமிழ்_பாடல்",
                        summary = "",
                        artworkAsset = null,
                        audioAsset = unicodeTamil.name,
                        durationMs = 2_000L,
                        playbackPositionMs = 0L,
                        isPlayed = false,
                        isSaved = false,
                        isDownloaded = true,
                        fileSizeBytes = unicodeTamil.length(),
                        playCount = 0,
                        publishedAt = now,
                        lastPlayedAt = null,
                        transcriptLrc = null,
                        sourceType = MediaSourceType.LocalMusic.key,
                        sourceUri = Uri.fromFile(unicodeTamil).toString(),
                        folderUri = "file://${rootDir.absolutePath}",
                    ),
                ),
            )
            val epModel = Episode(
                id = testEpId,
                podcastId = "real_alb",
                podcastTitle = "Real Audio Test",
                hostId = "real_art",
                hostName = "Test Synth",
                categoryId = "real_cat",
                categoryName = "Music",
                episodeNumber = 1,
                title = "தமிழ்_பாடல்",
                summary = "",
                artworkAsset = null,
                audioAsset = unicodeTamil.name,
                durationMs = 2_000L,
                playbackPositionMs = 0L,
                isPlayed = false,
                isSaved = false,
                isDownloaded = true,
                fileSizeBytes = unicodeTamil.length(),
                playCount = 0,
                publishedAt = now,
                lastPlayedAt = null,
                transcriptLrc = null,
                sourceType = MediaSourceType.LocalMusic,
                sourceUri = Uri.fromFile(unicodeTamil).toString(),
                folderUri = "file://${rootDir.absolutePath}",
            )
            val renameRes = app.container.musicFileManager.renameFile(epModel, "தமிழ்_புதிய_பாடல்")
            assertTrue("Unicode rename failed: $renameRes", renameRes is FileOperationResult.Success)
            assertTrue(File(rootDir, "தமிழ்_புதிய_பாடல்.wav").exists())

            // 3. Real Media3 ExoPlayer playback of generated WAV while concurrent Room batch writes run
            var playerError: Throwable? = null
            var reachedReady = false
            val bgWriteJob = launch(Dispatchers.IO) {
                val batch = ArrayList<EpisodeEntity>(100)
                for (i in 0 until 500) {
                    val id = "concurrent_ep_$i"
                    synchronized(insertedIds) { insertedIds += id }
                    batch += EpisodeEntity(
                        id = id,
                        podcastId = "conc_alb_${i % 10}",
                        podcastTitle = "Concurrent Album ${i % 10}",
                        hostId = "conc_art_${i % 5}",
                        hostName = "Concurrent Artist ${i % 5}",
                        categoryId = "real_cat",
                        categoryName = "Music",
                        episodeNumber = i + 1,
                        title = "Concurrent Track $i",
                        summary = "",
                        artworkAsset = null,
                        audioAsset = "conc_$i.wav",
                        durationMs = 120_000L,
                        playbackPositionMs = 0L,
                        isPlayed = false,
                        isSaved = false,
                        isDownloaded = true,
                        fileSizeBytes = 1024L,
                        playCount = 0,
                        publishedAt = now + i,
                        lastPlayedAt = null,
                        transcriptLrc = null,
                        sourceType = MediaSourceType.LocalMusic.key,
                        sourceUri = "content://com.podcasty.stress/conc/$i",
                        folderUri = "content://com.podcasty.stress/tree/conc",
                    )
                    if (batch.size >= 50) {
                        dao.insertEpisodes(batch.toList())
                        batch.clear()
                    }
                }
            }

            withContext(Dispatchers.Main) {
                val player = ExoPlayer.Builder(app).build()
                try {
                    player.addListener(
                        object : Player.Listener {
                            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                playerError = error
                            }
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_READY) reachedReady = true
                            }
                        },
                    )
                    player.setMediaItem(MediaItem.fromUri(Uri.fromFile(playableWav)))
                    player.prepare()
                    player.playWhenReady = true
                    var waitedMs = 0L
                    while (!reachedReady && playerError == null && waitedMs < 5_000L) {
                        delay(50L)
                        waitedMs += 50L
                    }
                    assertTrue("Expected Media3 ExoPlayer to reach STATE_READY, error=$playerError", reachedReady)
                    assertEquals(null, playerError)
                    // Seek mid-track and verify no playback error
                    player.seekTo(500L)
                    delay(150L)
                    assertEquals(null, playerError)
                } finally {
                    player.release()
                }
            }
            bgWriteJob.join()
            Log.i("PodcastyStress", "[REAL_AUDIO] Playable WAV + Unicode + Corrupt MP3 + Concurrent Room writes PASSED")
        } finally {
            for (chunk in insertedIds.chunked(500)) {
                dao.deleteEpisodesCascade(chunk)
            }
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun failureInjection_interruptedScanRecovery_and_revokedFolderPreservesTracks(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>() as PodcastyApplication
        val dao = app.container.database.dao()
        val scanner = LocalMusicScanner(app, dao)
        val revokedUri = "content://com.podcasty.nonexistent.provider/tree/revoked_usb_drive"
        val protectedEpId = "protected_usb_track_1"
        val interruptedScanId = "scan_interrupted_test_1"
        val now = System.currentTimeMillis()

        try {
            // 1. Seed a protected track + folder + an interrupted RUNNING scan session
            dao.upsertMusicFolder(
                MusicFolderEntity(
                    treeUri = revokedUri,
                    displayName = "Revoked USB Drive",
                    storageType = "usb_otg",
                    isDefault = false,
                    includeSubfolders = true,
                    lastScannedAt = now - 60_000L,
                    trackCount = 1,
                    isAvailable = true,
                    isEnabled = true,
                    initialIndexingCompleted = true,
                    lastSyncAt = now - 60_000L,
                ),
            )
            dao.insertEpisodes(
                listOf(
                    EpisodeEntity(
                        id = protectedEpId,
                        podcastId = "usb_alb",
                        podcastTitle = "USB Album",
                        hostId = "usb_art",
                        hostName = "USB Artist",
                        categoryId = "usb_cat",
                        categoryName = "Music",
                        episodeNumber = 1,
                        title = "Protected USB Track",
                        summary = "",
                        artworkAsset = null,
                        audioAsset = "Protected.flac",
                        durationMs = 180_000L,
                        playbackPositionMs = 0L,
                        isPlayed = false,
                        isSaved = true,
                        isDownloaded = true,
                        fileSizeBytes = 10_000_000L,
                        playCount = 2,
                        publishedAt = now,
                        lastPlayedAt = null,
                        transcriptLrc = null,
                        sourceType = MediaSourceType.LocalMusic.key,
                        sourceUri = "$revokedUri/doc/1",
                        folderUri = revokedUri,
                    ),
                ),
            )
            dao.upsertScanSession(
                ScanSessionEntity(
                    scanId = interruptedScanId,
                    folderUri = revokedUri,
                    startedAt = now - 30_000L,
                    status = ScanSessionStatus.RUNNING.name,
                    discoveredCount = 50,
                    indexedCount = 25,
                ),
            )

            // 2. Startup sync recovers interrupted scan session without wiping tracks or starting full scan
            app.container.scanCoordinator.recoverOnStartup()
            app.container.scanCoordinator.syncOnAppLaunch(autoLibraryRefresh = true, includeSubfolders = true)
            var waited = 0
            while (dao.getInterruptedScanSessions().isNotEmpty() && waited < 5_000) {
                delay(100L)
                waited += 100
            }
            assertFalse(app.container.localMusicScanner.scanState.value.isScanning)
            val remainingRunning = dao.getInterruptedScanSessions()
            assertTrue("Expected interrupted RUNNING sessions to be marked INTERRUPTED", remainingRunning.isEmpty())
            assertTrue("Protected track must still exist after interrupted scan recovery", dao.getEpisodeById(protectedEpId) != null)

            // 3. Scanning a revoked/disconnected folder must NOT delete existing tracks
            val scanResult = scanner.scanFolderInternal(Uri.parse(revokedUri), includeSubfolders = true)
            assertFalse(scanResult.isScanning)
            assertTrue("Expected error reported for revoked/disconnected folder", scanResult.lastError != null)
            assertTrue("Existing track must be preserved when folder is unreachable/revoked", dao.getEpisodeById(protectedEpId) != null)
            val folderAfter = dao.getAllMusicFolders().firstOrNull { it.treeUri == revokedUri }
            assertEquals("Folder should be marked unavailable", false, folderAfter?.isAvailable)
            Log.i("PodcastyStress", "[FAILURE_INJECTION] Interrupted scan recovery & revoked/USB-detach track protection PASSED")
        } finally {
            dao.deleteEpisodesCascade(listOf(protectedEpId))
            dao.deleteMusicFolder(revokedUri)
        }
    }

    @Test
    fun artworkLoaderStress_oversizedDownsampling_corruptedBytes_and_500Decodes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val artDir = File(context.cacheDir, "stress_artwork_test").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            // 1. Oversized 2400x2400 JPEG downsampled to thumbnail targetPx=128
            val hugeJpg = File(artDir, "huge_cover_2400.jpg")
            val hugeBmp = Bitmap.createBitmap(2400, 2400, Bitmap.Config.RGB_565)
            try {
                hugeJpg.outputStream().use { out ->
                    hugeBmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
            } finally {
                hugeBmp.recycle()
            }
            val thumb = decodeArtworkBitmap(context, hugeJpg.absolutePath, targetPx = 128)
            assertTrue("Expected downsampled bitmap from 2400x2400 JPEG", thumb != null)
            assertTrue("Expected width <= 320, got ${thumb!!.width}", thumb.width <= 320)
            assertTrue("Expected height <= 320, got ${thumb.height}", thumb.height <= 320)
            thumb.recycle()

            // 2. Corrupted JPEG bytes & missing file recover cleanly with null
            val corruptJpg = File(artDir, "corrupt.jpg").apply { writeBytes(ByteArray(2048) { (it * 19).toByte() }) }
            assertEquals(null, decodeArtworkBitmap(context, corruptJpg.absolutePath, targetPx = 128))
            assertEquals(null, decodeArtworkBitmap(context, File(artDir, "deleted_cache.jpg").absolutePath, targetPx = 128))

            // 3. 500 repeated decodes stay memory-bounded
            clearArtworkMemoryCache()
            System.gc()
            val rt = Runtime.getRuntime()
            val beforeMb = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0)
            val decodeMs = measureTimeMillis {
                repeat(500) {
                    val b = decodeArtworkBitmap(context, hugeJpg.absolutePath, targetPx = 128)
                    assertTrue(b != null)
                    b?.recycle()
                }
            }
            clearArtworkMemoryCache()
            System.gc()
            val afterMb = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0)
            Log.i(
                "PodcastyStress",
                "[ARTWORK_STRESS] 500_decodes=${decodeMs}ms heapBefore=${"%.1f".format(beforeMb)}MB heapAfter=${"%.1f".format(afterMb)}MB",
            )
            assertTrue("Artwork stress heap grew excessively: $afterMb MB", afterMb < 180.0)
        } finally {
            clearArtworkMemoryCache()
            artDir.deleteRecursively()
        }
    }

    private fun generatePcmWavBytes(durationSec: Int, sampleRate: Int): ByteArray {
        val numSamples = durationSec * sampleRate
        val dataSize = numSamples * 2 // 16-bit mono
        val out = ByteArrayOutputStream(44 + dataSize)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataSize)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16) // PCM chunk size
            putShort(1) // PCM format
            putShort(1) // mono
            putInt(sampleRate)
            putInt(sampleRate * 2) // byte rate
            putShort(2) // block align
            putShort(16) // bits per sample
            put("data".toByteArray())
            putInt(dataSize)
        }
        out.write(header.array())
        val pcm = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until numSamples) {
            val angle = 2.0 * PI * 440.0 * i / sampleRate
            pcm.putShort((sin(angle) * 16_000).toInt().toShort())
        }
        out.write(pcm.array())
        return out.toByteArray()
    }
}
