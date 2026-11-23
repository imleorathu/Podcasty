/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.podcast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.podcasty.app.R
import com.podcasty.app.audio.AudioFormatCatalog
import com.podcasty.app.audio.AudioFormatCategory
import com.podcasty.app.audio.FormatSupportStatus
import com.podcasty.app.domain.Episode
import com.podcasty.app.ui.components.ActionListDivider
import com.podcasty.app.ui.components.ActionListDividerStyle
import com.podcasty.app.ui.components.LabeledCard
import com.podcasty.app.ui.components.PodcastyBottomSheet
import com.podcasty.app.ui.components.PodcastyCard
import com.podcasty.app.ui.components.PodcastyPillButton
import com.podcasty.app.ui.components.PodcastyPillButtonVariant
import com.podcasty.app.ui.components.PodcastyTextField
import com.podcasty.app.ui.components.PodcastyTextFieldSize
import com.podcasty.app.ui.components.TextHelper
import com.podcasty.app.ui.theme.LocalPodcastyColors

@Composable
fun ImportYouTubeOrRssBottomSheet(
    isWorking: Boolean,
    errorMessage: String?,
    onImportYouTube: (youtubeUrl: String, linkedAudioUrl: String?) -> Unit,
    onImportRssFeed: (rssUrl: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalPodcastyColors.current
    var youtubeUrl by remember { mutableStateOf("") }
    var linkedAudioUrl by remember { mutableStateOf("") }
    var rssUrl by remember { mutableStateOf("") }

    PodcastyBottomSheet(
        title = {
            Text(
                stringResource(R.string.import_podcast_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textMain,
            )
        },
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.primary,
                )
            }

            // YouTube Podcast Link (Policy-compliant reference)
            Text(
                text = stringResource(R.string.youtube_import_section_title),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textMain,
            )
            PodcastyTextField(
                value = youtubeUrl,
                onValueChange = { youtubeUrl = it },
                placeholder = "https://www.youtube.com/watch?v=...",
                size = PodcastyTextFieldSize.Small,
                modifier = Modifier.fillMaxWidth(),
            )
            PodcastyTextField(
                value = linkedAudioUrl,
                onValueChange = { linkedAudioUrl = it },
                placeholder = stringResource(R.string.youtube_optional_rss_audio_placeholder),
                size = PodcastyTextFieldSize.Small,
                modifier = Modifier.fillMaxWidth(),
            )
            PodcastyPillButton(
                label = if (isWorking) stringResource(R.string.importing_progress)
                else stringResource(R.string.youtube_import_save_action),
                onClick = {
                    if (!isWorking && youtubeUrl.isNotBlank()) {
                        onImportYouTube(youtubeUrl.trim(), linkedAudioUrl.trim().takeIf { it.isNotEmpty() })
                    }
                },
                variant = PodcastyPillButtonVariant.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
            TextHelper(text = stringResource(R.string.youtube_compliance_note))

            ActionListDivider(style = ActionListDividerStyle.FullWidth)

            // Creator RSS Podcast Feed
            Text(
                text = stringResource(R.string.rss_import_section_title),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textMain,
            )
            PodcastyTextField(
                value = rssUrl,
                onValueChange = { rssUrl = it },
                placeholder = "https://example.com/podcast/feed.xml",
                size = PodcastyTextFieldSize.Small,
                modifier = Modifier.fillMaxWidth(),
            )
            PodcastyPillButton(
                label = if (isWorking) stringResource(R.string.importing_progress)
                else stringResource(R.string.rss_import_action),
                onClick = {
                    if (!isWorking && rssUrl.isNotBlank()) {
                        onImportRssFeed(rssUrl.trim())
                    }
                },
                variant = PodcastyPillButtonVariant.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun LinkEpisodeAudioBottomSheet(
    episode: Episode,
    onSaveAudioUrl: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalPodcastyColors.current
    var audioUrl by remember(episode.id) { mutableStateOf(episode.linkedAudioUri.orEmpty()) }
    PodcastyBottomSheet(
        title = {
            Text(
                stringResource(R.string.youtube_link_audio_source),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textMain,
            )
        },
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = episode.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textMain,
            )
            TextHelper(text = stringResource(R.string.youtube_link_audio_helper))
            PodcastyTextField(
                value = audioUrl,
                onValueChange = { audioUrl = it },
                placeholder = "https://.../episode.mp3",
                size = PodcastyTextFieldSize.Small,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                PodcastyPillButton(
                    label = stringResource(R.string.cancel),
                    onClick = onDismiss,
                    variant = PodcastyPillButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                )
                PodcastyPillButton(
                    label = stringResource(R.string.save),
                    onClick = {
                        onSaveAudioUrl(audioUrl.trim().takeIf { it.isNotEmpty() })
                        onDismiss()
                    },
                    variant = PodcastyPillButtonVariant.Primary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
fun SupportedFormatsScreen(
    modifier: Modifier = Modifier,
) {
    val colors = LocalPodcastyColors.current
    val matrix = remember { AudioFormatCatalog.buildCompatibilityMatrix() }
    val ffmpegVersion = remember { AudioFormatCatalog.ffmpegVersion() }
    val grouped = remember(matrix) { matrix.groupBy { it.category } }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PodcastyCard(contentPadding = PaddingValues(18.dp)) {
            Text(
                text = stringResource(R.string.supported_formats_decoder_summary),
                style = MaterialTheme.typography.labelMedium,
                color = colors.textMuted,
            )
            Text(
                text = if (ffmpegVersion != null) {
                    "Media3 + Android MediaCodec + FFmpeg ($ffmpegVersion)"
                } else {
                    "Media3 + Android MediaCodec"
                },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textMain,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        AudioFormatCategory.entries.forEach { category ->
            val entries = grouped[category].orEmpty()
            if (entries.isNotEmpty()) {
                LabeledCard(label = category.label) {
                    Column(Modifier.fillMaxWidth()) {
                        entries.forEachIndexed { idx, entry ->
                            val statusColor = when (entry.status) {
                                FormatSupportStatus.NativeDecoderSupported -> colors.success
                                FormatSupportStatus.SoftwareDecoderSupported -> colors.primary
                                FormatSupportStatus.RequiresFormatParameters -> colors.accentAmber
                                FormatSupportStatus.Experimental -> colors.accentAmber
                                FormatSupportStatus.Unsupported -> colors.textMuted
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = "${entry.name} (${entry.extensions.joinToString(", ")})",
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                        color = colors.textMain,
                                    )
                                    Text(
                                        text = entry.status.label,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = statusColor,
                                    )
                                }
                                Text(
                                    text = "${entry.container} · ${entry.codec} · ${entry.decoderBackend}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textMuted,
                                )
                                Text(
                                    text = entry.notes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textMuted,
                                )
                            }
                            if (idx < entries.lastIndex) {
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
fun YouTubeOAuthBottomSheet(
    configuredClientId: String,
    isSignedIn: Boolean,
    errorMessage: String?,
    onStartPkceSignIn: (clientId: String) -> Unit,
    onSaveSessionToken: (token: String) -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalPodcastyColors.current
    var clientId by remember(configuredClientId) { mutableStateOf(configuredClientId) }
    var manualToken by remember { mutableStateOf("") }

    PodcastyBottomSheet(
        title = {
            Text(
                stringResource(R.string.lyrics_stream_oauth_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textMain,
            )
        },
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextHelper(text = stringResource(R.string.lyrics_stream_oauth_helper))
            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.primary,
                )
            }
            if (isSignedIn) {
                Text(
                    text = stringResource(R.string.lyrics_stream_oauth_signed_in),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.success,
                )
                PodcastyPillButton(
                    label = stringResource(R.string.lyrics_stream_oauth_sign_out),
                    onClick = { onSignOut(); onDismiss() },
                    variant = PodcastyPillButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            PodcastyTextField(
                value = clientId,
                onValueChange = { clientId = it },
                placeholder = stringResource(R.string.lyrics_stream_oauth_client_id_placeholder),
                size = PodcastyTextFieldSize.Small,
                modifier = Modifier.fillMaxWidth(),
            )
            PodcastyPillButton(
                label = stringResource(R.string.lyrics_stream_oauth_sign_in),
                onClick = { if (clientId.isNotBlank()) onStartPkceSignIn(clientId.trim()) },
                variant = PodcastyPillButtonVariant.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            PodcastyTextField(
                value = manualToken,
                onValueChange = { manualToken = it },
                placeholder = stringResource(R.string.lyrics_stream_oauth_token_placeholder),
                size = PodcastyTextFieldSize.Small,
                modifier = Modifier.fillMaxWidth(),
            )
            PodcastyPillButton(
                label = stringResource(R.string.lyrics_stream_oauth_save_token),
                onClick = {
                    if (manualToken.isNotBlank()) {
                        onSaveSessionToken(manualToken.trim())
                        onDismiss()
                    }
                },
                variant = PodcastyPillButtonVariant.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
