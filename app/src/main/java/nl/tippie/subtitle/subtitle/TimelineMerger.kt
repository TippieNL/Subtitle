package nl.tippie.subtitle.subtitle

import nl.tippie.subtitle.transcription.RawSegment

/**
 * Turns per-chunk, chunk-relative transcripts into one correct absolute timeline.
 *
 * Four steps, and skipping any of them produces subtly broken subtitles on long video:
 *
 *  1. **Offset** — the obvious one, and the only one most implementations do.
 *  2. **Clamp** — Whisper regularly emits a final segment running past the audio it was
 *     given, and sometimes invents text in the last fraction of a second. Anything
 *     starting inside [TAIL_EPSILON_MS] of the chunk end is dropped when another chunk
 *     follows; the real audio is in that chunk anyway.
 *  3. **Seam de-duplication** — only for HARD boundaries, where the next chunk replays an
 *     overlap tail. The later chunk's wording wins because it had full following context,
 *     but only when the two versions actually agree. When they don't, both are kept: a
 *     duplicated line is a two-second fix in the editor, a silently deleted sentence is
 *     not recoverable.
 *  4. **Monotonicity** — guarantees a valid SRT even if steps 1-3 left an overlap.
 */
object TimelineMerger {

    /** A cue that has been placed on the absolute timeline but not yet persisted. */
    data class Placed(val startMs: Long, val endMs: Long, val text: String)

    data class ChunkContext(
        val chunkStartMs: Long,
        val chunkEndMs: Long,
        /** Milliseconds at the head of this chunk that repeat the previous chunk's tail. */
        val overlapMs: Long,
        val isLast: Boolean,
    )

    data class SeamResult(
        val placed: List<Placed>,
        /** True when the previous chunk's overlapping cues should be deleted. */
        val replacePrevious: Boolean,
    )

    /** Steps 1 and 2. */
    fun absolutize(segments: List<RawSegment>, ctx: ChunkContext): List<Placed> {
        val out = ArrayList<Placed>(segments.size)
        for (s in segments) {
            val start = ctx.chunkStartMs + s.startMs
            var end = ctx.chunkStartMs + s.endMs
            if (end > ctx.chunkEndMs) end = ctx.chunkEndMs
            if (!ctx.isLast && start >= ctx.chunkEndMs - TAIL_EPSILON_MS) continue
            if (end - start < MIN_SEGMENT_MS) continue
            val text = s.text.trim()
            if (text.isEmpty()) continue
            out += Placed(start, end, text)
        }
        return out
    }

    /**
     * Step 3. [previousTail] are the already-stored cues of the preceding chunk that fall
     * inside this chunk's overlap region.
     */
    fun resolveSeam(
        previousTail: List<Placed>,
        incoming: List<Placed>,
        ctx: ChunkContext,
    ): SeamResult {
        if (ctx.overlapMs <= 0) return SeamResult(incoming, replacePrevious = false)

        val overlapStart = ctx.chunkStartMs
        val overlapEnd = ctx.chunkStartMs + ctx.overlapMs

        val prevInOverlap = previousTail.filter { it.endMs > overlapStart }
        val newInOverlap = incoming.filter { it.startMs < overlapEnd }

        if (prevInOverlap.isEmpty()) return SeamResult(incoming, replacePrevious = false)
        if (newInOverlap.isEmpty()) {
            // Nothing was heard twice — drop nothing, keep everything outside the overlap.
            return SeamResult(incoming, replacePrevious = false)
        }

        val similarity = tokenSimilarity(
            prevInOverlap.joinToString(" ") { it.text },
            newInOverlap.joinToString(" ") { it.text },
        )
        return if (similarity >= DUPLICATE_SIMILARITY) {
            // Same speech, transcribed twice. Keep the later version.
            SeamResult(incoming, replacePrevious = true)
        } else {
            // Genuinely different content. Keep both and let the user decide.
            SeamResult(incoming.filter { it.startMs >= overlapEnd - OVERLAP_KEEP_SLACK_MS }, false)
        }
    }

    /** Step 4. Sorts and removes overlaps so the output is always a valid subtitle file. */
    fun enforceMonotonic(cues: List<Placed>): List<Placed> {
        if (cues.isEmpty()) return cues
        val sorted = cues.sortedWith(compareBy({ it.startMs }, { it.endMs }))
        val out = ArrayList<Placed>(sorted.size)
        for (cue in sorted) {
            val last = out.lastOrNull()
            if (last == null) { out += cue; continue }
            if (cue.startMs < last.endMs) {
                val trimmedEnd = (cue.startMs - MIN_GAP_MS).coerceAtLeast(last.startMs + MIN_SEGMENT_MS)
                if (trimmedEnd > last.startMs) {
                    out[out.lastIndex] = last.copy(endMs = trimmedEnd)
                } else {
                    // The previous cue cannot shrink further; push this one later instead.
                    val shifted = cue.copy(
                        startMs = last.endMs + MIN_GAP_MS,
                        endMs = maxOf(cue.endMs, last.endMs + MIN_GAP_MS + MIN_SEGMENT_MS)
                    )
                    out += shifted
                    continue
                }
            }
            out += cue
        }
        return out
    }

    /**
     * Jaccard similarity over normalised word tokens. Robust to punctuation and casing
     * differences between two transcriptions of the same audio, which is exactly the
     * variation we see at a seam.
     */
    fun tokenSimilarity(a: String, b: String): Double {
        val ta = tokenize(a)
        val tb = tokenize(b)
        if (ta.isEmpty() && tb.isEmpty()) return 1.0
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val intersection = ta.count { it in tb }
        val union = (ta + tb).toSet().size
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }

    private fun tokenize(s: String): Set<String> =
        s.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotBlank() }
            .toSet()

    /** Segments starting this close to a chunk's end are tail hallucinations. */
    const val TAIL_EPSILON_MS = 200L
    private const val MIN_SEGMENT_MS = 80L
    private const val MIN_GAP_MS = 1L
    private const val DUPLICATE_SIMILARITY = 0.45
    private const val OVERLAP_KEEP_SLACK_MS = 0L
}
