# Subtitle

Android app that generates timed subtitles for a video using AI speech-to-text, lets you
edit them, and exports SRT/VTT or a video with burned-in subtitles.

No artificial limits on file size, duration, or cue count. Audio is streamed and processed
in chunks, and every chunk is checkpointed, so a three-hour film is a normal case rather
than an edge case. The limits that *do* exist are platform and vendor limits, and they are
documented rather than hidden — see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) §1.

## Status

MVP, end to end: pick a video → transcribe → edit → export. 50 unit tests passing.

| Working | Not in this build |
|---|---|
| SAF video picker with persisted permission | On-device Whisper (`LocalWhisperProvider`) |
| Metadata probe, multi-audio-track picker | Custom backend provider |
| Streaming audio extraction → 16 kHz mono | Translation |
| Silence-seeking chunker + timestamp merge | Word-level timestamp editing |
| OpenAI `whisper-1` transcription | Instrumented tests, long-video soak |
| WorkManager job: progress, cancel, resume | |
| Subtitle editor: edit, split, merge, retime, search | |
| Video preview with live subtitles | |
| SRT / WebVTT export via SAF | |
| Burn-in export (media3 `Transformer`) | |

## Build

Requires JDK 17+, Android SDK with platform 37 and build-tools 37.0.0.

```bash
./gradlew :app:assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest  # 50 unit tests
```

Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

The debug build uses the application id `nl.tippie.subtitle.debug`, so it installs
alongside a release build.

For a release APK, add a `signingConfig` to `app/build.gradle.kts` with your own keystore —
no keystore is committed to this repository.

## Setup after install

1. Open **Settings** → paste an OpenAI API key → **Test key**.
2. Home → **Select video** → pick the audio track and language → **Start transcription**.

The key is encrypted with an AES-256-GCM key held in the Android Keystore, stored in a
SharedPreferences file excluded from backup and device transfer, and never logged.

## Costs and data use

`whisper-1` is about **$0.006/minute**, so a two-hour film is roughly **$0.72**.

Only extracted audio is uploaded, never the video. At the default AAC-LC 32 kbps mono that
is about **14 MB per hour** of video (switch to lossless WAV in Settings for ~115 MB/h).

## Stack

Kotlin · Compose + Material 3 · Coroutines/Flow · WorkManager · Room · DataStore ·
media3 (ExoPlayer + Transformer + effect) · OkHttp · kotlinx.serialization.

**No FFmpeg** — audio extraction uses `MediaExtractor`/`MediaCodec`, and burn-in uses
media3's `Transformer` with a time-varying `TextOverlay`. See ARCHITECTURE §3 for why.

minSdk 26 · target/compileSdk 37 · AGP 9.4.1 · Gradle 9.7.1 · Kotlin 2.4.20
