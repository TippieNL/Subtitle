package nl.tippie.subtitle.media

import nl.tippie.subtitle.domain.model.BoundaryKind
import nl.tippie.subtitle.media.sink.ChunkSink
import java.io.File

/** One finished audio chunk, ready to hand to a transcription provider. */
data class ChunkRecord(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val overlapMs: Long,
    val boundary: BoundaryKind,
    val file: File,
    val sizeBytes: Long,
)

/**
 * Splits the PCM stream into chunks, preferring cuts that land in silence.
 *
 * Fixed-grid cutting slices words in half and forces overlap-and-dedup on every boundary.
 * Instead: when we reach the target length, buffer a search window and cut at the quietest
 * point in it. On typical speech the great majority of boundaries land in a real pause and
 * need no de-duplication at all. When the audio is continuous — music bed, crowd noise,
 * an unbroken monologue — we fall back to a hard cut and give the next chunk an overlap
 * tail, which [nl.tippie.subtitle.subtitle.TimelineMerger] then reconciles.
 *
 * Detection is fully relative: the quietest candidate must be below [SILENCE_RATIO] of the
 * window's mean energy. There is no absolute dB constant, so it adapts to a loud film mix
 * and a quiet lecture recording alike.
 *
 * Memory is bounded by the search window: 20 s of 16 kHz mono PCM is 640 KB, regardless of
 * how long the video is.
 */
