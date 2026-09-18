package nl.tippie.subtitle.util

import nl.tippie.subtitle.domain.model.ProcessingError

object Formatting {

    fun bytes(value: Long): String = ProcessingError.formatBytes(value)

    /** `2 h 14 min`, `7 min 12 s`, `43 s` */
    fun duration(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return when {
            h > 0 -> "$h h ${m} min"
            m > 0 -> "$m min ${s} s"
            else -> "$s s"
        }
    }

    fun eta(seconds: Long): String = when {
        seconds < 60 -> "under a minute"
        seconds < 3600 -> "${seconds / 60} min"
        else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
    }

    fun resolution(width: Int?, height: Int?): String? =
        if (width != null && height != null) "$width × $height" else null
}

/** ISO 639-1 subset covering Whisper's strongest languages, plus auto-detect. */
object Languages {
    const val AUTO = "auto"

    val supported: List<Pair<String, String>> = listOf(
        AUTO to "Detect automatically",
        "en" to "English",
        "nl" to "Dutch",
        "de" to "German",
        "fr" to "French",
        "es" to "Spanish",
        "it" to "Italian",
        "pt" to "Portuguese",
        "pl" to "Polish",
        "sv" to "Swedish",
        "da" to "Danish",
        "no" to "Norwegian",
        "fi" to "Finnish",
        "ru" to "Russian",
        "uk" to "Ukrainian",
        "tr" to "Turkish",
        "ar" to "Arabic",
        "hi" to "Hindi",
        "zh" to "Chinese",
        "ja" to "Japanese",
        "ko" to "Korean",
        "id" to "Indonesian",
        "cs" to "Czech",
        "el" to "Greek",
        "he" to "Hebrew",
        "ro" to "Romanian",
        "hu" to "Hungarian",
    )

    fun label(code: String?): String =
        supported.firstOrNull { it.first == (code ?: AUTO) }?.second ?: (code ?: AUTO)
}
