# Subtitle

Android app that generates timed subtitles for a video using AI speech-to-text,
lets you edit them, and exports SRT/VTT or a video with burned-in subtitles.

No artificial limits on file size, duration, or cue count — the pipeline streams
audio and checkpoints per chunk so a 3-hour movie is a normal case, not an edge case.

- **Status:** Phase 1 (architecture) complete.
- **Architecture:** [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

## At a glance

| | |
|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3 |
| Architecture | MVVM + clean layering, Hilt, Coroutines/Flow |
| Media | `MediaExtractor` / `MediaCodec` / media3 `Transformer` — no FFmpeg |
| Transcription | Pluggable `TranscriptionProvider`; OpenAI `whisper-1` first, local whisper.cpp next |
| Background | WorkManager long-running worker, resumable per chunk |
| Storage | Room (+ Paging 3), DataStore, EncryptedSharedPreferences for keys |
| SDK | minSdk 26, target/compileSdk 36 |
