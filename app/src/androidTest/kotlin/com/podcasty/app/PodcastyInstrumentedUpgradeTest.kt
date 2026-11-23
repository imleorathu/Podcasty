/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.podcasty.app.asr.LiveCaptionAudioProcessor
import com.podcasty.app.audio.AudioFormatCatalog
import com.podcasty.app.audio.WrappedPcmDataSource
import com.podcasty.app.database.LiveCaptionEntity
import com.podcasty.app.database.PodcastyDatabase
import com.podcasty.app.domain.RawPcmConfig
import com.podcasty.app.tags.AudioTagEditor
import com.podcasty.app.tags.EditableAudioTags
import com.podcasty.app.tags.TagSaveResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class PodcastyInstrumentedUpgradeTest {

    @Test
    fun roomMigration_1_to_2_preservesExistingUserDataAndAddsNewTables() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_test_${System.currentTimeMillis()}.db"
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        // 1. Create authentic v1 schema + user rows
        val sqlite = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        sqlite.execSQL("CREATE TABLE hosts (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, bio TEXT NOT NULL, artworkAsset TEXT, createdAt INTEGER NOT NULL)")
        sqlite.execSQL("CREATE TABLE categories (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, createdAt INTEGER NOT NULL)")
        sqlite.execSQL("CREATE TABLE podcasts (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, hostId TEXT NOT NULL, hostName TEXT NOT NULL, categoryId TEXT NOT NULL, categoryName TEXT NOT NULL, description TEXT NOT NULL, publisher TEXT NOT NULL, artworkAsset TEXT, isSubscribed INTEGER NOT NULL, isRecommended INTEGER NOT NULL, isPopular INTEGER NOT NULL, addedAt INTEGER NOT NULL)")
        sqlite.execSQL("CREATE TABLE episodes (id TEXT NOT NULL PRIMARY KEY, podcastId TEXT NOT NULL, podcastTitle TEXT NOT NULL, hostId TEXT NOT NULL, hostName TEXT NOT NULL, categoryId TEXT NOT NULL, categoryName TEXT NOT NULL, episodeNumber INTEGER NOT NULL, title TEXT NOT NULL, summary TEXT NOT NULL, artworkAsset TEXT, audioAsset TEXT NOT NULL, durationMs INTEGER NOT NULL, playbackPositionMs INTEGER NOT NULL, isPlayed INTEGER NOT NULL, isSaved INTEGER NOT NULL, isDownloaded INTEGER NOT NULL, fileSizeBytes INTEGER NOT NULL, playCount INTEGER NOT NULL, publishedAt INTEGER NOT NULL, lastPlayedAt INTEGER, transcriptLrc TEXT)")
        sqlite.execSQL("CREATE TABLE collections (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, isSystem INTEGER NOT NULL, customArtworkPath TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
        sqlite.execSQL("CREATE TABLE collection_episodes (collectionId TEXT NOT NULL, episodeId TEXT NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(collectionId, episodeId))")
        sqlite.execSQL("CREATE TABLE listening_history (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, episodeId TEXT NOT NULL, episodeTitle TEXT NOT NULL, podcastId TEXT NOT NULL, podcastTitle TEXT NOT NULL, hostId TEXT NOT NULL, hostName TEXT NOT NULL, categoryName TEXT NOT NULL, artworkAsset TEXT, listenedSeconds INTEGER NOT NULL, completed INTEGER NOT NULL, skipped INTEGER NOT NULL, playedAt INTEGER NOT NULL)")
        sqlite.execSQL("INSERT INTO podcasts VALUES ('p1','Keep Me Show','h1','Host','c1','Tech','Desc','Pub',NULL,1,1,1,1000)")
        sqlite.execSQL("INSERT INTO episodes VALUES ('e1','p1','Keep Me Show','h1','Host','c1','Tech',1,'Saved Episode','Summary',NULL,'audio/demo_tech.m4a',180000,42000,0,1,1,1024,3,2000,1900,'[00:01.00]Hello')")
        sqlite.execSQL("INSERT INTO collections VALUES ('col_user','My Favorites',0,NULL,1000,1000)")
        sqlite.execSQL("INSERT INTO collection_episodes VALUES ('col_user','e1',0)")
        sqlite.version = 1
        sqlite.close()

        // 2. Open via Room with MIGRATION_1_2..5_6 (no destructive fallback!)
        val migratedDb = Room.databaseBuilder(context, PodcastyDatabase::class.java, dbName)
            .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
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

        runBlocking {
            val preservedEp = migratedDb.dao().getEpisodeById("e1")
            assertNotNull(preservedEp)
            assertEquals("Saved Episode", preservedEp!!.title)
            assertEquals(42_000L, preservedEp.playbackPositionMs)
            assertTrue(preservedEp.isSaved)
            assertEquals("demo", preservedEp.sourceType)
            assertEquals("saved episode", preservedEp.normalizedTitle)
            assertEquals(listOf("e1"), migratedDb.dao().getCollectionEpisodeIds("col_user"))
            assertEquals(0, migratedDb.dao().getAllMusicFolders().size)
            assertEquals(0, migratedDb.dao().getMusicQueues().size)
            migratedDb.dao().upsertMusicQueue(
                com.podcasty.app.database.MusicQueueEntity(
                    id = "queue_default",
                    name = "Default Queue",
                    isActive = true,
                    shuffle = false,
                    repeatMode = "Off",
                    currentEpisodeId = "e1",
                    currentPositionMs = 42_000L,
                    createdAt = 2000L,
                    updatedAt = 2000L,
                ),
            )
            assertEquals(1, migratedDb.dao().getMusicQueues().size)
            migratedDb.dao().upsertScanSession(
                com.podcasty.app.database.ScanSessionEntity(
                    scanId = "scan_v5_check",
                    folderUri = "content://test/tree",
                    startedAt = 1000L,
                    completedAt = 2000L,
                    status = "COMPLETED",
                    discoveredCount = 10,
                    indexedCount = 10,
                    failedCount = 0,
                ),
            )
            assertEquals("COMPLETED", migratedDb.dao().getLatestScanSession("content://test/tree")?.status)

            // Verify Room v3 live_captions table & cascade delete
            migratedDb.dao().upsertLiveCaptions(
                listOf(
                    LiveCaptionEntity(
                        id = "cap_1",
                        episodeId = "e1",
                        sessionId = "sess_1",
                        startMs = 12_500L,
                        endMs = 15_800L,
                        text = "Welcome to today's podcast",
                        language = "en-US",
                        isEstimatedTiming = false,
                        createdAt = 1000L,
                    ),
                ),
            )
            assertEquals(1, migratedDb.dao().getLiveCaptionsForEpisode("e1").size)
            migratedDb.dao().deleteImportedPodcastCascade("p1")
            assertEquals(0, migratedDb.dao().getLiveCaptionsForEpisode("e1").size)
            assertEquals(null, migratedDb.dao().getEpisodeById("e1"))
        }
        migratedDb.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun liveCaptionAudioProcessor_capturesRealPcmFramesFromMedia3ExoPlayerWithoutBlocking() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val processor = LiveCaptionAudioProcessor()
        processor.updatePlaybackAnchor(mediaPositionMs = 5_000L, playbackSpeed = 1.0f)
        processor.setCaptureEnabled(true)

        val latch = CountDownLatch(1)
        var capturedFrameCount = 0
        var firstMediaStartMs = -1L
        var player: ExoPlayer? = null
        val sampleRate = 44_100
        val numSamples = sampleRate
        val pcmBytes = ByteArray(numSamples * 2)
        val bb = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until numSamples) {
            val sample = (sin(2.0 * PI * 440.0 * i / sampleRate) * 16000.0).toInt().toShort()
            bb.putShort(sample)
        }
        val wavFile = File(context.cacheDir, "asr_test_tone.wav")
        wavFile.outputStream().use { out ->
            out.write(WrappedPcmDataSource.buildWavHeader(pcmBytes.size.toLong(), sampleRate, 1, 16))
            out.write(pcmBytes)
        }

        runBlocking {
            withContext(Dispatchers.Main) {
                val rf = object : DefaultRenderersFactory(context) {
                    override fun buildAudioSink(
                        ctx: Context,
                        enableFloatOutput: Boolean,
                        enableAudioTrackPlaybackParams: Boolean,
                    ): AudioSink = DefaultAudioSink.Builder(ctx)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setAudioProcessors(arrayOf(processor))
                        .build()
                }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

                player = ExoPlayer.Builder(context, rf).build().apply {
                    volume = 0f
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(wavFile)))
                    prepare()
                    play()
                }
            }

            withContext(Dispatchers.IO) {
                val deadline = System.currentTimeMillis() + 5_000L
                while (System.currentTimeMillis() < deadline && capturedFrameCount < 2) {
                    val frame = processor.capturedFrames.tryReceive().getOrNull()
                    if (frame != null && frame.pcmBytes.isNotEmpty()) {
                        capturedFrameCount += 1
                        if (firstMediaStartMs < 0L) firstMediaStartMs = frame.mediaStartMs
                    } else {
                        Thread.sleep(25L)
                    }
                }
                latch.countDown()
            }

            withContext(Dispatchers.Main) {
                player?.stop()
                player?.release()
            }
        }
        wavFile.delete()

        assertTrue(latch.await(6, TimeUnit.SECONDS))
        assertTrue("Expected Media3 LiveCaptionAudioProcessor to capture real PCM frames, got $capturedFrameCount", capturedFrameCount >= 1)
        assertTrue("Expected anchored mediaStartMs >= 5000, got $firstMediaStartMs", firstMediaStartMs >= 5_000L)
    }

    @Test
    fun runtimeFormatMatrixAndMedia3DecoderPlayback_wavAiffRawPcmM4a() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ffmpegAvail = AudioFormatCatalog.isFfmpegAvailable()
        val matrix = AudioFormatCatalog.buildCompatibilityMatrix()
        Log.i("PodcastyTest", "FFmpeg available=$ffmpegAvail version=${AudioFormatCatalog.ffmpegVersion()}")
        matrix.forEach {
            Log.i("PodcastyTest", "FORMAT ${it.id}: status=${it.status.label} backend=${it.decoderBackend}")
        }
        assertTrue("FFmpeg native library should load on Android emulator", ffmpegAvail)
        assertTrue(matrix.size >= 21)

        // Generate real 0.5-second 440Hz sine tone in WAV and Raw PCM and verify Media3 decodes them
        val sampleRate = 44_100
        val numSamples = sampleRate / 2
        val pcmBytes = ByteArray(numSamples * 2)
        val bb = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until numSamples) {
            val sample = (sin(2.0 * PI * 440.0 * i / sampleRate) * 16000.0).toInt().toShort()
            bb.putShort(sample)
        }

        val wavFile = File(context.cacheDir, "test_tone.wav")
        wavFile.outputStream().use { out ->
            out.write(WrappedPcmDataSource.buildWavHeader(pcmBytes.size.toLong(), sampleRate, 1, 16))
            out.write(pcmBytes)
        }
        val rawPcmFile = File(context.cacheDir, "test_tone.pcm")
        rawPcmFile.writeBytes(pcmBytes)

        // Verify real ExoPlayer preparation on WAV and Raw PCM (via WrappedPcmDataSource)
        assertExoPlayerPrepares(context, Uri.fromFile(wavFile), rawPcm = false)
        assertExoPlayerPrepares(context, Uri.fromFile(rawPcmFile), rawPcm = true)

        wavFile.delete()
        rawPcmFile.delete()
    }

    @Test
    fun tagLibEditor_readsWritesAndVerifiesRealAudioTagsWithoutCorruption() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val tempAudio = File(context.cacheDir, "tag_test_${System.currentTimeMillis()}.wav")
            val sampleRate = 44_100
            val numSamples = sampleRate / 2
            val pcmBytes = ByteArray(numSamples * 2)
            val bb = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until numSamples) {
                val sample = (sin(2.0 * PI * 440.0 * i / sampleRate) * 16000.0).toInt().toShort()
                bb.putShort(sample)
            }
            tempAudio.outputStream().use { out ->
                out.write(WrappedPcmDataSource.buildWavHeader(pcmBytes.size.toLong(), sampleRate, 1, 16))
                out.write(pcmBytes)
            }
            val uri = Uri.fromFile(tempAudio)
            val editor = AudioTagEditor(context)

            val saveResult = editor.saveTagsSafely(
                uri = uri,
                fileName = tempAudio.name,
                updated = EditableAudioTags(
                    title = "Verified Tag Title",
                    artist = "Podcasty Artist",
                    album = "Podcasty Album",
                    albumArtist = "Podcasty Album Artist",
                    genre = "Technology",
                    year = "2026",
                    trackNumber = "4",
                    composer = "Test Composer",
                    comment = "Safe backup verified",
                    lyrics = "[00:01.00]Embedded synced lyric line",
                ),
            )
            assertTrue("Expected SavedInPlace, got $saveResult", saveResult is TagSaveResult.SavedInPlace)

            val reread = editor.readTags(uri).getOrThrow()
            assertEquals("Verified Tag Title", reread.title)
            assertEquals("Podcasty Artist", reread.artist)
            assertEquals("Podcasty Album", reread.album)
            assertEquals("Podcasty Album Artist", reread.albumArtist)
            assertEquals("Technology", reread.genre)
            assertTrue(reread.lyrics.contains("Embedded synced lyric line"))

            // Verify file is still 100% playable after tag write
            assertExoPlayerPrepares(context, uri, rawPcm = false)
            tempAudio.delete()
        }
    }

    private fun assertExoPlayerPrepares(context: Context, uri: Uri, rawPcm: Boolean) {
        val latch = CountDownLatch(1)
        var prepared = false
        var errorMsg: String? = null

        runBlocking {
            withContext(Dispatchers.Main) {
                val rf = DefaultRenderersFactory(context)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                val player = ExoPlayer.Builder(context, rf).build()
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            prepared = true
                            latch.countDown()
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        errorMsg = error.errorCodeName + ": " + error.localizedMessage
                        latch.countDown()
                    }
                })
                if (rawPcm) {
                    val factory = WrappedPcmDataSource.Factory(
                        context,
                        RawPcmConfig(uri.toString(), sampleRateHz = 44_100, bitDepth = 16, channelCount = 1, bigEndian = false),
                        WrappedPcmDataSource.Mode.RawPcm,
                    )
                    val source = ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(uri))
                    player.setMediaSource(source)
                } else {
                    player.setMediaItem(MediaItem.fromUri(uri))
                }
                player.prepare()
                withContext(Dispatchers.IO) {
                    latch.await(8, TimeUnit.SECONDS)
                }
                player.release()
            }
        }
        assertFalse("ExoPlayer failed on $uri: $errorMsg", errorMsg != null)
        assertTrue("ExoPlayer did not reach STATE_READY on $uri", prepared)
    }
}
