package nl.tippie.subtitle

import com.google.common.truth.Truth.assertThat
import nl.tippie.subtitle.media.SincResampler
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class SincResamplerTest {

    private fun tone(frequencyHz: Double, rate: Int, samples: Int, amplitude: Double = 0.5) =
        ShortArray(samples) { i ->
            (sin(2 * PI * frequencyHz * i / rate) * amplitude * 32767).toInt().toShort()
        }

    private fun rms(data: ShortArray, skip: Int): Double {
        if (data.size <= skip * 2) return 0.0
        var acc = 0.0
        for (i in skip until data.size - skip) {
            val v = data[i] / 32768.0
            acc += v * v
        }
        return kotlin.math.sqrt(acc / (data.size - skip * 2))
    }

    @Test
    fun `identity rate returns input unchanged`() {
        val resampler = SincResampler(16_000, 16_000)
        val input = tone(440.0, 16_000, 1000)
        val out = resampler.process(input, input.size)
        assertThat(out.toList()).isEqualTo(input.toList())
    }

    @Test
    fun `48k to 16k produces one third the samples`() {
        val resampler = SincResampler(48_000, 16_000)
        val input = tone(440.0, 48_000, 48_000)   // one second
        val out = resampler.process(input, input.size) + resampler.flush()
        // Allow a small edge tolerance for the filter's tail.
        assertThat(out.size).isAtLeast(15_900)
        assertThat(out.size).isAtMost(16_100)
    }

    @Test
    fun `44100 to 16k produces the expected sample count`() {
        val resampler = SincResampler(44_100, 16_000)
        val input = tone(300.0, 44_100, 44_100 * 2)   // two seconds
        val out = resampler.process(input, input.size) + resampler.flush()
        assertThat(out.size).isAtLeast(31_800)
        assertThat(out.size).isAtMost(32_200)
    }

    @Test
    fun `passband amplitude is preserved`() {
        // 440 Hz is far below the 8 kHz output Nyquist, so level must survive.
        val resampler = SincResampler(48_000, 16_000)
        val input = tone(440.0, 48_000, 48_000)
        val out = resampler.process(input, input.size) + resampler.flush()

        val inputRms = rms(input, 0)
        val outputRms = rms(out, 200)
        assertThat(abs(outputRms - inputRms)).isLessThan(0.02)
    }

    @Test
    fun `content above the output nyquist is attenuated instead of aliasing`() {
        // 15 kHz cannot exist at 16 kHz output; without a proper anti-alias filter it
        // would fold down to 1 kHz at nearly full amplitude.
        val resampler = SincResampler(48_000, 16_000)
        val input = tone(15_000.0, 48_000, 48_000)
        val out = resampler.process(input, input.size) + resampler.flush()

        val inputRms = rms(input, 0)
        val outputRms = rms(out, 400)
        assertThat(outputRms).isLessThan(inputRms * 0.05)
    }

    @Test
    fun `streaming in small blocks matches one big block`() {
        val input = tone(700.0, 48_000, 48_000)

        val whole = SincResampler(48_000, 16_000).let { it.process(input, input.size) + it.flush() }

        val streamed = SincResampler(48_000, 16_000).let { r ->
            val parts = mutableListOf<Short>()
            var offset = 0
            while (offset < input.size) {
                val n = minOf(1024, input.size - offset)
                val block = input.copyOfRange(offset, offset + n)
                parts += r.process(block, n).toList()
                offset += n
            }
            parts += r.flush().toList()
            parts.toShortArray()
        }

        assertThat(streamed.size).isEqualTo(whole.size)
        // Identical arithmetic, so the results must match exactly.
        assertThat(streamed.toList()).isEqualTo(whole.toList())
    }
}
