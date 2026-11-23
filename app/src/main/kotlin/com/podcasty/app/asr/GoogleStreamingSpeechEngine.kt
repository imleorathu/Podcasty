/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.asr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Production real-time streaming speech recognition engine connecting over Secure WebSocket (`wss://`)
 * to the Podcasty Node.js backend, which bridges bidirectional gRPC to Google Cloud Speech-to-Text.
 *
 * Binary audio frame wire format (16-byte header + LINEAR16 16kHz mono PCM payload):
 *   [0..7]   int64 big-endian mediaStartMs
 *   [8..15]  int64 big-endian mediaEndMs
 *   [16..N]  16-bit signed little-endian PCM bytes
 *
 * Security:
 * - Never embeds Google service-account credentials inside the Android app.
 * - Requires `wss://` in production; permits `ws://` strictly for local emulator/localhost development.
 * - Sends user-configured bearer token in Authorization header only, never in logs or query parameters.
 */
class GoogleStreamingSpeechEngine(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // required for long-lived WebSockets
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
) : SpeechRecognitionEngine {

    override val engineType: AsrEngineType = AsrEngineType.GoogleCloud
    override val displayName: String = AsrEngineType.GoogleCloud.displayName
    override val supportedLanguages: Set<String> = AsrEngineType.GoogleCloud.supportedLanguages
    override val requiresNetwork: Boolean = true

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = MutableSharedFlow<RecognitionEvent>(extraBufferCapacity = 64)

    private val activeConfig = AtomicReference<RecognitionSessionConfig?>(null)
    private val activeSocket = AtomicReference<WebSocket?>(null)
    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private var reconnectJob: Job? = null
    private var senderJob: Job? = null
    private var reconnectAttempts = 0

    // Bounded outgoing queue so slow networks never stall preprocessing or cause OOM
    private var outgoingChunks = Channel<ProcessedAudioChunk>(
        capacity = 24,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override fun checkAvailability(config: RecognitionSessionConfig): EngineAvailability {
        val url = config.backendWebSocketUrl.trim()
        if (url.isBlank()) {
            return EngineAvailability.ConfigurationRequired(
                "Configure your Podcasty Speech WebSocket backend URL in Settings → Lyrics & Captions to enable Google Cloud live captions.",
            )
        }
        val uri = runCatching { URI(url) }.getOrNull()
            ?: return EngineAvailability.ConfigurationRequired("Invalid ASR WebSocket URL.")
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        val isLocalDevHost = host == "10.0.2.2" || host == "localhost" || host == "127.0.0.1"
        if (scheme != "wss" && !(scheme == "ws" && isLocalDevHost)) {
            return EngineAvailability.ConfigurationRequired(
                "ASR backend URL must use Secure WebSocket (wss://). Plain ws:// is only allowed for localhost/10.0.2.2 development.",
            )
        }
        if (!isSupportedAsrLanguageTag(config.languageCode)) {
            return EngineAvailability.UnsupportedLanguage(config.languageCode, supportedLanguages)
        }
        return EngineAvailability.Ready
    }

    override fun startSession(config: RecognitionSessionConfig): Flow<RecognitionEvent> {
        stopSession()
        activeConfig.set(config)
        isRunning.set(true)
        isPaused.set(false)
        reconnectAttempts = 0
        outgoingChunks = Channel(capacity = 24, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        when (val availability = checkAvailability(config)) {
            is EngineAvailability.Ready -> {
                openWebSocket(config)
                startSenderLoop()
            }
            is EngineAvailability.ConfigurationRequired -> {
                isRunning.set(false)
                events.tryEmit(RecognitionEvent.Error(availability.message, recoverable = false))
            }
            is EngineAvailability.ModelRequired -> {
                isRunning.set(false)
                events.tryEmit(RecognitionEvent.Error(availability.message, recoverable = false))
            }
            is EngineAvailability.UnsupportedLanguage -> {
                isRunning.set(false)
                events.tryEmit(
                    RecognitionEvent.Error(
                        "Language ${availability.languageCode} is not supported by $displayName.",
                        recoverable = false,
                    ),
                )
            }
        }
        return events.asSharedFlow()
    }

    override fun feedAudioChunk(chunk: ProcessedAudioChunk) {
        if (!isRunning.get() || isPaused.get()) return
        if (!chunk.isSpeech) return
        outgoingChunks.trySend(chunk)
    }

    override fun pauseSession() {
        if (!isRunning.get()) return
        isPaused.set(true)
        drainOutgoing()
        val sessionId = activeConfig.get()?.sessionId ?: return
        sendControlMessage(
            buildJsonObject {
                put("type", "pause")
                put("sessionId", sessionId)
            },
        )
    }

    override fun resumeSession(newMediaPositionMs: Long) {
        val cfg = activeConfig.get() ?: return
        if (!isRunning.get()) return
        isPaused.set(false)
        drainOutgoing()
        val updated = cfg.copy(initialMediaPositionMs = newMediaPositionMs.coerceAtLeast(0L))
        activeConfig.set(updated)
        val socket = activeSocket.get()
        if (socket != null) {
            sendControlMessage(
                buildJsonObject {
                    put("type", "resume")
                    put("sessionId", updated.sessionId)
                    put("mediaStartMs", updated.initialMediaPositionMs)
                },
            )
        } else {
            openWebSocket(updated)
        }
    }

    override fun stopSession() {
        isRunning.set(false)
        isPaused.set(false)
        reconnectJob?.cancel()
        reconnectJob = null
        senderJob?.cancel()
        senderJob = null
        val cfg = activeConfig.getAndSet(null)
        val socket = activeSocket.getAndSet(null)
        if (socket != null && cfg != null) {
            runCatching {
                socket.send(
                    buildJsonObject {
                        put("type", "stop")
                        put("sessionId", cfg.sessionId)
                    }.toString(),
                )
                socket.close(1000, "Session stopped")
            }
        }
        drainOutgoing()
    }

    private fun openWebSocket(config: RecognitionSessionConfig) {
        if (!isRunning.get()) return
        val reqBuilder = Request.Builder().url(config.backendWebSocketUrl.trim())
        config.authToken?.trim()?.takeIf { it.isNotEmpty() }?.let { token ->
            reqBuilder.header("Authorization", "Bearer $token")
        }
        val socket = httpClient.newWebSocket(
            reqBuilder.build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!isRunning.get()) {
                        webSocket.close(1000, "Already stopped")
                        return
                    }
                    activeSocket.set(webSocket)
                    reconnectAttempts = 0
                    val startPayload = buildJsonObject {
                        put("type", "start")
                        put("sessionId", config.sessionId)
                        put("episodeId", config.episodeId)
                        put("languageCode", config.languageCode)
                        put("sampleRateHertz", config.sampleRateHz)
                        put("encoding", "LINEAR16")
                        put("enableInterimResults", true)
                        put("enableWordTimeOffsets", true)
                        put("mediaStartMs", config.initialMediaPositionMs)
                    }
                    webSocket.send(startPayload.toString())
                    events.tryEmit(RecognitionEvent.Connected(config.sessionId, displayName))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    parseServerMessage(text, config)?.let { events.tryEmit(it) }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (activeSocket.get() === webSocket) {
                        activeSocket.set(null)
                    }
                    if (isRunning.get() && code != 1000) {
                        scheduleReconnect("Connection closed ($code)")
                    } else {
                        events.tryEmit(RecognitionEvent.Closed(reason.ifBlank { "Closed" }))
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (activeSocket.get() === webSocket) {
                        activeSocket.set(null)
                    }
                    val httpCode = response?.code
                    val recoverable = httpCode == null || httpCode >= 500 || httpCode == 429
                    if (!recoverable) {
                        isRunning.set(false)
                        val detail = when (httpCode) {
                            401, 403 -> "ASR backend authentication failed (HTTP $httpCode). Check your token in Settings."
                            else -> "ASR backend rejected connection (HTTP $httpCode)."
                        }
                        events.tryEmit(RecognitionEvent.Error(detail, recoverable = false))
                        return
                    }
                    if (isRunning.get()) {
                        scheduleReconnect(t.localizedMessage ?: "Network interruption")
                    }
                }
            },
        )
        activeSocket.set(socket)
    }

    private fun startSenderLoop() {
        senderJob?.cancel()
        senderJob = scope.launch {
            for (chunk in outgoingChunks) {
                if (!isRunning.get()) break
                if (isPaused.get()) continue
                val socket = activeSocket.get() ?: continue
                // Apply backpressure guard: skip frame if socket queue exceeds 256 KB
                if (socket.queueSize() > 256 * 1024L) continue
                val framed = encodeBinaryAudioFrame(chunk.mediaStartMs, chunk.mediaEndMs, chunk.pcm16Bytes)
                socket.send(framed)
            }
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (!isRunning.get()) return
        val cfg = activeConfig.get() ?: return
        if (reconnectAttempts >= MaxReconnectAttempts) {
            isRunning.set(false)
            events.tryEmit(
                RecognitionEvent.Error(
                    "Live caption server unreachable after $MaxReconnectAttempts retries ($reason).",
                    recoverable = true,
                ),
            )
            return
        }
        reconnectAttempts += 1
        val delayMs = (1_000L * (1L shl (reconnectAttempts - 1).coerceAtMost(4))).coerceAtMost(15_000L)
        events.tryEmit(RecognitionEvent.Reconnecting(reconnectAttempts, reason))
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (isRunning.get()) {
                openWebSocket(cfg)
            }
        }
    }

    private fun sendControlMessage(payload: JsonObject) {
        runCatching { activeSocket.get()?.send(payload.toString()) }
    }

    private fun drainOutgoing() {
        while (outgoingChunks.tryReceive().isSuccess) {
            // discard buffered audio
        }
    }

    internal fun parseServerMessage(rawJson: String, config: RecognitionSessionConfig): RecognitionEvent? {
        val obj = runCatching { json.parseToJsonElement(rawJson) as? JsonObject }.getOrNull() ?: return null
        val type = obj["type"]?.jsonPrimitive?.content?.lowercase().orEmpty()
        return when (type) {
            "ready" -> RecognitionEvent.Connected(config.sessionId, displayName)
            "partial", "final" -> {
                val text = obj["text"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (text.isBlank()) return null
                val startMs = obj["startMs"]?.jsonPrimitive?.longOrNull ?: config.initialMediaPositionMs
                val endMs = (obj["endMs"]?.jsonPrimitive?.longOrNull ?: (startMs + 1_200L)).coerceAtLeast(startMs)
                val status = if (type == "final" || obj["status"]?.jsonPrimitive?.content.equals("final", true)) {
                    LiveCaptionStatus.FINAL
                } else {
                    LiveCaptionStatus.PARTIAL
                }
                val precisionRaw = obj["timingPrecision"]?.jsonPrimitive?.content?.lowercase()
                val precision = if (precisionRaw == "estimated") {
                    CaptionTimingPrecision.ESTIMATED
                } else {
                    CaptionTimingPrecision.EXACT
                }
                val lang = obj["language"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: config.languageCode
                val segId = obj["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: "${config.sessionId}_${startMs}_${status.name}"
                RecognitionEvent.Caption(
                    LiveCaptionSegment(
                        id = segId,
                        sessionId = obj["sessionId"]?.jsonPrimitive?.content ?: config.sessionId,
                        startMs = startMs.coerceAtLeast(0L),
                        endMs = endMs.coerceAtLeast(startMs.coerceAtLeast(0L)),
                        text = text,
                        language = lang,
                        status = status,
                        timingPrecision = precision,
                    ),
                )
            }
            "error" -> {
                val msg = obj["message"]?.jsonPrimitive?.content ?: "Speech recognition error"
                val recoverable = obj["recoverable"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
                RecognitionEvent.Error(msg, recoverable = recoverable)
            }
            "closed", "session_ended" -> RecognitionEvent.Closed("Session ended by server")
            else -> null
        }
    }

    companion object {
        private const val MaxReconnectAttempts = 5

        fun encodeBinaryAudioFrame(mediaStartMs: Long, mediaEndMs: Long, pcm16Bytes: ByteArray): ByteString {
            val buffer = ByteBuffer.allocate(16 + pcm16Bytes.size).order(ByteOrder.BIG_ENDIAN)
            buffer.putLong(mediaStartMs)
            buffer.putLong(mediaEndMs)
            buffer.put(pcm16Bytes)
            return buffer.array().toByteString()
        }
    }
}
