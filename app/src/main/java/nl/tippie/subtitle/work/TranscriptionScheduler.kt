package nl.tippie.subtitle.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

object TranscriptionScheduler {

    /**
     * One job per project, [ExistingWorkPolicy.KEEP], so a double tap cannot start the
     * same transcription twice.
     */
    fun start(
        context: Context,
        projectId: Long,
        requiresNetwork: Boolean,
        requireUnmetered: Boolean,
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                when {
                    !requiresNetwork -> NetworkType.NOT_REQUIRED
                    requireUnmetered -> NetworkType.UNMETERED
                    else -> NetworkType.CONNECTED
                }
            )
            .setRequiresStorageNotLow(true)
            .build()

        val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(Data.Builder().putLong(TranscriptionWorker.KEY_PROJECT_ID, projectId).build())
            .setConstraints(constraints)
            .addTag(tag(projectId))
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(TranscriptionWorker.uniqueName(projectId), ExistingWorkPolicy.KEEP, request)
    }

    /** Restarts a paused/failed job; already-transcribed chunks are skipped by the worker. */
    fun resume(context: Context, projectId: Long, requiresNetwork: Boolean, requireUnmetered: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                when {
                    !requiresNetwork -> NetworkType.NOT_REQUIRED
                    requireUnmetered -> NetworkType.UNMETERED
                    else -> NetworkType.CONNECTED
                }
            )
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(Data.Builder().putLong(TranscriptionWorker.KEY_PROJECT_ID, projectId).build())
            .setConstraints(constraints)
            .addTag(tag(projectId))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(TranscriptionWorker.uniqueName(projectId), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context, projectId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(TranscriptionWorker.uniqueName(projectId))
    }

    fun observe(context: Context, projectId: Long): Flow<WorkInfo?> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(TranscriptionWorker.uniqueName(projectId))
            .map { it.firstOrNull() }

    fun tag(projectId: Long) = "project:$projectId"
}

object TranslationScheduler {

    fun start(context: Context, projectId: Long, targetLanguage: String, requireUnmetered: Boolean) {
        val request = OneTimeWorkRequestBuilder<TranslationWorker>()
            .setInputData(
                Data.Builder()
                    .putLong(TranslationWorker.KEY_PROJECT_ID, projectId)
                    .putString(TranslationWorker.KEY_TARGET_LANGUAGE, targetLanguage)
                    .build()
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (requireUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED
                    )
                    .build()
            )
            .addTag(TranscriptionScheduler.tag(projectId))
            .build()

        // REPLACE, not KEEP: re-running is the documented way to retry failed lines, and
        // the worker skips anything already translated.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(TranslationWorker.uniqueName(projectId), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context, projectId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(TranslationWorker.uniqueName(projectId))
    }

    fun observe(context: Context, projectId: Long): Flow<WorkInfo?> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(TranslationWorker.uniqueName(projectId))
            .map { it.firstOrNull() }
}
