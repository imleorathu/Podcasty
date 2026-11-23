# Podcasty

**Podcasty** is a modern Android offline music and podcast player designed for smooth, high-quality audio playback. It features YouTube podcast importing, smart music library management, customizable equalizer presets, smooth animations, background playback, and a beautifully optimized UI with efficient battery usage.

## Key Features

- **Offline Music & Podcast Playback**: Built on AndroidX Media3 (ExoPlayer) with custom DSP/equalizer support, gapless/local playback, queue management, playback speed control, and sleep timer.
- **Background & Lock-Screen Controls**: Foreground media playback service (`PodcastPlaybackService`) with MediaSession integration and lock-screen transport/lyrics notifications.
- **Smart Library Organization**: Dedicated **Podcasts** section on Home for imported/downloaded episodes, **Albums** grid with embedded artwork, and **Folders** browser (folder icons on directories, embedded album artwork on individual audio tracks).
- **YouTube & RSS Podcast Import**: Import podcasts from YouTube links, RSS/Atom feeds, or direct audio URLs, prioritizing original audio language tracks and synced captions.
- **Resilient Local Storage**: Room database with non-destructive schema migrations and background SAF folder indexing.

## Requirements

- Android 12+ (`minSdk = 31`, `compileSdk = 37`, `targetSdk = 37`)
- JDK 21 (e.g., Android Studio JBR)

## Building & Release Signing

Debug build and unit tests:

```bash
./gradlew testDebugUnitTest lintRelease
./gradlew assembleDebug
```

Signed release build — provide release signing credentials via environment variables or gitignored `local.properties` (never commit keystores or passwords):

```properties
PODCASTY_RELEASE_STORE_FILE=/path/to/podcasty-release.jks
PODCASTY_RELEASE_STORE_PASSWORD=<store-password>
PODCASTY_RELEASE_KEY_ALIAS=<key-alias>
PODCASTY_RELEASE_KEY_PASSWORD=<key-password>
```

Then run:

```bash
./gradlew assembleRelease
```

## Releases

Download verified signed release APKs and SHA-256 checksums from [GitHub Releases](https://github.com/imleorathu/Podcasty/releases).

Verify a downloaded APK checksum:

```bash
shasum -a 256 -c Podcasty-v1.0.0-release.apk.sha256
```
