package nl.tippie.subtitle.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.tippie.subtitle.AppContainer
import nl.tippie.subtitle.domain.model.ProcessingError
import nl.tippie.subtitle.domain.model.SubtitleTrack
import nl.tippie.subtitle.media.burnin.BurnInEngine
import nl.tippie.subtitle.media.burnin.BurnInException
import java.io.File

/** Renders a video with hardcoded subtitles, then copies it to the user's chosen location. */
class BurnInWorker(
    appContext: Context,
    params: WorkerParameters,
    private val container: AppContainer,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = info("Rendering video", 0)

    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        val projectId = inputData.getLong(KEY_PROJECT_ID, -1L)
        val destination = inputData.getString(KEY_DESTINATION_URI)
        if (projectId <= 0 || destination == null) {
            return androidx.work.ListenableWorker.Result.failure(
                error(ProcessingError.Unexpected("export", IllegalArgumentException("missing arguments")))
            )
        }

        val project = container.repository.getProject(projectId)
            ?: return androidx.work.ListenableWorker.Result.failure(
                error(ProcessingError.Unexpected("export", IllegalStateException("project missing")))
            )
        val track = runCatching {
            SubtitleTrack.valueOf(inputData.getString(KEY_TRACK) ?: SubtitleTrack.ORIGINAL.name)
        }.getOrDefault(SubtitleTrack.ORIGINAL)
        val cues = container.repository.getCues(projectId)
            .map { if (track == SubtitleTrack.ORIGINAL) it else it.copy(text = it.textFor(track)) }
        if (cues.isEmpty()) {
            return androidx.work.ListenableWorker.Result.failure(
                error(ProcessingError.Unexpected("export", IllegalStateException("no subtitles to burn in")))
            )
        }

        ProcessingNotifications.ensureChannel(applicationContext)
        runCatching { setForeground(info(project.name, 0)) }

        val style = container.settingsStore.current().style
        val temp = File(applicationContext.cacheDir, "exports").apply { mkdirs() }
            .let { File(it, "burnin_$projectId.mp4") }
        temp.delete()

        val result = BurnInEngine(applicationContext).render(
            sourceUri = Uri.parse(project.sourceUri),
            cues = cues,
            style = style,
            outputFile = temp,
            onProgress = { percent ->
                runCatching {
                    kotlinx.coroutines.runBlocking {
                        setProgress(
                            WorkProgress(Stage.RENDERING, percent.toLong(), 100, 0, 0, 0, null).toData()
                        )
                        setForeground(info(project.name, percent))
                    }
                }
            },
        )

        return result.fold(
            onSuccess = {
                val copied = withContext(Dispatchers.IO) {
                    runCatching {
                        applicationContext.contentResolver.openOutputStream(Uri.parse(destination))
                            ?.use { out -> temp.inputStream().use { it.copyTo(out, 256 * 1024) } }
                            ?: throw java.io.IOException("could not open destination")
                    }
                }
                temp.delete()
                copied.fold(
                    onSuccess = { androidx.work.ListenableWorker.Result.success() },
                    onFailure = { androidx.work.ListenableWorker.Result.failure(error(ProcessingError.ExportWriteFailed(it))) }
                )
            },
            onFailure = { throwable ->
                temp.delete()
                val processingError = (throwable as? BurnInException)?.error
                    ?: ProcessingError.Unexpected("video export", throwable)
                androidx.work.ListenableWorker.Result.failure(error(processingError))
            }
        )
    }

    private fun error(e: ProcessingError): Data =
        Data.Builder().putString(WorkProgress.KEY_ERROR, e.userMessage).build()

    private fun info(title: String, percent: Int): ForegroundInfo {
        ProcessingNotifications.ensureChannel(applicationContext)
        val notification = ProcessingNotifications.build(
            applicationContext, title, "Rendering video with subtitles", percent,
            WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                ProcessingNotifications.NOTIFICATION_ID + 1, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING.takeIf {
                    Build.VERSION.SDK_INT >= 35
                } ?: ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(ProcessingNotifications.NOTIFICATION_ID + 1, notification)
        }
    }

    companion object {
        const val KEY_PROJECT_ID = "projectId"
        const val KEY_DESTINATION_URI = "destinationUri"
        const val KEY_TRACK = "track"
        fun uniqueName(projectId: Long) = "burnin:$projectId"
    }
}
