package nl.tippie.subtitle

import com.google.common.truth.Truth.assertThat
import nl.tippie.subtitle.domain.model.Cue
import nl.tippie.subtitle.subtitle.format.SrtWriter
import nl.tippie.subtitle.subtitle.format.SubtitleParser
import nl.tippie.subtitle.subtitle.format.TimeFormat
import nl.tippie.subtitle.subtitle.format.VttWriter
import org.junit.Test
import java.io.StringWriter

class SubtitleFormatTest {

    private val cues = listOf(
        Cue(id = 1, projectId = 1, index = 0, startMs = 1_000, endMs = 3_500, text = "First line"),
        Cue(id = 2, projectId = 1, index = 1, startMs = 3_600, endMs = 6_000, text = "Second\nwith a break"),
        Cue(id = 3, projectId = 1, index = 2, startMs = 3_725_400, endMs = 3_727_000, text = "Past one hour"),
    )

    @Test
    fun `srt timestamps use a comma`() {
        assertThat(TimeFormat.srt(3_725_400)).isEqualTo("01:02:05,400")
    }

    @Test
    fun `vtt timestamps use a period`() {
        assertThat(TimeFormat.vtt(3_725_400)).isEqualTo("01:02:05.400")
    }

    @Test
    fun `negative times are clamped rather than producing garbage`() {
        assertThat(TimeFormat.srt(-5_000)).isEqualTo("00:00:00,000")
    }

    @Test
    fun `srt output is numbered from one and blank-line separated`() {
        val writer = StringWriter()
        SrtWriter.write(cues, writer)
        val text = writer.toString()

        assertThat(text).startsWith("1\n00:00:01,000 --> 00:00:03,500\nFirst line\n\n")
        assertThat(text).contains("2\n00:00:03,600 --> 00:00:06,000\n")
        assertThat(text).contains("3\n01:02:05,400 --> 01:02:07,000\n")
    }

    @Test
    fun `vtt output carries the required header`() {
        val writer = StringWriter()
        VttWriter.write(cues, writer)
        assertThat(writer.toString()).startsWith("WEBVTT\n\n")
    }

    @Test
    fun `blank cues are skipped and numbering stays contiguous`() {
        val withBlank = cues + Cue(id = 4, projectId = 1, index = 3, startMs = 9_000, endMs = 10_000, text = "   ")
        val writer = StringWriter()
        SrtWriter.write(withBlank, writer)
        assertThat(writer.toString()).doesNotContain("4\n")
    }

    @Test
    fun `srt round-trips through the parser`() {
        val writer = StringWriter()
        SrtWriter.write(cues, writer)
        val parsed = SubtitleParser.parse(writer.toString(), projectId = 1)

        assertThat(parsed).hasSize(3)
        assertThat(parsed.map { it.startMs }).isEqualTo(cues.map { it.startMs })
        assertThat(parsed.map { it.endMs }).isEqualTo(cues.map { it.endMs })
        assertThat(parsed.map { it.text }).isEqualTo(cues.map { it.text })
    }

    @Test
    fun `vtt round-trips through the parser`() {
        val writer = StringWriter()
        VttWriter.write(cues, writer)
        val parsed = SubtitleParser.parse(writer.toString(), projectId = 1)
        assertThat(parsed.map { it.text }).isEqualTo(cues.map { it.text })
    }

    @Test
    fun `parser accepts windows line endings`() {
        val content = "1\r\n00:00:01,000 --> 00:00:02,000\r\nHello\r\n\r\n"
        val parsed = SubtitleParser.parse(content, projectId = 1)
        assertThat(parsed).hasSize(1)
        assertThat(parsed[0].text).isEqualTo("Hello")
    }

    @Test
    fun `time parser accepts several shapes`() {
        assertThat(TimeFormat.parse("01:02:05.400")).isEqualTo(3_725_400)
        assertThat(TimeFormat.parse("01:02:05,400")).isEqualTo(3_725_400)
        assertThat(TimeFormat.parse("02:05")).isEqualTo(125_000)
        assertThat(TimeFormat.parse("5.5")).isEqualTo(5_500)
        assertThat(TimeFormat.parse("nonsense")).isNull()
    }
}
