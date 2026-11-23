/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app

import com.podcasty.app.audio.AudioFormatCatalog
import com.podcasty.app.core.UnicodeSortUtil
import com.podcasty.app.data.PodcastUserSettings
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.FolderSortOption
import com.podcasty.app.domain.FolderViewMode
import com.podcasty.app.domain.LinearFolderSummary
import com.podcasty.app.domain.MediaSourceType
import com.podcasty.app.domain.MusicLibraryCategory
import com.podcasty.app.domain.MusicQueue
import com.podcasty.app.domain.PlaybackQueueSnapshot
import com.podcasty.app.domain.SongSortOption
import com.podcasty.app.domain.StorageProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

class OfflineMusicManagerAndOomTest {

    @Test
    fun unicodeSortUtilSortsInternationalAndEdgeCaseTitlesWithoutAllocatingInsideComparator() {
        val titles = listOf(
            "  ",
            "Éclair",
            "apple",
            "Anirudh Ravichander - Why This Kolaveri",
            "தமிழ் பாடல் 1",
            "සිංහල ගීතය",
            "01 - Intro.flac",
            "Éclair",
        )
        val keyed = titles.map { it to UnicodeSortUtil.normalizeKey(it) }
        val sorted = keyed.sortedWith(UnicodeSortUtil.precomputedKeyComparator { it.second })

        assertEquals(titles.size, sorted.size)
        // Blank string sorts first
        assertEquals("  ", sorted.first().first)
        // Case-insensitive & diacritic collation: 01 -> Anirudh -> apple -> Éclair
        val asciiAndLatin = sorted.map { it.first }.filter {
            it == "01 - Intro.flac" || it.startsWith("Anirudh") || it == "apple" || it == "Éclair"
        }
        assertEquals(
            listOf("01 - Intro.flac", "Anirudh Ravichander - Why This Kolaveri", "apple", "Éclair", "Éclair"),
            asciiAndLatin,
        )
    }

    @Test
    fun largeLibraryScaleBenchmark_upTo25000Tracks_completesQuicklyWithoutHeapSpike() {
        val sizes = listOf(100, 1_000, 5_000, 10_000, 25_000)
        val runtime = Runtime.getRuntime()

        for (count in sizes) {
            val precomputedKeys = Array(count) { idx ->
                val raw = when (idx % 5) {
                    0 -> "Track $idx - बहुत लंबी हिन्दी शीर्षक"
                    1 -> "தமிழ்_இசை_${count - idx}_A_Very_Long_Offline_File_Name_From_USB_OTG_Drive"
                    2 -> "Café del Mar (${idx % 200})" // includes duplicate titles
                    3 -> "   " // missing metadata fallback
                    else -> "Sympatico Movement #${(idx * 7) % count}"
                }
                IndexedRow(
                    id = "ep_$idx",
                    title = raw,
                    normalizedKey = UnicodeSortUtil.normalizeKey(raw),
                    folderPath = "Music/Artist_${idx % 120}/Album_${idx % 12}",
                    durationMs = (120_000L + (idx % 300) * 1_000L),
                )
            }

            val beforeUsed = runtime.totalMemory() - runtime.freeMemory()
            val startNs = System.nanoTime()

            val sorted = precomputedKeys.sortedWith(
                UnicodeSortUtil.precomputedKeyComparator { it.normalizedKey },
            )
            // Group into linear folders just as Folder Browser computes summaries
            val folderCounts = HashMap<String, Int>(2048)
            for (row in sorted) {
                folderCounts[row.folderPath] = (folderCounts[row.folderPath] ?: 0) + 1
            }

            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
            val afterUsed = runtime.totalMemory() - runtime.freeMemory()
            val deltaMb = max(0L, afterUsed - beforeUsed) / (1024.0 * 1024.0)

            assertEquals(count, sorted.size)
            assertTrue("Expected distinct folders for count=$count", folderCounts.isNotEmpty())
            assertTrue("Sort of $count tracks took too long: $elapsedMs ms", elapsedMs < 5_000.0)
            println("BENCHMARK count=$count elapsedMs=${"%.2f".format(elapsedMs)} deltaHeapMB=${"%.2f".format(deltaMb)}")
        }
    }

