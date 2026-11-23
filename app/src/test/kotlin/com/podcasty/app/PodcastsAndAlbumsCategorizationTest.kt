/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app

import com.podcasty.app.domain.AlbumSortOption
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.MediaContentType
import com.podcasty.app.domain.MediaImportSource
import com.podcasty.app.domain.MediaSourceType
import com.podcasty.app.domain.Podcast
import com.podcasty.app.navigation.AppDestination
import com.podcasty.app.navigation.AppStackPage
import com.podcasty.app.navigation.destination
import com.podcasty.app.navigation.isDetailHeroPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastsAndAlbumsCategorizationTest {

    @Test
    fun `content type inference strictly separates local music from imported podcasts`() {
        assertEquals(MediaContentType.Music, MediaContentType.inferFromSourceType(MediaSourceType.LocalMusic))
        assertEquals(MediaContentType.Podcast, MediaContentType.inferFromSourceType(MediaSourceType.YouTubeReference))
        assertEquals(MediaContentType.Podcast, MediaContentType.inferFromSourceType(MediaSourceType.RssPodcast))
        assertEquals(MediaContentType.Podcast, MediaContentType.inferFromSourceType(MediaSourceType.Demo))

        assertEquals(MediaContentType.Music, MediaContentType.fromKey("MUSIC"))
        assertEquals(MediaContentType.Podcast, MediaContentType.fromKey("PODCAST"))
        assertEquals(MediaContentType.Podcast, MediaContentType.fromKey("podcast"))
        assertEquals(MediaContentType.Music, MediaContentType.fromKey("unknown"))
    }

    @Test
    fun `import source enum covers local library, youtube, rss, direct audio, and google translation`() {
        assertEquals(MediaImportSource.LocalLibrary, MediaImportSource.fromKey("local_library"))
        assertEquals(MediaImportSource.YouTube, MediaImportSource.fromKey("youtube"))
        assertEquals(MediaImportSource.Rss, MediaImportSource.fromKey("rss"))
        assertEquals(MediaImportSource.DirectAudio, MediaImportSource.fromKey("direct_audio"))
        assertEquals(MediaImportSource.GoogleTranslation, MediaImportSource.fromKey("google_translation"))
        assertEquals(MediaImportSource.Seed, MediaImportSource.fromKey("seed"))
    }

    @Test
    fun `episode and podcast helpers never misclassify music as podcast or vice versa`() {
        val musicTrack = Episode(
            id = "local_abc123",
            podcastId = "local_album_1",
            podcastTitle = "Ilaiyaraaja Hits",
            hostId = "local_host_1",
            hostName = "Ilaiyaraaja",
            categoryId = "local_cat_1",
            categoryName = "Tamil",
            episodeNumber = 1,
            title = "Thenpandi Cheemayile",
            summary = "Local FLAC",
            artworkAsset = "/cache/art_1.jpg",
            audioAsset = "01.flac",
            durationMs = 270_000L,
            playbackPositionMs = 0L,
            isPlayed = false,
            isSaved = false,
            isDownloaded = true,
            fileSizeBytes = 32_000_000L,
            playCount = 0,
            publishedAt = 1_700_000_000_000L,
            lastPlayedAt = null,
            transcriptLrc = null,
            sourceType = MediaSourceType.LocalMusic,
            contentType = MediaContentType.Music,
            importSource = MediaImportSource.LocalLibrary.key,
            importedAt = 1_700_000_000_000L,
        )
        val ytPodcast = Episode(
            id = "yt_ep_B30KvHixUmY",
            podcastId = "yt_show_gobinath",
            podcastTitle = "Gobinath",
            hostId = "yt_host_gobinath",
            hostName = "Gobinath",
            categoryId = "cat_youtube_podcasts",
            categoryName = "YouTube Podcasts",
            episodeNumber = 1,
            title = "Life Lessons from Arvind Swamy",
            summary = "YouTube podcast",
            artworkAsset = "/files/thumb.jpg",
            audioAsset = "B30KvHixUmY.m4a",
            durationMs = 5_880_000L,
            playbackPositionMs = 12_000L,
            isPlayed = false,
            isSaved = true,
            isDownloaded = true,
            fileSizeBytes = 93_000_000L,
            playCount = 1,
            publishedAt = 1_710_000_000_000L,
            lastPlayedAt = 1_710_000_100_000L,
            transcriptLrc = null,
            sourceType = MediaSourceType.YouTubeReference,
            contentType = MediaContentType.Podcast,
            importSource = MediaImportSource.YouTube.key,
            importedAt = 1_710_000_000_000L,
        )
        val podcastShow = Podcast(
            id = "yt_show_gobinath",
            title = "Gobinath",
            hostId = "yt_host_gobinath",
            hostName = "Gobinath",
            categoryId = "cat_youtube_podcasts",
            categoryName = "YouTube Podcasts",
            description = "Saved YouTube podcast",
            publisher = "YouTube",
            artworkAsset = "/files/thumb.jpg",
            isSubscribed = true,
            isRecommended = false,
            isPopular = false,
            addedAt = 1_710_000_000_000L,
            sourceType = MediaSourceType.YouTubeReference,
            contentType = MediaContentType.Podcast,
            importSource = MediaImportSource.YouTube.key,
            importedAt = 1_710_000_000_000L,
        )

        assertTrue(musicTrack.isMusicContent)
        assertFalse(musicTrack.isPodcastContent)
        assertTrue(ytPodcast.isPodcastContent)
        assertFalse(ytPodcast.isMusicContent)
        assertEquals(MediaContentType.Podcast, podcastShow.contentType)
    }

    @Test
    fun `library navigation destinations map Albums and AlbumDetails to Library stack`() {
        assertEquals(AppDestination.Library, AppStackPage.LibraryPodcasts.destination)
        assertEquals(AppDestination.Library, AppStackPage.PodcastDetails.destination)
        assertEquals(AppDestination.Library, AppStackPage.LibraryAlbums.destination)
        assertEquals(AppDestination.Library, AppStackPage.AlbumDetails.destination)

        assertTrue(AppStackPage.AlbumDetails.isDetailHeroPage())
        assertTrue(AppStackPage.PodcastDetails.isDetailHeroPage())
        assertFalse(AppStackPage.LibraryAlbums.isDetailHeroPage())
        assertFalse(AppStackPage.LibraryPodcasts.isDetailHeroPage())
        assertEquals(3, AlbumSortOption.entries.size)
    }
}
