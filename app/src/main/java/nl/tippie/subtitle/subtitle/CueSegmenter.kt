package nl.tippie.subtitle.subtitle

/**
 * Turns raw transcript segments into cues a human can actually read.
 *
 * Whisper happily returns a single 30-second segment containing four sentences. Shown as
 * one subtitle that is unreadable. The rules here are the standard broadcast ones:
 * at most two lines, ~42 characters per line, 1-7 seconds on screen, and a characters-per-
 * second ceiling.
 *
 * Splitting never changes the words — it only chooses where to break and how to divide the
 * segment's duration, proportionally to the text length of each part.
 */
object CueSegmenter {

    data class Config(
        val maxCharsPerLine: Int = 42,
        val maxLines: Int = 2,
        val minDurationMs: Long = 1_000,
        val maxDurationMs: Long = 7_000,
        val maxCharsPerSecond: Double = 20.0,
        /** Adjacent short cues closer than this may be merged. */
        val mergeGapMs: Long = 250,
    ) {
        val maxChars: Int get() = maxCharsPerLine * maxLines
    }

    fun segment(
        input: List<TimelineMerger.Placed>,
        config: Config = Config(),
    ): List<TimelineMerger.Placed> {
        val split = input.flatMap { splitOne(it, config, depth = 0) }
        val merged = mergeShort(split, config)
        return merged.map { it.copy(text = wrap(it.text, config)) }
    }

    private fun splitOne(
        cue: TimelineMerger.Placed,
        config: Config,
        depth: Int,
    ): List<TimelineMerger.Placed> {
        val text = cue.text.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return emptyList()

        val duration = (cue.endMs - cue.startMs).coerceAtLeast(1)
        val needsSplit = text.length > config.maxChars ||
            duration > config.maxDurationMs ||
            (text.length * 1000.0 / duration) > config.maxCharsPerSecond

        if (!needsSplit || depth >= MAX_SPLIT_DEPTH) return listOf(cue.copy(text = text))

        // How many parts do we need to satisfy both the length and the duration ceiling?
        val byChars = ceilDiv(text.length, config.maxChars)
        val byDuration = ceilDiv(duration, config.maxDurationMs).toInt()
        val parts = maxOf(byChars, byDuration, 2)

        val pieces = splitText(text, parts, config)
        if (pieces.size <= 1) return listOf(cue.copy(text = text))

        val totalChars = pieces.sumOf { it.length }.coerceAtLeast(1)
        val out = ArrayList<TimelineMerger.Placed>(pieces.size)
        var cursor = cue.startMs
        for ((i, piece) in pieces.withIndex()) {
            val share = if (i == pieces.lastIndex) {
                cue.endMs - cursor
            } else {
                (duration * piece.length / totalChars).coerceAtLeast(MIN_PIECE_MS)
            }
            val end = (cursor + share).coerceAtMost(cue.endMs)
            out += TimelineMerger.Placed(cursor, end, piece)
            cursor = end
        }
        // Proportional time allocation can still leave one part over the ceiling when the
        // text is unevenly distributed, so re-split what is still too long.
        return out.filter { it.endMs > it.startMs && it.text.isNotBlank() }
            .flatMap { splitOne(it, config, depth + 1) }
    }

    /**
     * Splits into roughly [parts] pieces, preferring sentence ends, then clause
     * boundaries, then plain word boundaries. Never splits inside a word.
     */
    private fun splitText(text: String, parts: Int, config: Config): List<String> {
        if (parts <= 1) return listOf(text)
        val targetLen = (text.length.toDouble() / parts).toInt().coerceAtLeast(1)

        val pieces = mutableListOf<String>()
        var remaining = text
        while (remaining.length > config.maxChars || pieces.size < parts - 1) {
            if (remaining.length <= targetLen || remaining.isBlank()) break
            // The target drives the cut, not a minimum piece size: when the duration
            // ceiling forces a split, short pieces are the correct answer.
            val limit = targetLen.coerceIn(MIN_PIECE_CHARS, config.maxChars)
            val cut = findBreak(remaining, limit, config.maxChars)
            if (cut <= 0 || cut >= remaining.length) break
            pieces += remaining.substring(0, cut).trim()
            remaining = remaining.substring(cut).trim()
        }
        if (remaining.isNotBlank()) pieces += remaining
        return pieces.filter { it.isNotBlank() }
    }

