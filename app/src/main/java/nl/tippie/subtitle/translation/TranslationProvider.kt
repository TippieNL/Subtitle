package nl.tippie.subtitle.translation

import nl.tippie.subtitle.domain.model.ProcessingError

/**
 * Translates already-timed subtitle text. Deliberately separate from
 * [nl.tippie.subtitle.transcription.TranscriptionProvider]: that one turns audio into
 * text, this one turns text into other text, and they have different providers, different
 * costs and different privacy stories.
 */
interface TranslationProvider {
    val id: TranslationProviderId
    val capabilities: TranslationCapabilities

    suspend fun isAvailable(): Boolean

    /**
     * Must return **exactly** `request.lines.size` strings, in the same order.
     * Returning a different count is a failure, not something to paper over — the caller
     * re-splits the batch rather than guessing which line went missing.
     */
    suspend fun translate(request: TranslationRequest): Result<List<String>>
}

enum class TranslationProviderId(val key: String, val displayName: String) {
    OPENAI_CHAT("openai_chat", "OpenAI (text translation)"),
    NONE("none", "None");

    companion object {
        fun fromKey(key: String): TranslationProviderId =
            entries.firstOrNull { it.key == key } ?: OPENAI_CHAT
    }
}

data class TranslationCapabilities(
    val requiresNetwork: Boolean,
    /** Drives the privacy banner, exactly as on the transcription side. */
    val sendsTextOffDevice: Boolean,
    val privacyNote: String,
    val supportedTargets: List<String>,
)

data class TranslationRequest(
    /** The lines to translate, in timeline order. */
    val lines: List<String>,
    /**
     * Lines immediately preceding [lines], supplied as context only and never returned.
     * Subtitle cues are frequently mid-sentence fragments; without the run-up the model
     * mistranslates pronouns, tense and word order.
     */
    val contextBefore: List<String>,
    val sourceLanguage: String?,
    val targetLanguage: String,
)

class TranslationException(
    val error: ProcessingError,
    val retryable: Boolean,
    val retryAfterSeconds: Long? = null,
) : Exception(error.userMessage, error.cause)
