# Subtitle

Android app that generates timed subtitles for a video using AI speech-to-text, lets you
edit them, and exports SRT/VTT or a video with burned-in subtitles.

No artificial limits on file size, duration, or cue count. Audio is streamed and processed
in chunks, and every chunk is checkpointed, so a three-hour film is a normal case rather
than an edge case. The limits that *do* exist are platform and vendor limits, and they are
documented rather than hidden — see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) §1.

## Status

MVP, end to end: pick a video → transcribe → translate → edit → export. 102 tests passing.

| Working | Not in this build |
|---|---|
| SAF video picker with persisted permission | On-device Whisper (`LocalWhisperProvider`) |
| Metadata probe, multi-audio-track picker | Custom backend provider |
| Streaming audio extraction → 16 kHz mono | Word-level timestamp editing |
| Silence-seeking chunker + timestamp merge | |
| OpenAI `whisper-1` transcription | Long-video soak testing |
| WorkManager job: progress, cancel, resume | |
| Subtitle editor: edit, split, merge, retime, search | |
| Video preview with live subtitles | |
| SRT / WebVTT export via SAF | |
| Burn-in export (media3 `Transformer`) | |
| **Translate speech → English while transcribing** | |
| **Translate finished subtitles to any language** | |

## Build

Requires JDK 17+, Android SDK with platform 37 and build-tools 37.0.0.

```bash
./gradlew :app:assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest  # full suite — no device needed
```

Everything is tested on the JVM (Robolectric covers Room, the repository and the workers),
including a three-hour and a twelve-hour chunking soak test and the v1→v2 database
migration. There is no `androidTest` source set on purpose: a test nobody can run looks
like coverage without being any. See [ARCHITECTURE §14](docs/ARCHITECTURE.md) for what is
covered and what is still unverified.

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

## Translating to English

Two options, and the cheaper one is easy to miss:

**While transcribing** — on the import screen, pick *"English subtitles"*. Whisper translates
the speech directly. **This costs nothing extra** and the timings are tighter, because they
come from the audio. English only.

**After transcribing** — the translate button in the editor. Works on projects you have
already transcribed (no re-upload, no second transcription charge), targets any supported
language, and keeps the original text alongside so you can switch between *Original*,
*Translation* and *Both* in the editor, in the export, and in the burned-in video.

Only subtitle text is sent for the second option; audio and video are not.

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