    /**
     * Picks a break point as close to [target] as possible, preferring sentence ends, then
     * clause boundaries, then plain word gaps. Searching only a window around the target
     * matters: scanning all the way to the hard maximum biases every cut long, which then
     * leaves the first piece over the duration ceiling.
     */
    private fun findBreak(text: String, target: Int, hardMax: Int): Int {
        val upper = minOf(text.length, maxOf(target + target / 3, target + 8), hardMax)
        val lower = (target * 0.55).toInt().coerceAtLeast(1)
        if (upper <= lower) return minOf(text.length, hardMax)

        fun nearest(predicate: (Int) -> Boolean): Int {
            var best = -1
            var bestDistance = Int.MAX_VALUE
            for (i in lower until upper) {
                if (!predicate(i)) continue
                val distance = kotlin.math.abs(i - target)
                if (distance < bestDistance) { bestDistance = distance; best = i }
            }
            return best
        }

        nearest { i -> text[i] in ".!?\u2026" && i + 1 < text.length && text[i + 1] == ' ' }
            .let { if (it > 0) return it + 1 }
        nearest { i -> text[i] in ",;:\u2014" && i + 1 < text.length && text[i + 1] == ' ' }
            .let { if (it > 0) return it + 1 }
        nearest { i -> text[i] == ' ' }.let { if (it > 0) return it }

        return upper
    }

    private fun mergeShort(
        cues: List<TimelineMerger.Placed>,
        config: Config,
    ): List<TimelineMerger.Placed> {
        if (cues.size < 2) return cues
        val out = ArrayList<TimelineMerger.Placed>(cues.size)
        for (cue in cues) {
            val last = out.lastOrNull()
            if (last == null) { out += cue; continue }
            val gap = cue.startMs - last.endMs
            val lastDuration = last.endMs - last.startMs
            val combinedText = "${last.text} ${cue.text}"
            val combinedDuration = cue.endMs - last.startMs
            val canMerge = lastDuration < config.minDurationMs &&
                gap in 0..config.mergeGapMs &&
                combinedText.length <= config.maxChars &&
                combinedDuration <= config.maxDurationMs
            if (canMerge) {
                out[out.lastIndex] = TimelineMerger.Placed(last.startMs, cue.endMs, combinedText)
            } else {
                out += cue
            }
        }
        return out
    }

    /** Balanced two-line wrap: avoids a 40-character line above a 3-character orphan. */
    fun wrap(text: String, config: Config): String {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.length <= config.maxCharsPerLine) return clean
        val words = clean.split(' ')
        if (words.size < 2) return clean

        var bestSplit = -1
        var bestScore = Int.MAX_VALUE
        var running = 0
        for (i in 0 until words.lastIndex) {
            running += words[i].length + if (i > 0) 1 else 0
            val left = running
            val right = clean.length - running - 1
            if (left > config.maxCharsPerLine || right > config.maxCharsPerLine * (config.maxLines - 1)) continue
            val score = kotlin.math.abs(left - right)
            if (score < bestScore) { bestScore = score; bestSplit = i }
        }
        if (bestSplit < 0) return clean
        val first = words.take(bestSplit + 1).joinToString(" ")
        val rest = words.drop(bestSplit + 1).joinToString(" ")
        return "$first\n$rest"
    }

    private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b
    private fun ceilDiv(a: Long, b: Long) = ((a + b - 1) / b).toInt()

    private const val MAX_SPLIT_DEPTH = 4
    private const val MIN_PIECE_MS = 300L
    private const val MIN_PIECE_CHARS = 4
}
