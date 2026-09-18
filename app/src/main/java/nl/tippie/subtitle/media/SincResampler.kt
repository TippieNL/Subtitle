package nl.tippie.subtitle.media

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Streaming windowed-sinc resampler, arbitrary input rate -> arbitrary output rate.
 *
 * Android exposes no public resampling API, and media3's audio processors that could do
 * this are @UnstableApi and have moved between minor releases. ~150 lines of well-tested
 * DSP is the cheaper dependency.
 *
 * Quality: Blackman-windowed sinc with [QUALITY] zero crossings either side, scaled by
 * the downsampling ratio so the anti-alias filter keeps its shape (48 kHz -> 16 kHz uses
 * 48 taps, not 16). Phase is quantised to [PHASES] steps; each phase row is normalised to
 * unit DC gain so there is no level drift.
 *
 * Memory is O(taps + block), independent of stream length.
 */
class SincResampler(
    private val inRate: Int,
    private val outRate: Int,
) {
    init {
        require(inRate > 0 && outRate > 0) { "rates must be positive" }
    }

    private val cutoff: Double = minOf(1.0, outRate.toDouble() / inRate)
    private val halfTaps: Int = ceil(QUALITY / cutoff).toInt().coerceAtLeast(2)
    private val taps: Int = halfTaps * 2
    private val table: Array<FloatArray> = buildTable()

    /** Input samples kept across calls: the filter's left/right support plus unconsumed tail. */
    private var buf = FloatArray(taps * 4)
    private var bufLen = 0
    /** Fractional read cursor, in input samples, relative to buf[0]. */
    private var cursor = 0.0
    private val step = inRate.toDouble() / outRate
    private var primed = false

    val outputRate: Int get() = outRate

    private fun buildTable(): Array<FloatArray> = Array(PHASES + 1) { p ->
        val frac = p.toDouble() / PHASES
        val row = FloatArray(taps)
        var sum = 0.0
        for (k in 0 until taps) {
            // Input offset of this tap relative to floor(cursor): -halfTaps+1 .. halfTaps
            val d = (k - halfTaps + 1) - frac
            val w = d / halfTaps
            val value = if (w <= -1.0 || w >= 1.0) 0.0 else sinc(cutoff * d) * blackman(w)
            row[k] = value.toFloat()
            sum += value
        }
        if (sum != 0.0) for (k in 0 until taps) row[k] = (row[k] / sum).toFloat()
        row
    }

    private fun sinc(x: Double): Double {
        if (x == 0.0) return 1.0
        val px = PI * x
        return sin(px) / px
    }

    private fun blackman(t: Double): Double {
        // t in [-1, 1]
        return 0.42 + 0.5 * cos(PI * t) + 0.08 * cos(2 * PI * t)
    }

    private fun ensureCapacity(extra: Int) {
        if (bufLen + extra <= buf.size) return
        var newSize = buf.size
        while (newSize < bufLen + extra) newSize *= 2
        buf = buf.copyOf(newSize)
    }

    /**
     * Feed mono 16-bit samples, receive resampled mono 16-bit samples.
     * Call [flush] once at end of stream to drain the filter tail.
     */
    fun process(input: ShortArray, count: Int): ShortArray {
        if (inRate == outRate) return if (count == input.size) input else input.copyOf(count)

        if (!primed) {
            // Zero-pad the left support so the first output sample is at input time 0.
            ensureCapacity(halfTaps)
            java.util.Arrays.fill(buf, 0, halfTaps, 0f)
            bufLen = halfTaps
            cursor = (halfTaps - 1).toDouble()
            primed = true
        }

        ensureCapacity(count)
        for (i in 0 until count) buf[bufLen + i] = input[i] / 32768f
        bufLen += count

        return produce(drain = false)
    }

    /** Drains the remaining samples, zero-padding the right support. */
    fun flush(): ShortArray {
        if (inRate == outRate || !primed) return ShortArray(0)
        ensureCapacity(halfTaps)
        java.util.Arrays.fill(buf, bufLen, bufLen + halfTaps, 0f)
        bufLen += halfTaps
        return produce(drain = true)
    }

    private fun produce(drain: Boolean): ShortArray {
        // We can emit while the full right support is available.
        val limit = bufLen - halfTaps
        val available = if (limit <= cursor) 0 else ((limit - cursor) / step).toInt()
        if (available <= 0) {
            if (!drain) compact()
            return ShortArray(0)
        }
        val out = ShortArray(available)
        var o = 0
        while (o < available) {
            val i0 = cursor.toInt()
            val frac = cursor - i0
            val phase = (frac * PHASES).toInt().coerceIn(0, PHASES)
            val row = table[phase]
            var acc = 0f
            val base = i0 - halfTaps + 1
            for (k in 0 until taps) {
                val idx = base + k
                if (idx in 0 until bufLen) acc += buf[idx] * row[k]
            }
            var v = (acc * 32768f).toInt()
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE.toInt()
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE.toInt()
            out[o++] = v.toShort()
            cursor += step
        }
        compact()
        return out
    }

    /** Drop input we can no longer reach, keeping the left support intact. */
    private fun compact() {
        val keepFrom = (cursor.toInt() - halfTaps + 1).coerceAtLeast(0)
        if (keepFrom <= 0) return
        System.arraycopy(buf, keepFrom, buf, 0, bufLen - keepFrom)
        bufLen -= keepFrom
        cursor -= keepFrom
    }

    companion object {
        /** Zero crossings of the sinc either side of the centre, before ratio scaling. */
        private const val QUALITY = 8
        private const val PHASES = 512
    }
}
