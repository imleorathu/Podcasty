/*
 * Podcasty — Physical Device Verification for:
 * 1) YouTube Original Audio Language Selection (https://youtu.be/B30KvHixUmY -> Tamil original)
 * 2) Progress Bar / Seeking UI Accuracy, Drag, Rapid Seek, Playing/Paused, Short/Long & Performance
 */
package com.podcasty.app

import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.taglib.TagLib
import com.podcasty.app.app.MainActivity
import com.podcasty.app.app.PodcastyApplication
import com.podcasty.app.domain.PlaybackState
import com.podcasty.app.podcast.YouTubeMetadataService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class PhysicalYouTubeAndSeekTest {
    private val tag = "PodcastyYTSeek"

    @Test
    fun testA_youtubeOriginalTamilAudioDownloadImportAndPlayback() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as PodcastyApplication
        val container = app.container
        val videoId = "B30KvHixUmY"

        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("am start -n com.podcasty.app/.app.MainActivity")
            .close()
        delay(1_200L)
        try {
            // 1. Fetch metadata for https://youtu.be/B30KvHixUmY
            val meta = YouTubeMetadataService.fetchMetadata(videoId).getOrThrow()
            Log.i(tag, "YT_META title=${meta.title} channel=${meta.channelName} durationMs=${meta.durationMs} source=${meta.metadataSource}")

            // 2. Resolve audio stream & inspect all discovered tracks
            container.youTubeAudioStreamResolver.invalidate(videoId)
            val stream = container.youTubeAudioStreamResolver.resolve(videoId).getOrThrow()
            Log.i(
                tag,
                "YT_STREAM_SELECTED trackId=${stream.audioTrackId} lang=${stream.audioLanguageCode} " +
                    "displayName=${stream.audioTrackDisplayName} kind=${stream.audioTrackKind} " +
                    "isOriginal=${stream.isOriginalAudio} isAutoDubbed=${stream.isAutoDubbed} isDrc=${stream.isDrc} " +
                    "mime=${stream.mimeType} codec=${stream.codec} bitrate=${stream.bitrateKbps}kbps " +
                    "sr=${stream.sampleRateHz}Hz ch=${stream.audioChannels} itag=${stream.itag}",
            )
            stream.availableTracks.forEachIndexed { i, t ->
                Log.i(
                    tag,
                    "YT_TRACK[$i] id=${t.trackId} lang=${t.languageCode} name=${t.displayName} " +
                        "kind=${t.kind} orig=${t.isOriginal} autoDub=${t.isAutoDubbed} " +
                        "codec=${t.codec} br=${t.bestBitrateKbps}kbps sr=${t.sampleRateHz}Hz",
                )
            }

            // Hard assertions: MUST select Tamil original, NEVER English auto-dub!
            assertTrue("Expected original audio to be selected, got ${stream.audioTrackDisplayName}", stream.isOriginalAudio)
            assertFalse("Must NEVER select auto-dubbed English track", stream.isAutoDubbed)
            val selectedLang = stream.audioLanguageCode.orEmpty().lowercase()
            assertTrue(
                "Expected Tamil (ta / ta-IN) audio track for B30KvHixUmY, got lang=$selectedLang trackId=${stream.audioTrackId} name=${stream.audioTrackDisplayName}",
                selectedLang.startsWith("ta") || stream.audioTrackId.orEmpty().lowercase().startsWith("ta"),
            )

            // 3. Download original Tamil audio file & embed metadata/artwork/captions via YouTubeMusicDownloadManager
            val downloadStart = SystemClock.elapsedRealtime()
            val bundle = container.youTubeMusicDownloadManager.downloadAudioAndEmbedCaptions(
                metadata = meta,
                preferredLanguage = "ta-IN",
            ) { progress ->
                if (progress.fraction == 1f || progress.fraction < 0.12f) {
                    Log.i(tag, "YT_DL_PROGRESS frac=${"%.2f".format(progress.fraction)} stage=${progress.stage}")
                }
            }.getOrThrow()
            val downloadMs = SystemClock.elapsedRealtime() - downloadStart

            assertTrue("Downloaded file must exist", bundle.audioFile.exists())
            assertTrue("Downloaded file must be > 500 KB, got ${bundle.audioFile.length()}", bundle.audioFile.length() > 500_000L)
            assertTrue("Bundle must confirm original audio", bundle.isOriginalAudio)
            assertFalse("Bundle must NOT be auto-dubbed", bundle.isAutoDubbed)
            assertTrue(
                "Bundle language must be Tamil, got ${bundle.audioLanguageCode}",
                bundle.audioLanguageCode.orEmpty().lowercase().startsWith("ta"),
            )

            // 4. Verify downloaded file container & audio stream properties via MediaExtractor + TagLib
            val extractor = MediaExtractor()
            var extractedMime = ""
            var extractedSr = 0
            var extractedCh = 0
            var extractedDurUs = 0L
            try {
                extractor.setDataSource(bundle.audioFile.absolutePath)
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("audio/")) {
                        extractedMime = mime
                        extractedSr = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
                        extractedCh = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0
                        extractedDurUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
                        break
                    }
                }
            } finally {
                extractor.release()
            }

            val tagMap = ParcelFileDescriptor.open(bundle.audioFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                TagLib.getMetadata(pfd.dup().detachFd(), false)?.propertyMap.orEmpty()
            }
            Log.i(
                tag,
                "YT_DOWNLOAD_VERIFIED file=${bundle.audioFile.name} sizeBytes=${bundle.audioFile.length()} " +
                    "downloadMs=$downloadMs extractorMime=$extractedMime sr=$extractedSr ch=$extractedCh " +
                    "durMs=${extractedDurUs / 1000L} tagTitle=${tagMap["TITLE"]?.firstOrNull()} " +
                    "tagArtist=${tagMap["ARTIST"]?.firstOrNull()} tagLang=${tagMap["LANGUAGE"]?.firstOrNull()} " +
                    "tagTrack=${tagMap["AUDIO_TRACK"]?.firstOrNull()}",
            )
            assertTrue("Downloaded audio must have valid audio track mime, got $extractedMime", extractedMime.startsWith("audio/"))
            assertTrue("Sample rate must be >= 22050 Hz, got $extractedSr", extractedSr >= 22_050)
            assertTrue("Duration must be > 60s, got ${extractedDurUs / 1000L}ms", extractedDurUs > 60_000_000L)

            // 5. Import into Podcasty library & verify Room persistence
            val containerLabel = if (bundle.mimeType.contains("mp4", true)) "M4A" else bundle.audioFile.extension.uppercase()
            val (podcastId, episodeId) = container.repository.saveYouTubeVideoImport(
                metadata = meta.copy(durationMs = bundle.durationMs.takeIf { it > 0L } ?: (extractedDurUs / 1000L)),
                preferredCaptionLanguage = bundle.extractedTranscript?.language ?: "ta",
                linkedAudioUri = bundle.audioFileUri,
                extractedTranscript = bundle.extractedTranscript,
                isDownloadedFile = true,
                downloadedFileSizeBytes = bundle.audioFile.length(),
                localArtworkPath = bundle.artworkFile?.absolutePath,
                bitrateKbps = bundle.bitrateKbps,
                audioContainerFormat = containerLabel,
                audioCodecLabel = "${bundle.codec} · ${extractedSr / 1000.0} kHz · TA",
                audioLanguageCode = bundle.audioLanguageCode,
            )
            val savedEp = container.repository.getEpisodeById(episodeId)
            assertNotNull("Imported episode must exist in Room", savedEp)
            assertEquals(bundle.audioFileUri, savedEp!!.linkedAudioUri)
            assertTrue("Imported episode must be marked downloaded", savedEp.isDownloaded)
            Log.i(tag, "YT_LIBRARY_IMPORTED podcastId=$podcastId episodeId=$episodeId title=${savedEp.title} durMs=${savedEp.durationMs}")

            // 6. Play the downloaded Tamil audio file in Podcasty's Media3 player & verify seeking on this long podcast!
            val playback = container.playbackController
            withContext(Dispatchers.Main) {
                playback.setPlaybackSpeed(1.0f)
                playback.playEpisode(episodeId, listOf(episodeId), shuffle = false)
            }
            var ready = false
            val deadline = SystemClock.elapsedRealtime() + 8_000L
            while (SystemClock.elapsedRealtime() < deadline) {
                if (playback.playbackState.value is PlaybackState.Playing) {
                    ready = true
                    break
                }
                delay(50L)
            }
            assertTrue("Downloaded Tamil audio must enter Playing state", ready)
            val durMs = (playback.playbackState.value as? PlaybackState.Playing)?.durationMs?.takeIf { it > 0L }
                ?: savedEp.durationMs
            assertTrue("Playing duration must be > 60s, got $durMs", durMs > 60_000L)

            // Seek long downloaded podcast to 25% and 50% and verify actual position
            for (pct in listOf(0.25, 0.50)) {
                val targetMs = (durMs * pct).toLong()
                withContext(Dispatchers.Main) { playback.seekTo(targetMs) }
                delay(450L)
                val actualMs = playback.playbackPositionMs.value
                val errMs = abs(actualMs - targetMs)
                Log.i(tag, "YT_LONG_SEEK pct=${(pct * 100).toInt()}% targetMs=$targetMs actualMs=$actualMs errMs=$errMs")
                assertTrue("Long podcast seek error too high: ${errMs}ms", errMs <= 3_000L)
            }

            withContext(Dispatchers.Main) { playback.pause() }
        } finally {
            withContext(Dispatchers.Main) { container.playbackController.pause() }
        }
    }

    @Test
    fun testB_comprehensiveSeekingAndPerformanceVerification() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as PodcastyApplication
        val container = app.container
        val playback = container.playbackController
        val dao = container.database.dao()

        val songs = dao.observeLocalSongsByTitleAsc(limit = 5000).first()
            .filter { it.durationMs > 0L && it.audioAsset.isNotBlank() }
        assertTrue("Need local songs on physical device for seek verification", songs.size >= 2)

        val shortSong = songs.filter { it.durationMs in 20_000L..120_000L }.minByOrNull { it.durationMs }
            ?: songs.minByOrNull { it.durationMs }!!
        val longSong = songs.filter { it.durationMs >= 240_000L }.maxByOrNull { it.durationMs }
            ?: songs.maxByOrNull { it.durationMs }!!
        val queueIds = listOf(shortSong.id, longSong.id)

        Log.i(tag, "SEEK_TRACKS short='${shortSong.title}'(${shortSong.durationMs}ms) long='${longSong.title}'(${longSong.durationMs}ms)")

        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("am start -n com.podcasty.app/.app.MainActivity")
            .close()
        delay(1_200L)
        try {
            // 1. Start playing long song & wait until Ready/Playing
            withContext(Dispatchers.Main) {
                playback.setPlaybackSpeed(1.0f)
                playback.playEpisode(longSong.id, queueIds, shuffle = false)
            }
            val startDeadline = SystemClock.elapsedRealtime() + 6_000L
            while (SystemClock.elapsedRealtime() < startDeadline) {
                if (playback.playbackState.value is PlaybackState.Playing) break
                delay(40L)
            }
            assertTrue("Expected Playing state for long song", playback.playbackState.value is PlaybackState.Playing)
            val longDurMs = (playback.playbackState.value as? PlaybackState.Playing)?.durationMs?.takeIf { it > 0L }
                ?: longSong.durationMs

            // Monitor structural root PlaybackState emissions during seeks (must stay 0 -> zero root recomposition!)
            var rootStateEmissionsDuringSeek = 0
            val stateCollector = launch {
                var first = true
                playback.playbackState.collect {
                    if (first) first = false else rootStateEmissionsDuringSeek++
                }
            }

            // 2. Tap-percent seeks while PLAYING: 10%, 25%, 50%, 75%, 90%
            val seekLatenciesMs = mutableListOf<Double>()
            for (pct in listOf(0.10, 0.25, 0.50, 0.75, 0.90)) {
                val targetMs = (longDurMs * pct).toLong()
                val t0 = System.nanoTime()
                withContext(Dispatchers.Main) { playback.seekTo(targetMs) }
                seekLatenciesMs += (System.nanoTime() - t0) / 1_000_000.0
                delay(380L)
                val actualMs = playback.playbackPositionMs.value
                val errMs = abs(actualMs - targetMs)
                Log.i(tag, "PLAYING_TAP_SEEK pct=${(pct * 100).toInt()}% target=$targetMs actual=$actualMs err=${errMs}ms")
                assertTrue("Playing seek at ${(pct * 100).toInt()}% drifted by ${errMs}ms", errMs <= 2_500L)
            }

            // 3. Simulated drag begin->end (5% -> 92%) & end->begin (90% -> 8%)
            val dragForwardTarget = (longDurMs * 0.92).toLong()
            withContext(Dispatchers.Main) { playback.seekTo(dragForwardTarget) }
            delay(380L)
            val fwdActual = playback.playbackPositionMs.value
            Log.i(tag, "DRAG_FWD target=$dragForwardTarget actual=$fwdActual err=${abs(fwdActual - dragForwardTarget)}ms")
            assertTrue("Drag forward drifted", abs(fwdActual - dragForwardTarget) <= 2_500L)

            val dragBackTarget = (longDurMs * 0.08).toLong()
            withContext(Dispatchers.Main) { playback.seekTo(dragBackTarget) }
            delay(380L)
            val backActual = playback.playbackPositionMs.value
            Log.i(tag, "DRAG_BACK target=$dragBackTarget actual=$backActual err=${abs(backActual - dragBackTarget)}ms")
            assertTrue("Drag backward drifted", abs(backActual - dragBackTarget) <= 2_500L)

            // 4. Rapid repeated seeks (15 fast seeks)
            val rapidTargets = (1..15).map { idx -> (longDurMs * ((idx * 6) % 90 + 5) / 100.0).toLong() }
            rapidTargets.forEach { target ->
                val t0 = System.nanoTime()
                withContext(Dispatchers.Main) { playback.seekTo(target) }
                seekLatenciesMs += (System.nanoTime() - t0) / 1_000_000.0
                delay(25L)
            }
            delay(450L)
            val rapidFinalActual = playback.playbackPositionMs.value
            val rapidFinalTarget = rapidTargets.last()
            Log.i(tag, "RAPID_15_SEEK target=$rapidFinalTarget actual=$rapidFinalActual err=${abs(rapidFinalActual - rapidFinalTarget)}ms rootEmissions=$rootStateEmissionsDuringSeek")
            assertTrue("Rapid seek final target drifted", abs(rapidFinalActual - rapidFinalTarget) <= 2_500L)
            assertEquals("Seeks must NOT cause structural root PlaybackState emissions", 0, rootStateEmissionsDuringSeek)
            stateCollector.cancel()

            // 5. Seek while PAUSED (must stay paused & accurately match target)
            withContext(Dispatchers.Main) { playback.pause() }
            delay(300L)
            assertTrue("Must be Paused before paused-seek test", playback.playbackState.value is PlaybackState.Paused)
            val pausedTarget = (longDurMs * 0.60).toLong()
            withContext(Dispatchers.Main) { playback.seekTo(pausedTarget) }
            delay(450L)
            val pausedActual = playback.playbackPositionMs.value
            Log.i(tag, "PAUSED_SEEK target=$pausedTarget actual=$pausedActual state=${playback.playbackState.value::class.simpleName}")
            assertTrue("Paused seek must stay Paused", playback.playbackState.value is PlaybackState.Paused)
            assertTrue("Paused seek position drifted: ${abs(pausedActual - pausedTarget)}ms", abs(pausedActual - pausedTarget) <= 2_000L)

            // 6. Seek immediately after changing tracks (switch to short song & seek immediately)
            withContext(Dispatchers.Main) {
                playback.playEpisode(shortSong.id, queueIds, shuffle = false)
            }
            delay(500L)
            val shortDurMs = shortSong.durationMs
            val shortTarget = (shortDurMs * 0.50).toLong()
            withContext(Dispatchers.Main) { playback.seekTo(shortTarget) }
            delay(450L)
            val shortActual = playback.playbackPositionMs.value
            Log.i(tag, "SHORT_TRACK_IMMEDIATE_SEEK dur=$shortDurMs target=$shortTarget actual=$shortActual err=${abs(shortActual - shortTarget)}ms")
            assertTrue("Immediate post-track-switch seek drifted: ${abs(shortActual - shortTarget)}ms", abs(shortActual - shortTarget) <= 2_500L)

            // 7. Clamping check (negative & > duration)
            withContext(Dispatchers.Main) { playback.seekTo(-10_000L) }
            delay(250L)
            assertTrue("Negative seek must clamp >= 0", playback.playbackPositionMs.value >= 0L)
            withContext(Dispatchers.Main) { playback.seekTo(shortDurMs + 99_000L) }
            delay(250L)
            assertTrue("Over-duration seek must clamp <= duration", playback.playbackPositionMs.value <= shortDurMs + 500L)

            val avgSeekMs = seekLatenciesMs.average()
            val maxSeekMs = seekLatenciesMs.maxOrNull() ?: 0.0
            val rt = Runtime.getRuntime()
            val javaHeapMb = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0)
            Log.i(
                tag,
                "SEEK_PERF_SUMMARY count=${seekLatenciesMs.size} avgMs=${"%.2f".format(avgSeekMs)} " +
                    "maxMs=${"%.2f".format(maxSeekMs)} javaHeapMb=${"%.1f".format(javaHeapMb)}",
            )

            withContext(Dispatchers.Main) { playback.pause() }
        } finally {
            withContext(Dispatchers.Main) { playback.pause() }
        }
    }
}
