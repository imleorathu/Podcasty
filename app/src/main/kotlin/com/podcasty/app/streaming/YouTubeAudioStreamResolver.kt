/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.streaming

import com.podcasty.ytmusicapi.YTMusicApiClient
import com.podcasty.ytmusicapi.YouTubeAudioTrackKind
import com.podcasty.ytmusicapi.YtMusicAdaptiveAudioFormat
import com.podcasty.ytmusicapi.YtMusicAudioTrackHint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.TimeUnit

data class YouTubeAudioTrackOption(
    val trackId: String,
    val languageCode: String?,
    val displayName: String,
    val kind: YouTubeAudioTrackKind,
    val isOriginal: Boolean,
    val isAutoDubbed: Boolean,
    val isDubbed: Boolean,
    val bestBitrateKbps: Int,
    val sampleRateHz: Int,
    val codec: String,
    val mimeType: String,
)

data class YouTubeAudioStream(
    val videoId: String,
    val url: String,
    val mimeType: String,
    val bitrateKbps: Int,
    val durationMs: Long,
    val title: String,
    val channelName: String,
    val itag: Int,
    val resolvedAtEpochMs: Long = System.currentTimeMillis(),
    val sampleRateHz: Int = 44_100,
    val audioChannels: Int = 2,
    val codec: String = "",
    val audioTrackId: String? = null,
    val audioLanguageCode: String? = null,
    val audioTrackDisplayName: String? = null,
    val audioTrackKind: YouTubeAudioTrackKind = YouTubeAudioTrackKind.UNKNOWN,
    val isOriginalAudio: Boolean = false,
    val isAutoDubbed: Boolean = false,
    val isDrc: Boolean = false,
    val availableTracks: List<YouTubeAudioTrackOption> = emptyList(),
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - resolvedAtEpochMs >= StreamTtlMs

    companion object {
        const val StreamTtlMs: Long = 30L * 60L * 1_000L
    }
}

sealed class YouTubeAudioResolutionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class AgeRestricted(message: String, cause: Throwable? = null) :
        YouTubeAudioResolutionException(message, cause)

    class Unavailable(message: String, cause: Throwable? = null) :
        YouTubeAudioResolutionException(message, cause)

    class OriginalAudioUnavailable(
        val videoId: String,
        val availableTracks: List<YouTubeAudioTrackOption>,
        message: String,
    ) : YouTubeAudioResolutionException(message)

    class LanguageSelectionRequired(
        val videoId: String,
        val availableTracks: List<YouTubeAudioTrackOption>,
        message: String,
    ) : YouTubeAudioResolutionException(message)

    class Network(cause: Throwable) :
        YouTubeAudioResolutionException(
            "Unable to reach YouTube. Check your connection and try again.",
            cause,
        )

    class Extraction(message: String, cause: Throwable? = null) :
        YouTubeAudioResolutionException(message, cause)
}

internal data class AudioCandidate(
    val url: String,
    val mimeType: String,
    val formatRank: Int,
    val averageBitrate: Int,
    val bitrate: Int,
    val sampleRateHz: Int,
    val audioChannels: Int,
    val codec: String,
    val itag: Int,
    val approxDurationMs: Long,
    val trackId: String?,
    val languageCode: String?,
    val displayName: String?,
    val kind: YouTubeAudioTrackKind,
    val isDefault: Boolean,
    val isAutoDubbed: Boolean,
    val isDrc: Boolean,
) {
    val effectiveBitrateBps: Int get() = averageBitrate.takeIf { it > 0 } ?: bitrate.coerceAtLeast(0)
    val bitrateKbps: Int get() = (effectiveBitrateBps / 1_000).coerceAtLeast(0)
    val normalizedLanguage: String? get() = YTMusicApiClient.normalizeYouTubeLanguageCode(languageCode)
    val trackKey: String
        get() = trackId?.trim()?.takeIf { it.isNotBlank() }
            ?: normalizedLanguage?.let { "$it.${kind.name.lowercase(Locale.US)}" }
            ?: "default"
}

internal sealed interface AudioTrackSelectionResult {
    data class Selected(
        val candidate: AudioCandidate,
        val isOriginalConfirmed: Boolean,
        val availableTracks: List<YouTubeAudioTrackOption>,
    ) : AudioTrackSelectionResult

