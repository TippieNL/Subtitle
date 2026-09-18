package nl.tippie.subtitle.domain.model

/**
 * Every failure path maps to one of these. There is deliberately no
 * "UnknownError(message)" catch-all shown to users as "something went wrong":
 * [Unexpected] still carries the concrete cause and the stage it happened in.
 */
sealed class ProcessingError(val userMessage: String, val cause: Throwable? = null) {

    class NoAudioTrack :
        ProcessingError("This video has no audio track, so there is nothing to transcribe.")

    class UnsupportedAudioCodec(mime: String) : ProcessingError(
        "This device can't decode ${prettyCodec(mime)} audio. " +
            "Try a file with AAC, MP3, Opus or FLAC audio."
    )

    class CorruptContainer(cause: Throwable?) : ProcessingError(
        "The file couldn't be read — it may be incomplete or corrupted.", cause
    )

    class UriPermissionLost : ProcessingError(
        "Lost access to this video. It may have been moved, renamed or deleted. Pick it again."
    )

    class InsufficientStorage(neededBytes: Long, freeBytes: Long) : ProcessingError(
        "Needs about ${formatBytes(neededBytes)} of free space; only ${formatBytes(freeBytes)} available."
    )

    class NetworkUnavailable : ProcessingError(
        "No internet connection. Transcription will resume automatically when you're back online."
    )

    class InvalidApiKey : ProcessingError(
        "Your OpenAI API key was rejected. Check it in Settings."
    )

    class QuotaExceeded : ProcessingError(
        "Your OpenAI account has no remaining credit for transcription."
    )

    class RateLimited(retryAfterSeconds: Long?) : ProcessingError(
        if (retryAfterSeconds != null) "Rate limited by OpenAI — retrying in ${retryAfterSeconds}s."
        else "Rate limited by OpenAI — retrying shortly."
    )

    class ApiKeyMissing : ProcessingError(
        "No API key set. Add your OpenAI API key in Settings to use cloud transcription."
    )

    class ProviderUnavailable(name: String) : ProcessingError(
        "$name isn't available yet in this build."
    )

    class ChunkFailed(chunkIndex: Int, totalChunks: Int, cause: Throwable?) : ProcessingError(
        "Chunk ${chunkIndex + 1} of $totalChunks failed after 3 attempts. " +
            "The rest of the video was still transcribed — you can retry the failed parts.",
        cause
    )

    class ForegroundServiceTimeout(done: Int, total: Int) : ProcessingError(
        "Paused by the system's background-work limit. $done of $total chunks are done — " +
            "reopen the app to continue from there."
    )

    class EncoderUnavailable(width: Int, height: Int) : ProcessingError(
        "This device can't re-encode video at ${width}×$height. Export the subtitle file instead."
    )

    class ExportWriteFailed(cause: Throwable?) : ProcessingError(
        "Couldn't write to the selected location. Pick a different folder and try again.", cause
    )

    class Unexpected(stage: String, cause: Throwable?) : ProcessingError(
        "Failed during $stage: ${cause?.message ?: cause?.javaClass?.simpleName ?: "no detail available"}",
        cause
    )

    companion object {
        private fun prettyCodec(mime: String) = when {
            mime.contains("eac3", true) || mime.contains("e-ac3", true) ->
                "E-AC-3 (Dolby Digital Plus)"
            mime.contains("ac3", true) -> "AC-3 (Dolby Digital)"
            mime.contains("dts", true) -> "DTS"
            mime.contains("truehd", true) -> "Dolby TrueHD"
            else -> mime.substringAfter('/').uppercase()
        }

        fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val units = listOf("KB", "MB", "GB", "TB")
            var value = bytes.toDouble() / 1024
            var i = 0
            while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
            return if (value >= 100) "${value.toInt()} ${units[i]}"
            else String.format("%.1f %s", value, units[i])
        }
    }
}
