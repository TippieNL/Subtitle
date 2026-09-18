package nl.tippie.subtitle.transcription.openai

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import nl.tippie.subtitle.domain.model.ProcessingError
import nl.tippie.subtitle.domain.model.ProviderId
import nl.tippie.subtitle.media.sink.ChunkEncoding
import nl.tippie.subtitle.transcription.AudioRequirements
import nl.tippie.subtitle.transcription.ChunkRequest
import nl.tippie.subtitle.transcription.ChunkTranscript
import nl.tippie.subtitle.transcription.ProviderCapabilities
import nl.tippie.subtitle.transcription.RawSegment
import nl.tippie.subtitle.transcription.RawWord
import nl.tippie.subtitle.transcription.TranscriptionException
import nl.tippie.subtitle.transcription.TranscriptionProvider
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OpenAI `/v1/audio/transcriptions`, model `whisper-1`.
 *
 * `whisper-1` is not a preference — it is the only OpenAI transcription model that
 * returns `verbose_json` with per-segment timestamps. The gpt-4o-transcribe family
 * returns text without timestamp arrays, which is useless for subtitles.
 *
 * Service limit: 25 MB per request. Our default 5-minute chunk is ~9.6 MB as WAV and
 * ~1.2 MB as AAC, so the limit never binds — but it is enforced in [audioRequirements]
 * so the chunker cannot accidentally exceed it.
 */
