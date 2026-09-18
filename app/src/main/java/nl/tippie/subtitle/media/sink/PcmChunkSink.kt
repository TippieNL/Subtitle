package nl.tippie.subtitle.media.sink

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/** Headerless little-endian PCM-16. Used by on-device inference, never uploaded. */
class PcmChunkSink(override val file: File) : ChunkSink {
    private val out = BufferedOutputStream(FileOutputStream(file), 64 * 1024)
    private var scratch = ByteArray(8192)
    private var closed = false

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
        out.write(scratch, 0, needed)
    }

    override fun close(): Long {
        if (!closed) { closed = true; out.flush(); out.close() }
        return file.length()
    }

    override fun abort() {
        closed = true
        runCatching { out.close() }
        file.delete()
    }
}
