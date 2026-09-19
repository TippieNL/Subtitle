# Subtitle — Architecture (Phase 1)

Android app: pick a video → extract audio → AI transcription → timed subtitles →
edit → export SRT/VTT or burn-in.

Design constraint from the brief: **no artificial caps** on file size, duration, or
cue count. This document states what that means in practice and where the *real*
(platform / vendor) limits are, because pretending they don't exist would produce
an app that crashes on a 3-hour movie.

---

## 0. TL;DR — the recommendation

| Decision | Choice |
|---|---|
| Transcription default | **Cloud `whisper-1` (OpenAI audio transcriptions)** for v1, behind a provider interface |
| Privacy default | **Local whisper.cpp** as the shipped alternative (v2), never uploads |
| Audio decode | `MediaExtractor` + `MediaCodec` + media3 `SonicAudioProcessor` / `ChannelMixingAudioProcessor` — **no FFmpeg** |
| Canonical audio format | 16 kHz **mono PCM-16**, streamed, never fully in RAM |
| Segmentation | **Silence-seeking chunker**: target length + search window, hard-cut fallback with overlap |
| Burn-in | **media3 `Transformer` + `TextOverlay`** — no FFmpeg |
| Long-running work | `WorkManager` long-running worker + foreground service (`mediaProcessing` / `dataSync`) |
| Resume | Per-chunk state rows in Room; the worker is a resumable state machine |
| Architecture | Clean-ish layering (ui / domain / data) + MVVM, Hilt, single-module first, Gradle modules at the seams later |

---

## 1. The three honest limits

These are **not** ours. They are imposed by Android or by the vendor and no amount of
architecture removes them. Everything else in this design exists to work *around* them.

### 1.1 OpenAI audio endpoint: 25 MB per request
`/v1/audio/transcriptions` rejects payloads over **25 MB**. This is per-request, not
per-account, so it is a *chunking* requirement, not a duration cap.

Budget at our canonical rate:

| Encoding | Bytes/sec | 25 MB holds |
|---|---|---|
| PCM-16 16 kHz mono | 32 000 | ~13 min |
| FLAC 16 kHz mono | ~16 000 | ~26 min |
| **AAC-LC 16 kHz mono @ 32 kbps** | **4 000** | **~1 h 44 min** |

We upload AAC-LC in an `.m4a` container (accepted by the endpoint, and MediaCodec's
AAC encoder is a *mandatory* Android codec since API 16, so it is available on every
device). At 32 kbps speech the WER impact vs. lossless is negligible for 16 kHz mono.

So the 25 MB limit is never the binding constraint — we chunk at **~5 minutes** anyway,
for progress granularity, cancellation latency, and resumability. 25 MB gives us ~20x
headroom over that.

### 1.2 Model choice determines whether we get timestamps at all
- `whisper-1` supports `response_format=verbose_json` and `timestamp_granularities[]`
  (`segment`, `word`). **We need this.**
- The `gpt-4o-transcribe` / `gpt-4o-mini-transcribe` family returns text (and streaming
  deltas) but **not** segment/word timestamp arrays.

A subtitle app without timestamps is a text app. **`whisper-1` is the required cloud
model**, not a preference. If a newer endpoint gains timestamp support, it slots in as
another `TranscriptionProvider` — that is the whole point of the interface.

### 1.3 Android foreground-service runtime caps
A 3-hour movie transcribed locally can take hours of CPU. Android does not let you own
the CPU indefinitely:

- `WorkManager.setExpedited()` is quota-limited (~10 min). **Not usable** for this.
- A long-running worker must call `setForeground(ForegroundInfo)` — a real foreground
  service with a notification.
- **API 34+**: the service type must be declared in the manifest and requested at runtime.
- **API 35+**: `dataSync` and `mediaProcessing` foreground services are subject to a
  **~6-hour-per-day cumulative cap**. When the budget is exhausted the system stops the
  service; `Service.onTimeout()` fires and you must stop cleanly.

**Consequence:** we cannot promise "start a 6-hour local transcription and walk away."
What we *can* promise, and what this design delivers:

- work is checkpointed per chunk, so a stop costs at most one chunk;
- the job resumes automatically on next app open, or via a `WorkManager` constraint
  (charging + idle) for overnight completion;
- the UI tells the truth: *"Paused by system — 41 of 68 chunks done, resumes when you
  reopen the app."*

Cloud transcription is largely immune to this: the phone is only doing extraction
(fast, ~50-100x realtime) plus uploads, so a full movie fits well inside the budget.

**Other real limits worth stating:** device free storage (see §4.4), Doze/battery
optimisation throttling background work, and thermal throttling on sustained local
inference (a phone will downclock after ~10-15 min of pinned NPU/CPU load — expect
local throughput to degrade over a long job, not stay linear).

---

## 2. Local vs cloud — the actual trade

