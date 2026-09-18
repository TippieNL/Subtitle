package nl.tippie.subtitle.translation.openai

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import nl.tippie.subtitle.domain.model.ProcessingError
import nl.tippie.subtitle.translation.TranslationCapabilities
import nl.tippie.subtitle.translation.TranslationException
import nl.tippie.subtitle.translation.TranslationProvider
import nl.tippie.subtitle.translation.TranslationProviderId
import nl.tippie.subtitle.translation.TranslationRequest
import nl.tippie.subtitle.transcription.openai.OpenAiErrorEnvelope
import nl.tippie.subtitle.util.Languages
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Translates subtitle text with OpenAI chat completions, in JSON mode.
 *
 * Why JSON mode and numbered lines rather than "translate this SRT": the one thing that
 * must not happen is the model merging two cues or dropping one, because every line maps
 * to a timestamp we already know is correct. Numbering makes a miscount detectable, and
 * the caller re-splits the batch instead of silently shifting every subsequent subtitle.
 *
 * The model is configurable because model names get retired; a wrong name surfaces as a
 * plain "model not found" message from the API rather than a mystery failure.
 */
class OpenAiTranslationProvider(
    private val apiKeyProvider: () -> String?,
    private val model: String = DEFAULT_MODEL,
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: String = "https://api.openai.com/v1",
) : TranslationProvider {

    override val id = TranslationProviderId.OPENAI_CHAT

    override val capabilities = TranslationCapabilities(
        requiresNetwork = true,
        sendsTextOffDevice = true,
        privacyNote = "Subtitle text (not audio, not video) is sent to OpenAI for translation.",
        supportedTargets = Languages.supported.map { it.first }.filterNot { it == Languages.AUTO },
    )

    override suspend fun isAvailable(): Boolean = !apiKeyProvider().isNullOrBlank()

    override suspend fun translate(request: TranslationRequest): Result<List<String>> = runCatching {
        if (request.lines.isEmpty()) return@runCatching emptyList()

        val key = apiKeyProvider()
        if (key.isNullOrBlank()) {
            throw TranslationException(ProcessingError.ApiKeyMissing(), retryable = false)
        }

        val body = json.encodeToString(
            ChatRequest(
                model = model,
                messages = listOf(
                    ChatMessage("system", systemPrompt(request)),
                    ChatMessage("user", userPrompt(request)),
                ),
            )
        ).toRequestBody(JSON_MEDIA_TYPE)

        val httpRequest = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(body)
            .build()

        execute(httpRequest).use { response ->
            if (!response.isSuccessful) throw mapHttpError(response)
            val payloadText = response.body?.string().orEmpty()
            val content = json.decodeFromString<ChatResponse>(payloadText)
                .choices.firstOrNull()?.message?.content
                ?: throw TranslationException(
                    ProcessingError.Unexpected("translation", IOException("empty response")),
                    retryable = true,
                )
            parse(content, request.lines.size)
        }
    }

    /**
     * Rejects a miscount rather than padding or truncating: a wrong count means the model
     * merged or split lines, and every following cue would inherit the wrong text.
     */
    private fun parse(content: String, expected: Int): List<String> {
        val payload = runCatching { json.decodeFromString<TranslationPayload>(content) }
            .getOrElse {
                throw TranslationException(
                    ProcessingError.Unexpected("translation", IOException("unparseable response")),
                    retryable = true,
                )
            }

        val byNumber = payload.lines.associateBy { it.n }
        val ordered = (1..expected).map { byNumber[it]?.t }

        if (ordered.any { it == null }) {
            throw TranslationException(
                ProcessingError.TranslationMiscount(expected, payload.lines.size),
                retryable = true,
            )
        }
        return ordered.map { it!!.trim() }
    }

    private fun systemPrompt(request: TranslationRequest): String {
        val target = Languages.label(request.targetLanguage)
        return """
            You translate subtitles into $target.

            Rules:
            - Return JSON: {"lines":[{"n":1,"t":"..."}]}
            - Return EXACTLY one entry per numbered input line, with the same numbers.
            - Never merge, split, reorder, add or drop lines. A line may be a fragment of a
              sentence that continues on the next line; translate it as that fragment.
            - Keep proper nouns, names, product names and technical terms.
            - Keep it short enough to read as a subtitle; prefer natural phrasing over literal.
            - Preserve a leading "-" (speaker change) and any [sound description] brackets.
            - If a line is already in $target, return it unchanged.
            - Translate only; never answer, explain or comment on the content.
        """.trimIndent()
    }

    private fun userPrompt(request: TranslationRequest): String = buildString {
        if (request.contextBefore.isNotEmpty()) {
            append("Preceding lines, for context only — do NOT translate or return these:\n")
            request.contextBefore.forEach { append("  ").append(it.replace("\n", " ")).append('\n') }
            append('\n')
        }
        request.sourceLanguage?.takeIf { it.isNotBlank() && it != Languages.AUTO }?.let {
            append("Source language: ").append(Languages.label(it)).append('\n')
        }
        append("Translate these ").append(request.lines.size).append(" lines:\n")
        request.lines.forEachIndexed { index, line ->
            append(index + 1).append(". ").append(line.replace("\n", " ")).append('\n')
        }
    }

    private fun mapHttpError(response: Response): TranslationException {
        val bodyText = runCatching { response.peekBody(8192).string() }.getOrNull().orEmpty()
        val apiMessage = runCatching {
            json.decodeFromString<OpenAiErrorEnvelope>(bodyText).error?.message
        }.getOrNull()
        val retryAfter = response.header("Retry-After")?.toLongOrNull()

        return when (response.code) {
            401, 403 -> TranslationException(ProcessingError.InvalidApiKey(), retryable = false)
            404 -> TranslationException(
                ProcessingError.TranslationModelUnavailable(model), retryable = false
            )
            429 -> if (apiMessage?.contains("quota", ignoreCase = true) == true) {
                TranslationException(ProcessingError.QuotaExceeded(), retryable = false)
            } else {
                TranslationException(
                    ProcessingError.RateLimited(retryAfter), retryable = true, retryAfterSeconds = retryAfter
                )
            }
            in 500..599 -> TranslationException(
                ProcessingError.Unexpected("translation", IOException("HTTP ${response.code}: $apiMessage")),
                retryable = true,
            )
            else -> TranslationException(
                ProcessingError.Unexpected(
                    "translation",
                    IOException("HTTP ${response.code}: ${apiMessage ?: bodyText.take(200)}")
                ),
                retryable = false,
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
                        TranslationException(
                            if (e is java.net.UnknownHostException || e is java.net.ConnectException)
                                ProcessingError.NetworkUnavailable()
                            else ProcessingError.Unexpected("translation", e),
                            retryable = true,
                        )
                    )
                }

                override fun onResponse(call: Call, response: Response) = cont.resume(response)
            })
        }

    companion object {
        /** Overridable in Settings, because model names do get retired. */
        const val DEFAULT_MODEL = "gpt-4o-mini"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.MINUTES)
            .callTimeout(5, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()
    }
}