    data class LanguageSelectionRequired(
        val availableTracks: List<YouTubeAudioTrackOption>,
        val reason: String,
    ) : AudioTrackSelectionResult

    data class OriginalUnavailable(
        val availableTracks: List<YouTubeAudioTrackOption>,
        val reason: String,
    ) : AudioTrackSelectionResult

    data class NoPlayableStreams(val reason: String) : AudioTrackSelectionResult
}

/**
 * Resolves the highest-quality original audio-only progressive stream for a YouTube video.
 *
 * Original-audio policy:
 * - Inspects all available audio tracks and source metadata (InnerTube `audioTrack` + `xtags`
 *   and NewPipeExtractor `AudioTrackType` + `audioLocale` + `audioTrackId` + `audioTrackName`).
 * - Strictly selects the source-confirmed ORIGINAL audio track rather than auto-dubbed/dubbed
 *   tracks (even when a dub is marked `audioIsDefault = true` or appears first).
 * - Never uses Android device locale or app UI language as the original audio language.
 * - If multiple audio languages exist and original language cannot be identified confidently,
 *   returns [YouTubeAudioResolutionException.LanguageSelectionRequired] with all available tracks
 *   instead of silently selecting a potentially incorrect dub.
 */
class YouTubeAudioStreamResolver(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val ytMusicClient: YTMusicApiClient = YTMusicApiClient(httpClient),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val cache = LinkedHashMap<String, YouTubeAudioStream>()
    private val inFlight = mutableMapOf<String, Deferred<Result<YouTubeAudioStream>>>()

    @Volatile
    private var initialized = false

    suspend fun resolve(
        videoId: String,
        preferredAudioTrackId: String? = null,
    ): Result<YouTubeAudioStream> {
        val cleanId = videoId.trim()
        val cacheKey = cacheKeyFor(cleanId, preferredAudioTrackId)
        val cached = lock.withLock {
            cache[cacheKey]?.takeIf { !it.isExpired() }
        }
        if (cached != null) return Result.success(cached)

        val deferred = lock.withLock {
            cache[cacheKey]?.takeIf { !it.isExpired() }?.let { return Result.success(it) }
            inFlight[cacheKey] ?: scope.async { resolveInternal(cleanId, preferredAudioTrackId) }
                .also { inFlight[cacheKey] = it }
        }

        val result = deferred.await()
        lock.withLock {
            inFlight.remove(cacheKey)
            result.getOrNull()?.let { cache[cacheKey] = it }
        }
        return result
    }

    suspend fun invalidate(videoId: String) {
        val prefix = "${videoId.trim()}::"
        lock.withLock {
            val keysToRemove = cache.keys.filter { it.startsWith(prefix) }
            keysToRemove.forEach { cache.remove(it) }
        }
    }

    private suspend fun resolveInternal(
        videoId: String,
        preferredAudioTrackId: String?,
    ): Result<YouTubeAudioStream> =
        withContext(Dispatchers.IO) {
            // 1. Primary: Query integrated YTmusicapi `get_song` (`streamingData.adaptiveFormats`)
            // Even when direct URLs are absent (SABR), `audioTrackHints` preserves authoritative
            // `audioTrack` (`id`, `displayName`, `isAutoDubbed`) and `xtags` (`acont=original`, `lang`).
            val ytMusicSong = ytMusicClient.getSong(videoId).getOrNull()
            if (ytMusicSong != null && ytMusicSong.isPlayable && ytMusicSong.audioFormats.isNotEmpty()) {
                val ytCandidates = ytMusicSong.audioFormats.map(::fromYtMusicFormat)
                when (
                    val selected = selectAudioCandidate(
                        candidates = ytCandidates,
                        preferredTrackId = preferredAudioTrackId,
                        trackHints = ytMusicSong.audioTrackHints,
                        videoLanguageHint = null,
                    )
                ) {
                    is AudioTrackSelectionResult.Selected -> {
                        val c = selected.candidate
                        return@withContext Result.success(
                            YouTubeAudioStream(
                                videoId = videoId,
                                url = c.url,
                                mimeType = c.mimeType.ifBlank { "audio/mp4" },
                                bitrateKbps = c.bitrateKbps,
                                durationMs = ytMusicSong.durationMs.takeIf { it > 0L } ?: c.approxDurationMs,
                                title = ytMusicSong.title,
                                channelName = ytMusicSong.author,
                                itag = c.itag,
                                sampleRateHz = c.sampleRateHz,
                                audioChannels = c.audioChannels,
                                codec = c.codec,
                                audioTrackId = c.trackId,
                                audioLanguageCode = c.languageCode,
                                audioTrackDisplayName = c.displayName,
                                audioTrackKind = c.kind,
                                isOriginalAudio = selected.isOriginalConfirmed,
                                isAutoDubbed = c.isAutoDubbed || c.kind == YouTubeAudioTrackKind.AUTO_DUBBED,
                                isDrc = c.isDrc,
                                availableTracks = selected.availableTracks,
                            ),
                        )
                    }
                    is AudioTrackSelectionResult.LanguageSelectionRequired -> {
                        return@withContext Result.failure(
                            YouTubeAudioResolutionException.LanguageSelectionRequired(
                                videoId = videoId,
                                availableTracks = selected.availableTracks,
                                message = selected.reason,
                            ),
                        )
                    }
                    is AudioTrackSelectionResult.OriginalUnavailable -> {
                        // Fall through to NewPipeExtractor in case another client exposes the original track
                    }
                    is AudioTrackSelectionResult.NoPlayableStreams -> {
                        // Fall through to NewPipeExtractor
                    }
                }
            }

            // 2. Fallback / Full multi-track extraction: NewPipeExtractor stream resolution
            try {
                ensureInitialized()
                val canonicalUrl = "https://www.youtube.com/watch?v=$videoId"
                val info = StreamInfo.getInfo(ServiceList.YouTube, canonicalUrl)
                val hints = ytMusicSong?.audioTrackHints.orEmpty()
                val videoLangHint = info.languageInfo?.toLanguageTag()?.takeIf { it.isNotBlank() && it != "und" }
                val candidates = info.audioStreams
                    .filter { stream ->
                        stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                            stream.isUrl &&
                            stream.content.isNotBlank()
                    }
                    .map { fromNewPipeStream(it, hints) }

                when (
                    val selection = selectAudioCandidate(
                        candidates = candidates,
                        preferredTrackId = preferredAudioTrackId,
                        trackHints = hints,
                        videoLanguageHint = videoLangHint,
                    )
                ) {
                    is AudioTrackSelectionResult.Selected -> {
                        val c = selection.candidate
                        Result.success(
                            YouTubeAudioStream(
                                videoId = videoId,
                                url = c.url,
                                mimeType = c.mimeType.ifBlank { "audio/mp4" },
                                bitrateKbps = c.bitrateKbps,
                                durationMs = info.duration.coerceAtLeast(0L) * 1_000L,
                                title = info.name.orEmpty(),
                                channelName = info.uploaderName.orEmpty(),
                                itag = c.itag,
                                sampleRateHz = c.sampleRateHz,
                                audioChannels = c.audioChannels,
                                codec = c.codec,
                                audioTrackId = c.trackId,
                                audioLanguageCode = c.languageCode,
                                audioTrackDisplayName = c.displayName,
                                audioTrackKind = c.kind,
                                isOriginalAudio = selection.isOriginalConfirmed,
                                isAutoDubbed = c.isAutoDubbed || c.kind == YouTubeAudioTrackKind.AUTO_DUBBED,
                                isDrc = c.isDrc,
                                availableTracks = selection.availableTracks,
                            ),
                        )
                    }
                    is AudioTrackSelectionResult.LanguageSelectionRequired -> {
                        Result.failure(
                            YouTubeAudioResolutionException.LanguageSelectionRequired(
                                videoId = videoId,
                                availableTracks = selection.availableTracks,
                                message = selection.reason,
                            ),
                        )
                    }
                    is AudioTrackSelectionResult.OriginalUnavailable -> {
                        Result.failure(
                            YouTubeAudioResolutionException.OriginalAudioUnavailable(
                                videoId = videoId,
                                availableTracks = selection.availableTracks,
                                message = selection.reason,
                            ),
                        )
                    }
                    is AudioTrackSelectionResult.NoPlayableStreams -> {
                        Result.failure(
                            YouTubeAudioResolutionException.Unavailable(selection.reason),
                        )
                    }
                }
            } catch (throwable: Throwable) {
                Result.failure(throwable.toResolutionException())
            }
        }

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            NewPipe.init(OkHttpDownloader(httpClient), Localization.fromLocale(Locale.US))
            initialized = true
        }
    }

    companion object {
        private fun cacheKeyFor(videoId: String, preferredAudioTrackId: String?): String =
            "$videoId::${preferredAudioTrackId?.trim()?.lowercase(Locale.US).orEmpty().ifBlank { "original" }}"

        internal fun selectBestAudioStream(
            streams: List<AudioStream>,
            preferredAudioTrackId: String? = null,
            trackHints: List<YtMusicAudioTrackHint> = emptyList(),
            videoLanguageHint: String? = null,
        ): AudioStream? {
            val playable = streams.filter { stream ->
                stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                    stream.isUrl &&
                    stream.content.isNotBlank()
            }
            if (playable.isEmpty()) return null
            val pairs = playable.map { it to fromNewPipeStream(it, trackHints) }
            val result = selectAudioCandidate(
                candidates = pairs.map { it.second },
                preferredTrackId = preferredAudioTrackId,
                trackHints = trackHints,
                videoLanguageHint = videoLanguageHint,
            )
            val chosenUrl = (result as? AudioTrackSelectionResult.Selected)?.candidate?.url ?: return null
            return pairs.firstOrNull { it.second.url == chosenUrl }?.first
        }

        internal fun selectAudioCandidate(
            candidates: List<AudioCandidate>,
            preferredTrackId: String? = null,
            trackHints: List<YtMusicAudioTrackHint> = emptyList(),
            videoLanguageHint: String? = null,
        ): AudioTrackSelectionResult {
            if (candidates.isEmpty()) {
                return AudioTrackSelectionResult.NoPlayableStreams(
                    "This YouTube video has no audio-only stream available.",
                )
            }

            // Enrich candidates with source track hints (from InnerTube manifest) when fields are missing
            val enriched = candidates.map { enrichWithHints(it, trackHints) }
            val availableTracks = buildTrackOptions(enriched)

            // Explicit user track selection
            if (!preferredTrackId.isNullOrBlank()) {
                val wanted = preferredTrackId.trim()
                val wantedNormLang = YTMusicApiClient.normalizeYouTubeLanguageCode(wanted)
                val matching = enriched.filter { c ->
                    c.trackKey.equals(wanted, ignoreCase = true) ||
                        c.trackId.equals(wanted, ignoreCase = true) ||
                        (wantedNormLang != null && c.normalizedLanguage == wantedNormLang) ||
                        (wantedNormLang != null && c.normalizedLanguage?.substringBefore('-') == wantedNormLang.substringBefore('-'))
                }
                val best = matching.maxWithOrNull(qualityComparator)
                    ?: return AudioTrackSelectionResult.OriginalUnavailable(
                        availableTracks = availableTracks,
                        reason = "Requested audio track ($wanted) is not available from YouTube for this video.",
                    )
                val isOrig = best.kind == YouTubeAudioTrackKind.ORIGINAL ||
                    (availableTracks.size == 1 && !isDubbedOrDescriptive(best))
                return AudioTrackSelectionResult.Selected(best, isOrig, availableTracks)
            }

            // 1. Source-confirmed ORIGINAL audio track(s)
            val originalCandidates = enriched.filter { it.kind == YouTubeAudioTrackKind.ORIGINAL }
            if (originalCandidates.isNotEmpty()) {
                val best = originalCandidates.maxWithOrNull(qualityComparator)!!
                return AudioTrackSelectionResult.Selected(
                    candidate = best,
                    isOriginalConfirmed = true,
                    availableTracks = availableTracks,
                )
            }

            // 2. Reject dubbed-only responses instead of pretending an auto-dub is original audio
            val nonDubbed = enriched.filterNot(::isDubbedOrDescriptive)
            if (nonDubbed.isEmpty()) {
                val labels = availableTracks.joinToString(", ") { it.displayName }
                return AudioTrackSelectionResult.OriginalUnavailable(
                    availableTracks = availableTracks,
                    reason = "Original audio track is unavailable from YouTube for this video (only dubbed audio was returned: $labels).",
                )
            }

            // 3. Cross-reference explicit original hint from source manifest (InnerTube hints or video languageInfo)
            val manifestOriginalHint = trackHints.firstOrNull { it.kind == YouTubeAudioTrackKind.ORIGINAL }
            if (manifestOriginalHint != null) {
                val hintTrackId = manifestOriginalHint.trackId
                val hintLang = YTMusicApiClient.normalizeYouTubeLanguageCode(manifestOriginalHint.languageCode)
                val matched = nonDubbed.filter { c ->
                    (hintTrackId != null && c.trackId.equals(hintTrackId, ignoreCase = true)) ||
                        (hintLang != null && (c.normalizedLanguage == hintLang ||
                            c.normalizedLanguage?.substringBefore('-') == hintLang.substringBefore('-')))
                }
                if (matched.isNotEmpty()) {
                    val best = matched.maxWithOrNull(qualityComparator)!!
                    return AudioTrackSelectionResult.Selected(
                        candidate = best.copy(kind = YouTubeAudioTrackKind.ORIGINAL),
                        isOriginalConfirmed = true,
                        availableTracks = availableTracks,
                    )
                }
            }

            // 4. Single-language / single-track video (standard video without multi-language dubbing)
            val distinctNonDubbedLangs = nonDubbed
                .mapNotNull { it.normalizedLanguage?.substringBefore('-') }
                .distinct()
            val distinctNonDubbedKeys = nonDubbed.map { it.trackKey }.distinct()
            if (distinctNonDubbedLangs.size <= 1 && distinctNonDubbedKeys.size <= 1) {
                val best = nonDubbed.maxWithOrNull(qualityComparator)!!
                return AudioTrackSelectionResult.Selected(
                    candidate = best,
                    isOriginalConfirmed = true,
                    availableTracks = availableTracks,
                )
            }

            // 5. Multiple distinct non-dubbed languages: check if source videoLanguageHint uniquely matches one
            val normalizedVideoLang = YTMusicApiClient.normalizeYouTubeLanguageCode(videoLanguageHint)
            if (normalizedVideoLang != null) {
                val baseVideoLang = normalizedVideoLang.substringBefore('-')
                val matchedByVideoLang = nonDubbed.filter { c ->
                    c.normalizedLanguage == normalizedVideoLang ||
                        c.normalizedLanguage?.substringBefore('-') == baseVideoLang
                }
                if (matchedByVideoLang.isNotEmpty() &&
                    matchedByVideoLang.map { it.normalizedLanguage?.substringBefore('-') }.distinct().size == 1
                ) {
                    val best = matchedByVideoLang.maxWithOrNull(qualityComparator)!!
                    return AudioTrackSelectionResult.Selected(
                        candidate = best.copy(kind = YouTubeAudioTrackKind.ORIGINAL),
                        isOriginalConfirmed = true,
                        availableTracks = availableTracks,
                    )
                }
            }

            // 6. Ambiguous multi-language tracks — NEVER silently guess a default/English dub!
            val langNames = availableTracks.joinToString(", ") { it.displayName }
            return AudioTrackSelectionResult.LanguageSelectionRequired(
                availableTracks = availableTracks,
                reason = "Multiple audio tracks found ($langNames), and the original audio language could not be confirmed automatically. Please select the audio language to download.",
            )
        }

        private fun isDubbedOrDescriptive(c: AudioCandidate): Boolean =
            c.isAutoDubbed ||
                c.kind == YouTubeAudioTrackKind.AUTO_DUBBED ||
                c.kind == YouTubeAudioTrackKind.DUBBED ||
                c.kind == YouTubeAudioTrackKind.DESCRIPTIVE

        private fun enrichWithHints(
            candidate: AudioCandidate,
            hints: List<YtMusicAudioTrackHint>,
        ): AudioCandidate {
            if (hints.isEmpty()) return candidate
            val matchingHint = hints.firstOrNull { hint ->
                (candidate.trackId != null && hint.trackId != null && candidate.trackId.equals(hint.trackId, ignoreCase = true)) ||
                    (candidate.normalizedLanguage != null &&
                        YTMusicApiClient.normalizeYouTubeLanguageCode(hint.languageCode) == candidate.normalizedLanguage &&
                        (candidate.kind == YouTubeAudioTrackKind.UNKNOWN || candidate.kind == hint.kind))
            } ?: return candidate
            val resolvedKind = if (candidate.kind != YouTubeAudioTrackKind.UNKNOWN) candidate.kind else matchingHint.kind
            return candidate.copy(
                trackId = candidate.trackId ?: matchingHint.trackId,
                displayName = candidate.displayName ?: matchingHint.displayName,
                languageCode = candidate.languageCode ?: matchingHint.languageCode,
                kind = resolvedKind,
                isAutoDubbed = candidate.isAutoDubbed || matchingHint.isAutoDubbed || resolvedKind == YouTubeAudioTrackKind.AUTO_DUBBED,
            )
        }

        private fun buildTrackOptions(candidates: List<AudioCandidate>): List<YouTubeAudioTrackOption> {
            val grouped = candidates.groupBy { it.trackKey }
            return grouped.values.mapNotNull { group ->
                val best = group.maxWithOrNull(qualityComparator) ?: return@mapNotNull null
                val lang = best.languageCode
                val isOrig = best.kind == YouTubeAudioTrackKind.ORIGINAL ||
                    (grouped.size == 1 && !isDubbedOrDescriptive(best))
                val label = buildDisplayLabel(best, isOrig)
                YouTubeAudioTrackOption(
                    trackId = best.trackId ?: best.trackKey,
                    languageCode = lang,
                    displayName = label,
                    kind = best.kind,
                    isOriginal = isOrig,
                    isAutoDubbed = best.isAutoDubbed || best.kind == YouTubeAudioTrackKind.AUTO_DUBBED,
                    isDubbed = best.kind == YouTubeAudioTrackKind.DUBBED || best.kind == YouTubeAudioTrackKind.AUTO_DUBBED,
                    bestBitrateKbps = best.bitrateKbps,
                    sampleRateHz = best.sampleRateHz,
                    codec = best.codec,
                    mimeType = best.mimeType,
                )
            }.sortedWith(
                compareByDescending<YouTubeAudioTrackOption> { it.isOriginal }
                    .thenBy { it.isAutoDubbed || it.isDubbed }
                    .thenByDescending { it.bestBitrateKbps }
                    .thenBy { it.displayName },
            )
        }

        private fun buildDisplayLabel(c: AudioCandidate, isOrig: Boolean): String {
            val base = c.displayName?.takeIf { it.isNotBlank() }
                ?: c.languageCode?.let { Locale.forLanguageTag(it).getDisplayName(Locale.ENGLISH).takeIf { d -> d.isNotBlank() } ?: it }
                ?: "Original audio"
            return when {
                c.isAutoDubbed || c.kind == YouTubeAudioTrackKind.AUTO_DUBBED ->
                    if (base.contains("dub", ignoreCase = true)) base else "$base (Auto-dubbed)"
                c.kind == YouTubeAudioTrackKind.DUBBED ->
                    if (base.contains("dub", ignoreCase = true)) base else "$base (Dubbed)"
                c.kind == YouTubeAudioTrackKind.DESCRIPTIVE ->
                    if (base.contains("descriptive", ignoreCase = true)) base else "$base (Descriptive)"
                isOrig ->
                    if (base.contains("original", ignoreCase = true)) base else "$base (Original)"
                else -> base
            }
        }

        private fun fromYtMusicFormat(f: YtMusicAdaptiveAudioFormat): AudioCandidate = AudioCandidate(
            url = f.url,
            mimeType = f.mimeType,
            formatRank = mimeRank(f.mimeType, f.itag),
            averageBitrate = f.averageBitrate,
            bitrate = f.bitrate,
            sampleRateHz = f.audioSampleRate.takeIf { it > 0 } ?: 44_100,
            audioChannels = f.audioChannels.takeIf { it > 0 } ?: 2,
            codec = f.codecs?.ifBlank { null } ?: inferCodec(f.mimeType, f.itag),
            itag = f.itag,
            approxDurationMs = f.approxDurationMs,
            trackId = f.audioTrackId,
            languageCode = f.audioLanguageCode,
            displayName = f.audioTrackDisplayName,
            kind = f.audioTrackKind,
            isDefault = f.audioIsDefault,
            isAutoDubbed = f.isAutoDubbed,
            isDrc = f.isDrc,
        )

        private fun fromNewPipeStream(
            stream: AudioStream,
            hints: List<YtMusicAudioTrackHint>,
        ): AudioCandidate {
            val urlXtags = extractUrlQueryParam(stream.content, "xtags")
            val parsedXtags = YTMusicApiClient.parseYouTubeXtags(urlXtags)
            val trackId = stream.audioTrackId?.trim()?.takeIf { it.isNotBlank() }
            val trackName = stream.audioTrackName?.trim()?.takeIf { it.isNotBlank() }
            val localeTag = stream.audioLocale?.toLanguageTag()?.takeIf { it.isNotBlank() && it != "und" }
            val langCode = parsedXtags.language
                ?: localeTag
                ?: trackId?.substringBefore('.')?.takeIf { it.isNotBlank() }

            val xtagsKind = YTMusicApiClient.classifyYouTubeAudioTrack(
                trackId = trackId,
                displayName = trackName,
                isAutoDubbed = false,
                xtags = parsedXtags,
            )
            val newPipeKind = when (stream.audioTrackType) {
                AudioTrackType.ORIGINAL -> YouTubeAudioTrackKind.ORIGINAL
                AudioTrackType.DUBBED ->
                    if (xtagsKind == YouTubeAudioTrackKind.AUTO_DUBBED) YouTubeAudioTrackKind.AUTO_DUBBED
                    else YouTubeAudioTrackKind.DUBBED
                AudioTrackType.DESCRIPTIVE -> YouTubeAudioTrackKind.DESCRIPTIVE
                AudioTrackType.SECONDARY -> YouTubeAudioTrackKind.SECONDARY
                null -> YouTubeAudioTrackKind.UNKNOWN
            }
            // Prefer xtags/trackId AUTO_DUBBED specificity over generic DUBBED, otherwise trust explicit NewPipe AudioTrackType
            val resolvedKind = when {
                xtagsKind == YouTubeAudioTrackKind.AUTO_DUBBED -> YouTubeAudioTrackKind.AUTO_DUBBED
                newPipeKind != YouTubeAudioTrackKind.UNKNOWN -> newPipeKind
                else -> xtagsKind
            }

            val itagItem = stream.itagItem
            val mimeType = stream.format?.mimeType ?: "audio/mp4"
            val sampleRate = itagItem?.sampleRate?.takeIf { it > 0 } ?: if (mimeType.contains("opus") || mimeType.contains("webm")) 48_000 else 44_100
            val channels = itagItem?.audioChannels?.takeIf { it > 0 } ?: 2
            val codec = stream.codec?.takeIf { it.isNotBlank() } ?: inferCodec(mimeType, stream.itag)

            val baseCandidate = AudioCandidate(
                url = stream.content,
                mimeType = mimeType,
                formatRank = formatRank(stream.format),
                averageBitrate = stream.averageBitrate.coerceAtLeast(0),
                bitrate = stream.bitrate.coerceAtLeast(0),
                sampleRateHz = sampleRate,
                audioChannels = channels,
                codec = codec,
                itag = stream.itag,
                approxDurationMs = itagItem?.approxDurationMs?.takeIf { it > 0L } ?: 0L,
                trackId = trackId,
                languageCode = langCode,
                displayName = trackName,
                kind = resolvedKind,
                isDefault = false,
                isAutoDubbed = resolvedKind == YouTubeAudioTrackKind.AUTO_DUBBED,
                isDrc = parsedXtags.isDrc || stream.content.contains("drc%3D1", ignoreCase = true) || stream.content.contains("drc=1", ignoreCase = true),
            )
            return enrichWithHints(baseCandidate, hints)
        }

        private val qualityComparator: Comparator<AudioCandidate> =
            compareBy<AudioCandidate> { it.formatRank }
                .thenBy { (it.effectiveBitrateBps + 4_000) / 16_000 }
                .thenBy { !it.isDrc }
                .thenBy { it.averageBitrate }
                .thenBy { it.bitrate }
                .thenBy { it.sampleRateHz }

        internal fun extractUrlQueryParam(url: String, key: String): String? = runCatching {
            val query = URI(url).rawQuery ?: return null
            query.split('&').firstNotNullOfOrNull { pair ->
                val eq = pair.indexOf('=')
                if (eq > 0 && pair.substring(0, eq).equals(key, ignoreCase = true)) {
                    URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                } else {
                    null
                }
            }
        }.getOrNull()

        private fun inferCodec(mimeType: String, itag: Int): String = when {
            itag in setOf(139, 140, 141, 599) || mimeType.contains("mp4") -> "AAC"
            itag in setOf(249, 250, 251, 600) || mimeType.contains("opus") || mimeType.contains("webm") -> "Opus"
            mimeType.contains("ogg") -> "Vorbis/Opus"
            mimeType.contains("mpeg") || mimeType.contains("mp3") -> "MP3"
            else -> mimeType.substringAfter('/', "Audio").uppercase(Locale.US)
        }

        private fun mimeRank(mimeType: String, itag: Int): Int = when {
            itag == 140 || itag == 141 || mimeType == "audio/mp4" -> 4
            itag == 251 || mimeType == "audio/webm" || mimeType == "audio/opus" -> 3
            mimeType.startsWith("audio/ogg") -> 2
            mimeType.startsWith("audio/") -> 1
            else -> 0
        }

        private fun formatRank(format: MediaFormat?): Int =
            when (format) {
                MediaFormat.M4A -> 4
                MediaFormat.WEBMA_OPUS,
                MediaFormat.OPUS,
                -> 3
                MediaFormat.WEBMA,
                MediaFormat.OGG,
                -> 2
                MediaFormat.MP3,
                MediaFormat.MP2,
                -> 1
                else -> 0
            }

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}

