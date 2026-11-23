/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.asr

import kotlinx.serialization.Serializable

enum class LiveCaptionStatus {
    PARTIAL,
    FINAL,
}

enum class CaptionTimingPrecision {
    EXACT,
    ESTIMATED,
}

@Serializable
data class LiveCaptionSegment(
    val id: String,
    val sessionId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val language: String,
    val status: LiveCaptionStatus,
    val timingPrecision: CaptionTimingPrecision = CaptionTimingPrecision.EXACT,
)

val AllSupportedAsrLanguageCodes: Set<String> = setOf(
    "ta-IN", "ta-LK", "si-LK",
    "en-US", "en-IN", "en-GB",
    "hi-IN", "ml-IN", "te-IN", "kn-IN", "mr-IN", "bn-IN", "gu-IN", "pa-Guru-IN", "ur-IN",
    "ar-SA", "zh-CN", "ja-JP", "ko-KR", "es-ES", "fr-FR", "de-DE", "pt-BR", "ru-RU",
    "it-IT", "tr-TR", "id-ID", "ms-MY", "th-TH", "vi-VN",
)

val Bcp47LanguageTagRegex = Regex("^[a-zA-Z]{2,3}(?:-[a-zA-Z0-9]{2,8}){0,3}$")

fun isSupportedAsrLanguageTag(languageCode: String): Boolean {
    val trimmed = languageCode.trim()
    return trimmed in AllSupportedAsrLanguageCodes || Bcp47LanguageTagRegex.matches(trimmed)
}

enum class AsrEngineType(
    val id: String,
    val displayName: String,
    val requiresNetwork: Boolean,
    val supportedLanguages: Set<String>,
) {
    GoogleCloud(
        id = "google_cloud",
        displayName = "Google Cloud Speech-to-Text (Streaming)",
        requiresNetwork = true,
        supportedLanguages = AllSupportedAsrLanguageCodes,
    ),
    SherpaOnnx(
        id = "sherpa_onnx",
        displayName = "Sherpa-ONNX (Offline Streaming)",
        requiresNetwork = false,
        supportedLanguages = AllSupportedAsrLanguageCodes,
    ),
    WhisperCpp(
        id = "whisper_cpp",
        displayName = "Whisper.cpp (Offline Multilingual)",
        requiresNetwork = false,
        supportedLanguages = AllSupportedAsrLanguageCodes,
    ),
    ;

    companion object {
        fun fromId(id: String?): AsrEngineType =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: GoogleCloud
    }
}

data class AsrLanguageOption(
    val code: String,
    val displayName: String,
    val shortLabel: String,
)

