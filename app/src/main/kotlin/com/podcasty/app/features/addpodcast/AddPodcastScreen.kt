/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.addpodcast

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.podcasty.app.R
import com.podcasty.app.asr.SupportedAsrLanguages
import com.podcasty.app.core.formatCompactDuration
import com.podcasty.app.core.formatShortDate
import com.podcasty.app.domain.MediaSourceType
import com.podcasty.app.domain.Podcast
import com.podcasty.app.podcast.DetectedPodcastUrlType
import com.podcasty.app.podcast.OfficialRssDiscoveryResult
import com.podcasty.app.podcast.ParsedPodcastEpisode
import com.podcasty.app.podcast.ParsedPodcastFeed
import com.podcasty.app.ui.components.ActionListDivider
import com.podcasty.app.ui.components.ActionListDividerStyle
import com.podcasty.app.ui.components.LabeledCard
import com.podcasty.app.ui.components.MaterialSymbol
import com.podcasty.app.ui.components.MaterialSymbols
import com.podcasty.app.ui.components.PodcastEpisodeRow
import com.podcasty.app.ui.components.PodcastyCard
import com.podcasty.app.ui.components.PodcastyPillButton
import com.podcasty.app.ui.components.PodcastyPillButtonVariant
import com.podcasty.app.ui.components.PodcastyTextField
import com.podcasty.app.ui.components.PodcastyTextFieldSize
import com.podcasty.app.ui.components.TextHelper
import com.podcasty.app.ui.components.rememberArtworkThumbnail
import com.podcasty.app.ui.theme.LocalPodcastyColors