private class OkHttpDownloader(
    private val client: OkHttpClient,
) : Downloader() {
    override fun execute(request: Request): Response {
        val method = request.httpMethod().uppercase(Locale.US)
        val body = request.dataToSend()?.toRequestBody(
            request.headers()["Content-Type"]?.firstOrNull()?.toMediaTypeOrNull(),
        )
        val builder = okhttp3.Request.Builder().url(request.url())
        request.headers().forEach { (name, values) ->
            values.forEach { value -> builder.addHeader(name, value) }
        }
        if (builder.build().header("User-Agent") == null) {
            builder.header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            )
        }
        when (method) {
            "HEAD" -> builder.head()
            "POST" -> builder.post(body ?: ByteArray(0).toRequestBody(null))
            else -> builder.get()
        }
        client.newCall(builder.build()).execute().use { response ->
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string().orEmpty(),
                response.request.url.toString(),
            )
        }
    }
}

private fun Throwable.toResolutionException(): YouTubeAudioResolutionException = when (this) {
    is YouTubeAudioResolutionException -> this
    is AgeRestrictedContentException -> YouTubeAudioResolutionException.AgeRestricted(
        "This YouTube video is age-restricted and cannot be streamed in Podcasty.",
        this,
    )
    is GeographicRestrictionException -> YouTubeAudioResolutionException.Unavailable(
        "This YouTube video is not available in your region.",
        this,
    )
    is PrivateContentException -> YouTubeAudioResolutionException.Unavailable(
        "This YouTube video is private.",
        this,
    )
    is PaidContentException -> YouTubeAudioResolutionException.Unavailable(
        "This YouTube video requires a paid membership.",
        this,
    )
    is ContentNotAvailableException -> YouTubeAudioResolutionException.Unavailable(
        message ?: "This YouTube video is unavailable.",
        this,
    )
    is ReCaptchaException -> YouTubeAudioResolutionException.Unavailable(
        "YouTube requested a verification challenge. Open the video in YouTube instead.",
        this,
    )
    is IOException -> YouTubeAudioResolutionException.Network(this)
    is ExtractionException -> YouTubeAudioResolutionException.Extraction(
        message ?: "YouTube audio extraction failed.",
        this,
    )
    else -> YouTubeAudioResolutionException.Extraction(
        message ?: "Unable to resolve YouTube audio.",
        this,
    )
}
