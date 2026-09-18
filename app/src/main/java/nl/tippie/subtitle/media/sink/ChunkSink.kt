package nl.tippie.subtitle.media.sink

import java.io.File

/** Writes mono 16 kHz PCM-16 into whatever container a transcription provider wants. */
interface ChunkSink {
    val file: File
    fun write(samples: ShortArray, offset: Int, count: Int)
    /** Finishes the file. Returns its size in bytes. */
    fun close(): Long
    /** Aborts and deletes the partial file. */
    fun abort()
}

enum class ChunkEncoding(val extension: String, val mimeType: String) {
    /** Lossless, ~1.92 MB per minute. Best accuracy, heaviest upload. */
    WAV("wav", "audio/wav"),
    /** AAC-LC 32 kbps in MP4, ~0.24 MB per minute. ~8x less data for no practical WER cost. */
    M4A("m4a", "audio/mp4"),
    /** Headerless PCM for on-device inference — never uploaded. */
    PCM("pcm", "application/octet-stream"),
}
