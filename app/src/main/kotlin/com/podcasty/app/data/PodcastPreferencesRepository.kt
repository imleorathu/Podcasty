/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.podcasty.app.domain.FolderSortOption
import com.podcasty.app.domain.FolderViewMode
import com.podcasty.app.domain.MusicLibraryCategory
import com.podcasty.app.domain.PodcastLayoutMode
import com.podcasty.app.domain.SongSortOption
import com.podcasty.app.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.podcastyDataStore: DataStore<Preferences> by preferencesDataStore(name = "podcasty_prefs")

@Serializable
data class FavoriteMusicFolder(val treeUri: String, val relativePath: String, val name: String)

val DefaultPlayerSecondaryControls = listOf("lyrics", "sleep", "output", "queue")
fun parsePlayerSecondaryControls(csv: String): List<String> = csv.split(',').filter { it in DefaultPlayerSecondaryControls }.distinct()

data class PodcastUserSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val reduceTransparency: Boolean = false,
    val livingArtworkBackground: Boolean = true,
    val showSpeedBadge: Boolean = true,
    val keepScreenOnForTranscript: Boolean = false,
    val defaultPlaybackSpeed: Float = 1.0f,
    val skipBackwardSeconds: Int = 10,
    val skipForwardSeconds: Int = 30,
    val autoDownloadSaved: Boolean = false,
    val podcastLayoutMode: PodcastLayoutMode = PodcastLayoutMode.Grid,
    val recentSearches: List<String> = emptyList(),
    val lastEpisodeId: String? = null,
    // Music Library & Storage
    val defaultMusicFolderUri: String? = null,
    val defaultMusicFolderName: String? = null,
    val autoLibraryRefresh: Boolean = true,
    val includeSubfolders: Boolean = true,
    val folderViewMode: FolderViewMode = FolderViewMode.Hierarchical,
    val folderSortOption: FolderSortOption = FolderSortOption.Name,
    val songSortOption: SongSortOption = SongSortOption.Title,
    val musicLibraryCategory: MusicLibraryCategory = MusicLibraryCategory.Folders,
    val lastFolderTreeUri: String? = null,
    val lastFolderSubpath: String = "",
    val artworkResolverVersion: Int = 0,
    // Lyrics & Captions (music lock-screen lyrics ON by default; AI podcast caption notification OFF by default)
    val autoSyncLyrics: Boolean = true,
    val autoScrollLyrics: Boolean = true,
    val preferredTranscriptLanguage: String = "en",
    val captionFontSizeScale: Float = 1.0f,
    val showLockScreenLyrics: Boolean = true,
    val lockScreenLyricsExpanded: Boolean = true,
    val showMiniPlayerLyrics: Boolean = true,
    val showLyricsTranslation: Boolean = true,
    val showLockScreenCaptions: Boolean = false,
    val showTranscriptNotification: Boolean = false,
    val hasRequestedNotificationPermission: Boolean = false,
    // Playback & Sleep Timer
    val sleepFadeOutSeconds: Int = 30,
    val activeSleepTimer: String = "",
    val resumePreviousPlayback: Boolean = true,
    val gaplessPlayback: Boolean = true,
    val crossfadeDurationMs: Int = 0,
    val playbackFadeDurationMs: Int = 0,
    val removePlayedFromQueue: Boolean = false,
    val optionalHomeCategory: String = "",
    val shakeSkipSensitivity: Int = 0,
    val replayGainMode: Int = 0,
    val playerSecondaryControls: List<String> = DefaultPlayerSecondaryControls,
    val favoriteMusicFolders: List<FavoriteMusicFolder> = emptyList(),
    // Bluetooth
    val pauseOnHeadphoneDisconnect: Boolean = true,
    val bluetoothMediaControls: Boolean = true,
    val autoResumeOnBluetoothReconnect: Boolean = false,
    // Real-Time AI Podcast Captions (ASR)
    val autoStartLiveCaptions: Boolean = true,
    val saveCaptionHistory: Boolean = true,
    val asrEngineId: String = "google_cloud",
    val asrLanguageCode: String = "en-US",
    val asrBackendUrl: String = "",
    // Audio Equalizer & Effects (disabled by default so original audio is untouched)
    val eqEnabled: Boolean = false,
    val eqPresetId: String = "flat",
    val eqBandLevelsCsv: String = "",
    val eqPreampDb: Float = 0f,
    val eqAutoClippingProtection: Boolean = true,
    val eqBassBoostStrength: Int = 0,
    val eqVirtualizerStrength: Int = 0,
    val eqBalance: Float = 0f,
    val eqMonoAudio: Boolean = false,
)

class PodcastPreferencesRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val ThemeModeKey = stringPreferencesKey("theme_mode")
        val ReduceTransparencyKey = booleanPreferencesKey("reduce_transparency")
        val LivingArtworkKey = booleanPreferencesKey("living_artwork_background")
        val ShowSpeedBadgeKey = booleanPreferencesKey("show_speed_badge")
        val KeepScreenOnTranscriptKey = booleanPreferencesKey("keep_screen_on_transcript")
        val DefaultSpeedKey = floatPreferencesKey("default_playback_speed")
        val SkipBackwardKey = intPreferencesKey("skip_backward_seconds")
        val SkipForwardKey = intPreferencesKey("skip_forward_seconds")
        val AutoDownloadSavedKey = booleanPreferencesKey("auto_download_saved")
        val PodcastLayoutModeKey = stringPreferencesKey("podcast_layout_mode")
        val RecentSearchesKey = stringPreferencesKey("recent_searches_json")
        val LastEpisodeIdKey = stringPreferencesKey("last_episode_id")

        val DefaultMusicFolderUriKey = stringPreferencesKey("default_music_folder_uri")
        val DefaultMusicFolderNameKey = stringPreferencesKey("default_music_folder_name")
        val AutoLibraryRefreshKey = booleanPreferencesKey("auto_library_refresh")
        val IncludeSubfoldersKey = booleanPreferencesKey("include_subfolders")
        val FolderViewModeKey = stringPreferencesKey("folder_view_mode")
        val FolderSortOptionKey = stringPreferencesKey("folder_sort_option")
        val SongSortOptionKey = stringPreferencesKey("song_sort_option")
        val MusicLibraryCategoryKey = stringPreferencesKey("music_library_category")
        val LastFolderTreeUriKey = stringPreferencesKey("last_folder_tree_uri")
        val LastFolderSubpathKey = stringPreferencesKey("last_folder_subpath")
        val ArtworkResolverVersionKey = intPreferencesKey("artwork_resolver_version")

        val AutoSyncLyricsKey = booleanPreferencesKey("auto_sync_lyrics")
        val AutoScrollLyricsKey = booleanPreferencesKey("auto_scroll_lyrics")
        val PreferredTranscriptLanguageKey = stringPreferencesKey("preferred_transcript_language")
        val CaptionFontSizeScaleKey = floatPreferencesKey("caption_font_size_scale")
        val ShowLockScreenLyricsKey = booleanPreferencesKey("show_lock_screen_lyrics")
        val LockScreenLyricsExpandedKey = booleanPreferencesKey("lock_screen_lyrics_expanded")
        val ShowMiniPlayerLyricsKey = booleanPreferencesKey("show_mini_player_lyrics")
        val ShowLyricsTranslationKey = booleanPreferencesKey("show_lyrics_translation")
        val ShowLockScreenCaptionsKey = booleanPreferencesKey("show_lock_screen_captions")
        val ShowTranscriptNotificationKey = booleanPreferencesKey("show_transcript_notification")
        val HasRequestedNotificationPermissionKey = booleanPreferencesKey("has_requested_notification_permission")

        val ActiveSleepTimerKey = stringPreferencesKey("active_sleep_timer")
        val SleepFadeOutSecondsKey = intPreferencesKey("sleep_fade_out_seconds")
        val ResumePreviousPlaybackKey = booleanPreferencesKey("resume_previous_playback")
        val FavoriteMusicFoldersKey = stringPreferencesKey("favorite_music_folders")
        val PlayerSecondaryControlsKey = stringPreferencesKey("player_secondary_controls")
        val ReplayGainModeKey = intPreferencesKey("replay_gain_mode")
        val ShakeSkipSensitivityKey = intPreferencesKey("shake_skip_sensitivity")
        val OptionalHomeCategoryKey = stringPreferencesKey("optional_home_category")
        val RemovePlayedQueueKey = booleanPreferencesKey("remove_played_from_queue")
        val PlaybackFadeDurationKey = intPreferencesKey("playback_fade_duration_ms")
        val CrossfadeDurationKey = intPreferencesKey("crossfade_duration_ms")
        val GaplessPlaybackKey = booleanPreferencesKey("gapless_playback")

        val PauseOnHeadphoneDisconnectKey = booleanPreferencesKey("pause_on_headphone_disconnect")
        val BluetoothMediaControlsKey = booleanPreferencesKey("bluetooth_media_controls")
        val AutoResumeBluetoothKey = booleanPreferencesKey("auto_resume_bluetooth")

        val AutoStartLiveCaptionsKey = booleanPreferencesKey("auto_start_live_captions")
        val SaveCaptionHistoryKey = booleanPreferencesKey("save_caption_history")
        val AsrEngineIdKey = stringPreferencesKey("asr_engine_id")
        val AsrLanguageCodeKey = stringPreferencesKey("asr_language_code")
        val AsrBackendUrlKey = stringPreferencesKey("asr_backend_url")

        val EqEnabledKey = booleanPreferencesKey("eq_enabled")
        val EqPresetIdKey = stringPreferencesKey("eq_preset_id")
        val EqBandLevelsCsvKey = stringPreferencesKey("eq_band_levels_csv")
        val EqPreampDbKey = floatPreferencesKey("eq_preamp_db")
        val EqAutoClippingProtectionKey = booleanPreferencesKey("eq_auto_clipping_protection")
        val EqBassBoostStrengthKey = intPreferencesKey("eq_bass_boost_strength")
        val EqVirtualizerStrengthKey = intPreferencesKey("eq_virtualizer_strength")
        val EqBalanceKey = floatPreferencesKey("eq_balance")
        val EqMonoAudioKey = booleanPreferencesKey("eq_mono_audio")
    }

    val settings: Flow<PodcastUserSettings> = context.podcastyDataStore.data.map { prefs ->
        val recentJson = prefs[Keys.RecentSearchesKey]
        val recent = if (recentJson.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString<List<String>>(recentJson) }.getOrDefault(emptyList())
        }
        PodcastUserSettings(
            themeMode = ThemeMode.fromKey(prefs[Keys.ThemeModeKey]),
            reduceTransparency = prefs[Keys.ReduceTransparencyKey] ?: false,
            livingArtworkBackground = prefs[Keys.LivingArtworkKey] ?: true,
            showSpeedBadge = prefs[Keys.ShowSpeedBadgeKey] ?: true,
            keepScreenOnForTranscript = prefs[Keys.KeepScreenOnTranscriptKey] ?: false,
            defaultPlaybackSpeed = (prefs[Keys.DefaultSpeedKey] ?: 1.0f).coerceIn(0.5f, 2.5f),
            skipBackwardSeconds = prefs[Keys.SkipBackwardKey] ?: 10,
            skipForwardSeconds = prefs[Keys.SkipForwardKey] ?: 30,
            autoDownloadSaved = prefs[Keys.AutoDownloadSavedKey] ?: false,
            podcastLayoutMode = if (prefs[Keys.PodcastLayoutModeKey] == "list") {
                PodcastLayoutMode.List
            } else {
                PodcastLayoutMode.Grid
            },
            recentSearches = recent,
            lastEpisodeId = prefs[Keys.LastEpisodeIdKey],
            defaultMusicFolderUri = prefs[Keys.DefaultMusicFolderUriKey],
            defaultMusicFolderName = prefs[Keys.DefaultMusicFolderNameKey],
            autoLibraryRefresh = prefs[Keys.AutoLibraryRefreshKey] ?: true,
            includeSubfolders = prefs[Keys.IncludeSubfoldersKey] ?: true,
            folderViewMode = prefs[Keys.FolderViewModeKey]?.let { name ->
                runCatching { FolderViewMode.valueOf(name) }.getOrNull()
            } ?: FolderViewMode.Hierarchical,
            folderSortOption = prefs[Keys.FolderSortOptionKey]?.let { name ->
                runCatching { FolderSortOption.valueOf(name) }.getOrNull()
            } ?: FolderSortOption.Name,
            songSortOption = prefs[Keys.SongSortOptionKey]?.let { name ->
                runCatching { SongSortOption.valueOf(name) }.getOrNull()
            } ?: SongSortOption.Title,
            musicLibraryCategory = prefs[Keys.MusicLibraryCategoryKey]?.let { name ->
                runCatching { MusicLibraryCategory.valueOf(name) }.getOrNull()
            } ?: MusicLibraryCategory.Folders,
            lastFolderTreeUri = prefs[Keys.LastFolderTreeUriKey],
            lastFolderSubpath = prefs[Keys.LastFolderSubpathKey] ?: "",
            artworkResolverVersion = prefs[Keys.ArtworkResolverVersionKey] ?: 0,
            autoSyncLyrics = prefs[Keys.AutoSyncLyricsKey] ?: true,
            autoScrollLyrics = prefs[Keys.AutoScrollLyricsKey] ?: true,
            preferredTranscriptLanguage = prefs[Keys.PreferredTranscriptLanguageKey] ?: "en",
            captionFontSizeScale = (prefs[Keys.CaptionFontSizeScaleKey] ?: 1.0f).coerceIn(0.75f, 1.5f),
            showLockScreenLyrics = prefs[Keys.ShowLockScreenLyricsKey] ?: true,
            lockScreenLyricsExpanded = prefs[Keys.LockScreenLyricsExpandedKey] ?: true,
            showMiniPlayerLyrics = prefs[Keys.ShowMiniPlayerLyricsKey] ?: true,
            showLyricsTranslation = prefs[Keys.ShowLyricsTranslationKey] ?: true,
            showLockScreenCaptions = prefs[Keys.ShowLockScreenCaptionsKey] ?: false,
            showTranscriptNotification = prefs[Keys.ShowTranscriptNotificationKey] ?: false,
            hasRequestedNotificationPermission = prefs[Keys.HasRequestedNotificationPermissionKey] ?: false,
            activeSleepTimer = prefs[Keys.ActiveSleepTimerKey] ?: "",
            sleepFadeOutSeconds = (prefs[Keys.SleepFadeOutSecondsKey] ?: 30).coerceIn(0, 120),
            resumePreviousPlayback = prefs[Keys.ResumePreviousPlaybackKey] ?: true,
            favoriteMusicFolders = runCatching { json.decodeFromString<List<FavoriteMusicFolder>>(prefs[Keys.FavoriteMusicFoldersKey] ?: "[]") }.getOrDefault(emptyList()),
            playerSecondaryControls = prefs[Keys.PlayerSecondaryControlsKey]?.let(::parsePlayerSecondaryControls) ?: DefaultPlayerSecondaryControls,
            replayGainMode = (prefs[Keys.ReplayGainModeKey] ?: 0).coerceIn(0, 2),
            shakeSkipSensitivity = (prefs[Keys.ShakeSkipSensitivityKey] ?: 0).coerceIn(0, 3),
            optionalHomeCategory = prefs[Keys.OptionalHomeCategoryKey] ?: "",
            removePlayedFromQueue = prefs[Keys.RemovePlayedQueueKey] ?: false,
            playbackFadeDurationMs = (prefs[Keys.PlaybackFadeDurationKey] ?: 0).coerceIn(0, 3000),
            crossfadeDurationMs = (prefs[Keys.CrossfadeDurationKey] ?: 0).coerceIn(0, 6000),
            gaplessPlayback = prefs[Keys.GaplessPlaybackKey] ?: true,
            pauseOnHeadphoneDisconnect = prefs[Keys.PauseOnHeadphoneDisconnectKey] ?: true,
            bluetoothMediaControls = prefs[Keys.BluetoothMediaControlsKey] ?: true,
            autoResumeOnBluetoothReconnect = prefs[Keys.AutoResumeBluetoothKey] ?: false,
            autoStartLiveCaptions = prefs[Keys.AutoStartLiveCaptionsKey] ?: true,
            saveCaptionHistory = prefs[Keys.SaveCaptionHistoryKey] ?: true,
            asrEngineId = prefs[Keys.AsrEngineIdKey] ?: "google_cloud",
            asrLanguageCode = prefs[Keys.AsrLanguageCodeKey] ?: "en-US",
            asrBackendUrl = prefs[Keys.AsrBackendUrlKey] ?: "",
            eqEnabled = prefs[Keys.EqEnabledKey] ?: false,
            eqPresetId = prefs[Keys.EqPresetIdKey] ?: "flat",
            eqBandLevelsCsv = prefs[Keys.EqBandLevelsCsvKey] ?: "",
            eqPreampDb = (prefs[Keys.EqPreampDbKey] ?: 0f).coerceIn(-15f, 6f),
            eqAutoClippingProtection = prefs[Keys.EqAutoClippingProtectionKey] ?: true,
            eqBassBoostStrength = (prefs[Keys.EqBassBoostStrengthKey] ?: 0).coerceIn(0, 1000),
            eqVirtualizerStrength = (prefs[Keys.EqVirtualizerStrengthKey] ?: 0).coerceIn(0, 1000),
            eqBalance = (prefs[Keys.EqBalanceKey] ?: 0f).coerceIn(-1f, 1f),
            eqMonoAudio = prefs[Keys.EqMonoAudioKey] ?: false,
        )
    }

    suspend fun toggleFavoriteMusicFolder(folder: FavoriteMusicFolder) {
        context.podcastyDataStore.edit { prefs ->
            val previous = runCatching { json.decodeFromString<List<FavoriteMusicFolder>>(prefs[Keys.FavoriteMusicFoldersKey] ?: "[]") }.getOrDefault(emptyList())
            val found = previous.any { it.treeUri == folder.treeUri && it.relativePath == folder.relativePath }
            val next = if (found) previous.filterNot { it.treeUri == folder.treeUri && it.relativePath == folder.relativePath } else (previous + folder).take(100)
            prefs[Keys.FavoriteMusicFoldersKey] = json.encodeToString(next)
        }
    }

    suspend fun setPlayerSecondaryControls(value: List<String>) {
        context.podcastyDataStore.edit { it[Keys.PlayerSecondaryControlsKey] = value.filter { action -> action in DefaultPlayerSecondaryControls }.distinct().joinToString(",") }
    }

    suspend fun setReplayGainMode(value: Int) {
        context.podcastyDataStore.edit { it[Keys.ReplayGainModeKey] = value.coerceIn(0, 2) }
    }

    suspend fun setShakeSkipSensitivity(value: Int) {
        context.podcastyDataStore.edit { it[Keys.ShakeSkipSensitivityKey] = value.coerceIn(0, 3) }
    }

    suspend fun setOptionalHomeCategory(value: String) {
        context.podcastyDataStore.edit { it[Keys.OptionalHomeCategoryKey] = value.takeIf { category -> category in setOf("favorites", "most", "never", "longest") } ?: "" }
    }

    suspend fun setRemovePlayedFromQueue(value: Boolean) {
        context.podcastyDataStore.edit { it[Keys.RemovePlayedQueueKey] = value }
    }

    suspend fun setPlaybackFadeDurationMs(value: Int) {
        context.podcastyDataStore.edit { it[Keys.PlaybackFadeDurationKey] = value.coerceIn(0, 3000) }
    }

    suspend fun setActiveSleepTimer(snapshot: String) {
        context.podcastyDataStore.edit { it[Keys.ActiveSleepTimerKey] = snapshot }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.podcastyDataStore.edit { it[Keys.ThemeModeKey] = mode.key }
    }

    suspend fun setReduceTransparency(enabled: Boolean) {
        context.podcastyDataStore.edit { it[Keys.ReduceTransparencyKey] = enabled }
    }

    suspend fun setLivingArtworkBackground(enabled: Boolean) {
        context.podcastyDataStore.edit { it[Keys.LivingArtworkKey] = enabled }
    }

    suspend fun setShowSpeedBadge(enabled: Boolean) {
        context.podcastyDataStore.edit { it[Keys.ShowSpeedBadgeKey] = enabled }
    }

    suspend fun setKeepScreenOnForTranscript(enabled: Boolean) {
        context.podcastyDataStore.edit { it[Keys.KeepScreenOnTranscriptKey] = enabled }
    }

    suspend fun setDefaultPlaybackSpeed(speed: Float) {
        context.podcastyDataStore.edit { it[Keys.DefaultSpeedKey] = speed.coerceIn(0.5f, 2.5f) }
    }

    suspend fun setSkipBackwardSeconds(seconds: Int) {
        context.podcastyDataStore.edit { it[Keys.SkipBackwardKey] = seconds }
    }

    suspend fun setSkipForwardSeconds(seconds: Int) {
        context.podcastyDataStore.edit { it[Keys.SkipForwardKey] = seconds }
    }

    suspend fun setAutoDownloadSaved(enabled: Boolean) {
        context.podcastyDataStore.edit { it[Keys.AutoDownloadSavedKey] = enabled }
    }

    suspend fun setPodcastLayoutMode(mode: PodcastLayoutMode) {
        context.podcastyDataStore.edit {
            it[Keys.PodcastLayoutModeKey] = if (mode == PodcastLayoutMode.List) "list" else "grid"
        }
    }

    suspend fun setDefaultMusicFolder(uri: String?, displayName: String?) {
        context.podcastyDataStore.edit { prefs ->
            if (uri == null) {
                prefs.remove(Keys.DefaultMusicFolderUriKey)
                prefs.remove(Keys.DefaultMusicFolderNameKey)
            } else {
                prefs[Keys.DefaultMusicFolderUriKey] = uri
                prefs[Keys.DefaultMusicFolderNameKey] = displayName ?: uri
            }
        }
    }

    suspend fun setAutoLibraryRefresh(enabled: Boolean) {
        context.podcastyDataStore.edit { it[Keys.AutoLibraryRefreshKey] = enabled }
    }

    suspend fun setIncludeSubfolders(enabled: Boolean) {
        context.podcastyDataStore.edit { it[Keys.IncludeSubfoldersKey] = enabled }
    }

    suspend fun setFolderViewMode(mode: FolderViewMode) {
        context.podcastyDataStore.edit { it[Keys.FolderViewModeKey] = mode.name }
    }

    suspend fun setFolderSortOption(option: FolderSortOption) {
        context.podcastyDataStore.edit { it[Keys.FolderSortOptionKey] = option.name }
    }

    suspend fun setSongSortOption(option: SongSortOption) {
        context.podcastyDataStore.edit { it[Keys.SongSortOptionKey] = option.name }
    }

    suspend fun setMusicLibraryCategory(category: MusicLibraryCategory) {
        context.podcastyDataStore.edit { it[Keys.MusicLibraryCategoryKey] = category.name }
    }

    suspend fun setLastFolderPosition(treeUri: String?, subpath: String) {
        context.podcastyDataStore.edit { prefs ->
            if (treeUri == null) prefs.remove(Keys.LastFolderTreeUriKey) else prefs[Keys.LastFolderTreeUriKey] = treeUri
            prefs[Keys.LastFolderSubpathKey] = subpath
        }
    }

    suspend fun setArtworkResolverVersion(version: Int) {
        context.podcastyDataStore.edit { it[Keys.ArtworkResolverVersionKey] = version }
    }

    suspend fun setAutoSyncLyrics(enabled: Boolean) {
        context.podcastyDataStore.edit { it[Keys.AutoSyncLyricsKey] = enabled }
    }

    suspend fun setAutoScrollLyrics(enabled: Boolean) {
        context.podcastyDataStore.edit { it[Keys.AutoScrollLyricsKey] = enabled }
    }

    suspend fun setPreferredTranscriptLanguage(lang: String) {
        context.podcastyDataStore.edit { it[Keys.PreferredTranscriptLanguageKey] = lang }
    }

    suspend fun setCaptionFontSizeScale(scale: Float) {
        context.podcastyDataStore.edit { it[Keys.CaptionFontSizeScaleKey] = scale.coerceIn(0.75f, 1.5f) }
    }

    suspend fun setShowLockScreenLyrics(enabled: Boolean) {
        context.podcastyDataStore.edit { it[Keys.ShowLockScreenLyricsKey] = enabled }
    }

    suspend fun setLockScreenLyricsExpanded(expanded: Boolean) {
        context.podcastyDataStore.edit { it[Keys.LockScreenLyricsExpandedKey] = expanded }
    }

    suspend fun setShowMiniPlayerLyrics(enabled: Boolean) {
        context.podcastyDataStore.edit { it[Keys.ShowMiniPlayerLyricsKey] = enabled }
    }

    suspend fun setShowLyricsTranslation(enabled: Boolean) {
        context.podcastyDataStore.edit { it[Keys.ShowLyricsTranslationKey] = enabled }
    }

    suspend fun setShowLockScreenCaptions(enabled: Boolean) {
        context.podcastyDataStore.edit { it[Keys.ShowLockScreenCaptionsKey] = enabled }
    }

    suspend fun setShowTranscriptNotification(enabled: Boolean) {
        context.podcastyDataStore.edit { it[Keys.ShowTranscriptNotificationKey] = enabled }
    }

    suspend fun markNotificationPermissionRequested() {
        context.podcastyDataStore.edit { it[Keys.HasRequestedNotificationPermissionKey] = true }
    }

    suspend fun setSleepFadeOutSeconds(seconds: Int) {
        context.podcastyDataStore.edit { it[Keys.SleepFadeOutSecondsKey] = seconds.coerceIn(0, 120) }
    }

    suspend fun setResumePreviousPlayback(enabled: Boolean) {
        context.podcastyDataStore.edit { it[Keys.ResumePreviousPlaybackKey] = enabled }
    }

    suspend fun setGaplessPlayback(enabled: Boolean) {
        context.podcastyDataStore.edit {
            it[Keys.GaplessPlaybackKey] = enabled
            if (enabled) it[Keys.CrossfadeDurationKey] = 0
        }
    }

    suspend fun setCrossfadeDurationMs(durationMs: Int) {
        val clamped = durationMs.coerceIn(0, 6_000)
        context.podcastyDataStore.edit {
            it[Keys.CrossfadeDurationKey] = clamped
            if (clamped > 0) it[Keys.GaplessPlaybackKey] = false
        }
    }

    suspend fun setPauseOnHeadphoneDisconnect(enabled: Boolean) {
        context.podcastyDataStore.edit { it[Keys.PauseOnHeadphoneDisconnectKey] = enabled }
    }

    suspend fun setBluetoothMediaControls(enabled: Boolean) {
        context.podcastyDataStore.edit { it[Keys.BluetoothMediaControlsKey] = enabled }
    }

    suspend fun setAutoResumeOnBluetoothReconnect(enabled: Boolean) {
        context.podcastyDataStore.edit { it[Keys.AutoResumeBluetoothKey] = enabled }
    }

    suspend fun addRecentSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return
        context.podcastyDataStore.edit { prefs ->
            val current = prefs[Keys.RecentSearchesKey]?.let {
                runCatching { json.decodeFromString<List<String>>(it) }.getOrDefault(emptyList())
            }.orEmpty()
            val updated = (listOf(trimmed) + current.filterNot { it.equals(trimmed, ignoreCase = true) }).take(8)
            prefs[Keys.RecentSearchesKey] = json.encodeToString(updated)
        }
    }

    suspend fun removeRecentSearch(query: String) {
        context.podcastyDataStore.edit { prefs ->
            val current = prefs[Keys.RecentSearchesKey]?.let {
                runCatching { json.decodeFromString<List<String>>(it) }.getOrDefault(emptyList())
            }.orEmpty()
            val updated = current.filterNot { it == query }
            prefs[Keys.RecentSearchesKey] = json.encodeToString(updated)
        }
    }

    suspend fun clearRecentSearches() {
        context.podcastyDataStore.edit { prefs ->
            prefs[Keys.RecentSearchesKey] = "[]"
        }
    }

    suspend fun setLastEpisodeId(episodeId: String?) {
        context.podcastyDataStore.edit { prefs ->
            if (episodeId == null) prefs.remove(Keys.LastEpisodeIdKey)
            else prefs[Keys.LastEpisodeIdKey] = episodeId
        }
    }

    suspend fun setAutoStartLiveCaptions(enabled: Boolean) {
        context.podcastyDataStore.edit { it[Keys.AutoStartLiveCaptionsKey] = enabled }
    }

    suspend fun setSaveCaptionHistory(enabled: Boolean) {
        context.podcastyDataStore.edit { it[Keys.SaveCaptionHistoryKey] = enabled }
    }

    suspend fun setAsrEngineId(engineId: String) {
        context.podcastyDataStore.edit { it[Keys.AsrEngineIdKey] = engineId }
    }

    suspend fun setAsrLanguageCode(languageCode: String) {
        context.podcastyDataStore.edit { it[Keys.AsrLanguageCodeKey] = languageCode }
    }

    suspend fun setAsrBackendUrl(url: String) {
        context.podcastyDataStore.edit { it[Keys.AsrBackendUrlKey] = url.trim() }
    }

    suspend fun saveEqualizerState(
        enabled: Boolean,
        presetId: String,
        bandLevelsCsv: String,
        preampDb: Float,
        autoClippingProtection: Boolean,
        bassBoostStrength: Int,
        virtualizerStrength: Int,
        balance: Float,
        monoAudio: Boolean,
    ) {
        context.podcastyDataStore.edit { prefs ->
            prefs[Keys.EqEnabledKey] = enabled
            prefs[Keys.EqPresetIdKey] = presetId
            prefs[Keys.EqBandLevelsCsvKey] = bandLevelsCsv
            prefs[Keys.EqPreampDbKey] = preampDb.coerceIn(-15f, 6f)
            prefs[Keys.EqAutoClippingProtectionKey] = autoClippingProtection
            prefs[Keys.EqBassBoostStrengthKey] = bassBoostStrength.coerceIn(0, 1000)
            prefs[Keys.EqVirtualizerStrengthKey] = virtualizerStrength.coerceIn(0, 1000)
            prefs[Keys.EqBalanceKey] = balance.coerceIn(-1f, 1f)
            prefs[Keys.EqMonoAudioKey] = monoAudio
        }
    }
}
