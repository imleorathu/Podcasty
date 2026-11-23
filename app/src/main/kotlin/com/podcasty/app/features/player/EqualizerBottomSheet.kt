/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.podcasty.app.R
import com.podcasty.app.audio.BuiltInEqualizerPresets
import com.podcasty.app.audio.EqualizerBandInfo
import com.podcasty.app.audio.EqualizerUiState
import com.podcasty.app.ui.components.ActionListDivider
import com.podcasty.app.ui.components.ActionListDividerStyle
import com.podcasty.app.ui.components.MaterialSymbol
import com.podcasty.app.ui.components.MaterialSymbols
import com.podcasty.app.ui.components.PodcastyBottomSheet
import com.podcasty.app.ui.components.PodcastyCard
import com.podcasty.app.ui.components.PodcastyDialog
import com.podcasty.app.ui.components.PodcastyPillButton
import com.podcasty.app.ui.components.PodcastyPillButtonVariant
import com.podcasty.app.ui.components.PodcastyTextField
import com.podcasty.app.ui.components.PodcastyTextFieldSize
import com.podcasty.app.ui.theme.LocalPodcastyColors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun EqualizerBottomSheet(
    state: EqualizerUiState,
    onEnabledChanged: (Boolean) -> Unit,
    onSelectPreset: (String) -> Unit,
    onBandLevelChanged: (Int, Int) -> Unit,
    onResetBand: (Int) -> Unit,
    onPreampChanged: (Float) -> Unit,
    onAutoClippingProtectionChanged: (Boolean) -> Unit,
    onBassBoostChanged: (Int) -> Unit,
    onVirtualizerChanged: (Int) -> Unit,
    onBalanceChanged: (Float) -> Unit,
    onMonoAudioChanged: (Boolean) -> Unit,
    onSaveCustomPreset: (String) -> Unit,
    onUpdateCustomPreset: (String) -> Unit,
    onRenameCustomPreset: (String, String) -> Unit,
    onDeleteCustomPreset: (String) -> Unit,
    onResetEqualizer: () -> Unit,
    onRestoreDefaults: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalPodcastyColors.current
    val caps = state.capabilities
    val selectedCustomPreset = remember(state.selectedPresetId, state.customPresets) {
        state.customPresets.firstOrNull { it.id == state.selectedPresetId }
    }

    var showSaveDialog by remember { mutableStateOf(false) }
    var savePresetName by remember { mutableStateOf("") }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renamePresetName by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf(false) }

    PodcastyBottomSheet(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MaterialSymbol(MaterialSymbols.GraphicEq, null, size = 20.dp, tint = colors.primary)
                Text(
                    text = stringResource(R.string.player_equalizer_audio),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 600.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. Master Enable/Disable & Hardware Capability Status
            PodcastyCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = stringResource(R.string.eq_master_enable),
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.textMain,
                        )
                        val subtitle = when {
                            !caps.isAttached && caps.unavailableReason != null -> caps.unavailableReason
                            !caps.isAttached -> stringResource(R.string.eq_waiting_for_session)
                            !state.enabled -> stringResource(R.string.eq_bypassed_status)
                            else -> buildString {
                                append("${caps.bandCount}-band")
                                caps.equalizerImplementor?.let { append(" · $it") }
                                if (caps.audioSessionId > 0) append(" · #${caps.audioSessionId}")
                            }
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.enabled && caps.isSupported) colors.primary else colors.textMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(
                        checked = state.enabled,
                        onCheckedChange = onEnabledChanged,
                    )
                }
            }

            // 2. Presets Selector (12 Built-in + User Custom Presets)
            PodcastyCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.eq_presets_title),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.textMain,
                        )
                        Text(
                            text = if (selectedCustomPreset != null) {
                                stringResource(R.string.eq_custom_badge)
                            } else {
                                stringResource(R.string.eq_builtin_badge)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textMuted,
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.allPresets.forEach { preset ->
                            val selected = preset.id == state.selectedPresetId
                            val shape = RoundedCornerShape(20.dp)
                            Box(
                                modifier = Modifier
                                    .clip(shape)
                                    .background(if (selected) colors.primary else colors.glassElevated)
                                    .border(
                                        width = 1.dp,
                                        color = if (selected) colors.primary else colors.borderGlass,
                                        shape = shape,
                                    )
                                    .clickable { onSelectPreset(preset.id) }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = preset.name,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    ),
                                    color = if (selected) colors.onPrimary else colors.textMain,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    // Preset management actions (Save, Update/Rename/Delete for custom, Reset, Restore)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EqCompactActionChip(
                            label = stringResource(R.string.eq_save_preset),
                            symbol = MaterialSymbols.Add,
                            onClick = {
                                savePresetName = ""
                                showSaveDialog = true
                            },
                        )
                        if (selectedCustomPreset != null) {
                            EqCompactActionChip(
                                label = stringResource(R.string.eq_update_preset),
                                symbol = MaterialSymbols.Check,
                                onClick = { onUpdateCustomPreset(selectedCustomPreset.id) },
                            )
                            EqCompactActionChip(
                                label = stringResource(R.string.eq_rename_preset),
                                symbol = MaterialSymbols.Edit,
                                onClick = {
                                    renamePresetName = selectedCustomPreset.name
                                    showRenameDialog = true
                                },
                            )
                            EqCompactActionChip(
                                label = stringResource(R.string.eq_delete_preset),
                                symbol = MaterialSymbols.Delete,
                                destructive = true,
                                onClick = { showDeleteConfirm = true },
                            )
                        }
                        EqCompactActionChip(
                            label = stringResource(R.string.eq_reset),
                            symbol = MaterialSymbols.Refresh,
                            onClick = onResetEqualizer,
                        )
                        if (state.customPresets.isNotEmpty()) {
                            EqCompactActionChip(
                                label = stringResource(R.string.eq_restore_defaults),
                                symbol = MaterialSymbols.Close,
                                onClick = { showRestoreConfirm = true },
                            )
                        }
                    }
                }
            }

            // 3. Dynamic Hardware Frequency Band Sliders
            PodcastyCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .alpha(if (state.enabled) 1f else 0.55f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.eq_bands_title),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.textMain,
                        )
                        if (caps.isSupported) {
                            Text(
                                text = "${formatSignedDb(caps.minGainDb)} .. ${formatSignedDb(caps.maxGainDb)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textMuted,
                            )
                        }
                    }

                    if (!caps.isSupported || caps.bands.isEmpty()) {
                        Text(
                            text = caps.unavailableReason ?: stringResource(R.string.eq_unavailable_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.eq_tap_db_to_reset_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted,
                        )
                        val minDb = caps.minGainDb
                        val maxDb = caps.maxGainDb
                        caps.bands.forEach { band ->
                            val currentMb = state.bandLevelsMb.getOrElse(band.index) { 0 }
                                .coerceIn(caps.minGainMb, caps.maxGainMb)
                            val currentDb = currentMb / 100f
                            EqualizerBandRow(
                                band = band,
                                gainDb = currentDb,
                                minDb = minDb,
                                maxDb = maxDb,
                                enabled = state.enabled,
                                onGainChanged = { newDb ->
                                    val newMb = (newDb * 100f).roundToInt().coerceIn(caps.minGainMb, caps.maxGainMb)
                                    onBandLevelChanged(band.index, newMb)
                                },
                                onReset = { onResetBand(band.index) },
                            )
                        }
                    }
                }
            }

            // 4. Preamp & Clipping Protection
            PodcastyCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .alpha(if (state.enabled) 1f else 0.55f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.eq_preamp_title),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.textMain,
                        )
                        Text(
                            text = formatSignedDb(state.preampDb),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (abs(state.preampDb) > 0.05f) colors.primary else colors.textMuted,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = state.enabled) { onPreampChanged(0f) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    Slider(
                        value = state.preampDb.coerceIn(-15f, 6f),
                        onValueChange = onPreampChanged,
                        valueRange = -15f..6f,
                        enabled = state.enabled,
                        colors = SliderDefaults.colors(
                            thumbColor = colors.primary,
                            activeTrackColor = colors.primary,
                            inactiveTrackColor = colors.borderGlass,
                        ),
                    )
                    ActionListDivider(style = ActionListDividerStyle.FullWidth)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = stringResource(R.string.eq_clipping_protection),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = colors.textMain,
                            )
                            val headroomText = if (state.effectiveHeadroomDb < -0.05f) {
                                stringResource(R.string.eq_headroom_active, formatSignedDb(state.effectiveHeadroomDb))
                            } else {
                                stringResource(R.string.eq_headroom_neutral)
                            }
                            Text(
                                text = headroomText,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textMuted,
                            )
                        }
                        Switch(
                            checked = state.autoClippingProtection,
                            onCheckedChange = onAutoClippingProtectionChanged,
                            enabled = state.enabled,
                        )
                    }
                }
            }

            // 5. Optional Audio Effects (only shown when technically supported)
            PodcastyCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .alpha(if (state.enabled) 1f else 0.55f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.eq_effects_title),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textMain,
                    )

                    if (caps.bassBoostSupported) {
                        LabeledEffectSlider(
                            label = stringResource(R.string.eq_bass_boost),
                            valueLabel = "${(state.bassBoostStrength / 10)}%",
                            value = state.bassBoostStrength.toFloat(),
                            range = 0f..1000f,
                            enabled = state.enabled,
                            onValueChange = { onBassBoostChanged(it.roundToInt()) },
                            onReset = { onBassBoostChanged(0) },
                        )
                    }

                    if (caps.virtualizerSupported) {
                        LabeledEffectSlider(
                            label = stringResource(R.string.eq_virtualizer),
                            valueLabel = "${(state.virtualizerStrength / 10)}%",
                            value = state.virtualizerStrength.toFloat(),
                            range = 0f..1000f,
                            enabled = state.enabled,
                            onValueChange = { onVirtualizerChanged(it.roundToInt()) },
                            onReset = { onVirtualizerChanged(0) },
                        )
                    }

                    val balanceText = when {
                        abs(state.balance) < 0.04f -> stringResource(R.string.eq_balance_center)
                        state.balance < 0f -> "L ${(abs(state.balance) * 100f).roundToInt()}%"
                        else -> "R ${(state.balance * 100f).roundToInt()}%"
                    }
                    LabeledEffectSlider(
                        label = stringResource(R.string.eq_channel_balance),
                        valueLabel = balanceText,
                        value = state.balance,
                        range = -1f..1f,
                        enabled = state.enabled,
                        onValueChange = onBalanceChanged,
                        onReset = { onBalanceChanged(0f) },
                    )

                    ActionListDivider(style = ActionListDividerStyle.FullWidth)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = stringResource(R.string.eq_mono_audio),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = colors.textMain,
                            )
                            Text(
                                text = stringResource(R.string.eq_mono_audio_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textMuted,
                            )
                        }
                        Switch(
                            checked = state.monoAudio,
                            onCheckedChange = onMonoAudioChanged,
                            enabled = state.enabled,
                        )
                    }
                }
            }

            // 6. Active Audio Pipeline Diagnostics (objective, honest readout)
            val diag = state.diagnostics
            if (diag.sampleRateHz > 0 || diag.audioSessionId > 0) {
                val parts = buildList {
                    diag.codecMimeType?.substringAfter('/')?.uppercase(Locale.US)?.let { add(it) }
                    if (diag.sampleRateHz > 0) {
                        val khz = diag.sampleRateHz / 1000.0
                        add(if (khz % 1.0 == 0.0) "${khz.toInt()} kHz" else String.format(Locale.US, "%.1f kHz", khz))
                    }
                    if (diag.channelCount > 0) add(if (diag.channelCount == 1) "Mono" else "${diag.channelCount}ch")
                    add(diag.encodingLabel)
                    add(if (diag.dspBypassed && !state.enabled) "DSP Bypassed" else "Active")
                }
                Text(
                    text = parts.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    // Save custom preset dialog
    if (showSaveDialog) {
        PodcastyDialog(
            title = stringResource(R.string.eq_save_preset_dialog_title),
            description = stringResource(R.string.eq_save_preset_dialog_desc),
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = { showSaveDialog = false },
            confirmLabel = stringResource(R.string.save),
            onConfirm = {
                if (savePresetName.isNotBlank()) {
                    onSaveCustomPreset(savePresetName)
                    showSaveDialog = false
                }
            },
            confirmVariant = PodcastyPillButtonVariant.Primary,
            content = {
                PodcastyTextField(
                    value = savePresetName,
                    onValueChange = { savePresetName = it.take(36) },
                    placeholder = stringResource(R.string.eq_preset_name_placeholder),
                    size = PodcastyTextFieldSize.Small,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }

    // Rename custom preset dialog
    if (showRenameDialog && selectedCustomPreset != null) {
        PodcastyDialog(
            title = stringResource(R.string.eq_rename_preset),
            description = stringResource(R.string.eq_rename_preset_desc),
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = { showRenameDialog = false },
            confirmLabel = stringResource(R.string.save),
            onConfirm = {
                if (renamePresetName.isNotBlank()) {
                    onRenameCustomPreset(selectedCustomPreset.id, renamePresetName)
                    showRenameDialog = false
                }
            },
            confirmVariant = PodcastyPillButtonVariant.Primary,
            content = {
                PodcastyTextField(
                    value = renamePresetName,
                    onValueChange = { renamePresetName = it.take(36) },
                    placeholder = stringResource(R.string.eq_preset_name_placeholder),
                    size = PodcastyTextFieldSize.Small,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }

    // Delete custom preset confirmation
    if (showDeleteConfirm && selectedCustomPreset != null) {
        PodcastyDialog(
            title = stringResource(R.string.eq_delete_preset),
            description = stringResource(R.string.eq_delete_preset_confirm, selectedCustomPreset.name),
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = { showDeleteConfirm = false },
            confirmLabel = stringResource(R.string.eq_delete_preset),
            onConfirm = {
                onDeleteCustomPreset(selectedCustomPreset.id)
                showDeleteConfirm = false
            },
            confirmVariant = PodcastyPillButtonVariant.Destructive,
        )
    }

    // Restore default presets confirmation
    if (showRestoreConfirm) {
        PodcastyDialog(
            title = stringResource(R.string.eq_restore_defaults),
            description = stringResource(R.string.eq_restore_defaults_confirm),
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = { showRestoreConfirm = false },
            confirmLabel = stringResource(R.string.eq_restore_defaults),
            onConfirm = {
                onRestoreDefaults()
                showRestoreConfirm = false
            },
            confirmVariant = PodcastyPillButtonVariant.Destructive,
        )
    }
}

@Composable
private fun EqualizerBandRow(
    band: EqualizerBandInfo,
    gainDb: Float,
    minDb: Float,
    maxDb: Float,
    enabled: Boolean,
    onGainChanged: (Float) -> Unit,
    onReset: () -> Unit,
) {
    val colors = LocalPodcastyColors.current
    val freqLabel = EqualizerBandInfo.formatFrequencyWithUnit(band.centerFreqHz)
    val dbLabel = formatSignedDb(gainDb)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = freqLabel,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textMain,
            maxLines = 1,
            modifier = Modifier.width(64.dp),
        )
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            // Subtle centered neutral 0 dB tick reference
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(12.dp)
                    .background(colors.borderGlass, RoundedCornerShape(1.dp)),
            )
            Slider(
                value = gainDb.coerceIn(minDb, maxDb),
                onValueChange = { raw ->
                    // Snap gently to 0.5 dB steps and exact 0.0 dB near center
                    val snapped = if (abs(raw) < 0.25f) 0f else (raw * 2f).roundToInt() / 2f
                    onGainChanged(snapped)
                },
                valueRange = minDb..maxDb,
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "$freqLabel $dbLabel" },
                colors = SliderDefaults.colors(
                    thumbColor = colors.primary,
                    activeTrackColor = colors.primary,
                    inactiveTrackColor = colors.borderGlass,
                ),
            )
        }
        Text(
            text = dbLabel,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (abs(gainDb) > 0.05f) FontWeight.Bold else FontWeight.Normal,
                fontSize = 11.sp,
            ),
            color = if (abs(gainDb) > 0.05f) colors.primary else colors.textMuted,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier
                .width(56.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = enabled, onClick = onReset)
                .padding(vertical = 6.dp, horizontal = 4.dp),
        )
    }
}

@Composable
private fun LabeledEffectSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onReset: () -> Unit,
) {
    val colors = LocalPodcastyColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = colors.textMain,
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = enabled, onClick = onReset)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = colors.primary,
                activeTrackColor = colors.primary,
                inactiveTrackColor = colors.borderGlass,
            ),
        )
    }
}

@Composable
private fun EqCompactActionChip(
    label: String,
    symbol: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalPodcastyColors.current
    val tint = if (destructive) colors.primary else colors.textMain
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(colors.glassElevated)
            .border(1.dp, colors.borderGlass, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        MaterialSymbol(symbol, null, size = 15.dp, tint = tint)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = tint,
            maxLines = 1,
        )
    }
}

private fun formatSignedDb(db: Float): String {
    val cleaned = if (abs(db) < 0.05f) 0f else db
    return if (cleaned > 0f) {
        String.format(Locale.US, "+%.1f dB", cleaned)
    } else {
        String.format(Locale.US, "%.1f dB", cleaned)
    }
}
