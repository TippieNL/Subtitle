package nl.tippie.subtitle.transcription.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VerboseTranscription(
    val task: String? = null,
    val language: String? = null,
    val duration: Double? = null,
    val text: String = "",
    val segments: List<VerboseSegment> = emptyList(),
    val words: List<VerboseWord> = emptyList(),
)

@Serializable
data class VerboseSegment(
    val id: Int = 0,
    val start: Double = 0.0,
    val end: Double = 0.0,
    val text: String = "",
    @SerialName("no_speech_prob") val noSpeechProb: Double = 0.0,
    @SerialName("avg_logprob") val avgLogprob: Double = 0.0,
    @SerialName("compression_ratio") val compressionRatio: Double = 0.0,
)

@Serializable
data class VerboseWord(
    val word: String = "",
    val start: Double = 0.0,
    val end: Double = 0.0,
)

@Serializable
data class OpenAiErrorEnvelope(val error: OpenAiError? = null)

@Serializable
data class OpenAiError(
    val message: String = "",
    val type: String? = null,
    val code: String? = null,
)
