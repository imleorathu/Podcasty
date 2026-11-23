/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app

import com.podcasty.app.core.UnicodeSortUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

class ExtremeScalabilityJvmTest {

    private data class SyntheticTrack(
        val id: String,
        val title: String,
        val artist: String,
        val album: String,
        val folderPath: String,
        val sizeBytes: Long,
    )

    @Test
    fun benchmark_1k_to_100k_sorting_grouping_and_dedup() {
        val tiers = listOf(1_000, 5_000, 10_000, 20_000, 30_000, 50_000, 100_000)
        val unicodePrefixes = listOf(
            "Alpha", "Éclair", "தமிழ் பாடல்", "हिन्दी गीत", "日本語トラック", "موسيقى", "🎵 Emoji", "",
        )
        val runtime = Runtime.getRuntime()

        for (count in tiers) {
            System.gc()
            val heapBeforeKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024L

            val tracks = ArrayList<SyntheticTrack>(count)
            val genMs = measureTimeMillis {
                for (i in 0 until count) {
                    val prefix = unicodePrefixes[i % unicodePrefixes.size]
                    val depth = (i % 8) + 1
                    val folder = (1..depth).joinToString("/") { "Dir_${(i + it) % 40}" }
                    tracks += SyntheticTrack(
                        id = "syn_$i",
                        title = if (prefix.isEmpty()) "Track_%06d".format(count - i) else "$prefix %06d".format(count - i),
                        artist = "Artist_${i % 1_000}",
                        album = "Album_${i % 2_000}",
                        folderPath = folder,
                        sizeBytes = 3_000_000L + (i % 5_000) * 1024L,
                    )
                }
            }

            var sortedSize = 0
            val sortMs = measureTimeMillis {
                val sorted = tracks.sortedWith(UnicodeSortUtil.precomputedKeyComparator(SyntheticTrack::title))
                sortedSize = sorted.size
            }
            assertEquals(count, sortedSize)

            var folderCount = 0
            var dedupCount = 0
            val groupAndDedupMs = measureTimeMillis {
                val folderMap = HashMap<String, Int>(2048)
                val dedup = HashSet<String>(count)
                for (t in tracks) {
                    folderMap[t.folderPath] = (folderMap[t.folderPath] ?: 0) + 1
                    dedup.add("${t.sizeBytes}:${t.title.lowercase()}")
                }
                folderCount = folderMap.size
                dedupCount = dedup.size
            }
            assertTrue(folderCount > 0)
            assertEquals(count, dedupCount)

            val heapAfterKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024L
            val deltaMb = ((heapAfterKb - heapBeforeKb).coerceAtLeast(0L)) / 1024.0
            println(
                "[JVM_STRESS] tracks=$count gen=${genMs}ms sort=${sortMs}ms groupDedup=${groupAndDedupMs}ms " +
                    "folders=$folderCount deltaHeap=${"%.1f".format(deltaMb)}MB",
            )
            assertTrue("Sort of $count tracks took too long: ${sortMs}ms", sortMs < 15_000L)
        }
    }
}
