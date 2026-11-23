/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.tags

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.podcasty.app.R
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.RawPcmConfig
import com.podcasty.app.tags.EditableAudioTags
import com.podcasty.app.ui.components.ActionList
import com.podcasty.app.ui.components.ActionListContainerStyle
import com.podcasty.app.ui.components.ActionListItem
import com.podcasty.app.ui.components.MaterialSymbol
import com.podcasty.app.ui.components.MaterialSymbols
import com.podcasty.app.ui.components.PodcastyBottomSheet
import com.podcasty.app.ui.components.PodcastyPillButton
import com.podcasty.app.ui.components.PodcastyPillButtonVariant
import com.podcasty.app.ui.components.PodcastyTextField
import com.podcasty.app.ui.components.PodcastyTextFieldSize
import com.podcasty.app.ui.components.RadioList
import com.podcasty.app.ui.components.SelectionOption
import com.podcasty.app.ui.components.TextHelper
import com.podcasty.app.ui.theme.LocalPodcastyColors

@Composable
fun TagEditorBottomSheet(
    targetEpisodes: List<Episode>,
    initialTags: EditableAudioTags,
    isSaving: Boolean,
    statusMessage: String?,
    onPickArtwork: () -> Unit,
    onSave: (EditableAudioTags) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalPodcastyColors.current
    val isBulk = targetEpisodes.size > 1
    var tags by remember(initialTags) { mutableStateOf(initialTags) }
    val artworkBitmap = remember(tags.artworkBytes, tags.removeArtwork) {
        val raw = tags.artworkBytes
        if (tags.removeArtwork || raw == null || raw.isEmpty()) null
        else runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 256 && bounds.outHeight / (sample * 2) >= 256) {
                sample *= 2
            }
            BitmapFactory.decodeByteArray(
                raw,
                0,
                raw.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                },
            )?.asImageBitmap()
        }.getOrNull()
    }

    PodcastyBottomSheet(
        title = {
            Text(
                text = if (isBulk) stringResource(R.string.tag_editor_bulk_title, targetEpisodes.size)
                else stringResource(R.string.tag_editor_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textMain,
            )
        },
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 580.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isBulk) {
                TextHelper(text = stringResource(R.string.tag_editor_bulk_helper))
            }
            if (!statusMessage.isNullOrBlank()) {
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.primary,
                )
            }

            // Artwork Preview & Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.glassElevated)
                        .border(1.dp, colors.borderGlass, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (artworkBitmap != null) {
                        Image(artworkBitmap, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                    } else {
                        MaterialSymbol(MaterialSymbols.Image, null, size = 30.dp, tint = colors.textMuted)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    PodcastyPillButton(
                        label = stringResource(R.string.tag_editor_replace_artwork),
                        onClick = onPickArtwork,
                        variant = PodcastyPillButtonVariant.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PodcastyPillButton(
                        label = stringResource(R.string.tag_editor_remove_artwork),
                        onClick = { tags = tags.copy(artworkBytes = null, removeArtwork = true, artworkModified = true) },
                        variant = PodcastyPillButtonVariant.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Basic Metadata
            TagSectionLabel(stringResource(R.string.tag_editor_basic_section))
            if (!isBulk) {
                TagField(stringResource(R.string.tag_field_title), tags.title) { tags = tags.copy(title = it) }
            }
            TagField(stringResource(R.string.tag_field_artist), tags.artist) { tags = tags.copy(artist = it) }
            TagField(stringResource(R.string.tag_field_album), tags.album) { tags = tags.copy(album = it) }
            TagField(stringResource(R.string.tag_field_album_artist), tags.albumArtist) { tags = tags.copy(albumArtist = it) }
            TagField(stringResource(R.string.tag_field_genre), tags.genre) { tags = tags.copy(genre = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TagField(stringResource(R.string.tag_field_year), tags.year, Modifier.weight(1f)) { tags = tags.copy(year = it) }
                if (!isBulk) {
                    TagField(stringResource(R.string.tag_field_track), tags.trackNumber, Modifier.weight(1f)) { tags = tags.copy(trackNumber = it) }
                    TagField(stringResource(R.string.tag_field_disc), tags.discNumber, Modifier.weight(1f)) { tags = tags.copy(discNumber = it) }
                }
            }
            TagField(stringResource(R.string.tag_field_composer), tags.composer) { tags = tags.copy(composer = it) }
            TagField(stringResource(R.string.tag_field_comment), tags.comment) { tags = tags.copy(comment = it) }

            // Advanced Metadata
            TagSectionLabel(stringResource(R.string.tag_editor_advanced_section))
            if (!isBulk) {
                TagField(stringResource(R.string.tag_field_lyrics), tags.lyrics) { tags = tags.copy(lyrics = it) }
            }
            TagField(stringResource(R.string.tag_field_copyright), tags.copyright) { tags = tags.copy(copyright = it) }
            TagField(stringResource(R.string.tag_field_publisher), tags.publisher) { tags = tags.copy(publisher = it) }
            if (!isBulk) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TagField(stringResource(R.string.tag_field_bpm), tags.bpm, Modifier.weight(1f)) { tags = tags.copy(bpm = it) }
                    TagField(stringResource(R.string.tag_field_encoder), tags.encoder, Modifier.weight(1f)) { tags = tags.copy(encoder = it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TagField(stringResource(R.string.tag_field_custom_key), tags.customKey, Modifier.weight(0.45f)) { tags = tags.copy(customKey = it) }
                    TagField(stringResource(R.string.tag_field_custom_value), tags.customValue, Modifier.weight(0.55f)) { tags = tags.copy(customValue = it) }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PodcastyPillButton(
                    label = stringResource(R.string.cancel),
                    onClick = onDismiss,
                    variant = PodcastyPillButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                )
                PodcastyPillButton(
                    label = if (isSaving) stringResource(R.string.tag_editor_saving) else stringResource(R.string.save),
                    onClick = { if (!isSaving) onSave(tags) },
                    variant = PodcastyPillButtonVariant.Primary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TagSectionLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = LocalPodcastyColors.current.textMuted,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun TagField(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onValueChange: (String) -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = LocalPodcastyColors.current.textMuted)
        PodcastyTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = label,
            size = PodcastyTextFieldSize.Small,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun RawPcmConfigBottomSheet(
    episode: Episode,
    sourceUri: String,
    onApplyAndPlay: (RawPcmConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalPodcastyColors.current
    var sampleRate by remember { mutableIntStateOf(44_100) }
    var bitDepth by remember { mutableIntStateOf(16) }
    var channels by remember { mutableIntStateOf(2) }
    var bigEndian by remember { mutableStateOf(false) }

    PodcastyBottomSheet(
        title = {
            Text(
                stringResource(R.string.raw_pcm_config_title),
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextHelper(text = stringResource(R.string.raw_pcm_config_helper, episode.title))

            TagSectionLabel(stringResource(R.string.raw_pcm_sample_rate))
            RadioList(
                options = listOf(16_000, 22_050, 44_100, 48_000, 96_000, 192_000).map {
                    SelectionOption(it, label = "$it Hz")
                },
                selectedValue = sampleRate,
                onValueSelected = { sampleRate = it },
            )

            TagSectionLabel(stringResource(R.string.raw_pcm_bit_depth))
            RadioList(
                options = listOf(8, 16, 24, 32).map { SelectionOption(it, label = "$it-bit PCM") },
                selectedValue = bitDepth,
                onValueSelected = { bitDepth = it },
            )

            TagSectionLabel(stringResource(R.string.raw_pcm_channels))
            RadioList(
                options = listOf(
                    SelectionOption(1, label = "Mono (1 ch)"),
                    SelectionOption(2, label = "Stereo (2 ch)"),
                ),
                selectedValue = channels,
                onValueSelected = { channels = it },
            )

            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.raw_pcm_big_endian,
                        trailingContent = { Switch(checked = bigEndian, onCheckedChange = { bigEndian = it }) },
                        onClick = { bigEndian = !bigEndian },
                    ),
                ),
                containerStyle = ActionListContainerStyle.Card,
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PodcastyPillButton(
                    label = stringResource(R.string.cancel),
                    onClick = onDismiss,
                    variant = PodcastyPillButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                )
                PodcastyPillButton(
                    label = stringResource(R.string.player_play),
                    onClick = {
                        onApplyAndPlay(
                            RawPcmConfig(
                                sourceUri = sourceUri,
                                sampleRateHz = sampleRate,
                                bitDepth = bitDepth,
                                channelCount = channels,
                                bigEndian = bigEndian,
                            ),
                        )
                        onDismiss()
                    },
                    variant = PodcastyPillButtonVariant.Primary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
