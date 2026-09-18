package nl.tippie.subtitle.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import nl.tippie.subtitle.domain.model.ProcessingError
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams one audio track out of a container and hands back mono 16 kHz PCM-16.
 *
 * Nothing accumulates: MediaExtractor reads one compressed sample at a time, MediaCodec
 * returns one output buffer at a time, and each block is pushed straight to [consumer].
 * Peak heap is a few codec buffers regardless of whether the input is 40 MB or 40 GB.
 */
class AudioDecoder(private val context: Context) {

    interface Consumer {
        /** Mono 16 kHz PCM-16. [count] valid samples in [samples]. */
        fun onPcm(samples: ShortArray, count: Int)
        fun onEnd()
    }

    /**
     * @param onProgressMs called with the source position as decoding advances.
     * @param isCancelled polled between buffers so cancellation is near-instant.
     */
    fun decode(
        uri: Uri,
        trackIndex: Int,
        consumer: Consumer,
        onProgressMs: (Long) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try {
                extractor.setDataSource(context, uri, null)
            } catch (e: SecurityException) {
                throw ProcessingErrorException(ProcessingError.UriPermissionLost())
            } catch (e: java.io.FileNotFoundException) {
                throw ProcessingErrorException(ProcessingError.UriPermissionLost())
            } catch (e: Exception) {
                throw ProcessingErrorException(ProcessingError.CorruptContainer(e))
            }

            if (trackIndex !in 0 until extractor.trackCount) {
                throw ProcessingErrorException(ProcessingError.NoAudioTrack())
            }
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw ProcessingErrorException(ProcessingError.NoAudioTrack())
            if (!mime.startsWith("audio/")) {
                throw ProcessingErrorException(ProcessingError.NoAudioTrack())
            }
            extractor.selectTrack(trackIndex)

            codec = try {
                MediaCodec.createDecoderByType(mime).apply {
                    configure(inputFormat, null, null, 0)
                    start()
                }
            } catch (e: Exception) {
                throw ProcessingErrorException(ProcessingError.UnsupportedAudioCodec(mime))
            }

            var sourceRate = inputFormat.optInt(MediaFormat.KEY_SAMPLE_RATE) ?: PcmFormat.SAMPLE_RATE
            var sourceChannels = inputFormat.optInt(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var resampler = SincResampler(sourceRate, PcmFormat.SAMPLE_RATE)

            var interleaved = ShortArray(8192)
            var mono = ShortArray(8192)

            val info = MediaCodec.BufferInfo()
            var sawInputEnd = false
            var sawOutputEnd = false

            while (!sawOutputEnd) {
                if (isCancelled()) throw kotlinx.coroutines.CancellationException("cancelled")

                if (!sawInputEnd) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEnd = true
                        } else {
                            codec.queueInputBuffer(
                                inIndex, 0, sampleSize, extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        val newRate = of.optInt(MediaFormat.KEY_SAMPLE_RATE) ?: sourceRate
                        val newChannels = of.optInt(MediaFormat.KEY_CHANNEL_COUNT) ?: sourceChannels
                        pcmEncoding = of.optInt(MediaFormat.KEY_PCM_ENCODING)
                            ?: AudioFormat.ENCODING_PCM_16BIT
                        if (newRate != sourceRate) {
                            sourceRate = newRate
                            resampler = SincResampler(sourceRate, PcmFormat.SAMPLE_RATE)
                        }
                        sourceChannels = newChannels
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> {
                        if (outIndex >= 0) {
                            if (info.size > 0) {
                                val outBuf = codec.getOutputBuffer(outIndex)!!
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)

                                val sampleCount = readInto(outBuf, pcmEncoding).let { arr ->
                                    if (arr.size > interleaved.size) interleaved = ShortArray(arr.size)
                                    System.arraycopy(arr, 0, interleaved, 0, arr.size)
                                    arr.size
                                }
                                if (mono.size < sampleCount) mono = ShortArray(sampleCount)
                                val frames = PcmUtil.downmixToMono(
                                    interleaved, sampleCount, sourceChannels.coerceAtLeast(1), mono
                                )
                                val resampled = resampler.process(mono, frames)
                                if (resampled.isNotEmpty()) consumer.onPcm(resampled, resampled.size)
                                onProgressMs(info.presentationTimeUs / 1000)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawOutputEnd = true
                            }
                        }
                    }
                }
            }

            val tail = resampler.flush()
            if (tail.isNotEmpty()) consumer.onPcm(tail, tail.size)
            consumer.onEnd()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Decoders may emit 16-bit or float PCM; normalise to 16-bit here. */
    private fun readInto(buf: ByteBuffer, encoding: Int): ShortArray {
        buf.order(ByteOrder.nativeOrder())
        return when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val fb = buf.asFloatBuffer()
                ShortArray(fb.remaining()) {
                    (fb.get().coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                ShortArray(buf.remaining()) { (((buf.get().toInt() and 0xFF) - 128) shl 8).toShort() }
            }
            else -> {
                val sb = buf.asShortBuffer()
                ShortArray(sb.remaining()) { sb.get() }
            }
        }
    }

    private fun MediaFormat.optInt(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}

/** Wraps a typed [ProcessingError] so it can travel up through normal exception plumbing. */
class ProcessingErrorException(val error: ProcessingError) : Exception(error.userMessage, error.cause)
