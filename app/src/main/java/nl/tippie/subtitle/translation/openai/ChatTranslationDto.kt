package nl.tippie.subtitle.translation.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.2,
    @SerialName("response_format") val responseFormat: ResponseFormat = ResponseFormat(),
)

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class ResponseFormat(val type: String = "json_object")

@Serializable
data class ChatResponse(val choices: List<ChatChoice> = emptyList())

@Serializable
data class ChatChoice(val message: ChatMessage? = null)

/** The JSON object we ask the model to return. */
@Serializable
data class TranslationPayload(val lines: List<TranslatedLine> = emptyList())

@Serializable
data class TranslatedLine(val n: Int = 0, val t: String = "")