@Composable
fun AddPodcastScreen(
    uiState: AddPodcastUiState,
    importedPodcasts: List<Podcast>,
    currentEpisodeId: String?,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onUrlChange: (String) -> Unit,
    onYouTubeLinkedAudioUrlChange: (String) -> Unit,
    onLoadDiscoveredRssFeed: (String) -> Unit,
    onInspectUrl: () -> Unit,
    onCaptionLanguageSelected: (String) -> Unit,
    onYouTubeAudioTrackSelected: (String) -> Unit = {},
    onAddPreviewToLibrary: () -> Unit,
    onPlayRssEpisode: (ParsedPodcastFeed, ParsedPodcastEpisode) -> Unit,
    onPlayDirectAudio: () -> Unit,
    onOpenYouTubeUrl: (String) -> Unit,
    onOpenPodcastDetails: (String) -> Unit,
    onDeleteImportedPodcast: (String) -> Unit,
    onOpenCaptionSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPodcastyColors.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    fun triggerInspect() {
        focusManager.clearFocus()
        keyboardController?.hide()
        onInspectUrl()
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 1. Unified single URL input card (auto-detects YouTube, RSS 2.0/Atom, or direct audio)
        item(key = "add-podcast-url-input") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PodcastyCard(contentPadding = PaddingValues(18.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = stringResource(R.string.add_podcast_input_heading),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.textMain,
                        )
                        PodcastyTextField(
                            value = uiState.urlInput,
                            onValueChange = onUrlChange,
                            placeholder = stringResource(R.string.add_podcast_url_placeholder),
                            size = PodcastyTextFieldSize.Medium,
                            onDone = { triggerInspect() },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // Detected provider pill (automatic, no manual selector needed)
                        uiState.detectedType?.let { detected ->
                            val badgeText = when (detected) {
                                DetectedPodcastUrlType.YouTube -> stringResource(R.string.add_podcast_detected_youtube)
                                DetectedPodcastUrlType.RssOrAtomFeed -> stringResource(R.string.add_podcast_detected_rss)
                                DetectedPodcastUrlType.DirectAudio -> stringResource(R.string.add_podcast_detected_direct_audio)
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                MaterialSymbol(
                                    symbol = when (detected) {
                                        DetectedPodcastUrlType.YouTube -> MaterialSymbols.PlayCircle
                                        DetectedPodcastUrlType.RssOrAtomFeed -> MaterialSymbols.Podcasts
                                        DetectedPodcastUrlType.DirectAudio -> MaterialSymbols.GraphicEq
                                    },
                                    size = 16.dp,
                                    tint = colors.primary,
                                )
                                Text(
                                    text = badgeText,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = colors.primary,
                                )
                            }
                        }

                        // Real-time AI caption language preference chips (English / Tamil)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = stringResource(R.string.add_podcast_caption_language_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.textMuted,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                SupportedAsrLanguages.forEach { lang ->
                                    val selected = lang.code.equals(uiState.selectedCaptionLanguage, ignoreCase = true)
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (selected) colors.primary else colors.buttonSecondary)
                                            .clickable(role = Role.Button) { onCaptionLanguageSelected(lang.code) }
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                    ) {
                                        Text(
                                            text = lang.displayName,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = if (selected) colors.onPrimary else colors.textMain,
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PodcastyPillButton(
                                label = if (uiState.isInspecting) {
                                    stringResource(R.string.add_podcast_inspecting)
                                } else {
                                    stringResource(R.string.add_podcast_inspect_action)
                                },
                                onClick = { triggerInspect() },
                                enabled = uiState.urlInput.isNotBlank() && !uiState.isInspecting && !uiState.isSaving,
                                variant = PodcastyPillButtonVariant.Primary,
                                modifier = Modifier.weight(1f),
                            )
                            if (uiState.isInspecting || uiState.isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                    color = colors.primary,
                                )
                            }
                        }
                    }
                }
                TextHelper(text = stringResource(R.string.add_podcast_input_helper))
            }
        }

        // Error or success banner
        if (!uiState.errorMessage.isNullOrBlank()) {
            item(key = "add-podcast-error") {
                PodcastyCard(contentPadding = PaddingValues(16.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MaterialSymbol(symbol = MaterialSymbols.Info, size = 20.dp, tint = colors.primary)
                        Text(
                            text = uiState.errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textMain,
                        )
                    }
                }
            }
        }

        if (!uiState.successMessage.isNullOrBlank()) {
            item(key = "add-podcast-success") {
                PodcastyCard(contentPadding = PaddingValues(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MaterialSymbol(symbol = MaterialSymbols.CheckCircle, size = 20.dp, tint = colors.success)
                            Text(
                                text = uiState.successMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textMain,
                            )
                        }
                        uiState.lastImportedPodcastId?.let { importedId ->
                            PodcastyPillButton(
                                label = stringResource(R.string.add_podcast_open_in_library),
                                onClick = { onOpenPodcastDetails(importedId) },
                                variant = PodcastyPillButtonVariant.Secondary,
                            )
                        }
                    }
                }
            }
        }

        // 2. Discovered Metadata Preview Card
        when (val preview = uiState.preview) {
            is AddPodcastPreview.RssFeed -> {
                item(key = "preview-rss-header") {
                    PodcastMetadataPreviewCard(
                        artworkUrl = preview.feed.artworkUrl,
                        badge = stringResource(R.string.add_podcast_badge_rss, preview.feed.episodes.size),
                        title = preview.feed.title,
                        creator = listOf(preview.feed.author, preview.feed.publisher)
                            .filter { it.isNotBlank() }
                            .distinct()
                            .joinToString(" · "),
                        subtitle = preview.feed.description.take(240),
                        primaryLabel = stringResource(R.string.add_podcast_add_to_library),
                        primaryEnabled = !uiState.isSaving,
                        onPrimaryClick = onAddPreviewToLibrary,
                        secondaryLabel = preview.feed.episodes.firstOrNull()?.let {
                            stringResource(R.string.add_podcast_play_latest)
                        },
                        onSecondaryClick = preview.feed.episodes.firstOrNull()?.let { latest ->
                            { onPlayRssEpisode(preview.feed, latest) }
                        },
                    )
                }

                item(key = "preview-rss-episodes-heading") {
                    Text(
                        text = stringResource(R.string.add_podcast_episodes_heading, preview.feed.episodes.size),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textMain,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                itemsIndexed(
                    items = preview.feed.episodes.take(40),
                    key = { idx, ep -> "${ep.guid}_$idx" },
                ) { index, ep ->
                    val durationStr = if (ep.durationMs > 0L) formatCompactDuration(ep.durationMs) else "Stream"
                    val dateStr = if (ep.publishedAtMs > 0L) formatShortDate(ep.publishedAtMs) else ""
                    val sub = listOf(dateStr, durationStr).filter { it.isNotBlank() }.joinToString(" · ")
                    PodcastyCard(contentPadding = PaddingValues(0.dp)) {
                        PodcastEpisodeRow(
                            number = index + 1,
                            title = ep.title,
                            subtitle = sub,
                            isCurrentPlaying = currentEpisodeId != null && currentEpisodeId.contains(ep.guid.hashCode().toString()),
                            onClick = { onPlayRssEpisode(preview.feed, ep) },
                            onMoreClick = { onPlayRssEpisode(preview.feed, ep) },
                            onLongClick = { onPlayRssEpisode(preview.feed, ep) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            is AddPodcastPreview.DirectAudio -> {
                item(key = "preview-direct-audio") {
                    PodcastMetadataPreviewCard(
                        artworkUrl = null,
                        badge = stringResource(R.string.add_podcast_badge_direct_audio),
                        title = preview.title,
                        creator = preview.creator,
                        subtitle = preview.audioUrl,
                        primaryLabel = stringResource(R.string.add_podcast_play_now),
                        primaryEnabled = !uiState.isSaving,
                        onPrimaryClick = onPlayDirectAudio,
                        secondaryLabel = stringResource(R.string.add_podcast_add_to_library),
                        onSecondaryClick = onAddPreviewToLibrary,
                    )
                }
            }

            is AddPodcastPreview.YouTubeVideo -> {
                item(key = "preview-youtube") {
                    val meta = preview.metadata
                    val durationLabel = if (meta.durationMs > 0L) {
                        " · ${formatCompactDuration(meta.durationMs)}"
                    } else {
                        ""
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PodcastMetadataPreviewCard(
                            artworkUrl = meta.thumbnailUrl,
                            badge = if (meta.isShorts) {
                                "YouTube Shorts · ${meta.metadataSource}"
                            } else {
                                "YouTube Video · ${meta.metadataSource}"
                            },
                            title = meta.title,
                            creator = "${meta.channelName}$durationLabel",
                            subtitle = "Video ID: ${meta.videoId}",
                            primaryLabel = when {
                                preview.isDownloadingAudio -> "Downloading Audio & Captions…"
                                preview.downloadedAudioUri != null -> "Play Downloaded Audio"
                                else -> stringResource(R.string.add_podcast_add_and_play)
                            },
                            primaryEnabled = !uiState.isSaving && !preview.isDownloadingAudio,
                            onPrimaryClick = onAddPreviewToLibrary,
                            secondaryLabel = stringResource(R.string.youtube_open_official),
                            onSecondaryClick = { onOpenYouTubeUrl(meta.canonicalUrl) },
                            extraContent = {
                                // Automatic YouTube Audio File Download & Caption Embedding progress ("Youtube Music Download" flow)
                                if (preview.isDownloadingAudio || !preview.downloadStatusMessage.isNullOrBlank()) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            if (preview.isDownloadingAudio) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(16.dp),
                                                    strokeWidth = 2.dp,
                                                    color = colors.primary,
                                                )
                                            } else {
                                                MaterialSymbol(
                                                    symbol = if (preview.downloadedAudioUri != null) {
                                                        MaterialSymbols.CheckCircle
                                                    } else {
                                                        MaterialSymbols.Info
                                                    },
                                                    size = 18.dp,
                                                    tint = if (preview.downloadedAudioUri != null) {
                                                        colors.success
                                                    } else {
                                                        colors.textMuted
                                                    },
                                                )
                                            }
                                            Text(
                                                text = preview.downloadStatusMessage.orEmpty(),
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontWeight = FontWeight.Medium,
                                                ),
                                                color = if (preview.downloadedAudioUri != null || preview.isDownloadingAudio) {
                                                    colors.textMain
                                                } else {
                                                    colors.textMuted
                                                },
                                            )
                                        }
                                        if (preview.isDownloadingAudio) {
                                            LinearProgressIndicator(
                                                progress = { preview.downloadProgressFraction.coerceIn(0f, 1f) },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(4.dp)
                                                    .clip(RoundedCornerShape(999.dp)),
                                                color = colors.primary,
                                                trackColor = colors.borderGlass,
                                            )
                                        }
                                        if (preview.availableAudioTracks.size > 1 || preview.requiresAudioLanguageSelection) {
                                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(
                                                    text = if (preview.requiresAudioLanguageSelection) {
                                                        "Select audio track language"
                                                    } else {
                                                        "Available audio tracks"
                                                    },
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                                    color = colors.textMuted,
                                                )
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .horizontalScroll(rememberScrollState()),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                ) {
                                                    preview.availableAudioTracks.forEach { track ->
                                                        val isSelected = preview.selectedAudioTrackId != null &&
                                                            track.trackId.equals(preview.selectedAudioTrackId, ignoreCase = true)
                                                        Box(
                                                            modifier = Modifier
                                                                .clip(RoundedCornerShape(10.dp))
                                                                .background(if (isSelected) colors.primary else colors.buttonSecondary)
                                                                .clickable(
                                                                    enabled = !preview.isDownloadingAudio,
                                                                    role = Role.Button,
                                                                ) {
                                                                    onYouTubeAudioTrackSelected(track.trackId)
                                                                }
                                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                                        ) {
                                                            Text(
                                                                text = "${track.displayName} · ${track.bestBitrateKbps}k",
                                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                                                color = if (isSelected) colors.onPrimary else colors.textMain,
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                // Automatic YouTube transcript extraction status (youtube-caption-extractor)
                                if (preview.isExtractingTranscript || !preview.transcriptStatusMessage.isNullOrBlank()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (preview.isExtractingTranscript) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                                color = colors.primary,
                                            )
                                        } else {
                                            MaterialSymbol(
                                                symbol = if (preview.extractedTranscript != null) {
                                                    MaterialSymbols.CheckCircle
                                                } else {
                                                    MaterialSymbols.Info
                                                },
                                                size = 18.dp,
                                                tint = if (preview.extractedTranscript != null) {
                                                    colors.success
                                                } else {
                                                    colors.textMuted
                                                },
                                            )
                                        }
                                        Text(
                                            text = preview.transcriptStatusMessage.orEmpty(),
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontWeight = if (preview.extractedTranscript != null) {
                                                    FontWeight.Medium
                                                } else {
                                                    FontWeight.Normal
                                                },
                                            ),
                                            color = if (preview.extractedTranscript != null) {
                                                colors.textMain
                                            } else {
                                                colors.textMuted
                                            },
                                        )
                                    }
                                }
                                // Official RSS podcast audio auto-discovery (Apple Podcasts / iTunes Directory)
                                if (preview.isDiscoveringRss || !preview.rssStatusMessage.isNullOrBlank()) {
                                    val matchedAudio = preview.rssDiscovery is OfficialRssDiscoveryResult.EpisodeAudioMatched
                                    val showFound = preview.rssDiscovery as? OfficialRssDiscoveryResult.ShowFeedFound
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (preview.isDiscoveringRss) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                                color = colors.primary,
                                            )
                                        } else {
                                            MaterialSymbol(
                                                symbol = if (matchedAudio || showFound != null) {
                                                    MaterialSymbols.CheckCircle
                                                } else {
                                                    MaterialSymbols.Info
                                                },
                                                size = 18.dp,
                                                tint = if (matchedAudio || showFound != null) {
                                                    colors.success
                                                } else {
                                                    colors.textMuted
                                                },
                                            )
                                        }
                                        Text(
                                            text = preview.rssStatusMessage.orEmpty(),
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontWeight = if (matchedAudio) FontWeight.Medium else FontWeight.Normal,
                                            ),
                                            color = if (matchedAudio) colors.textMain else colors.textMuted,
                                        )
                                    }
                                    if (showFound != null) {
                                        PodcastyPillButton(
                                            label = "Load ${showFound.podcastTitle} RSS Feed (${showFound.episodeCount} eps)",
                                            onClick = { onLoadDiscoveredRssFeed(showFound.feedUrl) },
                                            variant = PodcastyPillButtonVariant.Secondary,
                                        )
                                    }
                                }
                                // Optional authorized podcast audio URL (hidden once a local YouTube audio file is downloaded)
                                if (preview.downloadedAudioUri == null) {
                                    PodcastyTextField(
                                        value = preview.linkedAudioUrlInput,
                                        onValueChange = onYouTubeLinkedAudioUrlChange,
                                        placeholder = stringResource(R.string.add_podcast_youtube_linked_audio_placeholder),
                                        size = PodcastyTextFieldSize.Small,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            },
                        )
                        PodcastyCard(contentPadding = PaddingValues(16.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                MaterialSymbol(symbol = MaterialSymbols.Info, size = 20.dp, tint = colors.primary)
                                Text(
                                    text = stringResource(R.string.add_podcast_youtube_audio_notice),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textMuted,
                                )
                            }
                        }
                    }
                }
            }

            null -> Unit
        }

        // 3. Real-time AI captions quick link
        item(key = "add-podcast-asr-info") {
            PodcastyCard(contentPadding = PaddingValues(18.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.add_podcast_live_captions_card_title),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textMain,
                    )
                    Text(
                        text = stringResource(R.string.add_podcast_live_captions_card_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                    PodcastyPillButton(
                        label = stringResource(R.string.add_podcast_configure_asr),
                        onClick = onOpenCaptionSettings,
                        variant = PodcastyPillButtonVariant.Secondary,
                    )
                }
            }
        }

        // 4. Imported Podcasts & YouTube References Library Management (with removal)
        if (importedPodcasts.isNotEmpty()) {
            item(key = "imported-podcasts-card") {
                LabeledCard(label = stringResource(R.string.add_podcast_imported_heading)) {
                    Column(Modifier.fillMaxWidth()) {
                        importedPodcasts.forEachIndexed { idx, show ->
                            val isYouTube = show.sourceType == MediaSourceType.YouTubeReference
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onOpenPodcastDetails(show.id)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SmallArtworkThumb(
                                    artworkPath = show.artworkAsset,
                                    fallbackSymbol = if (isYouTube) MaterialSymbols.PlayCircle else MaterialSymbols.Podcasts,
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = show.title,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                        color = colors.textMain,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = if (isYouTube) {
                                            "YouTube Audio · Synced Captions (${show.preferredCaptionLanguage.uppercase()})"
                                        } else {
                                            "${show.hostName} · RSS · ${show.preferredCaptionLanguage}"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(colors.buttonSecondary)
                                        .clickable(role = Role.Button) { onDeleteImportedPodcast(show.id) }
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.remove_label),
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = colors.textMain,
                                    )
                                }
                            }
                            if (idx < importedPodcasts.lastIndex) {
                                ActionListDivider(style = ActionListDividerStyle.FullWidth)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PodcastMetadataPreviewCard(
    artworkUrl: String?,
    badge: String,
    title: String,
    creator: String,
    subtitle: String,
    primaryLabel: String,
    primaryEnabled: Boolean,
    onPrimaryClick: () -> Unit,
    secondaryLabel: String? = null,
    onSecondaryClick: (() -> Unit)? = null,
    extraContent: (@Composable () -> Unit)? = null,
) {
    val colors = LocalPodcastyColors.current
    val thumb = rememberArtworkThumbnail(artworkUrl, targetPx = 240)
    PodcastyCard(contentPadding = PaddingValues(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.buttonSecondary)
                        .border(1.dp, colors.borderGlass, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (thumb != null) {
                        Image(
                            bitmap = thumb,
                            contentDescription = title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        MaterialSymbol(symbol = MaterialSymbols.Podcasts, size = 34.dp, tint = colors.textMuted)
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = colors.textMain,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (creator.isNotBlank()) {
                        Text(
                            text = creator,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            extraContent?.invoke()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PodcastyPillButton(
                    label = primaryLabel,
                    onClick = onPrimaryClick,
                    enabled = primaryEnabled,
                    variant = PodcastyPillButtonVariant.Primary,
                    modifier = Modifier.weight(1f),
                )
                if (secondaryLabel != null && onSecondaryClick != null) {
                    PodcastyPillButton(
                        label = secondaryLabel,
                        onClick = onSecondaryClick,
                        enabled = primaryEnabled,
                        variant = PodcastyPillButtonVariant.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallArtworkThumb(
    artworkPath: String?,
    fallbackSymbol: String,
) {
    val colors = LocalPodcastyColors.current
    val thumb = rememberArtworkThumbnail(artworkPath, targetPx = 120)
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.buttonSecondary),
        contentAlignment = Alignment.Center,
    ) {
        if (thumb != null) {
            Image(
                bitmap = thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            MaterialSymbol(symbol = fallbackSymbol, size = 22.dp, tint = colors.textMuted)
        }
    }
}
