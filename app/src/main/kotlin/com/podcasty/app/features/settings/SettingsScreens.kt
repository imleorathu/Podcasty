/*
 * Podcasty — Android Podcast & Music Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import com.podcasty.app.ui.components.MaterialSymbol
import com.podcasty.app.ui.components.PodcastyBottomSheet
import com.podcasty.app.data.DefaultPlayerSecondaryControls
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import com.podcasty.app.BuildConfig
import com.podcasty.app.R
import com.podcasty.app.asr.AsrEngineType
import com.podcasty.app.asr.SupportedAsrLanguages
import com.podcasty.app.core.formatFileSize
import com.podcasty.app.core.formatSpeedLabel
import com.podcasty.app.data.PodcastUserSettings
import com.podcasty.app.domain.MusicFolder
import com.podcasty.app.domain.SupportedCaptionFontScales
import com.podcasty.app.domain.SupportedFadeOutSeconds
import com.podcasty.app.domain.SupportedPlaybackSpeeds
import com.podcasty.app.domain.SupportedSkipBackwardSeconds
import com.podcasty.app.domain.SupportedSkipForwardSeconds
import com.podcasty.app.domain.SupportedTranscriptLanguageOptions
import com.podcasty.app.domain.SupportedTranscriptLanguages
import com.podcasty.app.storage.LibraryScanState
import com.podcasty.app.storage.UsbVolumeSummary
import com.podcasty.app.ui.components.ActionList
import com.podcasty.app.ui.components.ActionListContainerStyle
import com.podcasty.app.ui.components.ActionListDivider
import com.podcasty.app.ui.components.ActionListDividerStyle
import com.podcasty.app.ui.components.ActionListItem
import com.podcasty.app.ui.components.HeroCard
import com.podcasty.app.ui.components.LabeledCard
import com.podcasty.app.ui.components.MaterialSymbols
import com.podcasty.app.ui.components.PodcastyCard
import com.podcasty.app.ui.components.PodcastyPillButton
import com.podcasty.app.ui.components.PodcastyPillButtonVariant
import com.podcasty.app.ui.components.PodcastyTextField
import com.podcasty.app.ui.components.PodcastyTextFieldSize
import com.podcasty.app.ui.components.RadioList
import com.podcasty.app.ui.components.Selection
import com.podcasty.app.ui.components.SelectionOption
import com.podcasty.app.ui.components.TextHelper
import com.podcasty.app.ui.theme.LocalPodcastyColors
import com.podcasty.app.ui.theme.ThemeMode
import kotlin.math.abs

@Composable
fun SettingsScreen(
    onAppearanceSelected: () -> Unit,
    onMusicLibrarySelected: () -> Unit,
    onLyricsCaptionsSelected: () -> Unit,
    onPlaybackSelected: () -> Unit,
    onBluetoothSelected: () -> Unit,
    onDownloadsSelected: () -> Unit,
    onAboutSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        ActionList(
            items = listOf(
                ActionListItem(R.string.settings_appearance, leadingSymbol = MaterialSymbols.Palette, onClick = onAppearanceSelected),
                ActionListItem(R.string.settings_music_library, leadingSymbol = MaterialSymbols.QueueMusic, onClick = onMusicLibrarySelected),
                ActionListItem(R.string.settings_lyrics_captions, leadingSymbol = MaterialSymbols.Chat, onClick = onLyricsCaptionsSelected),
                ActionListItem(R.string.settings_playback, leadingSymbol = MaterialSymbols.Subwoofer, onClick = onPlaybackSelected),
                ActionListItem(R.string.settings_bluetooth, leadingSymbol = MaterialSymbols.Headphones, onClick = onBluetoothSelected),
                ActionListItem(R.string.settings_downloads, leadingSymbol = MaterialSymbols.Storage, onClick = onDownloadsSelected),
                ActionListItem(R.string.settings_about, leadingSymbol = MaterialSymbols.Info, onClick = onAboutSelected),
            ),
            containerStyle = ActionListContainerStyle.Card,
        )
    }
}

@Composable
fun MusicLibrarySettingsScreen(
    settings: PodcastUserSettings,
    folders: List<MusicFolder>,
    scanState: LibraryScanState,
    onSelectDefaultFolder: () -> Unit,
    onSelectUsbOtgFolder: () -> Unit,
    onScanFoldersNow: () -> Unit,
    onScanMediaStore: () -> Unit,
    onAutoLibraryRefreshChanged: (Boolean) -> Unit,
    onIncludeSubfoldersChanged: (Boolean) -> Unit,
    onSupportedFormatsSelected: () -> Unit,
    onImportYouTubeOrRss: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    onToggleFolderEnabled: (String, Boolean) -> Unit = { _, _ -> },
    onRefreshFolder: (String) -> Unit = {},
    onExportPlaylists: () -> Unit = {},
    onRestorePlaylists: () -> Unit = {},
    onFindDuplicates: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalPodcastyColors.current
    val defaultFolderText = settings.defaultMusicFolderName
        ?: folders.firstOrNull { it.isDefault }?.displayName
        ?: stringResource(R.string.music_folder_none_selected)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionList(
            items = listOf(
                ActionListItem(
                    labelRes = R.string.music_library_default_folder,
                    leadingSymbol = MaterialSymbols.Storage,
                    trailingContent = {
                        Text(
                            text = defaultFolderText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = onSelectDefaultFolder,
                ),
                ActionListItem(
                    labelRes = R.string.music_library_select_folder,
                    leadingSymbol = MaterialSymbols.Add,
                    onClick = onSelectDefaultFolder,
                ),
                ActionListItem(
                    labelRes = R.string.music_library_add_usb_folder,
                    leadingSymbol = MaterialSymbols.Sensors,
                    onClick = onSelectUsbOtgFolder,
                ),
                ActionListItem(
                    labelRes = if (scanState.isScanning) R.string.music_library_scanning else R.string.music_library_scan_folder,
                    leadingSymbol = MaterialSymbols.Refresh,
                    onClick = { if (!scanState.isScanning) onScanFoldersNow() },
                ),
                ActionListItem(
                    labelRes = R.string.music_library_scan_mediastore,
                    leadingSymbol = MaterialSymbols.Search,
                    onClick = { if (!scanState.isScanning) onScanMediaStore() },
                ),
                ActionListItem(
                    labelRes = R.string.music_library_auto_refresh,
                    trailingContent = {
                        Switch(checked = settings.autoLibraryRefresh, onCheckedChange = onAutoLibraryRefreshChanged)
                    },
                    onClick = { onAutoLibraryRefreshChanged(!settings.autoLibraryRefresh) },
                ),
                ActionListItem(
                    labelRes = R.string.music_library_include_subfolders,
                    trailingContent = {
                        Switch(checked = settings.includeSubfolders, onCheckedChange = onIncludeSubfoldersChanged)
                    },
                    onClick = { onIncludeSubfoldersChanged(!settings.includeSubfolders) },
                ),
                ActionListItem(
                    labelRes = R.string.music_library_supported_formats,
                    leadingSymbol = MaterialSymbols.GraphicEq,
                    onClick = onSupportedFormatsSelected,
                ),
                ActionListItem(
                    labelRes = R.string.import_podcast_sheet_title,
                    leadingSymbol = MaterialSymbols.Podcasts,
                    onClick = onImportYouTubeOrRss,
                ),
            ),
            containerStyle = ActionListContainerStyle.Card,
            dividerStyle = ActionListDividerStyle.FullWidth,
        )

        val statusText = scanState.lastError ?: scanState.lastMessage
        if (!statusText.isNullOrBlank()) {
            TextHelper(text = statusText)
        }

        if (folders.isNotEmpty()) {
            LabeledCard(label = stringResource(R.string.music_library_connected_folders)) {
                Column(Modifier.fillMaxWidth()) {
                    folders.forEachIndexed { idx, folder ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = folder.displayName,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                        color = colors.textMain,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Switch(
                                        checked = folder.isEnabled,
                                        onCheckedChange = { enabled -> onToggleFolderEnabled(folder.treeUri, enabled) },
                                    )
                                }
                                val statusBadge = when {
                                    !folder.isEnabled -> "Disabled"
                                    !folder.isAvailable -> "Unavailable"
                                    folder.initialIndexingCompleted -> "Indexed"
                                    else -> "Initial index pending"
                                }
                                Text(
                                    text = "${folder.storageType.label} · ${folder.trackCount} tracks · $statusBadge",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (folder.isAvailable) colors.textMuted else colors.primary,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(Modifier.weight(1f)) {
                                    PodcastyPillButton(
                                        label = "Refresh",
                                        onClick = { if (!scanState.isScanning) onRefreshFolder(folder.treeUri) },
                                        variant = PodcastyPillButtonVariant.Secondary,
                                    )
                                }
                                Box(Modifier.weight(1f)) {
                                    PodcastyPillButton(
                                        label = stringResource(R.string.remove_label),
                                        onClick = { onRemoveFolder(folder.treeUri) },
                                        variant = PodcastyPillButtonVariant.Secondary,
                                    )
                                }
                            }
                        }
                        if (idx < folders.lastIndex) {
                            ActionListDivider(style = ActionListDividerStyle.FullWidth)
                        }
                    }
                }
            }
        }

        ActionList(
            items = listOf(
                ActionListItem(labelRes = R.string.music_find_duplicates, leadingSymbol = MaterialSymbols.Search, onClick = onFindDuplicates),
                ActionListItem(labelRes = R.string.playlist_backup_export, leadingSymbol = MaterialSymbols.Download, onClick = onExportPlaylists),
                ActionListItem(labelRes = R.string.playlist_backup_restore, leadingSymbol = MaterialSymbols.Refresh, onClick = onRestorePlaylists),
            ),
            containerStyle = ActionListContainerStyle.Card,
        )
        TextHelper(text = stringResource(R.string.music_library_saf_helper))
    }
}

@Composable
fun LyricsCaptionsSettingsScreen(
    settings: PodcastUserSettings,
    hasAsrTokenStored: Boolean,
    hasYouTubeApiKeyStored: Boolean,
    hazeState: HazeState?,
    onAutoSyncChanged: (Boolean) -> Unit,
    onAutoScrollChanged: (Boolean) -> Unit,
    onLanguageSelected: (String) -> Unit,
    onFontScaleSelected: (Float) -> Unit,
    onShowLockScreenLyricsChanged: (Boolean) -> Unit,
    onLockScreenLyricsExpandedChanged: (Boolean) -> Unit = {},
    onShowMiniPlayerLyricsChanged: (Boolean) -> Unit = {},
    onShowLyricsTranslationChanged: (Boolean) -> Unit = {},
    onShowLockScreenCaptionsChanged: (Boolean) -> Unit,
    onShowTranscriptNotificationChanged: (Boolean) -> Unit,
    onKeepScreenOnChanged: (Boolean) -> Unit,
    onAutoStartLiveCaptionsChanged: (Boolean) -> Unit,
    onSaveCaptionHistoryChanged: (Boolean) -> Unit,
    onAsrEngineSelected: (String) -> Unit,
    onAsrLanguageSelected: (String) -> Unit,
    onSaveAsrConnection: (backendUrl: String, authToken: String?, youtubeApiKey: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPodcastyColors.current
    var backendUrlInput by remember(settings.asrBackendUrl) { mutableStateOf(settings.asrBackendUrl) }
    var authTokenInput by remember { mutableStateOf("") }
    var youtubeApiKeyInput by remember { mutableStateOf("") }
    var savedBanner by remember { mutableStateOf(false) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PodcastyCard {
            Selection(
                labelRes = R.string.lyrics_transcript_language,
                options = SupportedTranscriptLanguageOptions.map { opt ->
                    SelectionOption(opt.code, label = opt.displayName)
                },
                selectedValue = settings.preferredTranscriptLanguage,
                onValueSelected = onLanguageSelected,
                hazeState = hazeState,
            )
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            Selection(
                labelRes = R.string.lyrics_caption_font_size,
                options = SupportedCaptionFontScales.map { scale ->
                    val label = when {
                        scale <= 0.9f -> "Compact (85%)"
                        scale <= 1.05f -> "Standard (100%)"
                        scale <= 1.2f -> "Large (115%)"
                        else -> "Extra large (130%)"
                    }
                    SelectionOption(scale, label = label)
                },
                selectedValue = SupportedCaptionFontScales.minByOrNull { abs(it - settings.captionFontSizeScale) } ?: 1.0f,
                onValueSelected = onFontScaleSelected,
                hazeState = hazeState,
            )
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.lyrics_auto_sync,
                        trailingContent = {
                            Switch(checked = settings.autoSyncLyrics, onCheckedChange = onAutoSyncChanged)
                        },
                        onClick = { onAutoSyncChanged(!settings.autoSyncLyrics) },
                    ),
                    ActionListItem(
                        labelRes = R.string.lyrics_auto_scroll,
                        trailingContent = {
                            Switch(checked = settings.autoScrollLyrics, onCheckedChange = onAutoScrollChanged)
                        },
                        onClick = { onAutoScrollChanged(!settings.autoScrollLyrics) },
                    ),
                    ActionListItem(
                        labelRes = R.string.lyrics_show_lock_screen_lyrics,
                        trailingContent = {
                            Switch(
                                checked = settings.showLockScreenLyrics,
                                onCheckedChange = onShowLockScreenLyricsChanged,
                            )
                        },
                        onClick = { onShowLockScreenLyricsChanged(!settings.showLockScreenLyrics) },
                    ),
                    ActionListItem(
                        labelRes = R.string.lyrics_lock_screen_expanded,
                        trailingContent = {
                            Switch(
                                checked = settings.lockScreenLyricsExpanded,
                                onCheckedChange = onLockScreenLyricsExpandedChanged,
                            )
                        },
                        onClick = { onLockScreenLyricsExpandedChanged(!settings.lockScreenLyricsExpanded) },
                    ),
                    ActionListItem(
                        labelRes = R.string.lyrics_show_mini_player_lyrics,
                        trailingContent = {
                            Switch(
                                checked = settings.showMiniPlayerLyrics,
                                onCheckedChange = onShowMiniPlayerLyricsChanged,
                            )
                        },
                        onClick = { onShowMiniPlayerLyricsChanged(!settings.showMiniPlayerLyrics) },
                    ),
                    ActionListItem(
                        labelRes = R.string.lyrics_show_translation,
                        trailingContent = {
                            Switch(
                                checked = settings.showLyricsTranslation,
                                onCheckedChange = onShowLyricsTranslationChanged,
                            )
                        },
                        onClick = { onShowLyricsTranslationChanged(!settings.showLyricsTranslation) },
                    ),
                    ActionListItem(
                        labelRes = R.string.lyrics_show_lock_screen_captions,
                        trailingContent = {
                            Switch(
                                checked = settings.showLockScreenCaptions,
                                onCheckedChange = onShowLockScreenCaptionsChanged,
                            )
                        },
                        onClick = { onShowLockScreenCaptionsChanged(!settings.showLockScreenCaptions) },
                    ),
                    ActionListItem(
                        labelRes = R.string.lyrics_show_transcript_notification,
                        trailingContent = {
                            Switch(
                                checked = settings.showTranscriptNotification,
                                onCheckedChange = onShowTranscriptNotificationChanged,
                            )
                        },
                        onClick = { onShowTranscriptNotificationChanged(!settings.showTranscriptNotification) },
                    ),
                    ActionListItem(
                        labelRes = R.string.playback_keep_screen_on_transcript,
                        trailingContent = {
                            Switch(checked = settings.keepScreenOnForTranscript, onCheckedChange = onKeepScreenOnChanged)
                        },
                        onClick = { onKeepScreenOnChanged(!settings.keepScreenOnForTranscript) },
                    ),
                ),
                containerStyle = ActionListContainerStyle.Plain,
                dividerStyle = ActionListDividerStyle.FullWidth,
            )
        }
        TextHelper(text = stringResource(R.string.lyrics_lock_screen_aod_helper))

        LabeledCard(label = stringResource(R.string.asr_settings_section)) {
            Column(Modifier.fillMaxWidth()) {
                ActionList(
                    items = listOf(
                        ActionListItem(
                            labelRes = R.string.asr_auto_start,
                            trailingContent = {
                                Switch(
                                    checked = settings.autoStartLiveCaptions,
                                    onCheckedChange = onAutoStartLiveCaptionsChanged,
                                )
                            },
                            onClick = { onAutoStartLiveCaptionsChanged(!settings.autoStartLiveCaptions) },
                        ),
                        ActionListItem(
                            labelRes = R.string.asr_save_history,
                            trailingContent = {
                                Switch(
                                    checked = settings.saveCaptionHistory,
                                    onCheckedChange = onSaveCaptionHistoryChanged,
                                )
                            },
                            onClick = { onSaveCaptionHistoryChanged(!settings.saveCaptionHistory) },
                        ),
                    ),
                    containerStyle = ActionListContainerStyle.Plain,
                    dividerStyle = ActionListDividerStyle.FullWidth,
                )
                ActionListDivider(style = ActionListDividerStyle.FullWidth)
                Selection(
                    labelRes = R.string.asr_engine_label,
                    options = AsrEngineType.entries.map { SelectionOption(it.id, label = it.displayName) },
                    selectedValue = AsrEngineType.fromId(settings.asrEngineId).id,
                    onValueSelected = onAsrEngineSelected,
                    hazeState = hazeState,
                )
                ActionListDivider(style = ActionListDividerStyle.FullWidth)
                Selection(
                    labelRes = R.string.asr_language_label,
                    options = SupportedAsrLanguages.map { SelectionOption(it.code, label = it.displayName) },
                    selectedValue = settings.asrLanguageCode,
                    onValueSelected = onAsrLanguageSelected,
                    hazeState = hazeState,
                )
                ActionListDivider(style = ActionListDividerStyle.FullWidth)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PodcastyTextField(
                        value = backendUrlInput,
                        onValueChange = {
                            backendUrlInput = it
                            savedBanner = false
                        },
                        placeholder = stringResource(R.string.asr_backend_url_placeholder),
                        size = PodcastyTextFieldSize.Small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PodcastyTextField(
                        value = authTokenInput,
                        onValueChange = {
                            authTokenInput = it
                            savedBanner = false
                        },
                        placeholder = if (hasAsrTokenStored) {
                            "Bearer token saved (enter new to replace)"
                        } else {
                            stringResource(R.string.asr_auth_token_placeholder)
                        },
                        size = PodcastyTextFieldSize.Small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PodcastyTextField(
                        value = youtubeApiKeyInput,
                        onValueChange = {
                            youtubeApiKeyInput = it
                            savedBanner = false
                        },
                        placeholder = if (hasYouTubeApiKeyStored) {
                            "YouTube API key saved (enter new to replace)"
                        } else {
                            stringResource(R.string.asr_youtube_api_key_placeholder)
                        },
                        size = PodcastyTextFieldSize.Small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PodcastyPillButton(
                        label = stringResource(R.string.asr_save_connection),
                        onClick = {
                            onSaveAsrConnection(
                                backendUrlInput.trim(),
                                authTokenInput.trim().takeIf { it.isNotEmpty() },
                                youtubeApiKeyInput.trim().takeIf { it.isNotEmpty() },
                            )
                            authTokenInput = ""
                            youtubeApiKeyInput = ""
                            savedBanner = true
                        },
                        variant = PodcastyPillButtonVariant.Primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (savedBanner) {
                        Text(
                            text = stringResource(R.string.asr_saved_confirmation),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.success,
                        )
                    }
                }
            }
        }
        TextHelper(text = stringResource(R.string.asr_settings_helper))
    }
}

@Composable
fun BluetoothSettingsScreen(
    settings: PodcastUserSettings,
    onPauseOnDisconnectChanged: (Boolean) -> Unit,
    onBluetoothMediaControlsChanged: (Boolean) -> Unit,
    onAutoResumeChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionList(
            items = listOf(
                ActionListItem(
                    labelRes = R.string.bluetooth_pause_on_disconnect,
                    trailingContent = {
                        Switch(
                            checked = settings.pauseOnHeadphoneDisconnect,
                            onCheckedChange = onPauseOnDisconnectChanged,
                        )
                    },
                    onClick = { onPauseOnDisconnectChanged(!settings.pauseOnHeadphoneDisconnect) },
                ),
                ActionListItem(
                    labelRes = R.string.bluetooth_media_controls,
                    trailingContent = {
                        Switch(
                            checked = settings.bluetoothMediaControls,
                            onCheckedChange = onBluetoothMediaControlsChanged,
                        )
                    },
                    onClick = { onBluetoothMediaControlsChanged(!settings.bluetoothMediaControls) },
                ),
                ActionListItem(
                    labelRes = R.string.bluetooth_auto_resume,
                    trailingContent = {
                        Switch(
                            checked = settings.autoResumeOnBluetoothReconnect,
                            onCheckedChange = onAutoResumeChanged,
                        )
                    },
                    onClick = { onAutoResumeChanged(!settings.autoResumeOnBluetoothReconnect) },
                ),
            ),
            containerStyle = ActionListContainerStyle.Card,
            dividerStyle = ActionListDividerStyle.FullWidth,
        )
        TextHelper(text = stringResource(R.string.bluetooth_helper))
    }
}

@Composable
fun AppearanceScreen(
    themeMode: ThemeMode,
    onThemeModeSelected: (ThemeMode) -> Unit,
    reduceTransparency: Boolean,
    onReduceTransparencyChanged: (Boolean) -> Unit,
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PodcastyCard {
            Selection(
                labelRes = R.string.appearance_theme_title,
                options = listOf(
                    SelectionOption(ThemeMode.System, labelRes = R.string.theme_system),
                    SelectionOption(ThemeMode.Light, labelRes = R.string.theme_light),
                    SelectionOption(ThemeMode.Dark, labelRes = R.string.theme_dark),
                    SelectionOption(ThemeMode.Oled, labelRes = R.string.theme_oled),
                ),
                selectedValue = themeMode,
                onValueSelected = onThemeModeSelected,
                hazeState = hazeState,
            )
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.appearance_reduce_transparency,
                        trailingContent = {
                            Switch(checked = reduceTransparency, onCheckedChange = onReduceTransparencyChanged)
                        },
                        onClick = { onReduceTransparencyChanged(!reduceTransparency) },
                    ),
                ),
                containerStyle = ActionListContainerStyle.Plain,
            )
        }
    }
}

@Composable
fun PlaybackSettingsScreen(
    settings: PodcastUserSettings,
    hazeState: HazeState?,
    onOpenEqualizer: () -> Unit = {},
    onDefaultSpeedSelected: (Float) -> Unit,
    onSkipBackwardSelected: (Int) -> Unit,
    onSkipForwardSelected: (Int) -> Unit,
    onFadeOutSecondsSelected: (Int) -> Unit,
    onResumePreviousChanged: (Boolean) -> Unit,
    onGaplessPlaybackChanged: (Boolean) -> Unit,
    onCrossfadeDurationChanged: (Int) -> Unit = {},
    onPlaybackFadeDurationChanged: (Int) -> Unit = {},
    onRemovePlayedFromQueueChanged: (Boolean) -> Unit = {},
    onOptionalHomeCategoryChanged: (String) -> Unit = {},
    onShakeSkipSensitivityChanged: (Int) -> Unit = {},
    onReplayGainModeChanged: (Int) -> Unit = {},
    onPlayerSecondaryControlsChanged: (List<String>) -> Unit = {},
    onShowSpeedBadgeChanged: (Boolean) -> Unit,
    onKeepScreenOnChanged: (Boolean) -> Unit,
    onLivingArtworkChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showControlEditor by remember { mutableStateOf(false) }
    if (showControlEditor) {
        PodcastyBottomSheet(
            title = { Text(stringResource(R.string.playback_customize_controls), style = MaterialTheme.typography.titleMedium) },
            onDismiss = { showControlEditor = false },
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val ordered = settings.playerSecondaryControls + DefaultPlayerSecondaryControls.filterNot { it in settings.playerSecondaryControls }
                ordered.forEach { action ->
                    val visible = action in settings.playerSecondaryControls
                    val label = when (action) { "lyrics" -> "Lyrics"; "sleep" -> "Sleep timer"; "output" -> "Audio output"; else -> "Queue" }
                    ActionList(items = listOf(ActionListItem(
                        labelText = label,
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val index = settings.playerSecondaryControls.indexOf(action)
                                IconButton(enabled = index > 0, onClick = {
                                    val updated = settings.playerSecondaryControls.toMutableList()
                                    java.util.Collections.swap(updated, index, index - 1)
                                    onPlayerSecondaryControlsChanged(updated)
                                }) { MaterialSymbol(MaterialSymbols.ArrowUpward, "Move up", size = 20.dp) }
                                IconButton(enabled = visible && index < settings.playerSecondaryControls.lastIndex, onClick = {
                                    val updated = settings.playerSecondaryControls.toMutableList()
                                    java.util.Collections.swap(updated, index, index + 1)
                                    onPlayerSecondaryControlsChanged(updated)
                                }) { MaterialSymbol(MaterialSymbols.ArrowDownward, "Move down", size = 20.dp) }
                                Switch(checked = visible, onCheckedChange = {
                                    onPlayerSecondaryControlsChanged(if (it) settings.playerSecondaryControls + action else settings.playerSecondaryControls - action)
                                })
                            }
                        },
                    )), containerStyle = ActionListContainerStyle.Plain)
                }
                PodcastyPillButton(label = "Restore defaults", onClick = { onPlayerSecondaryControlsChanged(DefaultPlayerSecondaryControls) }, variant = PodcastyPillButtonVariant.Secondary)
            }
        }
    }
    val colors = LocalPodcastyColors.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionList(
            items = listOf(
                ActionListItem(labelRes = R.string.playback_customize_controls, leadingSymbol = MaterialSymbols.Edit, onClick = { showControlEditor = true }),
                ActionListItem(
                    labelRes = R.string.player_equalizer_audio,
                    leadingSymbol = MaterialSymbols.GraphicEq,
                    trailingContent = {
                        Text(
                            text = if (settings.eqEnabled) "On" else "Off",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (settings.eqEnabled) colors.primary else colors.textMuted,
                        )
                    },
                    onClick = onOpenEqualizer,
                ),
            ),
            containerStyle = ActionListContainerStyle.Card,
        )
        PodcastyCard {
            Selection(
                labelRes = R.string.playback_replaygain,
                options = listOf(SelectionOption(0, label = "Off"), SelectionOption(1, label = "Track gain"), SelectionOption(2, label = "Album gain")),
                selectedValue = settings.replayGainMode,
                onValueSelected = onReplayGainModeChanged, hazeState = hazeState,
            )
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            Selection(
                labelRes = R.string.playback_shake_to_skip,
                options = listOf(SelectionOption(0, label = "Off"), SelectionOption(1, label = "Low sensitivity"), SelectionOption(2, label = "Medium sensitivity"), SelectionOption(3, label = "High sensitivity")),
                selectedValue = settings.shakeSkipSensitivity,
                onValueSelected = onShakeSkipSensitivityChanged, hazeState = hazeState,
            )
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            Selection(
                labelRes = R.string.home_optional_section,
                options = listOf(
                    SelectionOption("", label = "Hidden"),
                    SelectionOption("favorites", labelRes = R.string.home_favorites),
                    SelectionOption("most", labelRes = R.string.home_most_played),
                    SelectionOption("never", labelRes = R.string.home_never_played),
                    SelectionOption("longest", labelRes = R.string.home_longest_tracks),
                ),
                selectedValue = settings.optionalHomeCategory,
                onValueSelected = onOptionalHomeCategoryChanged, hazeState = hazeState,
            )
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            Selection(
                labelRes = R.string.playback_audio_fade,
                options = listOf(0, 150, 300, 600, 1000, 2000, 3000).map {
                    SelectionOption(it, label = if (it == 0) "Off" else "$it ms")
                },
                selectedValue = settings.playbackFadeDurationMs,
                onValueSelected = onPlaybackFadeDurationChanged,
                hazeState = hazeState,
            )
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            Selection(
                labelRes = R.string.playback_crossfade,
                options = listOf(0, 2_000, 4_000, 6_000).map {
                    SelectionOption(it, label = if (it == 0) "Off (Gapless)" else "${it / 1000} s")
                },
                selectedValue = settings.crossfadeDurationMs,
                onValueSelected = onCrossfadeDurationChanged,
                hazeState = hazeState,
            )
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            Selection(
                labelRes = R.string.playback_default_speed,
                options = SupportedPlaybackSpeeds.map { SelectionOption(it, label = formatSpeedLabel(it)) },
                selectedValue = SupportedPlaybackSpeeds.minByOrNull { abs(it - settings.defaultPlaybackSpeed) } ?: 1.0f,
                onValueSelected = onDefaultSpeedSelected,
                hazeState = hazeState,
            )
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            Selection(
                labelRes = R.string.playback_skip_backward,
                options = SupportedSkipBackwardSeconds.map {
                    SelectionOption(it, label = stringResource(R.string.playback_seconds_value, it))
                },
                selectedValue = settings.skipBackwardSeconds,
                onValueSelected = onSkipBackwardSelected,
                hazeState = hazeState,
            )
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            Selection(
                labelRes = R.string.playback_skip_forward,
                options = SupportedSkipForwardSeconds.map {
                    SelectionOption(it, label = stringResource(R.string.playback_seconds_value, it))
                },
                selectedValue = settings.skipForwardSeconds,
                onValueSelected = onSkipForwardSelected,
                hazeState = hazeState,
            )
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            Selection(
                labelRes = R.string.sleep_timer_fade_out_title,
                options = SupportedFadeOutSeconds.map { sec ->
                    SelectionOption(
                        sec,
                        label = if (sec == 0) stringResource(R.string.player_sleep_timer_off)
                        else stringResource(R.string.playback_seconds_value, sec),
                    )
                },
                selectedValue = settings.sleepFadeOutSeconds,
                onValueSelected = onFadeOutSecondsSelected,
                hazeState = hazeState,
            )
            ActionListDivider(style = ActionListDividerStyle.FullWidth)
            ActionList(
                items = listOf(
                    ActionListItem(
                        labelRes = R.string.playback_remove_played_queue,
                        trailingContent = { Switch(checked = settings.removePlayedFromQueue, onCheckedChange = onRemovePlayedFromQueueChanged) },
                        onClick = { onRemovePlayedFromQueueChanged(!settings.removePlayedFromQueue) },
                    ),
                    ActionListItem(
                        labelRes = R.string.playback_resume_previous,
                        trailingContent = {
                            Switch(checked = settings.resumePreviousPlayback, onCheckedChange = onResumePreviousChanged)
                        },
                        onClick = { onResumePreviousChanged(!settings.resumePreviousPlayback) },
                    ),
                    ActionListItem(
                        labelRes = R.string.playback_gapless,
                        trailingContent = {
                            Switch(checked = settings.gaplessPlayback, onCheckedChange = onGaplessPlaybackChanged)
                        },
                        onClick = { onGaplessPlaybackChanged(!settings.gaplessPlayback) },
                    ),
                    ActionListItem(
                        labelRes = R.string.playback_show_speed_badge,
                        trailingContent = {
                            Switch(checked = settings.showSpeedBadge, onCheckedChange = onShowSpeedBadgeChanged)
                        },
                        onClick = { onShowSpeedBadgeChanged(!settings.showSpeedBadge) },
                    ),
                    ActionListItem(
                        labelRes = R.string.playback_keep_screen_on_transcript,
                        trailingContent = {
                            Switch(checked = settings.keepScreenOnForTranscript, onCheckedChange = onKeepScreenOnChanged)
                        },
                        onClick = { onKeepScreenOnChanged(!settings.keepScreenOnForTranscript) },
                    ),
                ),
                containerStyle = ActionListContainerStyle.Plain,
                dividerStyle = ActionListDividerStyle.FullWidth,
            )
        }
        LabeledCard(label = stringResource(R.string.playback_fullscreen_background)) {
            RadioList(
                options = listOf(
                    SelectionOption(false, labelRes = R.string.playback_background_solid),
                    SelectionOption(true, labelRes = R.string.playback_background_living),
                ),
                selectedValue = settings.livingArtworkBackground,
                onValueSelected = onLivingArtworkChanged,
            )
        }
        TextHelper(
            text = stringResource(
                R.string.playback_background_helper,
                stringResource(R.string.playback_background_living),
                stringResource(R.string.playback_background_solid),
            ),
        )
    }
}

@Composable
fun DownloadsSettingsScreen(
    autoDownloadSaved: Boolean,
    downloadedBytes: Long,
    downloadedCount: Int,
    volumes: List<UsbVolumeSummary>,
    folders: List<MusicFolder>,
    onAutoDownloadChanged: (Boolean) -> Unit,
    onSelectInternalOrSdFolder: () -> Unit,
    onSelectUsbOtgFolder: () -> Unit,
    onRefreshLibrary: () -> Unit,
    onRestoreDemoDownloads: () -> Unit,
    onClearAllDownloads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPodcastyColors.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionList(
            items = listOf(
                ActionListItem(
                    labelRes = R.string.storage_browse_internal_sd,
                    leadingSymbol = MaterialSymbols.Storage,
                    onClick = onSelectInternalOrSdFolder,
                ),
                ActionListItem(
                    labelRes = R.string.storage_browse_usb_otg,
                    leadingSymbol = MaterialSymbols.Sensors,
                    onClick = onSelectUsbOtgFolder,
                ),
                ActionListItem(
                    labelRes = R.string.storage_refresh_library,
                    leadingSymbol = MaterialSymbols.Refresh,
                    onClick = onRefreshLibrary,
                ),
                ActionListItem(
                    labelRes = R.string.downloads_auto_cache,
                    trailingContent = {
                        Switch(checked = autoDownloadSaved, onCheckedChange = onAutoDownloadChanged)
                    },
                    onClick = { onAutoDownloadChanged(!autoDownloadSaved) },
                ),
                ActionListItem(
                    labelRes = R.string.downloads_storage_used,
                    trailingContent = {
                        Text(
                            text = "${formatFileSize(downloadedBytes)} ($downloadedCount)",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textMuted,
                        )
                    },
                ),
                ActionListItem(
                    labelRes = R.string.downloads_clear_all,
                    onClick = onClearAllDownloads,
                ),
            ),
            containerStyle = ActionListContainerStyle.Card,
            dividerStyle = ActionListDividerStyle.FullWidth,
        )

        if (volumes.isNotEmpty()) {
            LabeledCard(label = stringResource(R.string.storage_detected_volumes)) {
                Column(Modifier.fillMaxWidth()) {
                    volumes.forEachIndexed { idx, vol ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(vol.description, style = MaterialTheme.typography.bodyLarge, color = colors.textMain)
                                Text(
                                    text = if (vol.isRemovable) "Removable (SD / USB OTG) · ${vol.state}" else "Internal · ${vol.state}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textMuted,
                                )
                            }
                        }
                        if (idx < volumes.lastIndex) ActionListDivider(style = ActionListDividerStyle.FullWidth)
                    }
                }
            }
        }

        TextHelper(
            text = "${stringResource(R.string.downloads_info_helper)} Granted SAF folders: ${folders.size}.",
        )
    }
}

@Composable
fun AboutScreen(
    modifier: Modifier = Modifier,
) {
    val colors = LocalPodcastyColors.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HeroCard(
            title = stringResource(R.string.app_name),
            description = stringResource(R.string.about_description),
        ) {
            Image(
                painter = painterResource(R.drawable.podcasty_about_app_icon),
                contentDescription = null,
                modifier = Modifier.size(64.dp),
            )
        }
        ActionList(
            items = listOf(
                ActionListItem(
                    labelRes = R.string.about_version,
                    trailingContent = {
                        Text(
                            text = BuildConfig.VERSION_NAME,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textMuted,
                        )
                    },
                ),
                ActionListItem(
                    labelRes = R.string.about_license,
                    trailingContent = {
                        Text(
                            text = stringResource(R.string.about_license_value),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textMuted,
                        )
                    },
                ),
            ),
            containerStyle = ActionListContainerStyle.Card,
            dividerStyle = ActionListDividerStyle.FullWidth,
        )
        LabeledCard(
            label = stringResource(R.string.about_attribution_label),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.about_attribution_body),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
