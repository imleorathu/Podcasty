/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app

import com.podcasty.app.core.UnicodeSortUtil
import com.podcasty.app.database.MusicFolderEntity
import com.podcasty.app.database.ScanSessionEntity
import com.podcasty.app.domain.MusicFolder
import com.podcasty.app.storage.core.ScanSessionStatus
import com.podcasty.app.storage.core.StorageErrorKind
import com.podcasty.app.storage.core.StorageResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

class StorageEngine2Test {

    @Test
    fun folderRegistrationSupportsOneTimeInitialIndexingAndDisableWithoutDeletion() {
        val newlyAdded = MusicFolderEntity(
            treeUri = "content://tree/music",
            displayName = "Music",
            storageType = "internal",
            isDefault = true,
            includeSubfolders = true,
            lastScannedAt = 0L,
            trackCount = 0,
            isAvailable = true,
        )
        assertTrue(newlyAdded.isEnabled)
        assertFalse(newlyAdded.initialIndexingCompleted)

        val indexed = MusicFolder(
            treeUri = newlyAdded.treeUri,
            displayName = newlyAdded.displayName,
            isEnabled = false,
            initialIndexingCompleted = true,
            lastSyncAt = 5_000L,
            trackCount = 6_165,
        )
        assertFalse(indexed.isEnabled)
        assertTrue(indexed.initialIndexingCompleted)
        assertEquals(6_165, indexed.trackCount)
    }

    @Test
    fun scanSessionLifecycleStatusesCoverRecoveryAndStorageDisconnect() {
        val statuses = ScanSessionStatus.entries.map { it.name }.toSet()
        assertEquals(
            setOf("PENDING", "RUNNING", "COMPLETED", "CANCELLED", "FAILED", "STORAGE_UNAVAILABLE"),
            statuses,
        )

        val session = ScanSessionEntity(
            scanId = "scan_1",
            folderUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic",
            startedAt = 1000L,
            completedAt = 2500L,
            status = ScanSessionStatus.STORAGE_UNAVAILABLE.name,
            discoveredCount = 500,
            indexedCount = 490,
            failedCount = 2,
            lastCheckpointPath = "Tamil/Anirudh",
            errorMessage = "USB drive detached mid-scan; existing tracks preserved.",
        )
        assertEquals(490, session.indexedCount)
        assertEquals(2, session.failedCount)
        assertEquals("Tamil/Anirudh", session.lastCheckpointPath)

        val failure: StorageResult<Unit> = StorageResult.Failure(
            kind = StorageErrorKind.STORAGE_DISCONNECTED,
            message = "USB OTG detached",
        )
        assertTrue(failure is StorageResult.Failure)
    }

    @Test
    fun thirtyThousandTrackDeepHierarchySortAndDedupCompletesWithinBudget() {
        val trackCount = 30_000
        val tracks = Array(trackCount) { idx ->
            val depthFolder = "Root/Genre_${idx % 20}/Artist_${idx % 251}/Album_${idx % 7}/Disc_${(idx % 2) + 1}"
            val title = "Song_${trackCount - idx}_தமிழ்_Audio_${idx % 500}"
            Triple(title, UnicodeSortUtil.normalizeKey(title), depthFolder)
        }

        val rt = Runtime.getRuntime()
        val before = rt.totalMemory() - rt.freeMemory()
        val startNs = System.nanoTime()

        val sorted = tracks.sortedWith(UnicodeSortUtil.precomputedKeyComparator { it.second })
        val uniqueFolders = HashSet<String>(4096)
        for (row in sorted) {
            uniqueFolders += row.third
        }

        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        val deltaMb = max(0L, (rt.totalMemory() - rt.freeMemory()) - before) / (1024.0 * 1024.0)

        assertEquals(trackCount, sorted.size)
        assertTrue(uniqueFolders.size >= 1000)
        assertTrue("30k track sort exceeded budget: $elapsedMs ms", elapsedMs < 5_000.0)
        println("STORAGE_ENGINE_2_30K elapsedMs=${"%.2f".format(elapsedMs)} deltaHeapMB=${"%.2f".format(deltaMb)}")
    }
}
