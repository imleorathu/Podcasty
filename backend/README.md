# Podcasty Real-Time Speech Backend (Node.js + Google Cloud Speech-to-Text)

Secure WebSocket-to-gRPC bridge that streams 16 kHz `LINEAR16` mono PCM podcast audio from the Podcasty Android app to Google Cloud Speech-to-Text and returns media-timestamped `PARTIAL` and `FINAL` caption events in real time.

## Why a Backend?
- **Security**: Google Cloud service-account JSON credentials must never be bundled inside an Android APK.
- **Multilingual Accuracy**: Google Cloud Speech-to-Text natively supports real-time streaming recognition for **English (`en-US`, `en-IN`, `en-GB`)** and **Tamil (`ta-IN`)** with interim results and word time offsets.
- **Stream Rollover**: Automatically rolls over gRPC streams before Google's 305-second single-stream limit without dropping captions.

## Setup

1. Install dependencies:
   ```bash
   cd backend
   npm install
   ```
2. Create your environment configuration (never commit `.env` or service account keys):
   ```bash
   cp .env.example .env
   ```
3. Configure Google Cloud Speech-to-Text:
   - Enable the **Cloud Speech-to-Text API** in Google Cloud Console.
   - Create a service account with the **Cloud Speech Client** role and download its JSON key to a secure server path.
   - Export `GOOGLE_APPLICATION_CREDENTIALS=/secure/path/to/key.json`.
   - Generate a strong random token for `PODCASTY_API_TOKENS` (e.g., `openssl rand -hex 32`).
4. Start the server (deploy behind HTTPS/WSS TLS termination such as Cloud Run, Caddy, or Nginx, or set `TLS_CERT_PATH` and `TLS_KEY_PATH`):
   ```bash
   PODCASTY_API_TOKENS="your-random-token" \
   GOOGLE_APPLICATION_CREDENTIALS="/secure/path/key.json" \
   npm start
   ```
5. Connect from the Podcasty Android app:
   - Open **Settings → Lyrics & Captions → Real-Time AI Podcast Captions**.
   - Select **Google Cloud Speech-to-Text (Streaming)**.
   - Enter your WebSocket URL (e.g., `wss://captions.yourdomain.com/ws/captions`, or `ws://10.0.2.2:8443/ws/captions` for local Android emulator testing).
   - Enter your Bearer Token (stored encrypted in Android KeyStore AES-256-GCM).
   - Select your language (`English (US)` or `Tamil (India)`).

## Endpoints
- `GET /health` — Health status and supported languages.
- `WSS /ws/captions` — Authenticated bidirectional audio streaming bridge to Google Cloud Speech-to-Text (`en-US`, `ta-IN`, `en-IN`, `en-GB`).
- `GET /api/youtube/captions?videoId=<11-char-id>&lang=<en-US|ta-IN|...>` — Authenticated endpoint powered by [`youtube-caption-extractor`](https://github.com/devhims/youtube-caption-extractor) that extracts public YouTube metadata and normalized millisecond cues (`startMs`, `endMs`, `text`) for automatic synchronization in Podcasty's Lyrics page.

## Automated Tests

```bash
npm test
```
