/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.podcasty.app.app.MainActivity
import com.podcasty.app.app.PodcastyApplication
import com.podcasty.app.database.EpisodeEntity
import com.podcasty.app.database.MusicFolderEntity
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.MediaSourceType
import com.podcasty.app.storage.FileOperationResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PodcastyFullAppE2ETest {

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(
        object : TestWatcher() {
            override fun starting(description: Description) {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val pkg = instrumentation.targetContext.packageName
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    runCatching {
                        instrumentation.uiAutomation.grantRuntimePermission(pkg, Manifest.permission.POST_NOTIFICATIONS)
                    }
                    runCatching {
                        instrumentation.uiAutomation.grantRuntimePermission(pkg, Manifest.permission.READ_MEDIA_AUDIO)
                    }
                }
            }
        },
    ).around(composeRule)

    @Test
    fun fullAppNavigation_foldersCategories_librarySubpages_settingsAndPersistence() {
        composeRule.waitForIdle()

        // 1. Navigate across all 5 primary bottom destinations
        val mainTabs = listOf("Folders", "Insight", "Library", "Settings", "Home")
        for (tab in mainTabs) {
            composeRule.onAllNodesWithContentDescription(tab).onFirst().performClick()
            composeRule.waitForIdle()
        }

        // 2. Folders tab: verify single-row category bar (Folders | Albums | Artists | Songs | Playlists)
        composeRule.onAllNodesWithContentDescription("Folders").onFirst().performClick()
        composeRule.waitForIdle()
        for (cat in listOf("Albums", "Artists", "Songs", "Playlists", "Folders")) {
            composeRule.onAllNodesWithText(cat).onFirst().performClick()
            composeRule.waitForIdle()
        }

        // 3. Library tab: visit Search, Saved episodes, Downloads, Listening history, Collections, Podcasts
        composeRule.onAllNodesWithContentDescription("Library").onFirst().performClick()
        composeRule.waitForIdle()
        val libraryItems = listOf(
            "Search",
            "Podcasts",
            "Collections",
            "Saved episodes",
            "Downloads",
            "Listening history",
        )
        for (item in libraryItems) {
            composeRule.onAllNodesWithText(item).onFirst().performClick()
            composeRule.waitForIdle()
            Espresso.pressBack()
            composeRule.waitForIdle()
        }

        // 4. Settings tab: visit every settings page and verify UI responsiveness
        composeRule.onAllNodesWithContentDescription("Settings").onFirst().performClick()
        composeRule.waitForIdle()
        val settingsPages = listOf(
            "Appearance",
            "Music library",
            "Lyrics & captions",
            "Playback",
            "Bluetooth",
            "Downloads & storage",
            "About",
        )
        for (page in settingsPages) {
            composeRule.onAllNodesWithText(page).onFirst().performClick()
            composeRule.waitForIdle()
            Espresso.pressBack()
            composeRule.waitForIdle()
        }

        // 5. Verify a real Setting toggle persists via DataStore
        val app = ApplicationProvider.getApplicationContext<Context>() as PodcastyApplication
        val beforeBadge = runBlocking { app.container.preferences.settings.first().showSpeedBadge }
        composeRule.onAllNodesWithText("Playback").onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Show playback speed badge").onFirst().performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        val afterBadge = runBlocking { app.container.preferences.settings.first().showSpeedBadge }
        assertEquals(!beforeBadge, afterBadge)
        // Restore original setting
        composeRule.onAllNodesWithText("Show playback speed badge").onFirst().performClick()
        composeRule.waitForIdle()
        Espresso.pressBack()
        composeRule.waitForIdle()
    }

    @Test
    fun storageEngine2_onDeviceRenameFile_and_oneTimeIndexingFlag() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>() as PodcastyApplication
        val container = app.container
        val dao = container.database.dao()

        // Verify one-time indexing flag behavior on MusicFolderEntity & repeated syncOnAppLaunch is a no-op
        val folderUri = "content://com.podcasty.test/tree/e2e_music"
        dao.upsertMusicFolder(
            MusicFolderEntity(
                treeUri = folderUri,
                displayName = "E2E Music",
                storageType = "internal",
                isDefault = false,
                includeSubfolders = true,
                lastScannedAt = 1000L,
                trackCount = 1,
                isAvailable = true,
                isEnabled = true,
                initialIndexingCompleted = true,
                lastSyncAt = 1000L,
                lastMediaStoreGeneration = Long.MAX_VALUE,
            ),
        )
        val folders = dao.getAllMusicFolders()
        assertTrue(folders.any { it.treeUri == folderUri && it.initialIndexingCompleted && it.isEnabled })
        // Test 3: Reopen / syncOnAppLaunch 5 times -> no full indexing occurs
        repeat(5) {
            container.scanCoordinator.syncOnAppLaunch(autoLibraryRefresh = true, includeSubfolders = true)
            assertFalse(container.localMusicScanner.scanState.value.isScanning)
        }

        // Test real file:// rename via MusicFileManager + Room sync
        val testDir = File(app.filesDir, "e2e_audio_test").apply { mkdirs() }
        val originalFile = File(testDir, "original_track.wav").apply {
            writeBytes(ByteArray(1024) { 0x42 })
        }
        val epId = "e2e_ep_1"
        dao.insertEpisodes(
            listOf(
                EpisodeEntity(
                    id = epId,
                    podcastId = "e2e_pod",
                    podcastTitle = "E2E Album",
                    hostId = "e2e_host",
                    hostName = "E2E Artist",
                    categoryId = "e2e_cat",
                    categoryName = "Music",
                    episodeNumber = 1,
                    title = "original_track",
                    summary = "",
                    artworkAsset = null,
                    audioAsset = "original_track.wav",
                    durationMs = 10_000L,
                    playbackPositionMs = 0L,
                    isPlayed = false,
                    isSaved = false,
                    isDownloaded = true,
                    fileSizeBytes = 1024L,
                    playCount = 0,
                    publishedAt = 1000L,
                    lastPlayedAt = null,
                    transcriptLrc = null,
                    sourceType = MediaSourceType.LocalMusic.key,
                    sourceUri = Uri.fromFile(originalFile).toString(),
                    folderUri = folderUri,
                ),
            ),
        )

        val ep = Episode(
            id = epId,
            podcastId = "e2e_pod",
            podcastTitle = "E2E Album",
            hostId = "e2e_host",
            hostName = "E2E Artist",
            categoryId = "e2e_cat",
            categoryName = "Music",
            episodeNumber = 1,
            title = "original_track",
            summary = "",
            artworkAsset = null,
            audioAsset = "original_track.wav",
            durationMs = 10_000L,
            playbackPositionMs = 0L,
            isPlayed = false,
            isSaved = false,
            isDownloaded = true,
            fileSizeBytes = 1024L,
            playCount = 0,
            publishedAt = 1000L,
            lastPlayedAt = null,
            transcriptLrc = null,
            sourceType = MediaSourceType.LocalMusic,
            sourceUri = Uri.fromFile(originalFile).toString(),
            folderUri = folderUri,
        )

        val renameResult = container.musicFileManager.renameFile(ep, "renamed_track")
        assertTrue("Expected rename success, got $renameResult", renameResult is FileOperationResult.Success)
        val renamedFile = File(testDir, "renamed_track.wav")
        assertTrue(renamedFile.exists())
        assertFalse(originalFile.exists())
        assertEquals("renamed_track", dao.getEpisodeById(epId)?.title)

        // Cleanup test artifacts
        dao.deleteEpisodesCascade(listOf(epId))
        dao.deleteMusicFolder(folderUri)
        testDir.deleteRecursively()
        assertFalse(dao.getAllMusicFolders().any { it.treeUri == folderUri })
    }

    @Test
    fun roomBoundedObservationStress_2000EpisodesNeverMaterializesFullTable() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>() as PodcastyApplication
        val dao = app.container.database.dao()
        val repo = app.container.repository
        val stressFolder = "content://com.podcasty.stress/tree/oom_guard"
        val total = 2_000
        val batch = ArrayList<EpisodeEntity>(100)
        val insertedIds = ArrayList<String>(total)

        try {
            for (i in 0 until total) {
                val id = "stress_ep_$i"
                insertedIds += id
                batch += EpisodeEntity(
                    id = id,
                    podcastId = "stress_alb_${i % 40}",
                    podcastTitle = "Stress Album ${i % 40}",
                    hostId = "stress_art_${i % 20}",
                    hostName = "Stress Artist ${i % 20}",
                    categoryId = "stress_cat",
                    categoryName = "Music",
                    episodeNumber = i + 1,
                    title = "Stress Track $i",
                    summary = "",
                    artworkAsset = null,
                    audioAsset = "Stress/Track_$i.mp3",
                    durationMs = 180_000L,
                    playbackPositionMs = 0L,
                    isPlayed = false,
                    isSaved = false,
                    isDownloaded = true,
                    fileSizeBytes = 4_000_000L,
                    playCount = 0,
                    publishedAt = 1000L + i,
                    lastPlayedAt = null,
                    transcriptLrc = "[00:01.00]Large lyric line repeated for memory stress test $i",
                    sourceType = MediaSourceType.LocalMusic.key,
                    sourceUri = "content://com.podcasty.stress/doc/$i",
                    folderUri = stressFolder,
                    relativeFolderPath = "Stress",
                    normalizedTitle = "stress track $i",
                    normalizedArtist = "stress artist ${i % 20}",
                    normalizedAlbum = "stress album ${i % 40}",
                )
                if (batch.size >= 100) {
                    dao.insertEpisodes(batch.toList())
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) dao.insertEpisodes(batch.toList())

            // Verify repository.episodes stays strictly bounded (<= 500) and omits transcriptLrc blobs
            val observed = repo.episodes.first()
            assertTrue("Expected bounded summary <= 500, got ${observed.size}", observed.size <= 500)
            assertTrue("Expected transcriptLrc stripped from list summary", observed.all { it.transcriptLrc == null })

            // Verify all 2,000 tracks are still persistently stored and queryable via paged local count
            val localCount = repo.observeLocalMusicCount().first()
            assertTrue("Expected at least $total local tracks in Room, got $localCount", localCount >= total)
        } finally {
            for (chunk in insertedIds.chunked(500)) {
                dao.deleteEpisodesCascade(chunk)
            }
        }
    }
}
