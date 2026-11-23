/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.asr

import kotlinx.coroutines.flow.Flow

/**
 * Modular, provider-agnostic real-time speech recognition interface.
 *
 * Implementations:
 * - [GoogleStreamingSpeechEngine]: Secure WebSocket -> Node.js backend -> Google Cloud Speech-to-Text (en-US, ta-IN, ...)
 * - [SherpaStreamingSpeechEngine]: Offline streaming Sherpa-ONNX adapter (on-device models)
 * - [WhisperSpeechEngine]: Offline multilingual Whisper.cpp adapter (on-device GGML models)
 */
interface SpeechRecognitionEngine {
    val engineType: AsrEngineType
    val displayName: String
    val supportedLanguages: Set<String>
    val requiresNetwork: Boolean

    fun checkAvailability(config: RecognitionSessionConfig): EngineAvailability

    fun startSession(config: RecognitionSessionConfig): Flow<RecognitionEvent>

    fun feedAudioChunk(chunk: ProcessedAudioChunk)

    fun pauseSession()

    fun resumeSession(newMediaPositionMs: Long)

    fun stopSession()
}
