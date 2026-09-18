package nl.tippie.subtitle.transcription

import nl.tippie.subtitle.domain.model.ProcessingError
import nl.tippie.subtitle.domain.model.ProviderId
import nl.tippie.subtitle.domain.model.TranscriptionTask
import nl.tippie.subtitle.media.sink.ChunkEncoding
import java.io.File

/**
 * The single seam between the pipeline and whatever actually does speech-to-text.
 *
 * Nothing vendor-specific crosses this boundary: the orchestrator never knows whether it
 * is talking to an HTTP API, a JNI-bound local model, or a self-hosted backend.
 */
interface TranscriptionProvider {
    val id: ProviderId
    val capabilities: ProviderCapabilities
    val audioRequirements: AudioRequirements

    /** Usable right now — key present, model downloaded, etc. */
    suspend fun isAvailable(): Boolean

    /** Cheap credential/model check for the Settings screen. */
    suspend fun validate(): Result<Unit>

    /**
     * Transcribes one chunk. **Timestamps in the result are relative to the chunk**,
     * never absolute; shifting them is [nl.tippie.subtitle.subtitle.TimelineMerger]'s job.
     * Must honour coroutine cancellation.
     */
    suspend fun transcribe(request: ChunkRequest): Result<ChunkTranscript>
}

data class ProviderCapabilities(
    val supportsLanguageDetection: Boolean,
    val supportsWordTimestamps: Boolean,
    val supportsContextPrompt: Boolean,
    /** Can translate speech directly to English via [TranscriptionTask.TRANSLATE_TO_ENGLISH]. */
    val supportsTranslationToEnglish: Boolean,
    val requiresNetwork: Boolean,
    /**
     * Read by the UI to render the upload warning. Being part of the contract means a
     * provider cannot be added without declaring its privacy posture.
     */
    val sendsAudioOffDevice: Boolean,
    val privacyNote: String,
)

data class AudioRequirements(
    val encoding: ChunkEncoding,
    val sampleRateHz: Int,
    val channels: Int,
    /** Hard per-request ceiling, if the service has one. */
    val maxBytesPerChunk: Long?,
    val maxDurationPerChunkMs: Long?,
)

data class ChunkRequest(
    val audioFile: File,
    val chunkIndex: Int,
    val chunkDurationMs: Long,
    val language: String?,
    val contextPrompt: String?,
    val wantWordTimestamps: Boolean,
    /** Transcribe in the spoken language, or translate the speech straight to English. */
    val task: TranscriptionTask = TranscriptionTask.TRANSCRIBE,
)

data class RawSegment(val startMs: Long, val endMs: Long, val text: String)

data class RawWord(val startMs: Long, val endMs: Long, val word: String)

data class ChunkTranscript(
    val detectedLanguage: String?,
    val segments: List<RawSegment>,
    val words: List<RawWord>? = null,
)

/** Thrown by providers so the orchestrator can decide retry vs. fail vs. abort. */
class TranscriptionException(
    val error: ProcessingError,
    val retryable: Boolean,
    val retryAfterSeconds: Long? = null,
) : Exception(error.userMessage, error.cause)
