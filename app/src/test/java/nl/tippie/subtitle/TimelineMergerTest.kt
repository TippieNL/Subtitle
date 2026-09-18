package nl.tippie.subtitle

import com.google.common.truth.Truth.assertThat
import nl.tippie.subtitle.subtitle.TimelineMerger
import nl.tippie.subtitle.transcription.RawSegment
import org.junit.Test

class TimelineMergerTest {

    private fun ctx(
        start: Long = 300_000,
        end: Long = 600_000,
        overlap: Long = 0,
        isLast: Boolean = false,
    ) = TimelineMerger.ChunkContext(start, end, overlap, isLast)

    @Test
    fun `segments are shifted by the chunk start`() {
        val result = TimelineMerger.absolutize(
            listOf(RawSegment(0, 2_000, "Hello"), RawSegment(2_000, 5_000, "world")),
            ctx()
        )
        assertThat(result.map { it.startMs }).containsExactly(300_000L, 302_000L).inOrder()
        assertThat(result.map { it.endMs }).containsExactly(302_000L, 305_000L).inOrder()
    }

    @Test
    fun `a segment running past the chunk end is clamped`() {
        val result = TimelineMerger.absolutize(
            listOf(RawSegment(290_000, 320_000, "overrun")),
            ctx()
        )
        assertThat(result).hasSize(1)
        assertThat(result[0].endMs).isEqualTo(600_000L)
    }

    @Test
    fun `tail hallucination at the chunk end is dropped when another chunk follows`() {
        val result = TimelineMerger.absolutize(
            listOf(
                RawSegment(100_000, 102_000, "real speech"),
                RawSegment(299_950, 300_000, "Thanks for watching!"),
            ),
            ctx()
        )
        assertThat(result.map { it.text }).containsExactly("real speech")
    }

    @Test
    fun `tail segment is kept on the final chunk`() {
        val result = TimelineMerger.absolutize(
            listOf(RawSegment(299_000, 300_000, "the end")),
            ctx(isLast = true)
        )
        assertThat(result.map { it.text }).containsExactly("the end")
    }

    @Test
    fun `matching overlap text replaces the previous chunk's tail`() {
        val context = ctx(start = 300_000, end = 600_000, overlap = 3_000)
        val previous = listOf(
            TimelineMerger.Placed(299_000, 301_500, "and then she said the"),
        )
        val incoming = listOf(
            TimelineMerger.Placed(300_100, 302_000, "and then she said the thing"),
            TimelineMerger.Placed(302_100, 305_000, "next sentence"),
        )
        val result = TimelineMerger.resolveSeam(previous, incoming, context)
        assertThat(result.replacePrevious).isTrue()
        assertThat(result.placed).isEqualTo(incoming)
    }

    @Test
    fun `divergent overlap text keeps the previous chunk instead of deleting speech`() {
        val context = ctx(start = 300_000, end = 600_000, overlap = 3_000)
        val previous = listOf(TimelineMerger.Placed(299_000, 301_500, "completely different words here"))
        val incoming = listOf(
            TimelineMerger.Placed(300_100, 302_000, "nothing alike whatsoever friend"),
            TimelineMerger.Placed(303_100, 305_000, "later line"),
        )
        val result = TimelineMerger.resolveSeam(previous, incoming, context)
        assertThat(result.replacePrevious).isFalse()
        assertThat(result.placed.map { it.text }).contains("later line")
    }

    @Test
    fun `no overlap means nothing is deduplicated`() {
        val incoming = listOf(TimelineMerger.Placed(300_000, 302_000, "a"))
        val result = TimelineMerger.resolveSeam(
            listOf(TimelineMerger.Placed(298_000, 299_000, "b")), incoming, ctx(overlap = 0)
        )
        assertThat(result.replacePrevious).isFalse()
        assertThat(result.placed).isEqualTo(incoming)
    }

    @Test
    fun `monotonic pass removes overlapping cues`() {
        val result = TimelineMerger.enforceMonotonic(
            listOf(
                TimelineMerger.Placed(1_000, 5_000, "one"),
                TimelineMerger.Placed(3_000, 7_000, "two"),
                TimelineMerger.Placed(6_000, 9_000, "three"),
            )
        )
        for (i in 1 until result.size) {
            assertThat(result[i].startMs).isAtLeast(result[i - 1].endMs)
        }
        assertThat(result).hasSize(3)
    }

    @Test
    fun `monotonic pass sorts unordered input`() {
        val result = TimelineMerger.enforceMonotonic(
            listOf(
                TimelineMerger.Placed(9_000, 10_000, "c"),
                TimelineMerger.Placed(1_000, 2_000, "a"),
                TimelineMerger.Placed(5_000, 6_000, "b"),
            )
        )
        assertThat(result.map { it.text }).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun `token similarity ignores punctuation and casing`() {
        val similarity = TimelineMerger.tokenSimilarity(
            "And then, she said the thing.",
            "and then she said the thing"
        )
        assertThat(similarity).isAtLeast(0.9)
    }

    @Test
    fun `token similarity is low for unrelated text`() {
        val similarity = TimelineMerger.tokenSimilarity(
            "the quick brown fox",
            "entirely unrelated content"
        )
        assertThat(similarity).isLessThan(0.2)
    }
}
