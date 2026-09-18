package nl.tippie.subtitle

import com.google.common.truth.Truth.assertThat
import nl.tippie.subtitle.subtitle.CueSegmenter
import nl.tippie.subtitle.subtitle.TimelineMerger
import org.junit.Test

class CueSegmenterTest {

    private val config = CueSegmenter.Config()

    @Test
    fun `a short segment is left alone`() {
        val input = listOf(TimelineMerger.Placed(0, 2_000, "Just a short line."))
        val result = CueSegmenter.segment(input, config)
        assertThat(result).hasSize(1)
        assertThat(result[0].text).isEqualTo("Just a short line.")
    }

    @Test
    fun `a long segment is split into several cues`() {
        val text = "This is the first sentence of the block. " +
            "Here comes a second sentence that also carries weight. " +
            "And a third one closes the paragraph out neatly. " +
            "A fourth sentence makes sure we really exceed the limit."
        val input = listOf(TimelineMerger.Placed(0, 24_000, text))
        val result = CueSegmenter.segment(input, config)

        assertThat(result.size).isAtLeast(4)
        result.forEach {
            assertThat(it.text.replace("\n", " ").length).isAtMost(config.maxChars)
            assertThat(it.endMs - it.startMs).isAtMost(config.maxDurationMs)
        }
    }

    @Test
    fun `splitting preserves every word in order`() {
        val text = "alpha bravo charlie delta echo foxtrot golf hotel india juliet " +
            "kilo lima mike november oscar papa quebec romeo sierra tango uniform victor"
        val input = listOf(TimelineMerger.Placed(0, 20_000, text))
        val result = CueSegmenter.segment(input, config)

        val rebuilt = result.joinToString(" ") { it.text.replace("\n", " ") }
            .split(' ').filter { it.isNotBlank() }
        assertThat(rebuilt).isEqualTo(text.split(' '))
    }

    @Test
    fun `split cues stay inside the original time range and do not overlap`() {
        val text = "One sentence here. Another sentence follows it. A third sentence ends things. " +
            "And a fourth to be sure it splits more than once."
        val input = listOf(TimelineMerger.Placed(10_000, 34_000, text))
        val result = CueSegmenter.segment(input, config)

        assertThat(result.first().startMs).isEqualTo(10_000L)
        assertThat(result.last().endMs).isAtMost(34_000L)
        for (i in 1 until result.size) {
            assertThat(result[i].startMs).isAtLeast(result[i - 1].endMs)
        }
    }

    @Test
    fun `a segment that is long in time but short in text is still split`() {
        // 20 seconds on screen is unusable even for a handful of words.
        val input = listOf(TimelineMerger.Placed(0, 20_000, "Short text but a very long duration."))
        val result = CueSegmenter.segment(input, config)
        result.forEach { assertThat(it.endMs - it.startMs).isAtMost(config.maxDurationMs) }
    }

    @Test
    fun `wrapping produces at most two balanced lines`() {
        val text = "This line is definitely longer than forty-two characters in total length."
        val wrapped = CueSegmenter.wrap(text, config)
        val lines = wrapped.split("\n")
        assertThat(lines.size).isAtMost(config.maxLines)
        lines.forEach { assertThat(it.length).isAtMost(config.maxCharsPerLine) }
    }

    @Test
    fun `wrapping leaves a short line untouched`() {
        assertThat(CueSegmenter.wrap("Short line.", config)).isEqualTo("Short line.")
    }

    @Test
    fun `very short adjacent cues are merged`() {
        val input = listOf(
            TimelineMerger.Placed(0, 500, "Yeah."),
            TimelineMerger.Placed(600, 2_600, "That's what I meant."),
        )
        val result = CueSegmenter.segment(input, config)
        assertThat(result).hasSize(1)
        assertThat(result[0].text.replace("\n", " ")).isEqualTo("Yeah. That's what I meant.")
    }

    @Test
    fun `cues separated by a long gap are not merged`() {
        val input = listOf(
            TimelineMerger.Placed(0, 500, "Yeah."),
            TimelineMerger.Placed(9_000, 11_000, "Much later."),
        )
        val result = CueSegmenter.segment(input, config)
        assertThat(result).hasSize(2)
    }

    @Test
    fun `empty input yields no cues`() {
        assertThat(CueSegmenter.segment(emptyList(), config)).isEmpty()
    }
}