| | **Local (whisper.cpp)** | **Cloud (`whisper-1`)** |
|---|---|---|
| Privacy | Audio never leaves device | Audio uploaded to OpenAI |
| Cost | Free | ~$0.006/min → **2 h movie ≈ $0.72** |
| Offline | Yes | No |
| Accuracy | `base` ≈ usable, `small` ≈ good, `medium`+ impractical on phone | Equivalent to `large-v2` |
| Speed (flagship SoC, arm64) | `tiny.en` ~8-15x RT, `base` ~4-6x RT, `small` ~1-2x RT | Network-bound; ~10-30x RT in practice |
| 2 h movie, realistic | `base` ≈ 20-30 min, `small` ≈ 60-120 min + thermal throttling | ≈ 5-15 min, dominated by upload |
| Storage | Model on device: `tiny` 75 MB, `base` 142 MB, `small` 466 MB (q5_1: 31/57/181 MB) | None |
| RAM | ~0.3 / 0.5 / 1.0 GB for tiny/base/small | Negligible |
| Ship effort | **High** — NDK build, CMake, JNI, model download & management | **Low** — Retrofit + multipart |
| Battery | Heavy | Light |

### Recommendation
**Build the cloud provider first, ship it, then add local.** Reasons:

1. The hard parts of this app are the *pipeline* (extraction, chunking, timestamp
   merging, resume, editor, burn-in), not the inference call. The cloud provider gets a
   working end-to-end app fastest and lets you validate the pipeline against a
   known-good reference transcript.
2. whisper.cpp has no official Maven artifact. Integrating it means vendoring the
   source as a git submodule, an `externalNativeBuild` CMake config for `arm64-v8a`
   (+ optionally `x86_64` for the emulator), a JNI bridge, and a model
   download/verify/manage UX. That is a week of work that produces **zero** pipeline
   progress.
3. Because `TranscriptionProvider` is the only seam, adding it later is additive — no
   pipeline rewrite.

**But design for local from day one**, which is why the canonical intermediate format is
16 kHz mono PCM: that is *exactly* what whisper.cpp wants (as float32), and it is one
encode step away from what the cloud wants. Getting this right up front is what makes
the second provider cheap.

Do **not** ship a "local" provider backed by `android.speech.SpeechRecognizer` — it is
designed for short utterances, has no reliable segment timestamps, and its offline
models are not comparable in quality. It is not a Whisper substitute.

### Option C: your own backend (faster-whisper)
Worth it only if you want cheaper-than-OpenAI at volume, larger models, diarization, or
data residency control. Requirements if you go there:

- GPU box (a single RTX 4000-class card runs `large-v3` at ~15-30x realtime with
  `faster-whisper` / CTranslate2 int8_float16);
- an async job API — **not** a synchronous HTTP call: `POST /jobs` (resumable/chunked
  upload) → `GET /jobs/{id}` poll or webhook. A 5-minute chunk over mobile data will
  outlive any sane request timeout otherwise;
- auth that is not a bearer token baked into the APK (short-lived tokens from a
  sign-in);
- object storage + retention policy you can state in the privacy screen.

This is `BackendTranscriptionProvider`. Same interface, so it is a v3 concern. The app
does not require a backend; that requirement would only come from choosing this option.

---

## 3. Why no FFmpeg

`ffmpeg-kit`, the de-facto Android FFmpeg wrapper, was **retired by its maintainer in
January 2025** and its prebuilt binaries were pulled from the public repositories.
Remaining options are a community fork of unknown longevity or building FFmpeg yourself
(LGPL/GPL compliance, ~200 MB of native binaries across ABIs, a large APK, and an
ongoing CVE-patching obligation).

Everything this app needs is available natively:

| Need | Native replacement |
|---|---|
| Container parse / track enumeration | `MediaExtractor`, `MediaMetadataRetriever` |
| Audio decode (AAC/Opus/Vorbis/MP3/FLAC/AC3¹) | `MediaCodec` |
| Downmix to mono | media3 `ChannelMixingAudioProcessor` |
| Resample to 16 kHz | own polyphase windowed-sinc `SincResampler` (see note) |
| Encode AAC / mux `.m4a` | `MediaCodec` + `MediaMuxer` |
| Burn-in subtitles | media3 `Transformer` + `TextOverlay` |
| Playback with soft subs | media3 `ExoPlayer` + sideloaded `SubtitleConfiguration` |

¹ AC3/E-AC3/DTS decode is **not** guaranteed on all devices (often licensed only on TV
hardware). This is one of the real "unsupported format" cases the error layer must name
explicitly — see §7.

Net: smaller APK, no license question, no dead dependency. The cost is that we write the
chunker and PCM plumbing ourselves (~400 lines), which we'd want control over anyway.

---

## 4. The pipeline

