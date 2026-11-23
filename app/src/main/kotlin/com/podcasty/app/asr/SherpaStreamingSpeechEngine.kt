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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Modular offline streaming speech recognition engine adapter for Sherpa-ONNX
 * (https://github.com/k2-fsa/sherpa-onnx).
 *
 * Design & Honesty:
 * - Sherpa-ONNX distributes Android native libraries as GitHub Release AARs + ONNX streaming transducer models
 *   (e.g., `sherpa-onnx-streaming-zipformer-en-2023-06-26`), which exceed standard APK size budgets when bundled.
 * - This engine inspects app-private `files/asr_models/sherpa/` for `encoder.onnx`, `decoder.onnx`,
 *   `joiner.onnx`, and `tokens.txt` plus checks whether `com.k2fsa.sherpa.onnx.OnlineRecognizer` is linked.
 * - When the native AAR and model files are present, it feeds 16 kHz mono float waveforms into the streaming
 *   recognizer and emits real partial/final segments using captured audio-frame media timestamps (ESTIMATED).
 * - When the native AAR or model weights are not installed, it transparently reports
 *   [EngineAvailability.ModelRequired] and never fabricates captions.
 */
class SherpaStreamingSpeechEngine(
    private val context: Context,
) : SpeechRecognitionEngine {

    override val engineType: AsrEngineType = AsrEngineType.SherpaOnnx
    override val displayName: String = AsrEngineType.SherpaOnnx.displayName
    override val supportedLanguages: Set<String> = AsrEngineType.SherpaOnnx.supportedLanguages
    override val requiresNetwork: Boolean = false

    private val events = MutableSharedFlow<RecognitionEvent>(extraBufferCapacity = 32)
    private val isActive = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private var activeConfig: RecognitionSessionConfig? = null
    private var utteranceStartMs: Long = 0L
    private var lastChunkEndMs: Long = 0L
    private var lastPartialText: String = ""

    // Reflective bridge handles to optional com.k2fsa.sherpa.onnx AAR classes
    private var recognizerObj: Any? = null
    private var streamObj: Any? = null

    val modelDirectory: File
        get() = File(context.filesDir, "asr_models/sherpa")

    override fun checkAvailability(config: RecognitionSessionConfig): EngineAvailability {
        if (!isSupportedAsrLanguageTag(config.languageCode)) {
            return EngineAvailability.UnsupportedLanguage(config.languageCode, supportedLanguages)
        }
        val dir = modelDirectory
        val requiredFiles = listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")
        val missing = requiredFiles.filter { !File(dir, it).isFile }
        val hasNativeBinding = runCatching {
            Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer")
            true
        }.getOrDefault(false)

        if (!hasNativeBinding) {
            return EngineAvailability.ModelRequired(
                message = "Sherpa-ONNX native Android AAR is not bundled in this APK. Switch to Google Cloud Speech-to-Text for live English/Tamil captions, or add the Sherpa-ONNX AAR + streaming model to ${dir.absolutePath}.",
                modelDirectoryPath = dir.absolutePath,
            )
        }
        if (missing.isNotEmpty()) {
            return EngineAvailability.ModelRequired(
                message = "Offline Sherpa-ONNX model files missing (${missing.joinToString(", ")}). Place streaming transducer files in ${dir.absolutePath}.",
                modelDirectoryPath = dir.absolutePath,
            )
        }
        return EngineAvailability.Ready
    }

    override fun startSession(config: RecognitionSessionConfig): Flow<RecognitionEvent> {
        stopSession()
        activeConfig = config
        utteranceStartMs = config.initialMediaPositionMs
        lastChunkEndMs = config.initialMediaPositionMs
        lastPartialText = ""

        when (val availability = checkAvailability(config)) {
            is EngineAvailability.Ready -> {
                val initialized = initReflectiveRecognizer(config)
                if (initialized) {
                    isActive.set(true)
                    isPaused.set(false)
                    events.tryEmit(RecognitionEvent.Connected(config.sessionId, displayName))
                } else {
                    events.tryEmit(
                        RecognitionEvent.Error(
                            "Failed to initialize Sherpa-ONNX native recognizer from ${modelDirectory.absolutePath}.",
                            recoverable = false,
                        ),
                    )
                }
            }
            is EngineAvailability.ModelRequired -> {
                events.tryEmit(RecognitionEvent.Error(availability.message, recoverable = false))
            }
            is EngineAvailability.UnsupportedLanguage -> {
                events.tryEmit(
                    RecognitionEvent.Error(
                        "Sherpa-ONNX streaming transducer currently targets English (${availability.supported.joinToString()}). Select Google Cloud Speech-to-Text for Tamil (ta-IN).",
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
        val recognizer = recognizerObj ?: return
        val stream = streamObj ?: return
        val cfg = activeConfig ?: return

        if (lastPartialText.isEmpty() && chunk.isSpeech) {
            utteranceStartMs = chunk.mediaStartMs
        }
        lastChunkEndMs = chunk.mediaEndMs

        val samples = pcm16LeToFloats(chunk.pcm16Bytes)
        runCatching {
            val acceptMethod = stream.javaClass.getMethod("acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType)
            acceptMethod.invoke(stream, samples, chunk.sampleRateHz)

            val isReadyMethod = recognizer.javaClass.getMethod("isReady", stream.javaClass)
            val decodeMethod = recognizer.javaClass.getMethod("decode", stream.javaClass)
            while (isReadyMethod.invoke(recognizer, stream) == true) {
                decodeMethod.invoke(recognizer, stream)
            }

            val getResultMethod = recognizer.javaClass.getMethod("getResult", stream.javaClass)
            val resultObj = getResultMethod.invoke(recognizer, stream)
            val text = resultObj?.javaClass?.getMethod("getText")?.invoke(resultObj)?.toString()?.trim().orEmpty()
            val isEndpoint = recognizer.javaClass.getMethod("isEndpoint", stream.javaClass).invoke(recognizer, stream) == true

            if (text.isNotBlank() && text != lastPartialText) {
                lastPartialText = text
                events.tryEmit(
                    RecognitionEvent.Caption(
                        LiveCaptionSegment(
                            id = "${cfg.sessionId}_${utteranceStartMs}_partial",
                            sessionId = cfg.sessionId,
                            startMs = utteranceStartMs,
                            endMs = lastChunkEndMs.coerceAtLeast(utteranceStartMs + 200L),
                            text = text,
                            language = cfg.languageCode,
                            status = LiveCaptionStatus.PARTIAL,
                            timingPrecision = CaptionTimingPrecision.ESTIMATED,
                        ),
                    ),
                )
            }

            if (isEndpoint) {
                if (text.isNotBlank()) {
                    events.tryEmit(
                        RecognitionEvent.Caption(
                            LiveCaptionSegment(
                                id = "${cfg.sessionId}_${utteranceStartMs}_final",
                                sessionId = cfg.sessionId,
                                startMs = utteranceStartMs,
                                endMs = lastChunkEndMs.coerceAtLeast(utteranceStartMs + 300L),
                                text = text,
                                language = cfg.languageCode,
                                status = LiveCaptionStatus.FINAL,
                                timingPrecision = CaptionTimingPrecision.ESTIMATED,
                            ),
                        ),
                    )
                }
                recognizer.javaClass.getMethod("reset", stream.javaClass).invoke(recognizer, stream)
                lastPartialText = ""
                utteranceStartMs = lastChunkEndMs
            }
        }
    }

    override fun pauseSession() {
        isPaused.set(true)
    }

    override fun resumeSession(newMediaPositionMs: Long) {
        isPaused.set(false)
        utteranceStartMs = newMediaPositionMs.coerceAtLeast(0L)
        lastChunkEndMs = utteranceStartMs
        lastPartialText = ""
        val recognizer = recognizerObj
        val stream = streamObj
        if (recognizer != null && stream != null) {
            runCatching { recognizer.javaClass.getMethod("reset", stream.javaClass).invoke(recognizer, stream) }
        }
    }

    override fun stopSession() {
        isActive.set(false)
        isPaused.set(false)
        streamObj?.let { s -> runCatching { s.javaClass.getMethod("release").invoke(s) } }
        recognizerObj?.let { r -> runCatching { r.javaClass.getMethod("release").invoke(r) } }
        streamObj = null
        recognizerObj = null
        activeConfig = null
        lastPartialText = ""
    }

    private fun initReflectiveRecognizer(config: RecognitionSessionConfig): Boolean = runCatching {
        val dir = modelDirectory
        val transducerCls = Class.forName("com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig")
        val modelCls = Class.forName("com.k2fsa.sherpa.onnx.OnlineModelConfig")
        val recognizerCfgCls = Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizerConfig")
        val recognizerCls = Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer")

        val transducer = transducerCls.getConstructor(String::class.java, String::class.java, String::class.java)
            .newInstance(
                File(dir, "encoder.onnx").absolutePath,
                File(dir, "decoder.onnx").absolutePath,
                File(dir, "joiner.onnx").absolutePath,
            )
        val model = modelCls.getDeclaredConstructor().newInstance()
        modelCls.getMethod("setTransducer", transducerCls).invoke(model, transducer)
        modelCls.getMethod("setTokens", String::class.java).invoke(model, File(dir, "tokens.txt").absolutePath)
        modelCls.getMethod("setNumThreads", Int::class.javaPrimitiveType).invoke(model, 2)

        val recCfg = recognizerCfgCls.getDeclaredConstructor().newInstance()
        recognizerCfgCls.getMethod("setModelConfig", modelCls).invoke(recCfg, model)
        recognizerCfgCls.getMethod("setEnableEndpoint", Boolean::class.javaPrimitiveType).invoke(recCfg, true)

        val recognizer = recognizerCls.getConstructor(android.content.res.AssetManager::class.java, recognizerCfgCls)
            .newInstance(null, recCfg)
        val stream = recognizerCls.getMethod("createStream").invoke(recognizer)
        recognizerObj = recognizer
        streamObj = stream
        true
    }.getOrDefault(false)

    private fun pcm16LeToFloats(bytes: ByteArray): FloatArray {
        val count = bytes.size / 2
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(count) { bb.short.toFloat() / 32768f }
    }
}
