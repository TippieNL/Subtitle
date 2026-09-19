package nl.tippie.subtitle

import com.google.common.truth.Truth.assertThat
import nl.tippie.subtitle.domain.model.BoundaryKind
import nl.tippie.subtitle.media.ChunkRecord
import nl.tippie.subtitle.media.PcmFormat
import nl.tippie.subtitle.media.SilenceSeekingChunker
import nl.tippie.subtitle.media.sink.ChunkSink
import nl.tippie.subtitle.subtitle.CueSegmenter
import nl.tippie.subtitle.subtitle.TimelineMerger
import nl.tippie.subtitle.transcription.RawSegment
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Feature-length soak tests.
 *
 * The audio is *generated as it is fed*, never materialised: three hours of 16 kHz mono
 * PCM is 345 MB, so a test that built it as one array would prove nothing about the
 * streaming design and would not fit in a normal test heap anyway. Feeding it block by
 * block is what the real decoder does, so passing here means the chunker's memory is
 * genuinely bounded by its search window rather than by the length of the video.
 *
 * What these tests are actually looking for is drift: any per-chunk rounding that
 * accumulates would be invisible on a 30-second clip and put the last subtitle of a
 * three-hour film seconds out of sync.
 */
class LongVideoSoakTest {

    private class CountingSink(index: Int) : ChunkSink {
        override val file = File("chunk_$index")
        var samples = 0L
            private set
        override fun write(samples: ShortArray, offset: Int, count: Int) { this.samples += count }
        override fun close(): Long = samples * 2
        override fun abort() = Unit
    }

    /**
     * Streams [minutes] of speech-like audio with a pause every [pauseEveryS] seconds, in
     * [blockSamples]-sample blocks, never holding more than one block plus one period.
     *
     * One pause period (~1.2 MB) is built once and then copied cyclically. Calling sin()
     * per sample would mean ~1.5 billion trig operations across these tests, which makes a
     * suite people should run on every change take minutes. The chunker only measures
     * frame energy, so a repeating waveform exercises it identically.
     */
    private fun feed(
        chunker: SilenceSeekingChunker,
        minutes: Int,
        pauseEveryS: Int = 37,
        pauseMs: Int = 700,
        blockSamples: Int = 4096,
    ): Long {
        val totalSamples = PcmFormat.SAMPLE_RATE.toLong() * 60 * minutes
        val periodSamples = PcmFormat.SAMPLE_RATE * pauseEveryS
        val pauseLength = PcmFormat.SAMPLE_RATE * pauseMs / 1000
        val random = Random(1234)

        val period = ShortArray(periodSamples) { i ->
            if (i >= periodSamples - pauseLength) {
                random.nextInt(-25, 25).toShort()
            } else {
                val t = i.toDouble() / PcmFormat.SAMPLE_RATE
                ((sin(2 * PI * 170 * t) + 0.5 * sin(2 * PI * 820 * t)) * 0.35 * 16000)
                    .toInt().toShort()
            }
        }

        val block = ShortArray(blockSamples)
        var produced = 0L
        while (produced < totalSamples) {
            val n = minOf(blockSamples.toLong(), totalSamples - produced).toInt()
            var written = 0
            while (written < n) {
                val offsetInPeriod = ((produced + written) % periodSamples).toInt()
                val take = minOf(n - written, periodSamples - offsetInPeriod)
                System.arraycopy(period, offsetInPeriod, block, written, take)
                written += take
            }
            chunker.onPcm(block, n)
            produced += n
        }
        chunker.onEnd()
        return totalSamples
    }

    private fun run(minutes: Int, targetChunkMs: Long = 300_000): Triple<List<ChunkRecord>, List<CountingSink>, Long> {
        val records = mutableListOf<ChunkRecord>()
        val sinks = mutableListOf<CountingSink>()
        val chunker = SilenceSeekingChunker(
            targetChunkMs = targetChunkMs,
            openSink = { index -> CountingSink(index).also { sinks += it } },
            onChunk = { records += it },
        )
        val total = feed(chunker, minutes)
        return Triple(records, sinks, total)
    }

    @Test
    fun `a three hour film chunks without drift`() {
        val (records, _, totalSamples) = run(minutes = 180)
        val totalMs = totalSamples * 1000 / PcmFormat.SAMPLE_RATE

        // 3 h at 5 min per chunk, give or take where the pauses fall.
        assertThat(records.size).isIn(com.google.common.collect.Range.closed(33, 42))

        assertThat(records.first().startMs).isEqualTo(0L)

        // The critical assertion: the last chunk still ends at the true end of the audio.
        // Any per-chunk rounding that accumulated would show up here as seconds of error.
        assertThat(records.last().endMs).isIn(
            com.google.common.collect.Range.closed(totalMs - 20, totalMs + 20)
        )

        // Chunk indices are dense and ordered — resume relies on index N meaning the same
        // audio on every run.
        assertThat(records.map { it.index }).isEqualTo(records.indices.toList())
    }

