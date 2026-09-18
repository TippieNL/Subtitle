package nl.tippie.subtitle.media

/** Canonical audio format the whole transcription pipeline works in. */
object PcmFormat {
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BYTES_PER_SAMPLE = 2

    fun samplesToMs(samples: Long): Long = samples * 1000L / SAMPLE_RATE
    fun msToSamples(ms: Long): Long = ms * SAMPLE_RATE / 1000L
}

object PcmUtil {

    /** Average interleaved channels down to mono, in place into [out]. Returns frame count. */
    fun downmixToMono(input: ShortArray, sampleCount: Int, channels: Int, out: ShortArray): Int {
        if (channels == 1) {
            System.arraycopy(input, 0, out, 0, sampleCount)
            return sampleCount
        }
        val frames = sampleCount / channels
        var si = 0
        for (f in 0 until frames) {
            var acc = 0
            for (c in 0 until channels) acc += input[si++]
            out[f] = (acc / channels).toShort()
        }
        return frames
    }

    /** Root-mean-square of a block, normalised to 0..1. */
    fun rms(samples: ShortArray, from: Int, count: Int): Double {
        if (count <= 0) return 0.0
        var acc = 0.0
        for (i in from until from + count) {
            val v = samples[i] / 32768.0
            acc += v * v
        }
        return kotlin.math.sqrt(acc / count)
    }
}
