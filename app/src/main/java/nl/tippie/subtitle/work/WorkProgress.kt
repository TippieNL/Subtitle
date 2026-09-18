package nl.tippie.subtitle.work

import androidx.work.Data

enum class Stage(val label: String) {
    QUEUED("Queued"),
    PREPARING("Preparing"),
    EXTRACTING("Extracting audio"),
    TRANSCRIBING("Transcribing"),
    FINALISING("Building subtitles"),
    DONE("Done"),
    RENDERING("Rendering video"),
}

data class WorkProgress(
    val stage: Stage,
    val processedMs: Long,
    val totalMs: Long,
    val chunkIndex: Int,
    val chunkCount: Int,
    val failedChunks: Int,
    /** Null until we have enough samples for the estimate to mean anything. */
    val etaSeconds: Long?,
) {
    val fraction: Float
        get() = when {
            stage == Stage.DONE -> 1f
            chunkCount > 0 && stage == Stage.TRANSCRIBING -> chunkIndex.toFloat() / chunkCount
            totalMs > 0 -> (processedMs.toFloat() / totalMs).coerceIn(0f, 1f)
            else -> 0f
        }

    fun toData(): Data = Data.Builder()
        .putString(KEY_STAGE, stage.name)
        .putLong(KEY_PROCESSED, processedMs)
        .putLong(KEY_TOTAL, totalMs)
        .putInt(KEY_CHUNK, chunkIndex)
        .putInt(KEY_CHUNK_COUNT, chunkCount)
        .putInt(KEY_FAILED, failedChunks)
        .putLong(KEY_ETA, etaSeconds ?: -1L)
        .build()

    companion object {
        const val KEY_STAGE = "stage"
        const val KEY_PROCESSED = "processedMs"
        const val KEY_TOTAL = "totalMs"
        const val KEY_CHUNK = "chunkIndex"
        const val KEY_CHUNK_COUNT = "chunkCount"
        const val KEY_FAILED = "failedChunks"
        const val KEY_ETA = "eta"
        const val KEY_ERROR = "error"

        fun from(data: Data): WorkProgress? {
            val stageName = data.getString(KEY_STAGE) ?: return null
            val stage = runCatching { Stage.valueOf(stageName) }.getOrNull() ?: return null
            val eta = data.getLong(KEY_ETA, -1L)
            return WorkProgress(
                stage = stage,
                processedMs = data.getLong(KEY_PROCESSED, 0),
                totalMs = data.getLong(KEY_TOTAL, 0),
                chunkIndex = data.getInt(KEY_CHUNK, 0),
                chunkCount = data.getInt(KEY_CHUNK_COUNT, 0),
                failedChunks = data.getInt(KEY_FAILED, 0),
                etaSeconds = eta.takeIf { it >= 0 },
            )
        }
    }
}
