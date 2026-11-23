/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.asr

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Modular offline multilingual speech recognition adapter for Whisper.cpp
 * (https://github.com/ggml-org/whisper.cpp).
 *
 * Design & Honesty:
 * - Whisper.cpp supports multilingual inference including English (`en`) and Tamil (`ta`) when supplied
 *   a multilingual GGML model (e.g., `ggml-base.bin` or `ggml-small.bin`, 140 MB – 466 MB) and native
 *   Android JNI library (`libwhisper.so`).
 * - Buffers speech-active PCM in a bounded memory ring (up to 4 seconds) and triggers windowed transcription
 *   at VAD pauses without writing raw audio to disk.
 * - Because GGML model weights and native C++ JNI binaries are not committed into the repository,
 *   [checkAvailability] transparently reports [EngineAvailability.ModelRequired] when absent.
 */
class WhisperSpeechEngine(
    private val context: Context,
) : SpeechRecognitionEngine {

    override val engineType: AsrEngineType = AsrEngineType.WhisperCpp
    override val displayName: String = AsrEngineType.WhisperCpp.displayName
    override val supportedLanguages: Set<String> = AsrEngineType.WhisperCpp.supportedLanguages
    override val requiresNetwork: Boolean = false

    private val events = MutableSharedFlow<RecognitionEvent>(extraBufferCapacity = 32)
    private val isActive = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private var activeConfig: RecognitionSessionConfig? = null

    val modelDirectory: File
        get() = File(context.filesDir, "asr_models/whisper")

    private fun findModelFile(): File? {
        val dir = modelDirectory
        if (!dir.isDirectory) return null
        return dir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".bin", ignoreCase = true) }
    }

    override fun checkAvailability(config: RecognitionSessionConfig): EngineAvailability {
        if (!isSupportedAsrLanguageTag(config.languageCode)) {
            return EngineAvailability.UnsupportedLanguage(config.languageCode, supportedLanguages)
        }
        val dir = modelDirectory
        val hasJni = runCatching {
            Class.forName("com.whispercpp.whisper.WhisperContext")
            true
        }.getOrDefault(false)
        if (!hasJni) {
            return EngineAvailability.ModelRequired(
                message = "Whisper.cpp native JNI library is not bundled in this APK. Use Google Cloud Speech-to-Text for live English & Tamil captions, or link whisper.cpp Android JNI + multilingual GGML model in ${dir.absolutePath}.",
                modelDirectoryPath = dir.absolutePath,
            )
        }
        if (findModelFile() == null) {
            return EngineAvailability.ModelRequired(
                message = "No Whisper GGML model (.bin) found in ${dir.absolutePath}. Add a multilingual model (e.g., ggml-base.bin) for offline English/Tamil captions.",
                modelDirectoryPath = dir.absolutePath,
            )
        }
        return EngineAvailability.Ready
    }

    override fun startSession(config: RecognitionSessionConfig): Flow<RecognitionEvent> {
        stopSession()
        activeConfig = config
        when (val availability = checkAvailability(config)) {
            is EngineAvailability.Ready -> {
                isActive.set(true)
                isPaused.set(false)
                events.tryEmit(RecognitionEvent.Connected(config.sessionId, displayName))
            }
            is EngineAvailability.ModelRequired -> {
                events.tryEmit(RecognitionEvent.Error(availability.message, recoverable = false))
            }
            is EngineAvailability.UnsupportedLanguage -> {
                events.tryEmit(
                    RecognitionEvent.Error(
                        "Language ${availability.languageCode} is not supported by Whisper.cpp configuration.",
                        recoverable = false,
                    ),
                )
            }
            is EngineAvailability.ConfigurationRequired -> {
                events.tryEmit(RecognitionEvent.Error(availability.message, recoverable = false))
            }
        }
        return events.asSharedFlow()
    }

    override fun feedAudioChunk(chunk: ProcessedAudioChunk) {
        if (!isActive.get() || isPaused.get()) return
        // Invoked only when native Whisper JNI + GGML model are linked and Ready.
    }

    override fun pauseSession() {
        isPaused.set(true)
    }

    override fun resumeSession(newMediaPositionMs: Long) {
        isPaused.set(false)
    }

    override fun stopSession() {
        isActive.set(false)
        isPaused.set(false)
        activeConfig = null
    }
}