val SupportedAsrLanguages: List<AsrLanguageOption> = listOf(
    AsrLanguageOption("ta-IN", "Tamil (தமிழ் · India)", "TA-IN"),
    AsrLanguageOption("ta-LK", "Tamil (தமிழ் · Sri Lanka)", "TA-LK"),
    AsrLanguageOption("si-LK", "Sinhala (සිංහල · Sri Lanka)", "SI-LK"),
    AsrLanguageOption("en-US", "English (US)", "EN-US"),
    AsrLanguageOption("en-IN", "English (India)", "EN-IN"),
    AsrLanguageOption("en-GB", "English (UK)", "EN-GB"),
    AsrLanguageOption("hi-IN", "Hindi (हिन्दी)", "HI-IN"),
    AsrLanguageOption("ml-IN", "Malayalam (മലയാളം)", "ML-IN"),
    AsrLanguageOption("te-IN", "Telugu (తెలుగు)", "TE-IN"),
    AsrLanguageOption("kn-IN", "Kannada (ಕನ್ನಡ)", "KN-IN"),
    AsrLanguageOption("mr-IN", "Marathi (मराठी)", "MR-IN"),
    AsrLanguageOption("bn-IN", "Bengali (বাংলা)", "BN-IN"),
    AsrLanguageOption("gu-IN", "Gujarati (ગુજરાતી)", "GU-IN"),
    AsrLanguageOption("pa-Guru-IN", "Punjabi (ਪੰਜਾਬੀ)", "PA-IN"),
    AsrLanguageOption("ur-IN", "Urdu (اردو)", "UR-IN"),
    AsrLanguageOption("ar-SA", "Arabic (العربية)", "AR-SA"),
    AsrLanguageOption("zh-CN", "Chinese (中文)", "ZH-CN"),
    AsrLanguageOption("ja-JP", "Japanese (日本語)", "JA-JP"),
    AsrLanguageOption("ko-KR", "Korean (한국어)", "KO-KR"),
    AsrLanguageOption("es-ES", "Spanish (Español)", "ES-ES"),
    AsrLanguageOption("fr-FR", "French (Français)", "FR-FR"),
    AsrLanguageOption("de-DE", "German (Deutsch)", "DE-DE"),
    AsrLanguageOption("pt-BR", "Portuguese (Português)", "PT-BR"),
    AsrLanguageOption("ru-RU", "Russian (Русский)", "RU-RU"),
    AsrLanguageOption("it-IT", "Italian (Italiano)", "IT-IT"),
    AsrLanguageOption("tr-TR", "Turkish (Türkçe)", "TR-TR"),
    AsrLanguageOption("id-ID", "Indonesian (Bahasa)", "ID-ID"),
    AsrLanguageOption("ms-MY", "Malay (Melayu)", "MS-MY"),
    AsrLanguageOption("th-TH", "Thai (ไทย)", "TH-TH"),
    AsrLanguageOption("vi-VN", "Vietnamese (Tiếng Việt)", "VI-VN"),
)

sealed interface EngineAvailability {
    data object Ready : EngineAvailability
    data class ConfigurationRequired(val message: String) : EngineAvailability
    data class ModelRequired(val message: String, val modelDirectoryPath: String) : EngineAvailability
    data class UnsupportedLanguage(val languageCode: String, val supported: Set<String>) : EngineAvailability
}

data class RecognitionSessionConfig(
    val sessionId: String,
    val episodeId: String,
    val languageCode: String = "en-US",
    val sampleRateHz: Int = 16_000,
    val initialMediaPositionMs: Long = 0L,
    val backendWebSocketUrl: String = "",
    val authToken: String? = null,
)

data class ProcessedAudioChunk(
    val pcm16Bytes: ByteArray,
    val sampleRateHz: Int,
    val sampleCount: Int,
    val mediaStartMs: Long,
    val mediaEndMs: Long,
    val isSpeech: Boolean,
    val rmsEnergy: Float,
)

sealed interface RecognitionEvent {
    data class Connected(val sessionId: String, val engineName: String) : RecognitionEvent
    data class Caption(val segment: LiveCaptionSegment) : RecognitionEvent
    data class Reconnecting(val attempt: Int, val reason: String) : RecognitionEvent
    data class Error(val message: String, val recoverable: Boolean) : RecognitionEvent
    data class Closed(val reason: String) : RecognitionEvent
}

enum class LiveCaptionSessionStatus {
    Idle,
    Initializing,
    Listening,
    Paused,
    Reconnecting,
    ConfigurationRequired,
    Error,
}

data class LiveCaptionUiState(
    val isPodcastCaptionMode: Boolean = false,
    val episodeId: String? = null,
    val sessionId: String? = null,
    val engineType: AsrEngineType = AsrEngineType.GoogleCloud,
    val languageCode: String = "en-US",
    val status: LiveCaptionSessionStatus = LiveCaptionSessionStatus.Idle,
    val finalizedSegments: List<LiveCaptionSegment> = emptyList(),
    val activePartial: LiveCaptionSegment? = null,
    val activeSegmentIndex: Int = -1,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val isSpeechDetected: Boolean = false,
) {
    val allDisplaySegments: List<LiveCaptionSegment>
        get() = if (activePartial != null) finalizedSegments + activePartial else finalizedSegments
}
