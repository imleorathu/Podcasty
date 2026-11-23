/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.addpodcast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.podcasty.app.asr.SecureCredentialStore
import com.podcasty.app.data.PodcastPreferencesRepository
import com.podcasty.app.domain.PodcastRepository
import com.podcasty.app.podcast.DetectedPodcastUrlType
import com.podcasty.app.podcast.ExtractedYouTubeTranscript
import com.podcasty.app.podcast.InspectedRemoteSource
import com.podcasty.app.podcast.OfficialRssDiscoveryResult
import com.podcasty.app.podcast.ParsedPodcastEpisode
import com.podcasty.app.podcast.ParsedPodcastFeed
import com.podcasty.app.podcast.PodcastUrlDetector
import com.podcasty.app.podcast.RssAndAtomFeedParser
import com.podcasty.app.podcast.RssPodcastDiscoveryService
import com.podcasty.app.podcast.YouTubeCaptionExtractionResult
import com.podcasty.app.podcast.YouTubeCaptionExtractorClient
import com.podcasty.app.podcast.YouTubeMetadataService
import com.podcasty.app.podcast.YouTubeMusicDownloadManager
import com.podcasty.app.podcast.YouTubeVideoMetadata
import com.podcasty.app.streaming.YouTubeAudioResolutionException
import com.podcasty.app.streaming.YouTubeAudioTrackOption
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface AddPodcastPreview {
    data class RssFeed(val feed: ParsedPodcastFeed) : AddPodcastPreview
    data class DirectAudio(
        val audioUrl: String,
        val title: String,
        val creator: String,
        val contentType: String?,
    ) : AddPodcastPreview
    data class YouTubeVideo(
        val metadata: YouTubeVideoMetadata,
        val isExtractingTranscript: Boolean = false,
        val extractedTranscript: ExtractedYouTubeTranscript? = null,
        val transcriptStatusMessage: String? = null,
        val isDownloadingAudio: Boolean = false,
        val downloadProgressFraction: Float = 0f,
        val downloadStatusMessage: String? = null,
        val downloadedAudioUri: String? = null,
        val selectedAudioTrackId: String? = null,
        val selectedAudioTrackSummary: String? = null,
        val availableAudioTracks: List<YouTubeAudioTrackOption> = emptyList(),
        val requiresAudioLanguageSelection: Boolean = false,
        val audioSelectionError: String? = null,
        val isDiscoveringRss: Boolean = false,
        val rssDiscovery: OfficialRssDiscoveryResult? = null,
        val rssStatusMessage: String? = null,
        val linkedAudioUrlInput: String = "",
    ) : AddPodcastPreview
}

data class AddPodcastUiState(
    val urlInput: String = "",
    val detectedType: DetectedPodcastUrlType? = null,
    val selectedCaptionLanguage: String = "en-US",
    val isInspecting: Boolean = false,
    val isSaving: Boolean = false,
    val preview: AddPodcastPreview? = null,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val lastImportedPodcastId: String? = null,
)

