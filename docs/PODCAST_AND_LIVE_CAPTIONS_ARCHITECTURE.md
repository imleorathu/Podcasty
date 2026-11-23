# Podcasty — YouTube, RSS Podcast & Real-Time AI Captions Architecture

## 1. Design Principles
- **Strict UI Preservation**: Reuses Podcasty's existing Airmedy-matched design system (`PodcastyTheme`, `PodcastyCard`, `PodcastyTextField`, `PodcastyPillButton`, `PodcastEpisodeRow`, `StackPageHeader`, `FullScreenPlayer`, and `FullScreenPlayerTranscriptPanel`).
- **Separation of Concerns**:
  - **Add Podcast Page (`AppStackPage.LibraryAddPodcast`)**: Dedicated page under Library for pasting YouTube, RSS 2.0/Atom, or direct audio URLs, inspecting metadata, browsing discovered episodes, and managing imported shows.
  - **Lyrics Page (`FullScreenPlayerTranscriptPanel`)**: Dedicated solely to displaying synchronized lyrics for normal music tracks and synchronized live AI captions for playing RSS/online podcasts (URL pasting and embedded YouTube IFrame removed).
  - **Single Native Audio Player**: All authorized RSS/direct audio streams play through Podcasty's existing Media3 `PodcastPlaybackService` + `ExoPlayer` with HTTP Range support and zero full-file downloads.

## 2. Speech Recognition Engine Evaluation & Modular Architecture

| Engine | Streaming | English (`en-US`) | Tamil (`ta-IN`) | Deployment Model | Status in Podcasty |
|---|---|---|---|---|---|
| **Google Cloud Speech-to-Text** | Native bidirectional gRPC | Excellent (`latest_long`) | Supported (`default`) | Secure WebSocket (`wss://`) via `backend/` Node.js server | **Primary implementation** (`GoogleStreamingSpeechEngine`) |
| **Sherpa-ONNX** (`k2-fsa/sherpa-onnx`) | Native streaming transducer | Excellent (Zipformer-EN) | Limited streaming transducer support | Offline on-device (requires GitHub Release Android AAR + ONNX weights) | **Modular adapter** (`SherpaStreamingSpeechEngine`) |
| **Whisper.cpp** (`ggml-org/whisper.cpp`) | Windowed VAD chunks | Excellent | Supported (multilingual `base`/`small`) | Offline on-device (requires native JNI + 140–466 MB GGML `.bin`) | **Modular adapter** (`WhisperSpeechEngine`) |
| **Vosk** (`alphacep/vosk-api`) | Native streaming Kaldi | Good | Very limited official Tamil support | Offline on-device | Evaluated; superseded by Google STT (Tamil+English) & Sherpa-ONNX (English) |

All recognition engines implement `com.podcasty.app.asr.SpeechRecognitionEngine`, keeping the UI and playback controller completely decoupled from any specific provider.

## 3. Real-Time Audio Capture & Synchronization Pipeline

```text
Media3 ExoPlayer (RSS / Direct HTTPS Progressive Stream — HTTP Range, no disk download)
   │
   ▼ (Before SonicAudioProcessor -> 1:1 original media duration regardless of playback speed)
LiveCaptionAudioProcessor (Custom Media3 BaseAudioProcessor — non-blocking pass-through)
   │  Bounded Channel<CapturedPcmFrame>(capacity = 32, DROP_OLDEST)
   │  Tags every frame with exact (mediaStartMs, mediaEndMs) from sample-count anchor
   ▼ (Dispatchers.Default — never touches UI or audio render threads)
AudioChunkPreprocessor
   │  Downmix stereo -> mono -> Linear resample to 16 kHz -> Soft RMS normalize -> Energy+ZCR VAD
   │  Slices 100 ms (1,600 samples = 3,200 bytes LINEAR16) chunks with original media timestamps
   ▼
SpeechRecognitionEngine (GoogleStreamingSpeechEngine | SherpaStreamingSpeechEngine | WhisperSpeechEngine)
   │  wss:// Secure WebSocket (16-byte big-endian [mediaStartMs][mediaEndMs] + PCM16)
   │  -> Node.js Backend (`backend/src/server.js`) -> Google Cloud STT Bidirectional gRPC
   ▼
LiveCaptionStateAggregator
   │  PARTIAL displayed immediately -> replaced by FINAL -> deduplicated -> sorted by startMs
   │  O(log N) binary search maps current playback position to active sentence (zero drift)
   ▼
FullScreenPlayerTranscriptPanel (Shared Display Adapter) + Room `live_captions` (optional history)
```

## 4. YouTube Audio Playback
- Uses **YouTube Data API v3** (`videos?part=snippet,contentDetails`, when the user configures an optional API key stored encrypted in Android KeyStore) and YouTube's official **oEmbed** endpoint for public metadata (title, channel, thumbnail, duration, video ID).
- Resolves audio-only progressive streams on-device with **NewPipeExtractor** and plays them through the existing Media3/ExoPlayer pipeline. Video is never rendered and no YouTube IFrame is embedded.
- Stream URLs are short-lived. Resolved streams are cached for up to 30 minutes, and ExoPlayer errors trigger one automatic re-resolution at the current position before falling back.
- If extraction fails (age restriction, region block, private/paid video, YouTube changes, or network failure), Podcasty keeps the saved reference and offers a direct **Open in YouTube** action.
- Extraction is unofficial, may break when YouTube changes, and remains subject to YouTube's Terms of Service. A creator-provided RSS/MP3 audio URL can always be linked as an override.

## 5. Persistence (Room v3 + DataStore + KeyStore)
- **Room `PodcastyDatabase` v3 (`MIGRATION_2_3`)**:
  - `podcasts`: stores `feedUrl`, `sourceType`, `youtubeVideoId`, `preferredCaptionLanguage`.
  - `live_captions`: stores optional AI caption history (`id`, `episodeId`, `sessionId`, `startMs`, `endMs`, `text`, `language`, `isEstimatedTiming`, `createdAt`).
  - Cascade deletion (`deleteImportedPodcastCascade`) removes an imported podcast, its episodes, transcripts, and live caption history in a single transaction.
- **Zero Raw Audio Persistence**: Audio frames live strictly in bounded memory buffers and are released immediately after processing.
- **Encrypted Credentials (`SecureCredentialStore`)**: Optional ASR backend Bearer Token and optional YouTube Data API v3 Key are encrypted via Android KeyStore AES-256-GCM. Google Cloud service-account JSON keys exist strictly on the Node.js backend server.
