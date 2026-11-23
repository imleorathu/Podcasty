package com.podcasty.app

import android.content.Intent
import android.content.ComponentName
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.podcasty.app.player.PodcastPlaybackService
import com.podcasty.app.domain.PodcastRepository
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.kyant.taglib.TagLib
import androidx.room.Room
import com.podcasty.app.database.PodcastyDatabase
import com.podcasty.app.data.PlaylistBackupManager
import com.podcasty.app.data.PlaylistBackup
import com.podcasty.app.data.PlaylistBackupEntry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.podcasty.app.app.MainActivity
import com.podcasty.app.app.PodcastyApplication
import com.podcasty.app.domain.PlaybackState
import com.podcasty.app.domain.SleepTimerPreset
import com.podcasty.app.domain.SleepTimerRequest
import com.podcasty.app.domain.SongSortOption
import com.podcasty.app.domain.SortOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MusicEnhancementDeviceTest {
    @Test fun duplicateComparisonConfirmsBytesWithoutDeletingAnyFile() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PodcastyApplication
        val sample = app.container.repository.observeLocalSongsPaged(limit = 1).first().firstOrNull()
        assumeTrue(sample != null)
        val entity = app.container.database.dao().getEpisodeById(sample!!.id)!!
        val database = Room.inMemoryDatabaseBuilder(app, PodcastyDatabase::class.java).build()
        val dir = File(app.cacheDir, "duplicate-verification").apply { mkdirs() }
        try {
            val files = (0..2).map { File(dir, "$it.mp3").apply { writeBytes(ByteArray(64) { byte -> if (name == "2.mp3") 7 else byte.toByte() }) } }
            val tracks = files.mapIndexed { index, file -> sample.copy(id = "dup_$index", sourceUri = Uri.fromFile(file).toString()) }
            database.dao().insertEpisodes(tracks.map { entity.copy(id = it.id, sourceUri = it.sourceUri, fileSizeBytes = 64) })
            val repository = object : PodcastRepository by app.container.repository {
                override suspend fun getEpisodesByIds(episodeIds: List<String>) = tracks.filter { it.id in episodeIds }
            }
            val result = com.podcasty.app.storage.DuplicateMusicDetector(app, database.dao(), repository).find()
            assertEquals(1, result.groups.size)
            assertEquals(setOf("dup_0", "dup_1"), result.groups.single().map { it.id }.toSet())
            assertTrue(files.all { it.exists() })
            assertEquals(0, result.unreadableFiles)
        } finally { database.close(); dir.deleteRecursively() }
    }

    @Test fun favoritesAndQuickActionOrderPersistWithoutChangingDefaults() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PodcastyApplication
        val preferences = app.container.preferences
        val saved = preferences.settings.first()
        val favorite = com.podcasty.app.data.FavoriteMusicFolder("content://test/folder", "Music/Example", "Example")
        try {
            preferences.setPlayerSecondaryControls(listOf("queue", "lyrics", "queue", "invalid"))
            assertEquals(listOf("queue", "lyrics"), preferences.settings.first().playerSecondaryControls)
            preferences.toggleFavoriteMusicFolder(favorite)
            assertTrue(preferences.settings.first().favoriteMusicFolders.contains(favorite))
            preferences.toggleFavoriteMusicFolder(favorite)
            assertFalse(preferences.settings.first().favoriteMusicFolders.contains(favorite))
        } finally {
            preferences.setPlayerSecondaryControls(saved.playerSecondaryControls)
            if (preferences.settings.first().favoriteMusicFolders.contains(favorite)) preferences.toggleFavoriteMusicFolder(favorite)
        }
    }

    @Test fun replayGainTagsAffectRealNativeOutputWithEqualizerDisabled() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PodcastyApplication
        val container = app.container
        val player = container.playbackController
        val eq = container.equalizerController
        val settings = container.preferences.settings.first()
        val savedQueue = player.queue.value
        val enabled = eq.uiState.value.enabled
        val sample = container.repository.observeLocalSongsPaged("", SongSortOption.Title, SortOrder.Ascending, 1, 0, Long.MAX_VALUE, "mp3").first().firstOrNull()
        assumeTrue("Need an indexed MP3 fixture source", sample != null)
        val entity = container.database.dao().getEpisodeById(sample!!.id)!!
        val file = File(app.cacheDir, "replaygain-verification.mp3")
        val fixtureId = "test_replaygain_verification"
        try {
            app.contentResolver.openInputStream(Uri.parse(sample.sourceUri))!!.use { input -> file.outputStream().use { input.copyTo(it) } }
            val properties = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
                HashMap(TagLib.getMetadata(it.dup().detachFd(), false)!!.propertyMap)
            }
            properties["REPLAYGAIN_TRACK_GAIN"] = arrayOf("-7.0 dB")
            properties["REPLAYGAIN_ALBUM_GAIN"] = arrayOf("-10.0 dB")
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use {
                assertTrue(TagLib.savePropertyMap(it.dup().detachFd(), properties))
            }
            container.database.dao().insertEpisodes(listOf(entity.copy(id = fixtureId, sourceUri = Uri.fromFile(file).toString(), audioAsset = file.name)))
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            eq.setEnabled(false)
            container.preferences.setReplayGainMode(1)
            delay(500)
            withContext(Dispatchers.Main) { player.playEpisode(fixtureId, listOf(fixtureId)) }
            repeat(80) { if (player.playbackState.value !is PlaybackState.Playing) delay(100) }
            assertTrue(player.playbackState.value is PlaybackState.Playing)
            delay(400)
            val trackGain = eq.readHardwareSnapshot().nativeInputGainsDb
            assertTrue("Native output gain must reflect track tag: $trackGain", trackGain.isNotEmpty() && trackGain.all { kotlin.math.abs(it + 7f) < 0.2f })
            container.preferences.setReplayGainMode(2)
            delay(600)
            val albumGain = eq.readHardwareSnapshot().nativeInputGainsDb
            assertTrue("Native output gain must reflect album tag: $albumGain", albumGain.all { kotlin.math.abs(it + 10f) < 0.2f })
            container.preferences.setReplayGainMode(0)
            delay(400)
            assertTrue(eq.readHardwareSnapshot().nativeInputGainsDb.all { kotlin.math.abs(it) < 0.2f })
        } finally {
            withContext(Dispatchers.Main) {
                player.pause()
                savedQueue.currentEpisodeId?.let { player.playEpisode(it, savedQueue.activeEpisodeIds); player.pause() }
            }
            container.preferences.setReplayGainMode(settings.replayGainMode)
            eq.setEnabled(enabled)
            container.database.dao().deleteEpisodesCascade(listOf(fixtureId))
            file.delete()
        }
    }

    @Test fun playlistBackupRejectsInvalidFilesAndMergesWithoutDuplicateEntries() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PodcastyApplication
        val sample = app.container.database.dao().observeLocalSongsByTitleAsc(1).first().firstOrNull()
        assumeTrue(sample != null)
        val track = app.container.database.dao().getEpisodeById(sample!!.id)!!
        val database = Room.inMemoryDatabaseBuilder(app, PodcastyDatabase::class.java).build()
        val file = File(app.cacheDir, "playlist-backup-verification.json")
        try {
            database.dao().insertEpisodes(listOf(track))
            val manager = PlaylistBackupManager(app, database)
            val backup = PlaylistBackup(playlists = listOf(PlaylistBackupEntry("test_backup", "Device backup test", listOf(track.id, track.id, "missing_track"))))
            file.writeText(Json.encodeToString(backup))
            val first = manager.restoreFrom(Uri.fromFile(file))
            assertEquals(1, first.playlists)
            assertEquals(1, first.missingTracks)
            manager.restoreFrom(Uri.fromFile(file))
            assertEquals(listOf(track.id), database.dao().getCollectionEpisodeIds("test_backup"))
            assertEquals(1, database.dao().observeCollections().first().size)
            manager.exportTo(Uri.fromFile(file))
            val exported = Json.decodeFromString<PlaylistBackup>(file.readText())
            assertEquals(listOf(track.id), exported.playlists.single().episodeIds)
            file.writeText(Json.encodeToString(backup.copy(version = 999)))
            assertTrue(runCatching { manager.restoreFrom(Uri.fromFile(file)) }.isFailure)
            assertEquals(listOf(track.id), database.dao().getCollectionEpisodeIds("test_backup"))
        } finally {
            database.close()
            file.delete()
        }
    }

    @Test fun realQueueFadesSleepCancellationAndDatabaseFilters() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PodcastyApplication
        val container = app.container
        val player = container.playbackController
        val preferences = container.preferences
        val savedSettings = preferences.settings.first()
        val savedQueue = player.queue.value
        val candidates = container.repository.observeLocalSongsPaged("", SongSortOption.Title, SortOrder.Ascending, 100).first()
            .filter { it.durationMs > 10_000 && !it.sourceUri.isNullOrBlank() }.take(3)
        assumeTrue("Need three indexed audio files", candidates.size == 3)
        val ids = candidates.map { it.id }
        val mediaFuture = MediaController.Builder(app, SessionToken(app, ComponentName(app, PodcastPlaybackService::class.java))).buildAsync()
        val media = mediaFuture.get(10, java.util.concurrent.TimeUnit.SECONDS)
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            preferences.setPlaybackFadeDurationMs(300)
            preferences.setRemovePlayedFromQueue(false)
            delay(500)
            withContext(Dispatchers.Main) { player.playEpisode(ids[0], ids) }
            repeat(100) {
                if (player.playbackState.value !is PlaybackState.Playing) delay(100)
            }
            assertTrue("Real audio must start", player.playbackState.value is PlaybackState.Playing)
            withContext(Dispatchers.Main) {
                player.reorderQueue(listOf(ids[0], ids[0]))
                assertEquals(ids, player.queue.value.activeEpisodeIds)
                player.reorderQueue(ids.reversed())
                assertEquals(ids[0], player.queue.value.currentEpisodeId)
                player.addToQueue(ids)
                assertEquals(3, player.queue.value.activeEpisodeIds.size)
                player.pause()
            }
            delay(100)
            val fadingVolume = withContext(Dispatchers.Main) { media.volume }
            assertTrue("Pause must attenuate actual player volume: $fadingVolume", fadingVolume in 0.01f..0.99f)
            withContext(Dispatchers.Main) { player.resume() }
            delay(450)
            assertTrue("Cancelled pause fade must not pause resumed playback", player.playbackState.value is PlaybackState.Playing)
            assertEquals(1f, withContext(Dispatchers.Main) { media.volume }, 0.01f)
            withContext(Dispatchers.Main) {
                player.configureSleepTimer(SleepTimerRequest(SleepTimerPreset.ClockTime, fadeOutSeconds = 1, deadlineEpochMs = System.currentTimeMillis() + 1500))
            }
            delay(600)
            assertTrue(player.sleepTimer.value.isActive)
            assertTrue(preferences.settings.first().activeSleepTimer.isNotBlank())
            withContext(Dispatchers.Main) { player.setSleepTimer(SleepTimerPreset.Off) }
            delay(1600)
            assertTrue("Cancelled timer must not stop real playback", player.playbackState.value is PlaybackState.Playing)
            withContext(Dispatchers.Main) {
                player.configureSleepTimer(SleepTimerRequest(SleepTimerPreset.ClockTime, fadeOutSeconds = 1, deadlineEpochMs = System.currentTimeMillis() + 1500))
            }
            delay(2100)
            assertTrue("Deadline must pause playback", player.playbackState.value is PlaybackState.Paused)
            assertFalse(player.sleepTimer.value.isActive)
            assertEquals("", preferences.settings.first().activeSleepTimer)
            withContext(Dispatchers.Main) { player.removeFromQueue(ids[0]) }
            delay(700)
            assertFalse(player.queue.value.activeEpisodeIds.contains(ids[0]))
            assertEquals(ids[1], player.queue.value.currentEpisodeId)
            assertTrue(player.playbackState.value is PlaybackState.Paused)

            val beforeClear = player.queue.value
            withContext(Dispatchers.Main) { player.clearQueue() }
            delay(400)
            assertTrue(player.queue.value.activeEpisodeIds.isEmpty())
            withContext(Dispatchers.Main) { player.undoClearQueue() }
            delay(800)
            assertEquals(beforeClear.activeEpisodeIds, player.queue.value.activeEpisodeIds)
            assertEquals(beforeClear.currentEpisodeId, player.queue.value.currentEpisodeId)
            assertTrue(player.playbackState.value is PlaybackState.Paused)

            // End-of-track timers must stop before repeating or exposing the preloaded next track.
            withContext(Dispatchers.Main) {
                player.playEpisode(ids[0], ids)
                player.setRepeatMode(com.podcasty.app.domain.RepeatMode.One)
                player.configureSleepTimer(SleepTimerRequest(SleepTimerPreset.EndOfEpisode, fadeOutSeconds = 10))
            }
            delay(900)
            withContext(Dispatchers.Main) { player.seekTo((media.duration - 700L).coerceAtLeast(0L)) }
            delay(350)
            assertTrue("Track timer must attenuate real output", withContext(Dispatchers.Main) { media.volume } < 0.5f)
            delay(1700)
            assertTrue("End timer must stop repeat-one playback", player.playbackState.value is PlaybackState.Paused)
            assertEquals(ids[0], player.queue.value.currentEpisodeId)
            assertFalse(player.sleepTimer.value.isActive)

            container.preferences.setRemovePlayedFromQueue(true)
            delay(250)
            withContext(Dispatchers.Main) {
                player.setRepeatMode(com.podcasty.app.domain.RepeatMode.Off)
                player.playEpisode(ids[0], ids)
            }
            delay(800)
            withContext(Dispatchers.Main) { player.seekTo((media.duration - 500L).coerceAtLeast(0L)) }
            delay(1600)
            assertFalse("Completed track must be removed", player.queue.value.activeEpisodeIds.contains(ids[0]))
            assertEquals(ids[1], player.queue.value.currentEpisodeId)
            assertTrue(player.playbackState.value is PlaybackState.Playing)

            val short = container.repository.observeLocalSongsPaged("", SongSortOption.Duration, SortOrder.Ascending, 100, 0, 299_999, "").first()
            assertTrue(short.all { it.durationMs < 300_000 })
            assertEquals(short.map { it.durationMs }.sorted(), short.map { it.durationMs })
            val mp3 = container.repository.observeLocalSongsPaged("", SongSortOption.Title, SortOrder.Ascending, 100, 0, Long.MAX_VALUE, "mp3").first()
            assertTrue(mp3.all { it.containerFormat.equals("mp3", true) })
            val favorites = container.repository.observeSmartMusicCategory("favorites").first()
            assertTrue(favorites.all { it.isSaved })
            val never = container.repository.observeSmartMusicCategory("never").first()
            assertTrue(never.all { it.playCount == 0 && it.lastPlayedAt == null })
            val longest = container.repository.observeSmartMusicCategory("longest").first()
            assertTrue(longest.size <= 16)
            assertEquals(longest.map { it.durationMs }.sortedDescending(), longest.map { it.durationMs })
        } finally {
            withContext(Dispatchers.Main) {
                player.setSleepTimer(SleepTimerPreset.Off)
                player.pause()
                if (savedQueue.activeEpisodeIds.isNotEmpty() && savedQueue.currentEpisodeId != null) {
                    player.playEpisode(savedQueue.currentEpisodeId!!, savedQueue.activeEpisodeIds)
                    player.pause()
                }
            }
            preferences.setPlaybackFadeDurationMs(savedSettings.playbackFadeDurationMs)
            preferences.setRemovePlayedFromQueue(savedSettings.removePlayedFromQueue)
            withContext(Dispatchers.Main) { media.release() }
        }
    }
}