    @Test
    fun `chunk boundaries stay contiguous across a three hour timeline`() {
        val (records, _, _) = run(minutes = 180)
        for (i in 1 until records.size) {
            val expectedStart = records[i - 1].endMs - records[i].overlapMs
            assertThat(records[i].startMs).isIn(
                com.google.common.collect.Range.closed(expectedStart - 2, expectedStart + 2)
            )
            assertThat(records[i].endMs).isGreaterThan(records[i].startMs)
        }
    }

    @Test
    fun `no audio is lost or duplicated over three hours`() {
        val (records, sinks, totalSamples) = run(minutes = 180)
        val written = sinks.sumOf { it.samples }
        val replayed = records.sumOf { PcmFormat.msToSamples(it.overlapMs) }
        assertThat(written - replayed).isEqualTo(totalSamples)
    }

    @Test
    fun `regular pauses mean almost every boundary avoids a hard cut`() {
        val (records, _, _) = run(minutes = 180)
        val hard = records.count { it.boundary == BoundaryKind.HARD }
        // With a pause every 37 s there is always one inside the 20 s search window.
        assertThat(hard).isEqualTo(0)
    }

    @Test
    fun `a twelve hour recording still chunks correctly`() {
        // Well past any realistic film: checks nothing overflows or degrades with scale.
        val (records, sinks, totalSamples) = run(minutes = 720)
        val totalMs = totalSamples * 1000 / PcmFormat.SAMPLE_RATE

        assertThat(records.size).isAtLeast(130)
        assertThat(records.last().endMs).isIn(
            com.google.common.collect.Range.closed(totalMs - 40, totalMs + 40)
        )
        val written = sinks.sumOf { it.samples }
        val replayed = records.sumOf { PcmFormat.msToSamples(it.overlapMs) }
        assertThat(written - replayed).isEqualTo(totalSamples)
        // 12 h of timestamps must still fit comfortably and stay ordered.
        assertThat(records.last().endMs).isGreaterThan(43_000_000L)
    }

    @Test
    fun `a short chunk setting produces many chunks without losing audio`() {
        // Users can set 1-minute chunks; 3 h then means ~180 chunks and 180 uploads.
        val (records, sinks, totalSamples) = run(minutes = 180, targetChunkMs = 60_000)
        assertThat(records.size).isAtLeast(150)
        val written = sinks.sumOf { it.samples }
        val replayed = records.sumOf { PcmFormat.msToSamples(it.overlapMs) }
        assertThat(written - replayed).isEqualTo(totalSamples)
    }

    @Test
    fun `a feature length transcript segments into readable cues`() {
        // ~36 chunks of transcript, merged and segmented end to end, checking the output
        // is a valid monotonic subtitle track at scale rather than just per chunk.
        val cuesPerChunk = 40
        val chunkMs = 300_000L
        val chunks = 36

        val placed = mutableListOf<TimelineMerger.Placed>()
        for (chunk in 0 until chunks) {
            val segments = (0 until cuesPerChunk).map { i ->
                val start = i * (chunkMs / cuesPerChunk)
                RawSegment(
                    startMs = start,
                    endMs = start + 4_000,
                    text = "Sentence number $i of chunk $chunk, long enough to need wrapping across lines.",
                )
            }
            placed += TimelineMerger.absolutize(
                segments,
                TimelineMerger.ChunkContext(
                    chunkStartMs = chunk * chunkMs,
                    chunkEndMs = (chunk + 1) * chunkMs,
                    overlapMs = 0,
                    isLast = chunk == chunks - 1,
                )
            )
        }

        val cues = CueSegmenter.segment(TimelineMerger.enforceMonotonic(placed))

        assertThat(cues.size).isAtLeast(chunks * cuesPerChunk)
        val config = CueSegmenter.Config()
        for (i in cues.indices) {
            val cue = cues[i]
            assertThat(cue.endMs).isGreaterThan(cue.startMs)
            assertThat(cue.endMs - cue.startMs).isAtMost(config.maxDurationMs)
            cue.text.split("\n").forEach {
                assertThat(it.length).isAtMost(config.maxCharsPerLine)
            }
            if (i > 0) assertThat(cue.startMs).isAtLeast(cues[i - 1].endMs)
        }
        // Spans the full three hours.
        assertThat(cues.last().endMs).isGreaterThan(10_000_000L)
    }
}
