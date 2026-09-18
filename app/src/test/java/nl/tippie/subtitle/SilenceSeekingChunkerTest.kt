package nl.tippie.subtitle

import com.google.common.truth.Truth.assertThat
import nl.tippie.subtitle.domain.model.BoundaryKind
import nl.tippie.subtitle.media.ChunkRecord
import nl.tippie.subtitle.media.PcmFormat
import nl.tippie.subtitle.media.SilenceSeekingChunker
import nl.tippie.subtitle.media.sink.ChunkSink
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class SilenceSeekingChunkerTest {

    /** Counts samples instead of touching disk. */
    private class CountingSink(index: Int) : ChunkSink {
        override val file = File("chunk_$index")
        var samples = 0L
            private set
        override fun write(samples: ShortArray, offset: Int, count: Int) { this.samples += count }
        override fun close(): Long = samples * 2
        override fun abort() = Unit
    }

    private fun run(
        audio: ShortArray,
        targetChunkMs: Long = 60_000,
        blockSize: Int = 4096,
    ): Pair<List<ChunkRecord>, List<CountingSink>> {
        val records = mutableListOf<ChunkRecord>()
        val sinks = mutableListOf<CountingSink>()
        val chunker = SilenceSeekingChunker(
            targetChunkMs = targetChunkMs,
            searchWindowMs = 20_000,
            openSink = { index -> CountingSink(index).also { sinks += it } },
            onChunk = { records += it },
        )
        var offset = 0
        while (offset < audio.size) {
            val n = minOf(blockSize, audio.size - offset)
            chunker.onPcm(audio.copyOfRange(offset, offset + n), n)
            offset += n
        }
        chunker.onEnd()
        return records to sinks
    }

    private fun speech(seconds: Int, amplitude: Double = 0.4): ShortArray =
        ShortArray(PcmFormat.SAMPLE_RATE * seconds) { i ->
            val t = i.toDouble() / PcmFormat.SAMPLE_RATE
            ((sin(2 * PI * 180 * t) + 0.4 * sin(2 * PI * 900 * t)) * amplitude * 16000).toInt().toShort()
        }

    private fun silence(millis: Int): ShortArray =
        ShortArray(PcmFormat.SAMPLE_RATE * millis / 1000) {
            Random(it).nextInt(-30, 30).toShort()   // a realistic quiet noise floor
        }

    @Test
    fun `audio shorter than one chunk produces exactly one chunk`() {
        val (records, _) = run(speech(20), targetChunkMs = 60_000)
        assertThat(records).hasSize(1)
        assertThat(records[0].index).isEqualTo(0)
        assertThat(records[0].startMs).isEqualTo(0L)
        assertThat(records[0].overlapMs).isEqualTo(0L)
    }

    @Test
    fun `chunk timings are contiguous and cover the whole stream`() {
        val audio = speech(70) + silence(700) + speech(70)
        val (records, _) = run(audio, targetChunkMs = 60_000)

        assertThat(records.size).isAtLeast(2)
        assertThat(records.first().startMs).isEqualTo(0L)

        for (i in 1 until records.size) {
            val previousEnd = records[i - 1].endMs
            val expectedStart = previousEnd - records[i].overlapMs
            // Allow one block of rounding slack from ms<->sample conversion.
            assertThat(records[i].startMs).isIn(
                com.google.common.collect.Range.closed(expectedStart - 2, expectedStart + 2)
            )
        }

        val totalMs = audio.size.toLong() * 1000 / PcmFormat.SAMPLE_RATE
        assertThat(records.last().endMs).isIn(
            com.google.common.collect.Range.closed(totalMs - 50, totalMs + 50)
        )
    }

    @Test
    fun `every sample is written exactly once when boundaries land in silence`() {
        val audio = speech(70) + silence(700) + speech(40)
        val (records, sinks) = run(audio, targetChunkMs = 60_000)

        val written = sinks.sumOf { it.samples }
        val replayed = records.sumOf { PcmFormat.msToSamples(it.overlapMs) }
        assertThat(written - replayed).isEqualTo(audio.size.toLong())
    }

    @Test
    fun `continuous loud audio forces a hard cut with an overlap tail`() {
        // Uniform energy everywhere: there is no pause to find.
        val (records, _) = run(speech(100), targetChunkMs = 60_000)

        assertThat(records.size).isAtLeast(2)
        assertThat(records[0].boundary).isEqualTo(BoundaryKind.HARD)
        assertThat(records[1].overlapMs).isEqualTo(SilenceSeekingChunker.DEFAULT_HARD_OVERLAP_MS)
    }

    @Test
    fun `a clear pause is preferred over the fixed target position`() {
        // Pause placed 8 s before the 60 s target, inside the search window.
        val audio = speech(52) + silence(1_200) + speech(50)
        val (records, _) = run(audio, targetChunkMs = 60_000)

        assertThat(records[0].boundary).isEqualTo(BoundaryKind.SILENCE)
        assertThat(records[1].overlapMs).isEqualTo(0L)
        // The cut should sit in the pause, not at the 60 s mark.
        assertThat(records[0].endMs).isIn(com.google.common.collect.Range.closed(52_000L, 53_500L))
    }

    @Test
    fun `block size does not change the result`() {
        val audio = speech(70) + silence(600) + speech(30)
        val (a, _) = run(audio, blockSize = 512)
        val (b, _) = run(audio, blockSize = 16_384)
        assertThat(a.map { it.startMs to it.endMs }).isEqualTo(b.map { it.startMs to it.endMs })
    }

    @Test
    fun `chunking is deterministic, which is what makes resume safe`() {
        val audio = speech(80) + silence(500) + speech(80) + silence(500) + speech(40)
        val (first, _) = run(audio)
        val (second, _) = run(audio)
        assertThat(first.map { Triple(it.index, it.startMs, it.endMs) })
            .isEqualTo(second.map { Triple(it.index, it.startMs, it.endMs) })
    }
}
