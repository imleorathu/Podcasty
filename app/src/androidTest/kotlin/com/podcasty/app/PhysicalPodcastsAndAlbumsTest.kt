/*
 * Podcasty — Physical Device Verification for Podcasts & Albums Categories + Room v8->v9 Migration
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.podcasty.app.app.PodcastyApplication
import com.podcasty.app.domain.AlbumSortOption
import com.podcasty.app.domain.MediaContentType
import com.podcasty.app.domain.MediaImportSource
import com.podcasty.app.domain.MediaSourceType
import com.podcasty.app.domain.PlaybackState
import com.podcasty.app.domain.SortOrder
import com.podcasty.app.domain.itemOrNull
import com.podcasty.app.podcast.YouTubeVideoMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhysicalPodcastsAndAlbumsTest {

    private val tag = "PodcastsAlbumsTest"

    private fun app(): PodcastyApplication =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PodcastyApplication

    @Test
    fun test01_nonDestructiveMigrationPreservedLocalMusicAndImportedPodcasts(): Unit = runBlocking {
        val container = app().container
        val repo = container.repository

        val localMusicCount = repo.observeLocalMusicCount().first()
        val albumCount = repo.observeMusicAlbumCount().first()
        val podcastCount = repo.observePodcastEpisodeCount().first()
        val podcastShows = repo.observePodcastShows().first()
        val podcastEpisodes = repo.observePodcastEpisodes(limit = 50).first()
        val albums = repo.observeLocalAlbumsSorted("", AlbumSortOption.Title, SortOrder.Ascending, 200).first()

        Log.i(
            tag,
            "MIGRATION_CHECK localMusic=$localMusicCount albumCount=$albumCount podcastCount=$podcastCount " +
                "shows=${podcastShows.size} podcastEps=${podcastEpisodes.size} albumsSample=${albums.size}",
        )
        podcastEpisodes.forEach { ep ->
            Log.i(
                tag,
                "PODCAST_ROW id=${ep.id} title=${ep.title} show=${ep.podcastTitle} contentType=${ep.contentType} " +
                    "sourceType=${ep.sourceType} importSource=${ep.importSource} downloaded=${ep.isDownloaded} linked=${ep.linkedAudioUri}",
            )
        }

        // All existing local music files must be preserved and classified as MUSIC
        assertTrue("Expected existing local music preserved (>1000), got $localMusicCount", localMusicCount > 1000)
        assertTrue("Expected local music albums preserved (>50), got $albumCount", albumCount > 50)
        assertTrue("Expected albums query non-empty", albums.isNotEmpty())

        // Existing imported podcast(s) must be preserved and classified as PODCAST
        assertTrue("Expected at least 1 preserved podcast episode", podcastCount >= 1)
        assertTrue("Expected observePodcastEpisodes non-empty", podcastEpisodes.isNotEmpty())
        assertTrue("Expected observePodcastShows non-empty", podcastShows.isNotEmpty())

        // Strict separation checks: ZERO overlap between Podcasts and Albums
        assertTrue(
            "No local_music episode may appear in observePodcastEpisodes",
            podcastEpisodes.none { it.sourceType == MediaSourceType.LocalMusic || it.contentType != MediaContentType.Podcast },
        )
        assertTrue(
            "No local_music show may appear in observePodcastShows",
            podcastShows.none { it.sourceType == MediaSourceType.LocalMusic || it.contentType != MediaContentType.Podcast },
        )
        val podcastShowIds = podcastShows.mapTo(HashSet()) { it.id }
        assertTrue(
            "No podcast show may appear in Library Albums",
            albums.none { it.albumId in podcastShowIds || it.albumId.startsWith("yt_show_") || it.albumId.startsWith("rss_show_") },
        )

        // Home recent music must strictly exclude podcasts
        val homeRecentMusic = repo.observeRecentMusicEpisodes(limit = 16).first()
        assertTrue("Expected home recent music non-empty", homeRecentMusic.isNotEmpty())
        assertTrue(
            "Home recent music must contain only MUSIC content",
            homeRecentMusic.all { it.contentType == MediaContentType.Music && it.sourceType == MediaSourceType.LocalMusic },
        )
    }

    @Test
    fun test02_newPodcastImportAutoUpdatesAndPreventsDuplicatesThenCleansUp(): Unit = runBlocking {
        val repo = app().container.repository
        val testVideoId = "testPodCat01"
        val testChannel = "Test Podcast Channel Verification"
        val testTitle = "Test Imported Episode Verification"
        val meta = YouTubeVideoMetadata(
            videoId = testVideoId,
            title = testTitle,
            channelName = testChannel,
            description = "Physical device categorization verification episode.",
            durationMs = 600_000L,
            thumbnailUrl = null,
            canonicalUrl = "https://www.youtube.com/watch?v=$testVideoId",
            isShorts = false,
        )

        var importedPodcastId: String? = null
        try {
            val (podId1, epId1) = repo.saveYouTubeVideoImport(
                metadata = meta,
                preferredCaptionLanguage = "en-US",
                isDownloadedFile = false,
            )
            importedPodcastId = podId1

            // Re-import the exact same video — must upsert cleanly with ZERO duplicate rows
            val (podId2, epId2) = repo.saveYouTubeVideoImport(
                metadata = meta,
                preferredCaptionLanguage = "en-US",
                isDownloadedFile = false,
            )
            assertEquals(podId1, podId2)
            assertEquals(epId1, epId2)

            val podcastEpisodes = repo.observePodcastEpisodes(limit = 50).first()
            val matching = podcastEpisodes.filter { it.id == epId1 }
            assertEquals("Re-importing same YouTube video must not create duplicate entries", 1, matching.size)
            assertEquals(MediaContentType.Podcast, matching.first().contentType)
            assertEquals(MediaImportSource.YouTube.key, matching.first().importSource)
            assertEquals("Newest imported podcast should be first in Home Podcasts", epId1, podcastEpisodes.first().id)

            // Confirm it does NOT appear in Albums
            val albumsSearch = repo.observeLocalAlbumsSorted(testChannel, AlbumSortOption.Title, SortOrder.Ascending, 50).first()
            assertTrue("Imported podcast must NEVER appear in Albums search", albumsSearch.isEmpty())
            Log.i(tag, "IMPORT_DEDUP_CHECK passed epId=$epId1 podId=$podId1")
        } finally {
            importedPodcastId?.let { repo.deleteImportedPodcast(it) }
        }
    }

    @Test
    fun test03_albumsSortingSearchAndSharedPlaybackSwitchingBetweenPodcastAndAlbum(): Unit = runBlocking {
        val container = app().container
        val repo = container.repository
        val playback = container.playbackController

        // 1. Verify Albums sorting (Title, Artist, TrackCount) + AlbumDetails track loading
        val albumsByTitle = repo.observeLocalAlbumsSorted("", AlbumSortOption.Title, SortOrder.Ascending, 120).first()
        val albumsByArtist = repo.observeLocalAlbumsSorted("", AlbumSortOption.Artist, SortOrder.Ascending, 120).first()
        val albumsByTracks = repo.observeLocalAlbumsSorted("", AlbumSortOption.TrackCount, SortOrder.Descending, 120).first()
        assertTrue("Expected albums by title", albumsByTitle.isNotEmpty())
        assertTrue("Expected albums by artist", albumsByArtist.isNotEmpty())
        assertTrue("Expected albums by track count", albumsByTracks.isNotEmpty())
        assertTrue(
            "Top album by track count desc should have >= last album track count",
            albumsByTracks.first().trackCount >= albumsByTracks.last().trackCount,
        )

        val chosenAlbum = albumsByTracks.firstOrNull { !it.albumTitle.equals("Local Audio", ignoreCase = true) && it.albumTitle.length >= 4 }
            ?: albumsByTracks.first()
        val albumDetails = repo.observeMusicAlbumById(chosenAlbum.albumId).first()
        val albumTracks = repo.observeSongsForAlbum(chosenAlbum.albumId).first()
        assertNotNull("Expected observeMusicAlbumById to resolve album", albumDetails)
        assertTrue("Expected album tracks non-empty", albumTracks.isNotEmpty())
        assertTrue(
            "Every track in AlbumDetails must be MUSIC content",
            albumTracks.all { it.contentType == MediaContentType.Music },
        )

        // 2. Test search in Albums using the chosen album's full title
        val searched = repo.observeLocalAlbumsSorted(chosenAlbum.albumTitle, AlbumSortOption.TrackCount, SortOrder.Descending, 50).first()
        assertTrue(
            "Expected Albums search for '${chosenAlbum.albumTitle}' to include chosen album",
            searched.any { it.albumId == chosenAlbum.albumId },
        )

        // 3. Test playback switching: Play real imported Podcast -> Switch to Album track -> Pause cleanly
        val podcastEp = repo.observePodcastEpisodes(limit = 20).first().firstOrNull()
        assertNotNull("Expected at least 1 imported podcast episode for playback switching test", podcastEp)
        val albumTrack = albumTracks.first()

        playback.playEpisode(podcastEp!!.id, listOf(podcastEp.id), false)
        var reachedPodcast = false
        for (i in 0 until 50) {
            delay(200)
            val st = playback.playbackState.value
            val currentId = st.itemOrNull()?.episodeId
            if (currentId == podcastEp.id && (st is PlaybackState.Playing || st is PlaybackState.Paused)) {
                reachedPodcast = true
                break
            }
        }
        assertTrue("Shared Media3 player should load & play imported podcast (${podcastEp.id})", reachedPodcast)

        // Switch immediately to Album music track on the exact same Media3 engine
        playback.playEpisode(albumTrack.id, albumTracks.map { it.id }, false)
        var reachedAlbumTrack = false
        for (i in 0 until 50) {
            delay(200)
            val st = playback.playbackState.value
            val currentId = st.itemOrNull()?.episodeId
            if (currentId == albumTrack.id && (st is PlaybackState.Playing || st is PlaybackState.Paused)) {
                reachedAlbumTrack = true
                break
            }
        }
        assertTrue("Shared Media3 player should seamlessly switch to Album track (${albumTrack.title})", reachedAlbumTrack)

        // Leave device quiet after test (MediaController calls must run on Main thread)
        withContext(Dispatchers.Main) {
            if (playback.playbackState.value is PlaybackState.Playing) {
                playback.togglePlayPause()
            }
        }
        delay(300)
        assertFalse("Playback paused cleanly", playback.playbackState.value is PlaybackState.Playing)
        Log.i(
            tag,
            "PLAYBACK_SWITCH_CHECK passed podcast=${podcastEp.id} (${podcastEp.title}) -> album=${chosenAlbum.albumTitle} (${chosenAlbum.trackCount} tracks) track=${albumTrack.title}",
        )
    }
}