class WhisperApiProvider(
    private val apiKeyProvider: () -> String?,
    private val encoding: ChunkEncoding,
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: String = "https://api.openai.com/v1",
) : TranscriptionProvider {

    override val id = ProviderId.OPENAI_WHISPER

    override val capabilities = ProviderCapabilities(
        supportsLanguageDetection = true,
        supportsWordTimestamps = true,
        supportsContextPrompt = true,
        requiresNetwork = true,
        sendsAudioOffDevice = true,
        privacyNote = "Audio (not video) is uploaded to OpenAI for transcription. " +
            "About ${if (encoding == ChunkEncoding.WAV) "115 MB" else "14 MB"} per hour of video.",
    )

    override val audioRequirements = AudioRequirements(
        encoding = encoding,
        sampleRateHz = 16_000,
        channels = 1,
        maxBytesPerChunk = MAX_UPLOAD_BYTES,
        maxDurationPerChunkMs = null,
    )

    override suspend fun isAvailable(): Boolean = !apiKeyProvider().isNullOrBlank()

    override suspend fun validate(): Result<Unit> {
        val key = apiKeyProvider()
        if (key.isNullOrBlank()) {
            return Result.failure(TranscriptionException(ProcessingError.ApiKeyMissing(), false))
        }
        val request = Request.Builder()
            .url("$baseUrl/models/$MODEL")
            .header("Authorization", "Bearer $key")
            .get()
            .build()
        return runCatching {
            execute(request).use { response ->
                if (!response.isSuccessful) throw mapHttpError(response)
            }
        }
    }

    override suspend fun transcribe(request: ChunkRequest): Result<ChunkTranscript> = runCatching {
        val key = apiKeyProvider()
            ?: throw TranscriptionException(ProcessingError.ApiKeyMissing(), retryable = false)
        if (key.isBlank()) {
            throw TranscriptionException(ProcessingError.ApiKeyMissing(), retryable = false)
        }

        val size = request.audioFile.length()
        if (size > MAX_UPLOAD_BYTES) {
            // The chunker should prevent this; treat it as non-retryable so the caller re-splits.
            throw TranscriptionException(
                ProcessingError.Unexpected(
                    "upload",
                    IllegalStateException("chunk is ${size / 1_048_576} MB, over the 25 MB limit")
                ),
                retryable = false
            )
        }

        val mediaType = request.audioFile.extension.let {
            when (it) {
                "wav" -> "audio/wav"
                "m4a", "mp4" -> "audio/mp4"
                "flac" -> "audio/flac"
                else -> "application/octet-stream"
            }
        }.toMediaType()

        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", request.audioFile.name, request.audioFile.asRequestBody(mediaType)
            )
            .addFormDataPart("model", MODEL)
            .addFormDataPart("response_format", "verbose_json")
            .apply {
                addFormDataPart("timestamp_granularities[]", "segment")
                if (request.wantWordTimestamps) {
                    addFormDataPart("timestamp_granularities[]", "word")
                }
                request.language?.takeIf { it.isNotBlank() && it != AUTO }
                    ?.let { addFormDataPart("language", it) }
                request.contextPrompt?.takeIf { it.isNotBlank() }
                    ?.let { addFormDataPart("prompt", it.take(MAX_PROMPT_CHARS)) }
            }
            .build()

        val httpRequest = Request.Builder()
            .url("$baseUrl/audio/transcriptions")
            .header("Authorization", "Bearer $key")
            .post(body)
            .build()

        execute(httpRequest).use { response ->
            if (!response.isSuccessful) throw mapHttpError(response)
            val payload = response.body?.string().orEmpty()
            val parsed = json.decodeFromString<VerboseTranscription>(payload)
            toChunkTranscript(parsed)
        }
    }

    private fun toChunkTranscript(v: VerboseTranscription): ChunkTranscript {
        val segments = v.segments
            .filter { it.text.isNotBlank() }
            // Whisper marks pure-silence stretches with a high no-speech probability and
            // still emits text for them; those are hallucinations, not speech.
            .filterNot { it.noSpeechProb > NO_SPEECH_THRESHOLD && it.avgLogprob < LOGPROB_FLOOR }
            .map {
                RawSegment(
                    startMs = (it.start * 1000).toLong(),
                    endMs = (it.end * 1000).toLong(),
                    text = it.text.trim(),
                )
            }
        val words = v.words.takeIf { it.isNotEmpty() }?.map {
            RawWord((it.start * 1000).toLong(), (it.end * 1000).toLong(), it.word)
        }
        return ChunkTranscript(
            detectedLanguage = v.language?.takeIf { it.isNotBlank() },
            segments = segments,
            words = words,
        )
    }

    private fun mapHttpError(response: Response): TranscriptionException {
        val bodyText = runCatching { response.peekBody(8192).string() }.getOrNull().orEmpty()
        val apiMessage = runCatching {
            json.decodeFromString<OpenAiErrorEnvelope>(bodyText).error?.message
        }.getOrNull()
        val retryAfter = response.header("Retry-After")?.toLongOrNull()

        return when (response.code) {
            401, 403 -> TranscriptionException(ProcessingError.InvalidApiKey(), retryable = false)
            429 -> if (apiMessage?.contains("quota", ignoreCase = true) == true) {
                TranscriptionException(ProcessingError.QuotaExceeded(), retryable = false)
            } else {
                TranscriptionException(
                    ProcessingError.RateLimited(retryAfter), retryable = true, retryAfterSeconds = retryAfter
                )
            }
            in 500..599 -> TranscriptionException(
                ProcessingError.Unexpected("transcription", IOException("HTTP ${response.code}: $apiMessage")),
                retryable = true
            )
            else -> TranscriptionException(
                ProcessingError.Unexpected(
                    "transcription",
                    IOException("HTTP ${response.code}: ${apiMessage ?: bodyText.take(200)}")
                ),
                retryable = false
            )
        }
    }

    private suspend fun execute(request: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isCancelled) return
                    cont.resumeWithException(
                        TranscriptionException(
                            if (e is java.net.UnknownHostException || e is java.net.ConnectException)
                                ProcessingError.NetworkUnavailable()
                            else ProcessingError.Unexpected("transcription", e),
                            retryable = true
                        )
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    cont.resume(response)
                }
            })
        }

    companion object {
        const val MODEL = "whisper-1"
        const val AUTO = "auto"

        /** OpenAI's documented per-request limit. */
        const val MAX_UPLOAD_BYTES = 25L * 1024 * 1024

        /** The API caps the prompt at 224 tokens; ~800 chars stays comfortably inside. */
        private const val MAX_PROMPT_CHARS = 800

        private const val NO_SPEECH_THRESHOLD = 0.6
        private const val LOGPROB_FLOOR = -1.0

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            // A 5-minute WAV chunk over a slow mobile link legitimately takes minutes.
            .writeTimeout(10, TimeUnit.MINUTES)
            .readTimeout(10, TimeUnit.MINUTES)
            .callTimeout(20, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()
    }
}
