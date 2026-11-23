/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app

import com.podcasty.app.core.formatCompactDuration
import com.podcasty.app.core.formatFileSize
import com.podcasty.app.core.formatPlaybackTime
import com.podcasty.app.core.formatRemainingTime
import com.podcasty.app.core.formatSpeedLabel
import com.podcasty.app.core.formatTimerCountdown
import com.podcasty.app.core.libraryAlphabeticalIndexLabel
import com.podcasty.app.database.SeedPodcastLibrary
import com.podcasty.app.features.player.PlayerLayoutMode
import com.podcasty.app.features.player.moveQueueEpisode
import com.podcasty.app.features.player.parseEpisodeTranscript
import com.podcasty.app.features.player.resolvePlayerLayout
import com.podcasty.app.navigation.ContentScrollDelta
import com.podcasty.app.navigation.ContentScrollDirection
import com.podcasty.app.navigation.NavigationChromeScrollAccumulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastyCoreAndPlayerTest {

    @Test
    fun formattersRenderExpectedPlaybackAndSizeStrings() {
        assertEquals("0:00", formatPlaybackTime(0L))
        assertEquals("2:05", formatPlaybackTime(125_000L))
        assertEquals("1:02:03", formatPlaybackTime(3_723_000L))
        assertEquals("-1:30", formatRemainingTime(30_000L, 120_000L))
        assertEquals("--:--", formatRemainingTime(0L, 0L))
        assertEquals("3 min", formatCompactDuration(175_000L))
        assertEquals("1h 15m", formatCompactDuration(4_500_000L))
        assertEquals("1.0x", formatSpeedLabel(1.0f))
        assertEquals("1.25x", formatSpeedLabel(1.25f))
        assertEquals("1.5x", formatSpeedLabel(1.5f))
        assertEquals("512.0 KB", formatFileSize(512L * 1024L))
        assertEquals("1.5 MB", formatFileSize((1.5 * 1024 * 1024).toLong()))
        assertEquals("14:59", formatTimerCountdown(899))
        assertEquals("P", libraryAlphabeticalIndexLabel("podcasty"))
        assertEquals("#", libraryAlphabeticalIndexLabel("123 Show"))
        assertNull(libraryAlphabeticalIndexLabel("   "))
    }

    @Test
    fun seededPodcastLibraryIsEmptyByDefault() {
        val seed = SeedPodcastLibrary.buildSeed()
        assertTrue(seed.categories.isEmpty())
        assertTrue(seed.hosts.isEmpty())
        assertTrue(seed.podcasts.isEmpty())
        assertTrue(seed.episodes.isEmpty())
        assertTrue(seed.collections.isEmpty())
        assertTrue(seed.history.isEmpty())
    }

    @Test
    fun transcriptParserSplitsTimestampSpeakerAndBody() {
        val lines = parseEpisodeTranscript(
            """
            [00:00.00] Maya Lin: Welcome back to Silicon Pulse.
            [00:18.50] Dr. Julian Vance: Let's look at the Europa Clipper data.
            [00:42.00] Plain narrated segment without speaker tag.
            """.trimIndent(),
        )
        assertEquals(3, lines.size)
        assertEquals(0f, lines[0].timestampSeconds!!, 0.01f)
        assertEquals("Maya Lin", lines[0].speaker)
        assertEquals("Welcome back to Silicon Pulse.", lines[0].text)
        assertEquals(18.5f, lines[1].timestampSeconds!!, 0.01f)
        assertEquals("Dr. Julian Vance", lines[1].speaker)
        assertNull(lines[2].speaker)
        assertEquals("Plain narrated segment without speaker tag.", lines[2].text)
    }

    @Test
    fun playerLayoutAndQueueHelpersBehavePredictably() {
        assertEquals(PlayerLayoutMode.Phone, resolvePlayerLayout(412f, 915f))
        assertEquals(PlayerLayoutMode.Wide, resolvePlayerLayout(1024f, 680f))
        assertEquals(PlayerLayoutMode.Square, resolvePlayerLayout(500f, 500f))

        val moved = moveQueueEpisode(listOf("a", "b", "c", "d"), 1, 3)
        assertEquals(listOf("a", "c", "d", "b"), moved)

        val scrollAcc = NavigationChromeScrollAccumulator()
        assertFalse(scrollAcc.update(ContentScrollDelta(ContentScrollDirection.Up, 12f), 24f))
        assertTrue(scrollAcc.update(ContentScrollDelta(ContentScrollDirection.Up, 14f), 24f))
        assertTrue(scrollAcc.compact)
        assertTrue(scrollAcc.update(ContentScrollDelta(ContentScrollDirection.Down, 30f), 24f))
        assertFalse(scrollAcc.compact)
    }
}
