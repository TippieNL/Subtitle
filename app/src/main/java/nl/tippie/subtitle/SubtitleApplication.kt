package nl.tippie.subtitle

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nl.tippie.subtitle.work.BurnInWorker
import nl.tippie.subtitle.work.ProcessingNotifications
import nl.tippie.subtitle.work.TranscriptionWorker

class SubtitleApplication : Application(), Configuration.Provider {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ProcessingNotifications.ensureChannel(this)
        // Chunk directories for deleted projects would otherwise linger in cache.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { container.sweepOrphanedCache() }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(AppWorkerFactory(container))
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.WARN)
            .build()
}

/** Injects [AppContainer] into workers without pulling in an annotation processor. */
class AppWorkerFactory(private val container: AppContainer) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        TranscriptionWorker::class.java.name -> TranscriptionWorker(appContext, workerParameters, container)
        BurnInWorker::class.java.name -> BurnInWorker(appContext, workerParameters, container)
        else -> null
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as SubtitleApplication).container
