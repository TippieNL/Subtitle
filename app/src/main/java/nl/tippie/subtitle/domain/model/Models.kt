package nl.tippie.subtitle.domain.model

/**
 * All timings in this app are milliseconds. SRT/VTT have millisecond resolution,
 * Whisper returns seconds-as-float, and MediaCodec works in microseconds — we convert
 * at those three boundaries and nowhere else, so there is exactly one unit in the
 * domain, the database and the UI.
 */

enum class ProjectStatus {
    DRAFT, EXTRACTING, TRANSCRIBING, COMPLETED, FAILED, CANCELLED, PAUSED, TRANSLATING
}

/**
 * What the speech-to-text step should produce.
 *
 * [TRANSLATE_TO_ENGLISH] uses Whisper's translation endpoint, which turns foreign speech
 * directly into English text. It costs the same as transcription and its timestamps come
 * from the audio itself, so it is strictly better than transcribing and then translating
 * the text — when English is the target and you have not transcribed yet.
 */
enum class TranscriptionTask { TRANSCRIBE, TRANSLATE_TO_ENGLISH }

/** Which text a screen or export should use. */
enum class SubtitleTrack { ORIGINAL, TRANSLATION, BILINGUAL }

enum class ChunkState { PENDING, EXTRACTED, TRANSCRIBED, FAILED }

/** How a chunk boundary was chosen — drives whether seam de-duplication is needed. */
enum class BoundaryKind {
    /** Cut placed inside detected silence. No overlap, no dedup required. */
    SILENCE,
    /** Forced cut through continuous audio. Next chunk carries an overlap tail. */
    HARD
}

enum class ProviderId(val key: String, val displayName: String) {
    OPENAI_WHISPER("openai_whisper", "OpenAI Whisper (cloud)"),
    LOCAL_WHISPER("local_whisper", "On-device Whisper"),
    CUSTOM_BACKEND("custom_backend", "Custom backend");

    companion object {
        fun fromKey(key: String): ProviderId = entries.firstOrNull { it.key == key } ?: OPENAI_WHISPER
    }
}

data class AudioTrackInfo(
    val trackIndex: Int,
    val mimeType: String,
    val sampleRateHz: Int,
    val channelCount: Int,
    val language: String?,
    val bitrate: Int?,
) {
    val label: String
        get() = buildString {
            append(mimeType.substringAfter('/').uppercase())
            append(" · ")
            append(if (channelCount == 1) "mono" else "$channelCount ch")
            append(" · ${sampleRateHz / 1000} kHz")
            language?.let { append(" · $it") }
        }
}

data class MediaInfo(
    val displayName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val widthPx: Int?,
    val heightPx: Int?,
    val audioTracks: List<AudioTrackInfo>,
    val hasVideoTrack: Boolean,
)

data class Cue(
    val id: Long = 0,
    val projectId: Long,
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val chunkIndex: Int = 0,
    /** Null until a translation pass has run. The original [text] is never overwritten. */
    val translatedText: String? = null,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)

    /** The text to render for [track], falling back to the original when untranslated. */
    fun textFor(track: SubtitleTrack): String = when (track) {
        SubtitleTrack.ORIGINAL -> text
        SubtitleTrack.TRANSLATION -> translatedText ?: text
        SubtitleTrack.BILINGUAL ->
            if (translatedText.isNullOrBlank()) text
            else "${translatedText.replace("\n", " ")}\n${text.replace("\n", " ")}"
    }

    /** Characters per second — the standard readability metric for subtitles. */
    val charsPerSecond: Double
        get() = if (durationMs <= 0) Double.MAX_VALUE
        else text.replace("\n", " ").length * 1000.0 / durationMs
}

data class SubtitleProject(
    val id: Long = 0,
    val name: String,
    val sourceUri: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val widthPx: Int?,
    val heightPx: Int?,
    val audioTrackIndex: Int,
    val language: String?,
    val detectedLanguage: String?,
    val providerKey: String,
    val status: ProjectStatus,
    val task: TranscriptionTask,
    /** ISO code of the language the cues have been translated into, if any. */
    val translationLanguage: String?,
    val translatedCues: Int,
    val processedMs: Long,
    val totalChunks: Int,
    val completedChunks: Int,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val progressFraction: Float
        get() = when {
            status == ProjectStatus.COMPLETED -> 1f
            durationMs <= 0L -> 0f
            else -> (processedMs.toFloat() / durationMs).coerceIn(0f, 1f)
        }
}

/** Rendering options for preview and burn-in. */
data class SubtitleStyle(
    val fontSizeSp: Float = 20f,
    val textColor: Long = 0xFFFFFFFF,
    val backgroundColor: Long = 0x99000000,
    val outline: Boolean = true,
    val verticalPosition: VerticalPosition = VerticalPosition.BOTTOM,
    val bottomMarginPercent: Float = 6f,
) {
    enum class VerticalPosition { TOP, CENTER, BOTTOM }
}

data class TranscriptionConfig(
    val providerKey: String,
    val language: String?,            // null = auto-detect
    val task: TranscriptionTask = TranscriptionTask.TRANSCRIBE,
    val targetChunkMs: Long = 300_000,
    val parallelRequests: Int = 2,
    val wordTimestamps: Boolean = false,
)