class SilenceSeekingChunker(
    private val targetChunkMs: Long,
    private val searchWindowMs: Long = DEFAULT_SEARCH_WINDOW_MS,
    private val minSilenceMs: Long = DEFAULT_MIN_SILENCE_MS,
    private val hardOverlapMs: Long = DEFAULT_HARD_OVERLAP_MS,
    private val openSink: (index: Int) -> ChunkSink,
    private val onChunk: (ChunkRecord) -> Unit,
) : AudioDecoder.Consumer {

    private val targetSamples = PcmFormat.msToSamples(targetChunkMs).toInt().coerceAtLeast(MIN_CHUNK_SAMPLES)
    private val windowSamples = PcmFormat.msToSamples(searchWindowMs).toInt()
        .coerceAtMost(targetSamples)   // window can never exceed the chunk itself
    private val overlapSamples = PcmFormat.msToSamples(hardOverlapMs).toInt()
    private val minSilenceFrames = (minSilenceMs / FRAME_MS).toInt().coerceAtLeast(1)

    /** Offset within the chunk at which we stop writing through and start buffering. */
    private val windowStart = (targetSamples - windowSamples / 2).coerceAtLeast(0)

    private var window = ShortArray(windowSamples + 4096)
    private var windowLen = 0

    private var chunkIndex = 0
    private var chunkStartSample = 0L
    private var chunkOverlapMs = 0L
    private var writtenInChunk = 0        // samples already handed to the sink
    private var sink: ChunkSink = openSink(0)
    private var finished = false

    override fun onPcm(samples: ShortArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val buffered = windowLen
            val posInChunk = writtenInChunk + buffered

            if (posInChunk < windowStart) {
                val room = windowStart - posInChunk
                val n = minOf(count - offset, room)
                sink.write(samples, offset, n)
                writtenInChunk += n
                offset += n
            } else {
                val room = windowSamples - windowLen
                if (room <= 0) { cut(); continue }
                val n = minOf(count - offset, room)
                if (windowLen + n > window.size) window = window.copyOf((windowLen + n) * 2)
                System.arraycopy(samples, offset, window, windowLen, n)
                windowLen += n
                offset += n
                if (windowLen >= windowSamples) cut()
            }
        }
    }

    override fun onEnd() {
        if (finished) return
        finished = true
        if (windowLen > 0) {
            sink.write(window, 0, windowLen)
            writtenInChunk += windowLen
            windowLen = 0
        }
        emitChunk(BoundaryKind.SILENCE)
    }

    /** Aborts the in-flight chunk without emitting it (cancellation path). */
    fun abort() {
        finished = true
        runCatching { sink.abort() }
    }

    private fun cut() {
        val cutOffset = findCutOffset()
        val boundary = if (cutOffset != null) BoundaryKind.SILENCE else BoundaryKind.HARD
        // For a hard cut the boundary sits exactly at the target length.
        val splitAt = cutOffset ?: (targetSamples - windowStart).coerceIn(0, windowLen)

        sink.write(window, 0, splitAt)
        writtenInChunk += splitAt
        emitChunk(boundary)

        // Start the next chunk. A hard cut replays the tail so no word is lost at the seam.
        val replay = if (boundary == BoundaryKind.HARD) minOf(overlapSamples, splitAt) else 0
        chunkIndex++
        chunkStartSample += (writtenInChunk - replay)
        chunkOverlapMs = if (replay > 0) PcmFormat.samplesToMs(replay.toLong()) else 0L
        writtenInChunk = 0
        sink = openSink(chunkIndex)

        val carryFrom = splitAt - replay
        val carryLen = windowLen - carryFrom
        if (carryLen > 0) {
            sink.write(window, carryFrom, carryLen)
            writtenInChunk += carryLen
        }
        windowLen = 0
    }

    /**
     * Finds the midpoint of the quietest [minSilenceMs] run in the buffered window,
     * or null when the window has no convincing pause.
     */
    private fun findCutOffset(): Int? {
        val frameCount = windowLen / FRAME_SAMPLES
        if (frameCount < minSilenceFrames * 2) return null

        val energy = DoubleArray(frameCount)
        var total = 0.0
        for (f in 0 until frameCount) {
            val e = PcmUtil.rms(window, f * FRAME_SAMPLES, FRAME_SAMPLES)
            energy[f] = e
            total += e
        }
        val mean = total / frameCount
        if (mean <= 0.0) return windowLen / 2   // pure digital silence: cut in the middle

        // Sliding mean over minSilenceFrames, excluding the very edges so we don't
        // produce a degenerate empty chunk.
        val margin = minSilenceFrames
        var running = 0.0
        for (f in 0 until minSilenceFrames) running += energy[f]
        var best = Double.MAX_VALUE
        var bestFrame = -1
        for (start in 0..frameCount - minSilenceFrames) {
            if (start > 0) running += energy[start + minSilenceFrames - 1] - energy[start - 1]
            if (start >= margin && start + minSilenceFrames <= frameCount - margin) {
                val avg = running / minSilenceFrames
                if (avg < best) { best = avg; bestFrame = start }
            }
        }
        if (bestFrame < 0 || best > mean * SILENCE_RATIO) return null
        val midFrame = bestFrame + minSilenceFrames / 2
        return (midFrame * FRAME_SAMPLES).coerceIn(0, windowLen)
    }

    private fun emitChunk(boundary: BoundaryKind) {
        val size = sink.close()
        val startMs = PcmFormat.samplesToMs(chunkStartSample)
        val endMs = startMs + PcmFormat.samplesToMs(writtenInChunk.toLong())
        onChunk(
            ChunkRecord(
                index = chunkIndex,
                startMs = startMs,
                endMs = endMs,
                overlapMs = chunkOverlapMs,
                boundary = boundary,
                file = sink.file,
                sizeBytes = size,
            )
        )
    }

    companion object {
        const val DEFAULT_SEARCH_WINDOW_MS = 20_000L
        const val DEFAULT_MIN_SILENCE_MS = 400L
        const val DEFAULT_HARD_OVERLAP_MS = 3_000L

        /** Energy analysis frame: 20 ms. */
        private const val FRAME_MS = 20
        private const val FRAME_SAMPLES = PcmFormat.SAMPLE_RATE * FRAME_MS / 1000

        /** A cut candidate must be this much quieter than the window average. */
        private const val SILENCE_RATIO = 0.35

        private const val MIN_CHUNK_SAMPLES = PcmFormat.SAMPLE_RATE * 10
    }
}
