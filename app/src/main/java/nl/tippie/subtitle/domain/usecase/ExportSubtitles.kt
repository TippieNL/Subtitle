package nl.tippie.subtitle.domain.usecase

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.tippie.subtitle.domain.model.Cue
import nl.tippie.subtitle.domain.model.ProcessingError
import nl.tippie.subtitle.domain.model.SubtitleStyle
import nl.tippie.subtitle.domain.model.SubtitleTrack
import nl.tippie.subtitle.subtitle.format.SrtWriter
import nl.tippie.subtitle.subtitle.format.SubtitleFormat
import nl.tippie.subtitle.subtitle.format.VttWriter
import java.io.File

/**
 * Writes subtitles to a user-chosen location through the Storage Access Framework.
 *
 * Streams cue by cue into the destination's OutputStream — a 12-hour transcript is never
 * materialised as a single String.
 */
class ExportSubtitles(private val context: Context) {

    suspend fun toUri(
        cues: List<Cue>,
        format: SubtitleFormat,
        destination: Uri,
        style: SubtitleStyle? = null,
        track: SubtitleTrack = SubtitleTrack.ORIGINAL,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val projected = cues.project(track)
        runCatching {
            context.contentResolver.openOutputStream(destination, "wt")
                ?.bufferedWriter(Charsets.UTF_8)
                ?.use { writer ->
                    when (format) {
                        SubtitleFormat.SRT -> SrtWriter.write(projected, writer)
                        SubtitleFormat.VTT -> VttWriter.write(projected, writer, style?.let(::vttCss))
                    }
                } ?: throw java.io.IOException("could not open the selected file for writing")
        }.recoverCatching { throw ExportException(ProcessingError.ExportWriteFailed(it)) }
    }

    /** Writes a throwaway VTT for the in-app preview player. */
    suspend fun toPreviewFile(
        cues: List<Cue>,
        projectId: Long,
        track: SubtitleTrack = SubtitleTrack.ORIGINAL,
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "preview").apply { mkdirs() }
            // Track is part of the name so switching tracks makes the player reload.
            val file = File(dir, "preview_${projectId}_${track.name.lowercase()}.vtt")
            file.bufferedWriter(Charsets.UTF_8).use { VttWriter.write(cues.project(track), it) }
            file
        }.getOrNull()
    }

    /** Swaps each cue's text for the chosen track, leaving timings untouched. */
    private fun List<Cue>.project(track: SubtitleTrack): List<Cue> =
        if (track == SubtitleTrack.ORIGINAL) this
        else map { it.copy(text = it.textFor(track)) }

    private fun vttCss(style: SubtitleStyle): String = buildString {
        append("::cue {\n")
        append("  color: ${style.textColor.toCssColor()};\n")
        append("  background-color: ${style.backgroundColor.toCssColor()};\n")
        append("  font-size: ${style.fontSizeSp.toInt()}px;\n")
        append("}")
    }

    private fun Long.toCssColor(): String {
        val a = ((this shr 24) and 0xFF) / 255.0
        val r = (this shr 16) and 0xFF
        val g = (this shr 8) and 0xFF
        val b = this and 0xFF
        return "rgba($r, $g, $b, ${"%.2f".format(a)})"
    }

    fun suggestedFileName(
        projectName: String,
        format: SubtitleFormat,
        track: SubtitleTrack = SubtitleTrack.ORIGINAL,
        targetLanguage: String? = null,
    ): String {
        val base = projectName.substringBeforeLast('.').ifBlank { "subtitles" }
            .replace(Regex("[^A-Za-z0-9 _.-]"), "_")
        val suffix = when (track) {
            SubtitleTrack.ORIGINAL -> ""
            SubtitleTrack.TRANSLATION -> ".${targetLanguage ?: "translated"}"
            SubtitleTrack.BILINGUAL -> ".bilingual"
        }
        return "$base$suffix.${format.extension}"
    }
}

class ExportException(val error: ProcessingError) : Exception(error.userMessage, error.cause)
