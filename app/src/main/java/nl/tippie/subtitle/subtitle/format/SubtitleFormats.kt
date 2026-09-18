package nl.tippie.subtitle.subtitle.format

import nl.tippie.subtitle.domain.model.Cue
import java.io.Writer

enum class SubtitleFormat(val extension: String, val mimeType: String, val label: String) {
    SRT("srt", "application/x-subrip", "SubRip (.srt)"),
    VTT("vtt", "text/vtt", "WebVTT (.vtt)"),
}

object TimeFormat {
    /** `00:01:23,456` — SRT uses a comma before the milliseconds. */
    fun srt(ms: Long): String = format(ms, ',')

    /** `00:01:23.456` — WebVTT uses a period. */
    fun vtt(ms: Long): String = format(ms, '.')

    /** `1:23` / `1:02:03` for UI display. */
    fun display(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    /** `00:01:23.4` for the editor's timestamp fields. */
    fun precise(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val h = safe / 3_600_000
        val m = (safe % 3_600_000) / 60_000
        val s = (safe % 60_000) / 1000
        val milli = safe % 1000
        return String.format("%02d:%02d:%02d.%03d", h, m, s, milli)
    }

    /** Parses `hh:mm:ss.mmm`, `mm:ss.mmm`, `mm:ss` or plain seconds. Null if unparseable. */
    fun parse(input: String): Long? {
        val trimmed = input.trim().replace(',', '.')
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(':')
        return try {
            when (parts.size) {
                1 -> (parts[0].toDouble() * 1000).toLong()
                2 -> parts[0].toLong() * 60_000 + (parts[1].toDouble() * 1000).toLong()
                3 -> parts[0].toLong() * 3_600_000 + parts[1].toLong() * 60_000 +
                    (parts[2].toDouble() * 1000).toLong()
                else -> null
            }?.coerceAtLeast(0)
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun format(ms: Long, millisSeparator: Char): String {
        val safe = ms.coerceAtLeast(0)
        val h = safe / 3_600_000
        val m = (safe % 3_600_000) / 60_000
        val s = (safe % 60_000) / 1000
        val milli = safe % 1000
        return String.format("%02d:%02d:%02d%c%03d", h, m, s, millisSeparator, milli)
    }
}

object SrtWriter {
    /** Streams straight to the writer — a 12-hour transcript never sits in memory as one string. */
    fun write(cues: List<Cue>, out: Writer) {
        var index = 1
        for (cue in cues) {
            if (cue.text.isBlank()) continue
            out.write(index.toString()); out.write("\n")
            out.write(TimeFormat.srt(cue.startMs))
            out.write(" --> ")
            out.write(TimeFormat.srt(cue.endMs))
            out.write("\n")
            out.write(cue.text.trim())
            out.write("\n\n")
            index++
        }
        out.flush()
    }
}

object VttWriter {
    fun write(cues: List<Cue>, out: Writer, styleCss: String? = null) {
        out.write("WEBVTT\n\n")
        if (!styleCss.isNullOrBlank()) {
            out.write("STYLE\n")
            out.write(styleCss.trim())
            out.write("\n\n")
        }
        var index = 1
        for (cue in cues) {
            if (cue.text.isBlank()) continue
            out.write(index.toString()); out.write("\n")
            out.write(TimeFormat.vtt(cue.startMs))
            out.write(" --> ")
            out.write(TimeFormat.vtt(cue.endMs))
            out.write("\n")
            out.write(cue.text.trim())
            out.write("\n\n")
            index++
        }
        out.flush()
    }
}

/** Reads SRT or WebVTT — used to import an existing subtitle file into a project. */
object SubtitleParser {

    private val ARROW = Regex("""\s*-->\s*""")

    fun parse(content: String, projectId: Long): List<Cue> {
        val cues = mutableListOf<Cue>()
        val lines = content.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0
        var index = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty() || line.equals("WEBVTT", true) || line.startsWith("NOTE") ||
                line.startsWith("STYLE") || line.startsWith("REGION")
            ) { i++; continue }

            val timingLine = when {
                line.contains("-->") -> line
                i + 1 < lines.size && lines[i + 1].contains("-->") -> { i++; lines[i].trim() }
                else -> { i++; continue }
            }

            val parts = ARROW.split(timingLine.substringBefore(" position:").trim())
            if (parts.size < 2) { i++; continue }
            val start = TimeFormat.parse(parts[0].trim())
            val end = TimeFormat.parse(parts[1].trim().substringBefore(' '))
            if (start == null || end == null) { i++; continue }

            i++
            val text = StringBuilder()
            while (i < lines.size && lines[i].trim().isNotEmpty()) {
                if (text.isNotEmpty()) text.append('\n')
                text.append(lines[i].trim())
                i++
            }
            if (text.isNotBlank()) {
                cues += Cue(
                    projectId = projectId,
                    index = index++,
                    startMs = start,
                    endMs = maxOf(end, start + 1),
                    text = text.toString(),
                )
            }
        }
        return cues
    }
}
