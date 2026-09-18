package nl.tippie.subtitle.media.sink

import nl.tippie.subtitle.media.PcmFormat
import java.io.File
import java.io.RandomAccessFile

/**
 * Canonical RIFF/WAVE writer. The 44-byte header is written up front with placeholder
 * sizes and patched on close, so we never need the sample count in advance — which is
 * the whole point: chunk length is decided by silence detection, not known ahead of time.
 */
class WavChunkSink(override val file: File) : ChunkSink {

    private val raf = RandomAccessFile(file, "rw").apply {
        setLength(0)
        write(ByteArray(HEADER_BYTES))
    }
    private var dataBytes = 0L
    private var closed = false
    private var scratch = ByteArray(8192)

    override fun write(samples: ShortArray, offset: Int, count: Int) {
        if (count <= 0) return
        val needed = count * 2
        if (scratch.size < needed) scratch = ByteArray(needed)
        var b = 0
        for (i in offset until offset + count) {
            val v = samples[i].toInt()
            scratch[b++] = (v and 0xFF).toByte()
            scratch[b++] = ((v shr 8) and 0xFF).toByte()
        }
        raf.write(scratch, 0, needed)
        dataBytes += needed
    }

    override fun close(): Long {
        if (closed) return file.length()
        closed = true
        raf.seek(0)
        raf.write(header(dataBytes))
        raf.close()
        return file.length()
    }

    override fun abort() {
        closed = true
        runCatching { raf.close() }
        file.delete()
    }

    private fun header(dataLen: Long): ByteArray {
        val channels = PcmFormat.CHANNELS
        val rate = PcmFormat.SAMPLE_RATE
        val bits = 16
        val byteRate = rate * channels * bits / 8
        val blockAlign = channels * bits / 8
        val riffLen = (36 + dataLen).coerceAtMost(0xFFFFFFFFL)
        val h = ByteArray(HEADER_BYTES)
        var p = 0
        fun ascii(s: String) { for (c in s) h[p++] = c.code.toByte() }
        fun le32(v: Long) {
            h[p++] = (v and 0xFF).toByte(); h[p++] = ((v shr 8) and 0xFF).toByte()
            h[p++] = ((v shr 16) and 0xFF).toByte(); h[p++] = ((v shr 24) and 0xFF).toByte()
        }
        fun le16(v: Int) { h[p++] = (v and 0xFF).toByte(); h[p++] = ((v shr 8) and 0xFF).toByte() }

        ascii("RIFF"); le32(riffLen); ascii("WAVE")
        ascii("fmt "); le32(16); le16(1); le16(channels)
        le32(rate.toLong()); le32(byteRate.toLong()); le16(blockAlign); le16(bits)
        ascii("data"); le32(dataLen.coerceAtMost(0xFFFFFFFFL))
        return h
    }

    private companion object { const val HEADER_BYTES = 44 }
}