class AddPodcastViewModel(
    private val repository: PodcastRepository,
    private val preferences: PodcastPreferencesRepository,
    private val credentialStore: SecureCredentialStore,
    private val youTubeCaptionClient: YouTubeCaptionExtractorClient = YouTubeCaptionExtractorClient(),
    private val rssDiscoveryService: RssPodcastDiscoveryService = RssPodcastDiscoveryService(),
    private val youTubeDownloadManager: YouTubeMusicDownloadManager? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddPodcastUiState())
    val uiState: StateFlow<AddPodcastUiState> = _uiState.asStateFlow()

    private var currentBackendUrl: String = ""
    private var captionExtractJob: Job? = null
    private var audioDownloadJob: Job? = null
    private var rssDiscoveryJob: Job? = null
    private var autoInspectedYouTubeId: String? = null

    var onYouTubeReadyToPlayAudio: ((episodeId: String) -> Unit)? = null
    var onYouTubeTranscriptSynced: ((episodeId: String) -> Unit)? = null

    init {
        viewModelScope.launch {
            preferences.settings.collect { settings ->
                currentBackendUrl = settings.asrBackendUrl
                _uiState.update { it.copy(selectedCaptionLanguage = settings.asrLanguageCode) }
            }
        }
    }

    fun onUrlInputChange(value: String) {
        val detected = PodcastUrlDetector.detect(value).getOrNull()
        val quickDetected = detected?.type
        _uiState.update {
            it.copy(
                urlInput = value,
                detectedType = quickDetected,
                errorMessage = null,
                successMessage = null,
            )
        }
        // Automatically inspect, play audio in the music player, and extract synced captions in parallel
        // as soon as the user pastes a complete YouTube link!
        val pastedVideoId = detected?.youtubeVideoId
        if (detected?.type == DetectedPodcastUrlType.YouTube &&
            pastedVideoId != null &&
            pastedVideoId != autoInspectedYouTubeId
        ) {
            autoInspectedYouTubeId = pastedVideoId
            inspectUrl(autoPlayYouTubeOnLoad = true)
        }
    }

    fun onYouTubeLinkedAudioUrlChange(value: String) {
        _uiState.update { state ->
            val yt = state.preview as? AddPodcastPreview.YouTubeVideo ?: return@update state
            state.copy(
                preview = yt.copy(linkedAudioUrlInput = value),
                errorMessage = null,
            )
        }
    }

    fun onCaptionLanguageSelected(languageCode: String) {
        _uiState.update { it.copy(selectedCaptionLanguage = languageCode) }
        viewModelScope.launch { preferences.setAsrLanguageCode(languageCode) }
        val ytPreview = _uiState.value.preview as? AddPodcastPreview.YouTubeVideo
        if (ytPreview != null) {
            triggerAutoYouTubeCaptionExtraction(ytPreview.metadata.videoId, languageCode)
        }
    }

    fun onYouTubeAudioTrackSelected(trackId: String) {
        val ytPreview = _uiState.value.preview as? AddPodcastPreview.YouTubeVideo ?: return
        val episodeId = "yt_ep_${ytPreview.metadata.videoId}"
        val lang = _uiState.value.selectedCaptionLanguage
        _uiState.update { state ->
            val yt = state.preview as? AddPodcastPreview.YouTubeVideo ?: return@update state
            state.copy(
                errorMessage = null,
                preview = yt.copy(
                    selectedAudioTrackId = trackId,
                    requiresAudioLanguageSelection = false,
                    audioSelectionError = null,
                    isDownloadingAudio = true,
                    downloadProgressFraction = 0.05f,
                    downloadStatusMessage = "Downloading selected audio track ($trackId)…",
                ),
            )
        }
        triggerAutoYouTubeAudioDownloadAndEmbed(
            meta = ytPreview.metadata,
            languageCode = lang,
            autoPlayWhenReady = false,
            episodeId = episodeId,
            preferredAudioTrackId = trackId,
        )
    }

    fun loadDiscoveredRssFeedUrl(feedUrl: String) {
        onUrlInputChange(feedUrl)
        inspectUrl()
    }

    fun inspectUrl(autoPlayYouTubeOnLoad: Boolean = true) {
        val raw = _uiState.value.urlInput.trim()
        val detectedResult = PodcastUrlDetector.detect(raw)
        if (detectedResult.isFailure) {
            _uiState.update {
                it.copy(
                    isInspecting = false,
                    preview = null,
                    errorMessage = detectedResult.exceptionOrNull()?.localizedMessage ?: "Enter a valid URL.",
                    successMessage = null,
                )
            }
            return
        }
        val detected = detectedResult.getOrThrow()
        captionExtractJob?.cancel()
        audioDownloadJob?.cancel()
        rssDiscoveryJob?.cancel()
        _uiState.update {
            it.copy(
                detectedType = detected.type,
                isInspecting = true,
                preview = null,
                errorMessage = null,
                successMessage = null,
                lastImportedPodcastId = null,
            )
        }

        viewModelScope.launch {
            when (detected.type) {
                DetectedPodcastUrlType.YouTube -> {
                    val videoId = requireNotNull(detected.youtubeVideoId)
                    autoInspectedYouTubeId = videoId
                    val metaResult = YouTubeMetadataService.fetchMetadata(
                        videoId = videoId,
                        isShorts = detected.isYouTubeShorts,
                        optionalDataApiKey = credentialStore.getYouTubeApiKey(),
                    )
                    metaResult.onSuccess { meta ->
                        val lang = _uiState.value.selectedCaptionLanguage
                        val (podcastId, episodeId) = repository.saveYouTubeVideoImport(
                            metadata = meta,
                            preferredCaptionLanguage = lang,
                            linkedAudioUri = null,
                            extractedTranscript = null,
                        )
                        _uiState.update {
                            it.copy(
                                isInspecting = false,
                                lastImportedPodcastId = podcastId,
                                preview = AddPodcastPreview.YouTubeVideo(
                                    metadata = meta,
                                    isExtractingTranscript = true,
                                    transcriptStatusMessage = "Extracting autogenerated captions ($lang)…",
                                    isDownloadingAudio = youTubeDownloadManager != null,
                                    downloadProgressFraction = 0.05f,
                                    downloadStatusMessage = "Downloading YouTube video as audio file & embedding captions…",
                                    isDiscoveringRss = true,
                                    rssStatusMessage = "Searching official public RSS podcast feed for native audio…",
                                ),
                                successMessage = "Downloading \"${meta.title}\" as audio file & embedding synced captions…",
                                errorMessage = null,
                            )
                        }
                        // Start Native Android YouTube Caption Extractor + Automatic YouTube Audio File Download + Embed + Auto-Play
                        triggerAutoYouTubeCaptionExtraction(meta.videoId, lang)
                        triggerAutoYouTubeAudioDownloadAndEmbed(meta, lang, autoPlayWhenReady = autoPlayYouTubeOnLoad, episodeId = episodeId)
                        triggerOfficialRssDiscovery(meta)
                    }.onFailure { err ->
                        _uiState.update {
                            it.copy(
                                isInspecting = false,
                                errorMessage = err.localizedMessage ?: "Could not load YouTube metadata.",
                            )
                        }
                    }
                }

                DetectedPodcastUrlType.DirectAudio -> {
                    val title = RssAndAtomFeedParser.deriveTitleFromAudioUrl(detected.normalizedUrl)
                    val host = runCatching {
                        java.net.URI(detected.normalizedUrl).host.orEmpty().removePrefix("www.")
                    }.getOrDefault("Online Podcast")
                    _uiState.update {
                        it.copy(
                            isInspecting = false,
                            preview = AddPodcastPreview.DirectAudio(
                                audioUrl = detected.normalizedUrl,
                                title = title,
                                creator = host.ifBlank { "Online Podcast" },
                                contentType = null,
                            ),
                            errorMessage = null,
                        )
                    }
                }

                DetectedPodcastUrlType.RssOrAtomFeed -> {
                    val inspected = RssAndAtomFeedParser.fetchAndInspect(detected.normalizedUrl)
                    inspected.onSuccess { source ->
                        val preview = when (source) {
                            is InspectedRemoteSource.RssOrAtom -> AddPodcastPreview.RssFeed(source.feed)
                            is InspectedRemoteSource.DirectAudio -> AddPodcastPreview.DirectAudio(
                                audioUrl = source.audioUrl,
                                title = source.title,
                                creator = source.creator,
                                contentType = source.contentType,
                            )
                        }
                        _uiState.update {
                            it.copy(
                                isInspecting = false,
                                detectedType = if (source is InspectedRemoteSource.DirectAudio) {
                                    DetectedPodcastUrlType.DirectAudio
                                } else {
                                    DetectedPodcastUrlType.RssOrAtomFeed
                                },
                                preview = preview,
                                errorMessage = null,
                            )
                        }
                    }.onFailure { err ->
                        _uiState.update {
                            it.copy(
                                isInspecting = false,
                                preview = null,
                                errorMessage = err.localizedMessage ?: "Could not load RSS/Atom feed.",
                            )
                        }
                    }
                }
            }
        }
    }

    private fun triggerOfficialRssDiscovery(meta: YouTubeVideoMetadata) {
        rssDiscoveryJob?.cancel()
        rssDiscoveryJob = viewModelScope.launch {
            val discovered = rssDiscoveryService.discoverOfficialRssForYouTubeVideo(
                videoTitle = meta.title,
                channelName = meta.channelName,
            )
            _uiState.update { state ->
                val yt = state.preview as? AddPodcastPreview.YouTubeVideo ?: return@update state
                if (yt.metadata.videoId != meta.videoId) return@update state
                val updated = when (discovered) {
                    is OfficialRssDiscoveryResult.EpisodeAudioMatched -> yt.copy(
                        isDiscoveringRss = false,
                        rssDiscovery = discovered,
                        linkedAudioUrlInput = yt.linkedAudioUrlInput.ifBlank { discovered.audioUrl },
                        rssStatusMessage = "Official RSS audio matched (${discovered.podcastTitle}) · Ready to play natively",
                    )
                    is OfficialRssDiscoveryResult.ShowFeedFound -> yt.copy(
                        isDiscoveringRss = false,
                        rssDiscovery = discovered,
                        rssStatusMessage = "Official RSS feed found (${discovered.podcastTitle} · ${discovered.episodeCount} episodes)",
                    )
                    OfficialRssDiscoveryResult.NotFound -> yt.copy(
                        isDiscoveringRss = false,
                        rssDiscovery = discovered,
                        rssStatusMessage = "No public RSS feed found for this YouTube video. Paste an authorized MP3/RSS audio URL below or open in YouTube.",
                    )
                }
                state.copy(preview = updated)
            }
        }
    }

    private fun triggerAutoYouTubeAudioDownloadAndEmbed(
        meta: YouTubeVideoMetadata,
        languageCode: String,
        autoPlayWhenReady: Boolean,
        episodeId: String,
        preferredAudioTrackId: String? = null,
    ) {
        val manager = youTubeDownloadManager
        if (manager == null) {
            if (autoPlayWhenReady) {
                onYouTubeReadyToPlayAudio?.invoke(episodeId)
            }
            return
        }
        audioDownloadJob?.cancel()
        audioDownloadJob = viewModelScope.launch {
            val currentPreview = _uiState.value.preview as? AddPodcastPreview.YouTubeVideo
            val downloadResult = manager.downloadAudioAndEmbedCaptions(
                metadata = meta,
                preferredLanguage = languageCode,
                preferredAudioTrackId = preferredAudioTrackId,
                configuredBackendUrl = currentBackendUrl,
                authToken = credentialStore.getAsrAuthToken(),
                existingTranscript = currentPreview?.extractedTranscript,
            ) { progress ->
                _uiState.update { state ->
                    val yt = state.preview as? AddPodcastPreview.YouTubeVideo ?: return@update state
                    if (yt.metadata.videoId != meta.videoId) return@update state
                    state.copy(
                        preview = yt.copy(
                            isDownloadingAudio = true,
                            downloadProgressFraction = progress.fraction,
                            downloadStatusMessage = progress.stage,
                        ),
                    )
                }
            }

            downloadResult.onSuccess { bundle ->
                val resolvedMeta = if (bundle.durationMs > 0L && meta.durationMs <= 0L) {
                    meta.copy(durationMs = bundle.durationMs)
                } else {
                    meta
                }
                val latestPreview = _uiState.value.preview as? AddPodcastPreview.YouTubeVideo
                val resolvedTranscript = bundle.extractedTranscript ?: latestPreview?.extractedTranscript
                val containerLabel = when {
                    bundle.mimeType.contains("mp4", ignoreCase = true) -> "M4A"
                    bundle.mimeType.contains("webm", ignoreCase = true) -> "WebM"
                    bundle.mimeType.contains("ogg", ignoreCase = true) -> "OGG"
                    else -> bundle.audioFile.extension.uppercase()
                }
                val sampleRateKhz = if (bundle.sampleRateHz > 0) "%.1f kHz".format(bundle.sampleRateHz / 1000f) else ""
                val trackLabel = bundle.audioTrackDisplayName
                    ?: bundle.audioLanguageCode?.uppercase()
                    ?: if (bundle.isOriginalAudio) "Original" else "Audio"
                val trackSummary = buildString {
                    append(trackLabel)
                    if (bundle.isOriginalAudio && !trackLabel.contains("original", ignoreCase = true)) {
                        append(" (Original)")
                    }
                    if (!bundle.audioLanguageCode.isNullOrBlank()) {
                        append(" · ${bundle.audioLanguageCode}")
                    }
                    append(" · ${bundle.codec.ifBlank { containerLabel }}")
                    if (bundle.bitrateKbps > 0) append(" ${bundle.bitrateKbps} kbps")
                    if (sampleRateKhz.isNotBlank()) append(" · $sampleRateKhz")
                }
                val (podcastId, savedEpId) = repository.saveYouTubeVideoImport(
                    metadata = resolvedMeta,
                    preferredCaptionLanguage = resolvedTranscript?.language ?: languageCode,
                    linkedAudioUri = bundle.audioFileUri,
                    extractedTranscript = resolvedTranscript,
                    isDownloadedFile = true,
                    downloadedFileSizeBytes = bundle.audioFile.length(),
                    localArtworkPath = bundle.artworkFile?.absolutePath,
                    bitrateKbps = bundle.bitrateKbps,
                    audioContainerFormat = containerLabel,
                    audioCodecLabel = listOf(
                        bundle.codec.ifBlank { containerLabel },
                        sampleRateKhz,
                        bundle.audioLanguageCode?.uppercase(),
                    ).filter { !it.isNullOrBlank() }.joinToString(" · "),
                    audioLanguageCode = bundle.audioLanguageCode,
                )
                val cueCount = resolvedTranscript?.cues?.size ?: 0
                val embedMsg = if (cueCount > 0) {
                    "Downloaded $trackSummary with $cueCount embedded synced caption lines"
                } else {
                    "Downloaded $trackSummary"
                }
                _uiState.update { state ->
                    val yt = state.preview as? AddPodcastPreview.YouTubeVideo
                    val updatedPreview = if (yt != null && yt.metadata.videoId == meta.videoId) {
                        yt.copy(
                            metadata = resolvedMeta,
                            isDownloadingAudio = false,
                            downloadProgressFraction = 1f,
                            downloadStatusMessage = embedMsg,
                            downloadedAudioUri = bundle.audioFileUri,
                            selectedAudioTrackId = bundle.audioTrackId ?: preferredAudioTrackId,
                            selectedAudioTrackSummary = trackSummary,
                            availableAudioTracks = bundle.availableTracks,
                            requiresAudioLanguageSelection = false,
                            audioSelectionError = null,
                            linkedAudioUrlInput = bundle.audioFileUri,
                            isExtractingTranscript = false,
                            extractedTranscript = resolvedTranscript ?: yt.extractedTranscript,
                            transcriptStatusMessage = if (cueCount > 0) {
                                "Embedded $cueCount synced caption lines (${resolvedTranscript?.language?.uppercase() ?: languageCode.uppercase()}) into audio file"
                            } else {
                                yt.transcriptStatusMessage
                            },
                        )
                    } else {
                        state.preview
                    }
                    state.copy(
                        lastImportedPodcastId = podcastId,
                        preview = updatedPreview,
                        errorMessage = null,
                        successMessage = "$embedMsg · Saved to Podcasty library.",
                    )
                }
                if (autoPlayWhenReady) {
                    onYouTubeReadyToPlayAudio?.invoke(savedEpId)
                }
                onYouTubeTranscriptSynced?.invoke(savedEpId)
            }.onFailure { err ->
                when (err) {
                    is YouTubeAudioResolutionException.LanguageSelectionRequired -> {
                        _uiState.update { state ->
                            val yt = state.preview as? AddPodcastPreview.YouTubeVideo ?: return@update state
                            if (yt.metadata.videoId != meta.videoId) return@update state
                            state.copy(
                                errorMessage = null,
                                successMessage = null,
                                preview = yt.copy(
                                    isDownloadingAudio = false,
                                    availableAudioTracks = err.availableTracks,
                                    requiresAudioLanguageSelection = true,
                                    audioSelectionError = err.message,
                                    downloadStatusMessage = err.message,
                                ),
                            )
                        }
                    }
                    is YouTubeAudioResolutionException.OriginalAudioUnavailable -> {
                        // Never pretend an auto-dubbed track is the original audio or silently stream it
                        _uiState.update { state ->
                            val yt = state.preview as? AddPodcastPreview.YouTubeVideo ?: return@update state
                            if (yt.metadata.videoId != meta.videoId) return@update state
                            state.copy(
                                errorMessage = err.message,
                                successMessage = null,
                                preview = yt.copy(
                                    isDownloadingAudio = false,
                                    availableAudioTracks = err.availableTracks,
                                    requiresAudioLanguageSelection = err.availableTracks.isNotEmpty(),
                                    audioSelectionError = err.message,
                                    downloadStatusMessage = err.message,
                                ),
                            )
                        }
                    }
                    else -> {
                        // Fallback to direct audio streaming only for non-language-ambiguity failures
                        _uiState.update { state ->
                            val yt = state.preview as? AddPodcastPreview.YouTubeVideo ?: return@update state
                            if (yt.metadata.videoId != meta.videoId) return@update state
                            state.copy(
                                preview = yt.copy(
                                    isDownloadingAudio = false,
                                    downloadStatusMessage = "Audio file download unavailable (${err.localizedMessage ?: "streaming instead"}).",
                                ),
                            )
                        }
                        if (autoPlayWhenReady) {
                            onYouTubeReadyToPlayAudio?.invoke(episodeId)
                        }
                    }
                }
            }
        }
    }

    private fun triggerAutoYouTubeCaptionExtraction(videoId: String, languageCode: String) {
        captionExtractJob?.cancel()
        _uiState.update { state ->
            val yt = state.preview as? AddPodcastPreview.YouTubeVideo ?: return@update state
            if (yt.metadata.videoId != videoId) return@update state
            state.copy(
                preview = yt.copy(
                    isExtractingTranscript = true,
                    extractedTranscript = null,
                    transcriptStatusMessage = "Extracting YouTube transcript ($languageCode)…",
                ),
            )
        }
        captionExtractJob = viewModelScope.launch {
            val result = youTubeCaptionClient.extractCaptions(
                videoId = videoId,
                languageCode = languageCode,
                configuredBackendUrl = currentBackendUrl,
                authToken = credentialStore.getAsrAuthToken(),
            )
            _uiState.update { state ->
                val yt = state.preview as? AddPodcastPreview.YouTubeVideo ?: return@update state
                if (yt.metadata.videoId != videoId) return@update state
                val updated = when (result) {
                    is YouTubeCaptionExtractionResult.Success -> {
                        val tr = result.transcript
                        val updatedMeta = if (yt.metadata.title.startsWith("YouTube Video (") && tr.title.isNotBlank()) {
                            yt.metadata.copy(title = tr.title, description = tr.description.ifBlank { yt.metadata.description })
                        } else {
                            yt.metadata
                        }
                        val episodeId = "yt_ep_$videoId"
                        runCatching {
                            val existingLink = yt.downloadedAudioUri
                                ?: yt.linkedAudioUrlInput.trim().takeIf { it.isNotEmpty() }
                            repository.saveYouTubeVideoImport(
                                metadata = updatedMeta,
                                preferredCaptionLanguage = tr.language,
                                linkedAudioUri = existingLink,
                                extractedTranscript = tr,
                                isDownloadedFile = yt.downloadedAudioUri != null,
                            )
                            onYouTubeTranscriptSynced?.invoke(episodeId)
                        }
                        yt.copy(
                            metadata = updatedMeta,
                            isExtractingTranscript = false,
                            extractedTranscript = tr,
                            transcriptStatusMessage = "Transcript synced · ${tr.cues.size} lines (${tr.language.uppercase()})",
                        )
                    }
                    is YouTubeCaptionExtractionResult.BackendNotConfigured -> yt.copy(
                        isExtractingTranscript = false,
                        extractedTranscript = null,
                        transcriptStatusMessage = result.message,
                    )
                    is YouTubeCaptionExtractionResult.Unavailable -> yt.copy(
                        isExtractingTranscript = false,
                        extractedTranscript = null,
                        transcriptStatusMessage = result.message,
                    )
                    is YouTubeCaptionExtractionResult.Error -> yt.copy(
                        isExtractingTranscript = false,
                        extractedTranscript = null,
                        transcriptStatusMessage = result.message,
                    )
                }
                state.copy(preview = updated)
            }
        }
    }

    fun addPreviewToLibrary(
        onRssImported: (podcastId: String) -> Unit = {},
        onDirectAudioImported: (podcastId: String, episodeId: String) -> Unit = { _, _ -> },
        onYouTubeSaved: (podcastId: String, episodeId: String) -> Unit = { _, _ -> },
    ) {
        val preview = _uiState.value.preview ?: return
        val lang = _uiState.value.selectedCaptionLanguage
        _uiState.update { it.copy(isSaving = true, errorMessage = null, successMessage = null) }
        viewModelScope.launch {
            runCatching {
                when (preview) {
                    is AddPodcastPreview.RssFeed -> {
                        val podcastId = repository.saveParsedPodcastFeed(preview.feed, preferredCaptionLanguage = lang)
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                lastImportedPodcastId = podcastId,
                                successMessage = "Added \"${preview.feed.title}\" (${preview.feed.episodes.size} episodes) to your library.",
                            )
                        }
                        onRssImported(podcastId)
                    }

                    is AddPodcastPreview.DirectAudio -> {
                        val (podcastId, episodeId) = repository.saveDirectAudioPodcast(
                            audioUrl = preview.audioUrl,
                            title = preview.title,
                            creator = preview.creator,
                            preferredCaptionLanguage = lang,
                        )
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                lastImportedPodcastId = podcastId,
                                successMessage = "Added \"${preview.title}\" to your library.",
                            )
                        }
                        onDirectAudioImported(podcastId, episodeId)
                    }

                    is AddPodcastPreview.YouTubeVideo -> {
                        val latestPreview = (_uiState.value.preview as? AddPodcastPreview.YouTubeVideo) ?: preview
                        val rawLinkedAudio = latestPreview.downloadedAudioUri
                            ?: latestPreview.linkedAudioUrlInput.trim().takeIf { it.isNotEmpty() }
                        val validatedLinkedAudio = if (rawLinkedAudio != null && !rawLinkedAudio.startsWith("file://")) {
                            val detected = PodcastUrlDetector.detect(rawLinkedAudio).getOrElse { err ->
                                throw IllegalArgumentException(
                                    err.localizedMessage ?: "Enter a valid HTTPS audio stream URL.",
                                )
                            }
                            if (detected.type == DetectedPodcastUrlType.YouTube) {
                                throw IllegalArgumentException(
                                    "Linked audio must be a direct creator audio URL (MP3/M4A/AAC), not another YouTube URL.",
                                )
                            }
                            detected.normalizedUrl
                        } else {
                            rawLinkedAudio
                        }

                        val (podcastId, episodeId) = repository.saveYouTubeVideoImport(
                            metadata = latestPreview.metadata,
                            preferredCaptionLanguage = lang,
                            linkedAudioUri = validatedLinkedAudio,
                            extractedTranscript = latestPreview.extractedTranscript,
                            isDownloadedFile = validatedLinkedAudio?.startsWith("file://") == true,
                        )
                        val cueCount = latestPreview.extractedTranscript?.cues?.size ?: 0
                        val transcriptNote = if (cueCount > 0) " with $cueCount embedded synced transcript lines" else " (downloading & embedding captions…)"
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                lastImportedPodcastId = podcastId,
                                successMessage = "Playing \"${latestPreview.metadata.title}\"$transcriptNote in Podcasty player.",
                            )
                        }
                        if (validatedLinkedAudio == null && youTubeDownloadManager != null && audioDownloadJob?.isActive != true) {
                            triggerAutoYouTubeAudioDownloadAndEmbed(
                                meta = latestPreview.metadata,
                                languageCode = lang,
                                autoPlayWhenReady = true,
                                episodeId = episodeId,
                            )
                        } else {
                            onYouTubeSaved(podcastId, episodeId)
                        }
                        if (latestPreview.extractedTranscript == null && captionExtractJob?.isActive != true) {
                            triggerAutoYouTubeCaptionExtraction(latestPreview.metadata.videoId, lang)
                        }
                    }
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = err.localizedMessage ?: "Failed to save podcast to library.",
                    )
                }
            }
        }
    }

    /**
     * Imports the RSS feed (if not already saved) and immediately starts streaming the chosen
     * episode through Podcasty's existing native Media3 player (triggering real-time AI captions).
     */
    fun importAndPlayRssEpisode(
        feed: ParsedPodcastFeed,
        episode: ParsedPodcastEpisode,
        onReadyToPlay: (episodeId: String, allEpisodeIds: List<String>) -> Unit,
    ) {
        val lang = _uiState.value.selectedCaptionLanguage
        _uiState.update { it.copy(isSaving = true, errorMessage = null) }
        viewModelScope.launch {
            runCatching {
                val podcastId = repository.saveParsedPodcastFeed(feed, preferredCaptionLanguage = lang)
                val targetEpId = "rss_ep_${shortHash("$podcastId::${episode.guid}")}"
                val allIds = feed.episodes.map { ep -> "rss_ep_${shortHash("$podcastId::${ep.guid}")}" }
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        lastImportedPodcastId = podcastId,
                        successMessage = "Streaming \"${episode.title}\" in Podcasty player.",
                    )
                }
                onReadyToPlay(targetEpId, allIds)
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = err.localizedMessage ?: "Could not start podcast playback.",
                    )
                }
            }
        }
    }

    fun deleteImportedPodcast(podcastId: String) {
        viewModelScope.launch {
            repository.deleteImportedPodcast(podcastId)
            _uiState.update { state ->
                if (state.lastImportedPodcastId == podcastId) {
                    state.copy(lastImportedPodcastId = null, successMessage = "Removed podcast and caption history.")
                } else {
                    state.copy(successMessage = "Removed podcast and caption history.")
                }
            }
        }
    }

    private fun shortHash(input: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }

    class Factory(
        private val repository: PodcastRepository,
        private val preferences: PodcastPreferencesRepository,
        private val credentialStore: SecureCredentialStore,
        private val youTubeCaptionClient: YouTubeCaptionExtractorClient,
        private val rssDiscoveryService: RssPodcastDiscoveryService,
        private val youTubeDownloadManager: YouTubeMusicDownloadManager? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AddPodcastViewModel(
                repository,
                preferences,
                credentialStore,
                youTubeCaptionClient,
                rssDiscoveryService,
                youTubeDownloadManager,
            ) as T
    }
}