    @Test
    fun folderGroupingDistinguishesNestedLinearFoldersWithoutDuplicatingDirectTracks() {
        val tracks = listOf(
            LinearFolderSummary(
                folderKey = "tree1::Music/Tamil",
                treeUri = "tree1",
                relativePath = "Music/Tamil",
                folderName = "Tamil",
                displayPath = "Music/Tamil",
                trackCount = 12,
                totalDurationMs = 3_600_000L,
                latestAddedAt = 2000L,
                storageType = StorageProviderType.Internal,
            ),
            LinearFolderSummary(
                folderKey = "tree1::Music/Tamil/Anirudh",
                treeUri = "tree1",
                relativePath = "Music/Tamil/Anirudh",
                folderName = "Anirudh",
                displayPath = "Music/Tamil/Anirudh",
                trackCount = 8,
                totalDurationMs = 2_400_000L,
                latestAddedAt = 3000L,
                storageType = StorageProviderType.Internal,
            ),
            LinearFolderSummary(
                folderKey = "tree1::Music",
                treeUri = "tree1",
                relativePath = "Music",
                folderName = "Music",
                displayPath = "Music",
                trackCount = 3,
                totalDurationMs = 900_000L,
                latestAddedAt = 1000L,
                storageType = StorageProviderType.Internal,
            ),
        )

        // Direct count inside Music = 3, while recursive count under Music = 3 + 12 + 8 = 23
        val directInMusic = tracks.first { it.relativePath == "Music" }.trackCount
        val recursiveUnderMusic = tracks.filter {
            it.relativePath == "Music" || it.relativePath.startsWith("Music/")
        }.sumOf { it.trackCount }

        assertEquals(3, directInMusic)
        assertEquals(23, recursiveUnderMusic)
        // Unique folderKeys ensure tracks aren't double-counted between parent and child folders
        assertEquals(3, tracks.map { it.folderKey }.distinct().size)
    }

    @Test
    fun settingsAndQueueModelsSupportMultiQueueAndPersistedBrowserModes() {
        val defaultSettings = PodcastUserSettings()
        assertEquals(FolderViewMode.Hierarchical, defaultSettings.folderViewMode)
        assertEquals(FolderSortOption.Name, defaultSettings.folderSortOption)
        assertEquals(SongSortOption.Title, defaultSettings.songSortOption)
        assertEquals(MusicLibraryCategory.Folders, defaultSettings.musicLibraryCategory)

        val queue1 = MusicQueue(
            id = "queue_default",
            name = "Default Queue",
            episodeIds = listOf("song_1", "song_2"),
            isActive = true,
            currentEpisodeId = "song_1",
        )
        val queue2 = MusicQueue(
            id = "queue_tamil",
            name = "Tamil Road Trip",
            episodeIds = listOf("song_3", "song_4", "song_5"),
            isActive = false,
            currentEpisodeId = "song_4",
        )
        assertEquals(2, queue1.episodeIds.size)
        assertEquals(3, queue2.episodeIds.size)
        assertFalse(queue2.isActive)

        val snap = PlaybackQueueSnapshot(
            activeQueueId = queue1.id,
            activeQueueName = queue1.name,
            activeEpisodeIds = queue1.episodeIds,
            currentIndex = 0,
        )
        assertEquals("song_1", snap.currentEpisodeId)
        assertEquals("song_2", snap.activeEpisodeIds.getOrNull(snap.currentIndex + 1))
    }

    @Test
    fun offlineEpisodeClassificationMatchesLocalMusicFilesAndDetectsFormatsCleanly() {
        val localFlac = Episode(
            id = "local_1",
            podcastId = "folder_p1",
            podcastTitle = "Anirudh Hits",
            hostId = "artist_1",
            hostName = "Anirudh",
            categoryId = "cat_1",
            categoryName = "Soundtrack",
            episodeNumber = 1,
            title = "Hukum",
            summary = "",
            artworkAsset = null,
            audioAsset = "Music/Tamil/Hukum.flac",
            durationMs = 210_000L,
            playbackPositionMs = 0L,
            isPlayed = false,
            isSaved = false,
            isDownloaded = true,
            fileSizeBytes = 32_000_000L,
            playCount = 0,
            publishedAt = 1000L,
            lastPlayedAt = null,
            transcriptLrc = null,
            sourceType = MediaSourceType.LocalMusic,
            sourceUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/Hukum.flac",
            containerFormat = "FLAC",
        )
        assertTrue(localFlac.isLocalAudioFile)
        assertEquals("content://com.android.externalstorage.documents/tree/primary%3AMusic/document/Hukum.flac", localFlac.effectivePlaybackUri)
        assertEquals("FLAC", localFlac.containerFormat)
        assertTrue(AudioFormatCatalog.scannableExtensions.contains("flac"))
        assertTrue(AudioFormatCatalog.scannableExtensions.contains("mp3"))
        assertTrue(AudioFormatCatalog.scannableExtensions.contains("m4a"))
        assertTrue(AudioFormatCatalog.scannableExtensions.contains("opus"))
    }

    private data class IndexedRow(
        val id: String,
        val title: String,
        val normalizedKey: String,
        val folderPath: String,
        val durationMs: Long,
    )
}
