package nl.tippie.subtitle

import android.content.Context
import kotlinx.coroutines.flow.first
import nl.tippie.subtitle.data.ProjectRepository
import nl.tippie.subtitle.data.db.SubtitleDatabase
import nl.tippie.subtitle.data.prefs.AppSettings
import nl.tippie.subtitle.data.prefs.SettingsStore
import nl.tippie.subtitle.data.security.ApiKeyStore
import nl.tippie.subtitle.domain.model.ProcessingError
import nl.tippie.subtitle.domain.model.ProviderId
import nl.tippie.subtitle.media.MediaInfoReader
import nl.tippie.subtitle.transcription.TranscriptionProvider
import nl.tippie.subtitle.transcription.openai.WhisperApiProvider
import java.io.File

/**
 * Hand-rolled DI.
 *
 * Deliberate: the graph is a dozen singletons, and Hilt would add an annotation processor,
 * a WorkerFactory bridge and a Kotlin/KSP version coupling for no benefit at this size.
 * Every dependency is constructed here and passed explicitly, so swapping one in a test is
 * assignment, not a compiler plugin. Introduce Hilt when the graph outgrows one screenful.
 */
class AppContainer(private val context: Context) {

    val database: SubtitleDatabase by lazy { SubtitleDatabase.build(context) }
    val repository: ProjectRepository by lazy { ProjectRepository(database) }
    val settingsStore: SettingsStore by lazy { SettingsStore(context) }
    val apiKeyStore: ApiKeyStore by lazy { ApiKeyStore(context) }
    val mediaInfoReader: MediaInfoReader by lazy { MediaInfoReader(context) }

    /** Temp audio chunks live in cache so the OS can reclaim them under pressure. */
    fun chunkDir(projectId: Long): File =
        File(context.cacheDir, "projects/$projectId/chunks").apply { mkdirs() }

    fun clearProjectCache(projectId: Long) {
        File(context.cacheDir, "projects/$projectId").deleteRecursively()
    }

    fun cacheSizeBytes(): Long =
        File(context.cacheDir, "projects").walkBottomUp()
            .filter { it.isFile }.sumOf { it.length() }

    fun clearAllCache() {
        File(context.cacheDir, "projects").deleteRecursively()
    }

    /** Sweeps chunk directories for projects that no longer exist. */
    suspend fun sweepOrphanedCache() {
        val root = File(context.cacheDir, "projects")
        if (!root.isDirectory) return
        val live = database.projectDao().observeAll().first().map { it.id.toString() }.toSet()
        root.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name !in live) dir.deleteRecursively()
        }
    }

    fun providerFor(settings: AppSettings): TranscriptionProvider =
        when (ProviderId.fromKey(settings.providerKey)) {
            ProviderId.OPENAI_WHISPER -> WhisperApiProvider(
                apiKeyProvider = { apiKeyStore.get(ApiKeyStore.OPENAI) },
                encoding = settings.uploadEncoding,
            )
            // Phase 6 / 7. Declared here so the UI can list them and explain honestly
            // that they are not in this build, rather than pretending they work.
            ProviderId.LOCAL_WHISPER ->
                throw UnavailableProviderException(ProcessingError.ProviderUnavailable("On-device Whisper"))
            ProviderId.CUSTOM_BACKEND ->
                throw UnavailableProviderException(ProcessingError.ProviderUnavailable("Custom backend"))
        }
}

class UnavailableProviderException(val error: ProcessingError) : Exception(error.userMessage)