```
 ContentResolver Uri  (SAF, persisted permission)
        │
        ├─► MediaMetadataRetriever ──► duration, resolution, bitrate (no full read)
        ├─► MediaExtractor ──────────► track list: pick audio track (user choice if >1)
        │
        ▼
 ┌─────────────────────────────────────────────────────────────┐
 │ AudioPipeline  (streaming, O(1) memory, ~64 KB buffers)     │
 │                                                             │
 │  MediaExtractor.readSampleData()                            │
 │        ↓ compressed packets                                 │
 │  MediaCodec (async) ── decode ──► PCM-16 @ source rate/ch   │
 │        ↓                                                    │
 │  ChannelMixingAudioProcessor  ──► mono                      │
 │        ↓                                                    │
 │  SonicAudioProcessor          ──► 16 000 Hz                 │
 │        ↓                                                    │
 │  SilenceSeekingChunker  (RMS + adaptive noise floor)        │
 │        ↓ emits ChunkBoundary(startUs, endUs)                │
 │        ↓                                                    │
 │  ChunkSink ──┬─► PcmChunkSink   (local)  → chunk_%04d.pcm   │
 │              └─► AacChunkSink   (cloud)  → chunk_%04d.m4a   │
 └─────────────────────────────────────────────────────────────┘
        │  writes to cacheDir/projects/<id>/chunks/
        ▼
 ┌─────────────────────────────────────────────────────────────┐
 │ TranscriptionOrchestrator   (per-chunk, resumable)          │
 │   for each chunk with state != TRANSCRIBED:                 │
 │     result = provider.transcribe(chunk, lang, prompt)       │
 │     shift timestamps by chunk.startUs                       │
 │     clamp / trim to chunk bounds                            │
 │     seam-merge against previous chunk's tail                │
 │     persist cues; mark chunk TRANSCRIBED; delete chunk file │
 └─────────────────────────────────────────────────────────────┘
        │
        ▼
 CueSegmenter  ──► readable cues (≤2 lines, ≤42 chars/line, 1-7 s, CPS-capped)
        │
        ▼
 Room ──► Editor (Compose + ExoPlayer) ──► Export (SRT / VTT / burn-in)
```

### 4.1 Extraction: never load the file
`MediaExtractor` reads one compressed sample at a time from the `Uri`'s file
descriptor. `MediaCodec` in **async mode** (`setCallback`) hands back one output buffer
at a time. Peak heap from the audio path is a handful of codec buffers — tens of
kilobytes — regardless of whether the input is 40 MB or 40 GB.

The output of each processor stage is drained immediately into the chunk sink. Nothing
accumulates.

### 4.2 Chunking: silence-seeking, not fixed-grid
Fixed 5-minute cuts slice words in half. Overlap-and-dedup fixes that but is fiddly and
can drop or duplicate words at the seam. Better: **put the cut where there is no speech.**

```
targetChunkMs   = 300_000            // 5 min
searchWindowMs  =  20_000            // ±20 s around the target
minSilenceMs    =    400             // a plausible sentence gap
```

At `targetChunkMs` into the current chunk, open a search window and pick the longest
run of frames whose RMS is below an adaptively-tracked noise floor (rolling 10th
percentile of recent frame energy, so it adapts to a noisy film mix vs. a quiet
lecture). Cut at that run's midpoint.

If no qualifying silence exists in the window — continuous speech, music bed, loud
ambience — fall back to a hard cut at `targetChunkMs` **plus a 3 s overlap tail** on the
next chunk, and mark the boundary `HARD`. Only `HARD` boundaries pay the dedup cost.

Result: on typical content the great majority of boundaries are `SILENCE` and need no
seam handling at all.

### 4.3 Timestamp merging — the part that's easy to get wrong
Every chunk's transcript comes back with timestamps relative to that chunk. Four things
must happen, in order:

1. **Offset.** `cue.startUs += chunk.startUs`. Trivial, and the only step most
   implementations do.
2. **Clamp.** Whisper occasionally emits a segment whose end runs past the audio it was
   given (a known behaviour at the tail of a clip). Clamp `endUs` to `chunk.endUs`, and
   **drop** any segment starting within ~200 ms of `chunk.endUs` when a next chunk
   exists — it is a tail hallucination, and the real audio is in the next chunk anyway.
3. **Seam dedup (HARD boundaries only).** The overlap region is transcribed twice.
   Take cues from chunk *N* that end before `overlapStart`, cues from chunk *N+1* that
   start after `overlapStart`, and for the contested region pick the version from
   *N+1* (its cue has full following context, so its wording is generally better) —
   unless normalised-token Levenshtein between the two candidate texts is below a
   similarity threshold, in which case keep both and let the editor sort it out rather
   than silently deleting a sentence. **Never silently drop speech.**
4. **Monotonicity pass.** Sort by start; if `cue[i].start < cue[i-1].end`, nudge
   `cue[i-1].end` down. Guarantees a valid SRT.

**Cross-chunk context.** Pass the last ~200 characters of chunk *N*'s transcript as the
`prompt` parameter of chunk *N+1*'s request. This measurably improves continuity of
proper nouns, terminology, and casing across the seam. It is also the mechanism for
custom vocabulary later.

### 4.4 Storage, not RAM, is the real budget
Temp chunk files, worst case, for the **cloud** path:

