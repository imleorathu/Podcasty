/*
 * Podcasty — Android Podcast & Music Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.domain

enum class RepeatMode {
    Off,
    All,
    One,
    ;

    fun next(): RepeatMode = when (this) {
        Off -> All
        All -> One
        One -> Off
    }
}

data class PlaybackItem(
    val episodeId: String,
    val podcastId: String,
    val hostId: String,
    val title: String,
    val podcastTitle: String,
    val hostName: String,
    val artworkAsset: String?,
    val audioAsset: String,
    val durationMs: Long,
    val sourceType: MediaSourceType = MediaSourceType.Demo,
    val sourceUri: String? = null,
    val youtubeUrl: String? = null,
    val linkedAudioUri: String? = null,
)

sealed interface PlaybackState {
    data object Idle : PlaybackState
    data class Preparing(val item: PlaybackItem) : PlaybackState
    data class Playing(
        val item: PlaybackItem,
        val positionMs: Long,
        val durationMs: Long,
        val playbackSpeed: Float = 1.0f,
    ) : PlaybackState

    data class Paused(
        val item: PlaybackItem,
        val positionMs: Long,
        val durationMs: Long,
        val playbackSpeed: Float = 1.0f,
    ) : PlaybackState

    data class Failed(val message: String, val item: PlaybackItem? = null) : PlaybackState
}

fun PlaybackState.showsMiniPlayer(): Boolean = when (this) {
    PlaybackState.Idle -> false
    is PlaybackState.Failed -> item != null
    is PlaybackState.Preparing, is PlaybackState.Playing, is PlaybackState.Paused -> true
}

fun PlaybackState.itemOrNull(): PlaybackItem? = when (this) {
    is PlaybackState.Preparing -> item
    is PlaybackState.Playing -> item
    is PlaybackState.Paused -> item
    is PlaybackState.Failed -> item
    PlaybackState.Idle -> null
}

fun PlaybackState.positionMsOrZero(): Long = when (this) {
    is PlaybackState.Playing -> positionMs
    is PlaybackState.Paused -> positionMs
    else -> 0L
}

fun PlaybackState.durationMsOrZero(): Long = when (this) {
    is PlaybackState.Playing -> durationMs
    is PlaybackState.Paused -> durationMs
    is PlaybackState.Preparing -> item.durationMs
    is PlaybackState.Failed -> item?.durationMs ?: 0L
    else -> 0L
}

fun PlaybackState.playbackSpeedOrDefault(): Float = when (this) {
    is PlaybackState.Playing -> playbackSpeed
    is PlaybackState.Paused -> playbackSpeed
    else -> 1.0f
}

data class PlaybackQueueSnapshot(
    val activeQueueId: String = MusicQueue.DefaultQueueId,
    val activeQueueName: String = MusicQueue.DefaultQueueName,
    val activeEpisodeIds: List<String> = emptyList(),
    val currentIndex: Int = -1,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.Off,
) {
    val currentEpisodeId: String?
        get() = activeEpisodeIds.getOrNull(currentIndex)

    fun canNavigatePrevious(): Boolean = activeEpisodeIds.isNotEmpty()
    fun canNavigateNext(): Boolean = activeEpisodeIds.isNotEmpty()
}

enum class SleepTimerPreset(val minutes: Int?) {
    Off(null),
    FiveMin(5),
    TenMin(10),
    FifteenMin(15),
    TwentyMin(20),
    ThirtyMin(30),
    FortyFiveMin(45),
    SixtyMin(60),
    CustomDuration(null),
    ClockTime(null),
    EndOfEpisode(-1),
    AfterTracks(-2),
}

data class SleepTimerRequest(
    val preset: SleepTimerPreset,
    val customMinutes: Int? = null,
    val trackCount: Int? = null,
    val fadeOutSeconds: Int = 30,
    val deadlineEpochMs: Long? = null,
)

data class SleepTimerState(
    val preset: SleepTimerPreset = SleepTimerPreset.Off,
    val remainingSeconds: Int? = null,
    val remainingTracks: Int? = null,
    val fadeOutSeconds: Int = 30,
    val isFadingOut: Boolean = false,
    val currentVolumeMultiplier: Float = 1.0f,
) {
    val isActive: Boolean get() = preset != SleepTimerPreset.Off
}

val SupportedPlaybackSpeeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f)
val SupportedSkipBackwardSeconds = listOf(10, 15, 30)
val SupportedSkipForwardSeconds = listOf(15, 30, 45, 60)
val SupportedFadeOutSeconds = listOf(0, 10, 20, 30, 60)
val SupportedCaptionFontScales = listOf(0.85f, 1.0f, 1.15f, 1.3f)
data class TranscriptLanguageOption(
    val code: String,
    val displayName: String,
)

val SupportedTranscriptLanguageOptions: List<TranscriptLanguageOption> = listOf(
    TranscriptLanguageOption("auto", "Auto-detect (All languages)"),
    TranscriptLanguageOption("ta", "Tamil (தமிழ்)"),
    TranscriptLanguageOption("si", "Sinhala (සිංහල)"),
    TranscriptLanguageOption("en", "English"),
    TranscriptLanguageOption("hi", "Hindi (हिन्दी)"),
    TranscriptLanguageOption("ml", "Malayalam (മലയാളം)"),
    TranscriptLanguageOption("te", "Telugu (తెలుగు)"),
    TranscriptLanguageOption("kn", "Kannada (ಕನ್ನಡ)"),
    TranscriptLanguageOption("mr", "Marathi (मराठी)"),
    TranscriptLanguageOption("bn", "Bengali (বাংলা)"),
    TranscriptLanguageOption("gu", "Gujarati (ગુજરાતી)"),
    TranscriptLanguageOption("pa", "Punjabi (ਪੰਜਾਬੀ)"),
    TranscriptLanguageOption("ur", "Urdu (اردو)"),
    TranscriptLanguageOption("ar", "Arabic (العربية)"),
    TranscriptLanguageOption("zh", "Chinese (中文)"),
    TranscriptLanguageOption("ja", "Japanese (日本語)"),
    TranscriptLanguageOption("ko", "Korean (한국어)"),
    TranscriptLanguageOption("es", "Spanish (Español)"),
    TranscriptLanguageOption("fr", "French (Français)"),
    TranscriptLanguageOption("de", "German (Deutsch)"),
    TranscriptLanguageOption("pt", "Portuguese (Português)"),
    TranscriptLanguageOption("ru", "Russian (Русский)"),
    TranscriptLanguageOption("it", "Italian (Italiano)"),
    TranscriptLanguageOption("tr", "Turkish (Türkçe)"),
    TranscriptLanguageOption("id", "Indonesian (Bahasa Indonesia)"),
    TranscriptLanguageOption("ms", "Malay (Bahasa Melayu)"),
    TranscriptLanguageOption("th", "Thai (ไทย)"),
    TranscriptLanguageOption("vi", "Vietnamese (Tiếng Việt)"),
)

val SupportedTranscriptLanguages: List<String> = SupportedTranscriptLanguageOptions.map { it.code }

fun displayNameForTranscriptLanguage(code: String): String {
    val clean = code.trim()
    val exact = SupportedTranscriptLanguageOptions.firstOrNull { it.code.equals(clean, ignoreCase = true) }
    if (exact != null) return exact.displayName
    val primary = clean.substringBefore('-').substringBefore('_')
    val byPrimary = SupportedTranscriptLanguageOptions.firstOrNull { it.code.equals(primary, ignoreCase = true) }
    return byPrimary?.displayName ?: clean.uppercase()
}
