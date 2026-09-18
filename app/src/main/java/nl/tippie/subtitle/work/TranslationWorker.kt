package nl.tippie.subtitle.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.tippie.subtitle.AppContainer
import nl.tippie.subtitle.domain.model.ProcessingError
import nl.tippie.subtitle.domain.model.ProjectStatus
import nl.tippie.subtitle.subtitle.CueSegmenter
import nl.tippie.subtitle.subtitle.SubtitleTranslator
import nl.tippie.subtitle.util.Languages

/**
 * Translates a project's existing cues, writing into `translatedText` and leaving the
 * original `text` untouched.
 *
 * Resume is trivial here compared to transcription: the worker simply asks the database
 * for cues that still have no translation, so an interrupted run picks up exactly where
 * it stopped with no extra bookkeeping.
 */
class TranslationWorker(
    appContext: Context,
    params: WorkerParameters,
    private val container: AppContainer,
) : CoroutineWorker(appContext, params) {

    private var projectName = ""

    override suspend fun getForegroundInfo(): ForegroundInfo =
        info(projectName.ifBlank { "Translating subtitles" }, null)

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val projectId = inputData.getLong(KEY_PROJECT_ID, -1L)
        val target = inputData.getString(KEY_TARGET_LANGUAGE) ?: DEFAULT_TARGET
        if (projectId <= 0) {
            return@withContext Result.failure(
                error(ProcessingError.Unexpected("translation", IllegalArgumentException("no project id")))
            )
        }

        val project = container.repository.getProject(projectId)
            ?: return@withContext Result.failure(
                error(ProcessingError.Unexpected("translation", IllegalStateException("project missing")))
            )
        projectName = project.name

        val cueDao = container.database.cueDao()
        val projectDao = container.database.projectDao()

        // A target change invalidates anything translated into the previous target.
        if (project.translationLanguage != null && project.translationLanguage != target) {
            cueDao.clearTranslations(projectId)
        }

        val totalCues = cueDao.count(projectId)
        if (totalCues == 0) {
            return@withContext Result.failure(error(ProcessingError.NothingToTranslate()))
        }

        val settings = container.settingsStore.current()
        val provider = container.translationProviderFor(settings)
        if (!provider.isAvailable()) {
            return@withContext Result.failure(error(ProcessingError.ApiKeyMissing()))
        }

        ProcessingNotifications.ensureChannel(applicationContext)
        runCatching { setForeground(info(projectName, 0)) }
        container.repository.setStatus(projectId, ProjectStatus.TRANSLATING)

        try {
            val pending = cueDao.untranslated(projectId)
            if (pending.isEmpty()) {
                projectDao.setTranslationProgress(projectId, target, totalCues)
                container.repository.setStatus(projectId, ProjectStatus.COMPLETED)
                return@withContext Result.success()
            }

            val alreadyDone = totalCues - pending.size
            val translator = SubtitleTranslator(
                provider = provider,
                batchSize = settings.translationBatchSize,
            )

            // Flatten to one line per cue: the model gets cleaner input, and we re-wrap
            // afterwards because translated text rarely has the same length.
            val sourceTexts = pending.map { it.text.replace("\n", " ") }
            var failures = 0

            val results = translator.translateAll(
                texts = sourceTexts,
                sourceLanguage = project.detectedLanguage ?: project.language,
                targetLanguage = target,
                onProgress = { done, _ ->
                    kotlinx.coroutines.runBlocking {
                        report(alreadyDone + done, totalCues)
                    }
                },
                isCancelled = { isStopped },
            )

            for (result in results) {
                when (result) {
                    is SubtitleTranslator.LineResult.Translated -> {
                        val cue = pending[result.index]
                        val wrapped = CueSegmenter.wrap(result.text, CueSegmenter.Config())
                        cueDao.setTranslation(cue.id, wrapped)
                    }
                    is SubtitleTranslator.LineResult.Failed -> failures++
                }
            }

            val translated = cueDao.translatedCount(projectId)
            projectDao.setTranslationProgress(projectId, target, translated)

            if (isStopped) {
                container.repository.setStatus(projectId, ProjectStatus.PAUSED)
                return@withContext Result.success()
            }

            if (failures > 0) {
                container.repository.setError(
                    projectId, ProjectStatus.COMPLETED,
                    "$failures of $totalCues subtitles could not be translated. " +
                        "Run the translation again to retry just those."
                )
            } else {
                container.repository.setError(projectId, ProjectStatus.COMPLETED, null)
            }
            report(totalCues, totalCues)
            Result.success()

        } catch (e: CancellationException) {
            container.repository.setStatus(projectId, ProjectStatus.PAUSED)
            throw e
        } catch (e: Throwable) {
            container.repository.setError(
                projectId, ProjectStatus.COMPLETED,
                ProcessingError.Unexpected("translation", e).userMessage
            )
            Result.failure(error(ProcessingError.Unexpected("translation", e)))
        }
    }

    private suspend fun report(done: Int, total: Int) {
        setProgress(
            WorkProgress(
                stage = Stage.TRANSLATING,
                processedMs = done.toLong(),
                totalMs = total.toLong(),
                chunkIndex = done,
                chunkCount = total,
                failedChunks = 0,
                etaSeconds = null,
            ).toData()
        )
        runCatching {
            setForeground(info(projectName, if (total > 0) done * 100 / total else 0))
        }
    }

    private fun error(e: ProcessingError): Data =
        Data.Builder().putString(WorkProgress.KEY_ERROR, e.userMessage).build()

    private fun info(title: String, percent: Int?): ForegroundInfo {
        ProcessingNotifications.ensureChannel(applicationContext)
        val notification = ProcessingNotifications.build(
            applicationContext,
            title.ifBlank { "Translating subtitles" },
            "Translating subtitles",
            percent,
            WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                ProcessingNotifications.NOTIFICATION_ID + 2,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(ProcessingNotifications.NOTIFICATION_ID + 2, notification)
        }
    }

    companion object {
        const val KEY_PROJECT_ID = "projectId"
        const val KEY_TARGET_LANGUAGE = "targetLanguage"
        const val DEFAULT_TARGET = "en"
        fun uniqueName(projectId: Long) = "translate:$projectId"
    }
}