- AAC-LC 32 kbps mono → ~14.4 MB/hour of video.
- Chunks are deleted the moment they are transcribed, so steady-state disk is
  ~1 chunk + a small look-ahead: **< 10 MB**.

For the **local** path we keep PCM (no encode step): 115 MB/hour — but again only for
chunks not yet consumed, so ~2 chunks ≈ 25 MB steady-state.

Burn-in export is the expensive one: a re-encoded H.264/HEVC copy of the source, i.e.
roughly the source's size again, written through SAF to user-chosen storage.

**Before starting any job:** `StatFs` free-space check against an estimate, with a clear
message if it doesn't fit — never begin and fail at 90%.

---

## 5. Module & package layout

Single Gradle module for v1 (`:app`), but packaged so the seams are already cut. Promote
`:core:media`, `:core:transcription`, `:core:data` to real modules when build times
justify it — the package boundaries below are exactly the future module boundaries.

```
app/src/main/java/nl/tippie/subtitle/
├─ SubtitleApplication.kt              @HiltAndroidApp, WorkManager Configuration.Provider
├─ ui/
│  ├─ theme/                           Material 3, dynamic color
│  ├─ navigation/                      NavHost, typed routes (kotlinx.serialization)
│  ├─ home/            HomeScreen.kt          HomeViewModel.kt
│  ├─ import/          ImportScreen.kt        ImportViewModel.kt
│  ├─ processing/      ProcessingScreen.kt    ProcessingViewModel.kt
│  ├─ editor/          EditorScreen.kt        EditorViewModel.kt   CueRow.kt  PlayerSurface.kt
│  ├─ export/          ExportScreen.kt        ExportViewModel.kt
│  └─ settings/        SettingsScreen.kt      SettingsViewModel.kt
├─ domain/
│  ├─ model/           Project, Cue, SubtitleStyle, MediaInfo, AudioTrackInfo,
│  │                   TranscriptionConfig, JobStage, ProcessingError
│  ├─ repository/      ProjectRepository, CueRepository, SettingsRepository  (interfaces)
│  └─ usecase/         StartTranscription, ResumeTranscription, CancelJob,
│                      SplitCue, MergeCues, ShiftCueTimings, ExportSubtitles, BurnInSubtitles
├─ media/
│  ├─ MediaInfoReader.kt                MediaMetadataRetriever + MediaExtractor probe
│  ├─ AudioPipeline.kt                  decode → mono → 16 kHz, streaming
│  ├─ SilenceSeekingChunker.kt          boundary detection
│  ├─ sink/            PcmChunkSink.kt  AacChunkSink.kt        (ChunkSink interface)
│  └─ burnin/          SubtitleOverlay.kt (TextOverlay subclass)  BurnInEngine.kt (Transformer)
├─ transcription/
│  ├─ TranscriptionProvider.kt          the interface (§6)
│  ├─ TranscriptionProviderFactory.kt
│  ├─ openai/          WhisperApiProvider.kt  OpenAiApi.kt  dto/
│  ├─ local/           LocalWhisperProvider.kt  WhisperJni.kt  ModelManager.kt   (v2)
│  └─ backend/         BackendTranscriptionProvider.kt                            (v3)
├─ subtitle/
│  ├─ CueSegmenter.kt                   raw segments → readable cues
│  ├─ TimelineMerger.kt                 offset / clamp / seam-dedup / monotonicity
│  ├─ format/          SrtWriter.kt  VttWriter.kt  SrtParser.kt  VttParser.kt
│  └─ TranslationService.kt             optional, provider-backed
├─ data/
│  ├─ db/              SubtitleDatabase.kt, dao/, entity/, Converters.kt, migrations/
│  ├─ prefs/           SettingsDataStore.kt
│  ├─ security/        ApiKeyStore.kt   (EncryptedSharedPreferences / Keystore)
│  └─ repository/      *RepositoryImpl.kt
├─ work/
│  ├─ TranscriptionWorker.kt            long-running, foreground, resumable
│  ├─ BurnInWorker.kt
│  └─ WorkProgress.kt                   typed progress <-> Data
└─ di/                 AppModule, DatabaseModule, NetworkModule, TranscriptionModule
```

**Dependency rule:** `ui → domain ← data|media|transcription`. `domain` has no Android
imports beyond `Uri`. Repositories are interfaces in `domain`, implemented in `data`.

---

## 6. The provider seam

