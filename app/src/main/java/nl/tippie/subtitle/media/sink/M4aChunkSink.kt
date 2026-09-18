package nl.tippie.subtitle.media.sink

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import nl.tippie.subtitle.media.PcmFormat
import java.io.File
import java.nio.ByteBuffer

/**
 * AAC-LC 32 kbps mono in an MP4 container.
 *
 * The AAC encoder is a mandatory Android codec (API 16+), so this path exists on every
 * device. At 16 kHz mono it cuts upload volume ~8x versus WAV — 14 MB per hour of video
 * instead of 115 MB — which is the difference between "usable on mobile data" and not.
 *
 * Falls back to [WavChunkSink] at the call site if encoder creation fails.
 */
class M4aChunkSink(override val file: File) : ChunkSink {

    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private var trackIndex = -1
    private var muxerStarted = false
    private var closed = false
    private var presentationUs = 0L
    private val bufferInfo = MediaCodec.BufferInfo()

    init {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, PcmFormat.SAMPLE_RATE, PcmFormat.CHANNELS
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    override fun write(samples: ShortArray, offset: Int, count: Int) {
        var written = 0
        while (written < count) {
            drain(endOfStream = false)
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) continue
            val buf = codec.getInputBuffer(index) ?: continue
            buf.clear()
            val capacitySamples = buf.capacity() / 2
            val n = minOf(capacitySamples, count - written)
            for (i in 0 until n) buf.putShort(samples[offset + written + i])
            codec.queueInputBuffer(index, 0, n * 2, presentationUs, 0)
            presentationUs += n * 1_000_000L / PcmFormat.SAMPLE_RATE
            written += n
        }
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) TIMEOUT_US else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }
                index >= 0 -> {
                    val out: ByteBuffer? = codec.getOutputBuffer(index)
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (out != null && bufferInfo.size > 0 && !isConfig && muxerStarted) {
                        out.position(bufferInfo.offset)
                        out.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, out, bufferInfo)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    override fun close(): Long {
        if (closed) return file.length()
        closed = true
        runCatching {
            val index = codec.dequeueInputBuffer(TIMEOUT_US * 10)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drain(endOfStream = true)
        }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { if (muxerStarted) muxer.stop() }
        runCatching { muxer.release() }
        return file.length()
    }

    override fun abort() {
        closed = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { muxer.release() }
        file.delete()
    }

    companion object {
        private const val BIT_RATE = 32_000
        private const val MAX_INPUT = 16_384
        private const val TIMEOUT_US = 10_000L

        fun isSupported(): Boolean = runCatching {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).release(); true
        }.getOrDefault(false)
    }
}