```kotlin
// domain-level contract — no vendor types leak through it.
interface TranscriptionProvider {
    val id: ProviderId
    val capabilities: ProviderCapabilities

    /** True if usable right now (key present / model downloaded). */
    suspend fun isAvailable(): Boolean

    /** Cheap credential/model check for Settings. */
    suspend fun validate(): Result<Unit>

    /** What this provider wants each chunk encoded as, and how big it may be. */
    val audioRequirements: AudioRequirements

    /**
     * Transcribe one chunk. Timestamps returned are RELATIVE to the chunk.
     * Must be cancellable via coroutine cancellation.
     */
    suspend fun transcribe(request: ChunkRequest): Result<ChunkTranscript>
}

data class ProviderCapabilities(
    val supportsLanguageDetection: Boolean,
    val supportsWordTimestamps: Boolean,
    val supportsTranslationToEnglish: Boolean,
    val supportsContextPrompt: Boolean,
    val requiresNetwork: Boolean,
    val sendsAudioOffDevice: Boolean,   // drives the privacy banner — not optional
)

data class AudioRequirements(
    val encoding: ChunkEncoding,        // PCM_16 | AAC_M4A | FLAC
    val sampleRateHz: Int,              // 16_000
    val channels: Int,                  // 1
    val maxBytesPerChunk: Long?,        // 25 MiB for OpenAI, null for local
    val maxDurationPerChunkMs: Long?,
)

data class ChunkRequest(
    val audioFile: File,
    val chunkIndex: Int,
    val chunkStartUs: Long,             // for logging only; provider must not use it
    val language: String?,              // null = autodetect
    val contextPrompt: String?,         // tail of previous chunk
    val wantWordTimestamps: Boolean,
)

data class ChunkTranscript(
    val detectedLanguage: String?,
    val segments: List<RawSegment>,     // startUs/endUs relative to chunk
    val words: List<RawWord>?,
)
```

`sendsAudioOffDevice` is deliberately part of the contract: the UI reads it to render the
upload banner, so a provider physically cannot be added without declaring its privacy
posture.

The orchestrator only ever sees this interface. `AudioRequirements` is what lets the
*same* extraction pipeline serve a PCM-hungry local model and a size-capped cloud API —
the chunker reads it and picks the sink and the target chunk length.

---

## 7. Error taxonomy

Generic failures are a bug. Every failure maps to a sealed `ProcessingError` with a
specific user-facing string and, where possible, an action:

| Error | Message | Action |
|---|---|---|
| `NoAudioTrack` | "This video has no audio track." | — |
| `UnsupportedAudioCodec(mime)` | "This device can't decode {AC-3} audio. Try a file with AAC or MP3 audio." | — |
| `CorruptContainer` | "The file couldn't be read — it may be incomplete or corrupted." | — |
| `MultipleAudioTracks` | (not an error) track picker on Import screen | Choose track |
| `UriPermissionLost` | "Lost access to this video. It may have moved or been deleted." | Re-pick |
| `InsufficientStorage(need, have)` | "Needs about 1.2 GB free; 340 MB available." | Free space / change output |
| `NetworkUnavailable` | "No connection. Transcription will resume automatically." | Auto-retry |
| `InvalidApiKey` | "Your OpenAI API key was rejected." | Open Settings |
| `QuotaExceeded` | "Your OpenAI account is out of credit." | Open billing |
| `RateLimited(retryAfter)` | "Rate limited — retrying in 32 s." | Auto-backoff |
| `ChunkTooLarge` | internal; chunker re-splits and retries | Automatic |
| `ModelNotDownloaded` | "The {small} model isn't downloaded yet (466 MB)." | Download |
| `InsufficientMemoryForModel` | "This device doesn't have enough RAM for {small}. Try {base}." | Switch model |
| `ForegroundServiceTimeout` | "Paused by the system. 41 of 68 chunks done — reopen to continue." | Resume |
| `TranscriptionFailed(chunk, cause)` | "Chunk 12 of 68 failed after 3 attempts." | Retry chunk / skip |
| `EncoderUnavailable` | "This device can't re-encode video at {3840×2160}." | Lower resolution |
| `ExportWriteFailed` | "Couldn't write to the selected folder." | Pick another |

Per-chunk failures **do not** fail the job: mark the chunk `FAILED`, continue, and
surface a "3 chunks failed — retry?" affordance. A 68-chunk job must not be destroyed by
one 502.

Retries: exponential backoff with jitter on 429/5xx/IO, respecting `Retry-After`; 3
attempts per chunk before marking `FAILED`.

---

## 8. Privacy & security

- **No silent uploads.** The Import screen shows, before the button is enabled, exactly
  what leaves the device — derived from `capabilities.sendsAudioOffDevice`, not
  hardcoded per screen. First cloud run requires an explicit acknowledgement.
- **Audio only, never video.** The cloud path uploads ~14 MB/hour of 16 kHz mono AAC. The
  video file itself is never transmitted by any provider.
- **API keys** in `EncryptedSharedPreferences` (AES-256-GCM, master key in the Android
  Keystore, StrongBox when available). Never in `SharedPreferences`, never in
  `BuildConfig`, never logged. Masked in the UI after entry.
- **No content logging.** Transcript text, cue text, filenames, and audio paths are never
  written to logcat. Logging goes through a wrapper that takes IDs and durations, not
  content. `Timber` debug tree only in debug builds.
- **Cache hygiene.** Chunk files live in `cacheDir/projects/<id>/chunks/`, are deleted as
  consumed, and Settings has a "clear cache" with a real size readout. Orphaned project
  directories are swept on app start.
- Network security config: cleartext disabled. `usesCleartextTraffic=false`.
- No analytics SDK in v1.

---

## 9. Library choices

| Concern | Library | Why |
|---|---|---|
| UI | Jetpack Compose + Material 3 (`material3`, adaptive) | Required by brief; M3 expressive components |
| Navigation | `navigation-compose` with type-safe routes | Serializable route objects, no string parsing |
| DI | **none — hand-rolled `AppContainer`** | See below |
| Async | Coroutines + Flow | — |
| Background | WorkManager (`work-runtime-ktx`) | Long-running worker + foreground + constraints |
| Playback | media3 `exoplayer`, `ui-compose`, `exoplayer-*` | Side-loaded subtitle track for preview |
| Transform | media3 `transformer`, `effect`, `common` | Burn-in + audio processors, no FFmpeg |
| DB | Room | Unbounded cue lists; see the Paging note below |
| Prefs | DataStore (Proto or Preferences) | — |
| Secure store | **Android Keystore directly** | `EncryptedSharedPreferences` is deprecated |
| Network | OkHttp + `kotlinx-serialization` | One multipart endpoint doesn't justify Retrofit |
| Images | Coil | Thumbnails |
| Local ASR (v2) | whisper.cpp via NDK/CMake submodule | No Maven artifact exists |
| Logging | Timber (debug only) | — |
| Test | JUnit5/4, Turbine, MockK, Robolectric, `work-testing`, `room-testing`, Compose UI test | — |

**Explicitly not used:** ffmpeg-kit (retired, §3), `android.speech.SpeechRecognizer`
(wrong tool, §2), `AsyncTask`/`Loader`/`JobIntentService` (deprecated), Gson.

**Three deliberate simplifications made during implementation**, each reversible:

- **No Hilt.** The graph is a dozen singletons built in `AppContainer`, and workers get it
  through a 10-line `WorkerFactory`. Hilt would add an annotation processor and a
  Kotlin/KSP/AGP version coupling — the exact coupling that already forced two build
  changes above — for no benefit at this size. Introduce it when the graph outgrows one
  screenful.
- **No Retrofit.** One multipart endpoint, called from one place.
- **No Paging 3 yet.** Cues are text: a 2-hour film yields ~1,500 rows, a few hundred KB,
  and `LazyColumn` keys them so only visible rows compose. Paging earns its place past
  roughly 20,000 cues (a 20-hour recording); the DAO is already `Flow`-based, so swapping
  in `PagingSource` is a one-file change.

**Version note (corrected during Phase 2 — these are the versions that actually build):**
AGP **9.4.1**, Gradle **9.7.1**, Kotlin **2.4.20**, KSP **2.3.12**, media3 **1.11.1**,
Room **2.8.5**, WorkManager **2.11.2**, Compose BOM **2026.09.00**, OkHttp **5.5.0**.

Three things had to change from the Phase 1 plan once the build ran:

1. **compileSdk/targetSdk 37, not 36.** Current AndroidX (`compose-ui`, `okhttp-android`,
   `lifecycle`) refuses to be consumed by a project compiling against 36.
2. **AGP 9 ships built-in Kotlin support**, which is incompatible with KSP. Room needs
   KSP, so the project sets `android.builtInKotlin=false` and `android.newDsl=false` and
   applies `org.jetbrains.kotlin.android` explicitly. Revisit when KSP supports AGP's
   built-in Kotlin.
3. `OverlaySettings` lives in `androidx.media3.common`, not `androidx.media3.effect`, and
   `OverlayEffect` takes a plain `List`. Opt-in is project-wide via
   `-opt-in=androidx.media3.common.util.UnstableApi`.

---

## 10. SDK targets

| | Value | Reason |
|---|---|---|
| `minSdk` | **26** (Android 8.0) | Notification channels, stable async `MediaCodec`, `MediaMuxer` audio-only MP4, `java.time`. Costs ~<1% of devices vs. 21 and removes a pile of compat branches. |
| `targetSdk` / `compileSdk` | **36** (Android 16) | Required for Play; forces us to handle the FGS rules in §1.3 correctly rather than by accident. |
| ABIs | `arm64-v8a` (+ `x86_64` debug only) | Only relevant once whisper.cpp lands; `armeabi-v7a` is not worth the inference performance. |
| Java/Kotlin | JVM target 17, core library desugaring on | — |

---

## 11. Concurrency model

- **One** `TranscriptionWorker` per project, `ExistingWorkPolicy.KEEP`, unique name
  `transcribe:<projectId>`. Prevents a double-tap from running the job twice.
- Extraction and transcription run **pipelined**: a bounded `Channel<Chunk>` (capacity 2)
  between them, so uploads overlap decoding without letting the chunker run ahead and
  fill the disk.
- Cloud chunks may be transcribed with limited parallelism (default 2, settings-capped at
  4) — bounded by a `Semaphore`, with results reordered by chunk index before merging.
  Local inference is strictly serial (it already saturates the CPU).
- Cancellation is coroutine-native: `WorkManager.cancelUniqueWork` → `CancellationException`
  → `finally` blocks flush partial results to Room and delete temp files. A cancelled job
  is a *resumable* job, not a destroyed one.
- Progress via `setProgressAsync(Data)`: stage, chunk index, chunk count, processed µs,
  total µs, and a throughput-derived ETA (only shown after 3 chunks, so the first estimate
  isn't nonsense).

---

## 12. Phase plan

| Phase | Contents | State |
|---|---|---|
| 1 | This document | **done** |
| 2 | Gradle version catalog, manifest, DI, navigation, theme, Room schema | **done** |
| 3 | Picker, metadata, `AudioDecoder`, chunker, `TranscriptionProvider` + OpenAI impl, `TimelineMerger`, `CueSegmenter`, `TranscriptionWorker`, progress/cancel/resume | **done** |
| 4 | Editor (list + player), split/merge/retime/search, SRT/VTT read+write, styling, burn-in via Transformer | **done** |
| 5 | Tests (see §14). Device matrix still to do | **done** |
| 6 | `LocalWhisperProvider` (whisper.cpp), model manager | |
| T | Translation — both paths (§13) | **done** |
| 7 | `BackendTranscriptionProvider` | |

**MVP cut line** = end of Phase 4 with the OpenAI provider: select → transcribe a
2-hour video → edit → export SRT. That is a genuinely useful app.


---

## 13. Translation

Two separate mechanisms, because "translate the subtitles" means two different things
depending on whether you have transcribed yet.

### 13.1 Speech → English, during transcription (`TranscriptionTask.TRANSLATE_TO_ENGLISH`)

Whisper exposes `/v1/audio/translations` alongside `/v1/audio/transcriptions`: same model,
same price, same multipart shape, but the output is English regardless of what was spoken.
`WhisperApiProvider` simply routes to the other URL.

This is the better path whenever English is the goal and nothing has been transcribed yet:

- **No extra cost.** It replaces the transcription call, it does not add one.
- **Better timings.** Timestamps come from the audio, so the existing merge pipeline
  applies unchanged and nothing needs re-aligning.
- **No fragment problem** (see §13.2).

Two constraints, both inherent to the endpoint: the target is **always English** — there is
no target-language parameter — and the endpoint rejects `language` and
`timestamp_granularities[]`, so the provider omits both when translating. `verbose_json`
still returns per-segment timings.

Chosen on the Import screen, stored per project in `projects.task`, and surfaced only when
the provider declares `supportsTranslationToEnglish`.

### 13.2 Subtitles → any language, after the fact (`TranslationWorker`)

Translating the finished cues, via `TranslationProvider` → `OpenAiTranslationProvider`
(chat completions, JSON mode). Needed whenever §13.1 does not apply:

- the project is **already transcribed** — re-running §13.1 means re-extracting, re-uploading
  and paying the full transcription price again, versus a few cents of tokens here;
- the target is **not English**;
- you want the **original kept alongside** the translation.

**Storage.** `cues.translatedText` is a separate nullable column. The original `text` is
never overwritten. A bad translation is therefore always recoverable, and `SubtitleTrack`
(`ORIGINAL` / `TRANSLATION` / `BILINGUAL`) chooses what the editor, the preview player, the
subtitle export and the burn-in each render.

**The failure mode that actually matters.** Every cue maps to a timestamp we already know is
correct. If a model is given 20 lines and returns 19 — because it merged two short cues into
one better-reading sentence — then line 19 onwards inherits the wrong text and *every
subsequent subtitle in the file is wrong*. Silent corruption, invisible until playback.

So the count is treated as a hard contract:

- Lines are numbered and the response is JSON (`{"lines":[{"n":1,"t":"…"}]}`), which makes a
  miscount detectable rather than plausible-looking.
- A miscount is **never** patched by padding or truncating. The batch is split in half and
  retried, recursively, down to a single line. One line cannot be miscounted, so the
  recursion terminates in either a correct translation or a failure scoped to that one cue.
- A non-retryable error (bad key, retired model) short-circuits instead of binary-searching
  through 39 doomed requests.

**Context.** Cues are frequently mid-sentence fragments, because `CueSegmenter` split them
for readability. Translating a fragment in isolation produces wrong pronouns, tense and word
order — worst in languages whose syntax differs most from the source. Each request therefore
carries the batch as a numbered block plus the preceding 3 lines as context-only input.
This mitigates the problem; it does not eliminate it, and §13.1 avoids it entirely by working
from the audio.

**Re-wrapping.** Translated text rarely matches the source length, so each line is
re-wrapped with `CueSegmenter.wrap` after translation. Timings are never touched — only the
line break moves.

**Resume** is simpler than transcription's: the worker asks the DAO for cues with no
translation, so an interrupted run continues exactly where it stopped and re-running is also
how you retry failed lines. Changing the target language clears the previous translation
first.

**Privacy.** Subtitle *text* is sent to OpenAI; audio and video are not. Declared on
`TranslationCapabilities.sendsTextOffDevice`, the same pattern as the transcription side, and
stated in the translate dialog.

### 13.3 Schema migration

Adding translation took the database to v2: `cues.translatedText`, `projects.task`,
`projects.translationLanguage`, `projects.translatedCues`. `MIGRATION_1_2` is purely
additive with column-level defaults declared on the entities, so the schema and the migration
state the same thing and Room can validate it. Destructive migration is deliberately not
enabled — a v1 database can hold hours of paid transcription.


---

## 14. Testing

**102 tests, whole suite in under 30 seconds.** Everything runs on the JVM via
`./gradlew :app:testDebugUnitTest`. Robolectric
provides the Android framework, so Room, the repository and the workers are exercised for
real rather than mocked out.

There is deliberately **no instrumented (`androidTest`) source set**. An on-device test that
nobody can run is worse than no test: it looks like coverage and provides none. The Room
migration, the DAOs and the workers all run under Robolectric instead, so they execute in
CI and on any developer machine.

### What is covered, and why each exists

| Area | What it protects against |
|---|---|
| `SincResamplerTest` | Aliasing and level drift. Asserts passband level within 2% and that 15 kHz content is attenuated below 5% rather than folding down to 1 kHz. Also asserts block-size independence — streaming in 1 KB blocks must equal one big call, exactly. |
| `SilenceSeekingChunkerTest` | Lost or duplicated audio at boundaries, and non-determinism. Determinism is load-bearing: resume assumes chunk N covers the same audio on every run. |
| `LongVideoSoakTest` | Drift. Three hours and twelve hours of audio are **generated as they are fed**, never materialised — 3 h of 16 kHz mono is 345 MB, so a test that built it as one array would prove nothing about the streaming design. Asserts the last chunk still ends at the true end of the audio, that boundaries stay contiguous, and that written samples minus replayed overlap equals the input exactly. One pause period is built once and copied cyclically rather than calling `sin()` ~1.5 billion times, which is the difference between 0.6 s and 142 s for identical coverage. |
| `TimelineMergerTest` | The four merge steps, especially tail-hallucination dropping and seam de-duplication that keeps both versions when they disagree. |
| `CueSegmenterTest` | Unreadable output: line length, duration and CPS ceilings, and that splitting never loses or reorders a word. |
| `SubtitleFormatTest` | Malformed SRT/VTT, and round-tripping through the parser. |
| `SubtitleTranslatorTest` | The miscount failure mode (§13.2): split-and-retry, per-line failure isolation, and that a non-retryable error does not binary-search through doomed requests. |
| `TranslationTrackTest` | Blank subtitles from a partially translated file. |
| `TranslationWorkerTest` | Off-by-one placement of translations, and that re-running only retries what failed. |
| `MigrationTest` | An upgrade destroying paid transcription. Builds a genuine v1 database from Room's own exported v1 DDL, then opens it through the production `SubtitleDatabase.build()` path so Room's schema validation runs. |
| `ProjectRepositoryTest` | The hand-editing operations, on data that cost money to produce. |
| `ProcessingErrorTest` | Generic error messages. |

### Bugs these tests found

Worth recording, because they are the argument for writing them:

1. **`splitCue` crashed** on a single-character cue and on a cue shorter than 2 ms —
   `coerceIn` on an empty range. Now guarded by `Cue.canSplit`, which the editor also uses
   to disable the button rather than offer an action that silently does nothing.
2. **`splitCue` duplicated the translation** onto both halves, so a translated cue split in
   two exported the same sentence twice. Both halves are now marked untranslated; the next
   translation run fills them. A whole-cue translation cannot be cut at the same proportion
   as its source, because word order differs between languages.
3. **`mergeCues` silently dropped** the second cue's translation. Now joined.
4. **`shiftAll` collapsed cues** on a large backwards shift: clamping each cue at zero
   independently stacked several at zero with overlapping ranges — an invalid subtitle file
   — and destroyed relative timing the transcription got right. The shift is now reduced so
   the earliest cue lands exactly at zero, preserving every gap.
5. **E-AC-3 detection** checked for `"e-"` where Android's mime is `audio/eac3`.
6. **Cue splitting could exceed the duration ceiling**, and a minimum-piece-size floor then
   blocked the fix.

### What is still not tested

- **Nothing has run on real hardware.** MediaCodec behaviour across vendor decoders, the
  `Transformer` burn-in path on specific encoders, and foreground-service behaviour under
  Android 15's 6-hour cap are all unverified. The environment this was built in has no
  device and no KVM, so an emulator is not available either.
- **No live API call has been made.** `WhisperApiProvider` and `OpenAiTranslationProvider`
  are tested against their own error mapping and parsing, not against the real endpoints.
- Compose UI tests.
