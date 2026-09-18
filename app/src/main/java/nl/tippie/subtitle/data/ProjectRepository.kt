package nl.tippie.subtitle.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import nl.tippie.subtitle.data.db.CueEntity
import nl.tippie.subtitle.data.db.ProjectEntity
import nl.tippie.subtitle.data.db.SubtitleDatabase
import nl.tippie.subtitle.domain.model.Cue
import nl.tippie.subtitle.domain.model.MediaInfo
import nl.tippie.subtitle.domain.model.ProjectStatus
import nl.tippie.subtitle.domain.model.SubtitleProject
import nl.tippie.subtitle.domain.model.TranscriptionTask

class ProjectRepository(private val db: SubtitleDatabase) {

    private val projectDao = db.projectDao()
    private val cueDao = db.cueDao()
    private val chunkDao = db.chunkDao()

    fun observeProjects(): Flow<List<SubtitleProject>> =
        projectDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeProject(id: Long): Flow<SubtitleProject?> =
        projectDao.observe(id).map { it?.toDomain() }

    suspend fun getProject(id: Long): SubtitleProject? = projectDao.get(id)?.toDomain()

    suspend fun createProject(
        uri: String,
        info: MediaInfo,
        audioTrackIndex: Int,
        language: String?,
        providerKey: String,
        task: TranscriptionTask = TranscriptionTask.TRANSCRIBE,
    ): Long {
        val now = System.currentTimeMillis()
        return projectDao.insert(
            ProjectEntity(
                name = info.displayName,
                sourceUri = uri,
                durationMs = info.durationMs,
                sizeBytes = info.sizeBytes,
                widthPx = info.widthPx,
                heightPx = info.heightPx,
                audioTrackIndex = audioTrackIndex,
                language = language,
                detectedLanguage = null,
                providerKey = providerKey,
                status = ProjectStatus.DRAFT.name,
                task = task.name,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    suspend fun deleteProject(id: Long) = projectDao.delete(id)

    suspend fun setStatus(id: Long, status: ProjectStatus) =
        projectDao.setStatus(id, status.name)

    suspend fun setError(id: Long, status: ProjectStatus, message: String?) =
        projectDao.setError(id, status.name, message)

    /** Clears chunk bookkeeping and cues so the next run starts from zero. */
    suspend fun resetProgress(id: Long) {
        chunkDao.deleteForProject(id)
        cueDao.deleteForProject(id)
        projectDao.setProgress(id, 0, 0, 0)
    }

    suspend fun retryFailedChunks(id: Long) = chunkDao.resetFailed(id)

    suspend fun failedChunkCount(id: Long): Int = chunkDao.failedCount(id)

    // ---- cues ----------------------------------------------------------------

    fun observeCues(projectId: Long): Flow<List<Cue>> =
        cueDao.observeForProject(projectId).map { list ->
            list.mapIndexed { i, e -> e.toDomain(i) }
        }

    suspend fun getCues(projectId: Long): List<Cue> =
        cueDao.forProject(projectId).mapIndexed { i, e -> e.toDomain(i) }

    suspend fun updateCue(cue: Cue) = cueDao.update(
        CueEntity(
            cue.id, cue.projectId, cue.startMs, cue.endMs, cue.text, cue.chunkIndex,
            cue.translatedText
        )
    )

    suspend fun insertCue(cue: Cue): Long = cueDao.insert(
        CueEntity(
            0, cue.projectId, cue.startMs, cue.endMs, cue.text, cue.chunkIndex,
            cue.translatedText
        )
    )

    suspend fun clearTranslations(projectId: Long) = cueDao.clearTranslations(projectId)

    suspend fun deleteCue(id: Long) = cueDao.delete(id)

    /** Splits one cue in two at [atMs], dividing the text proportionally by character. */
    suspend fun splitCue(cue: Cue, atMs: Long) {
        val split = atMs.coerceIn(cue.startMs + 1, cue.endMs - 1)
        val fraction = (split - cue.startMs).toDouble() / (cue.endMs - cue.startMs)
        val flat = cue.text.replace("\n", " ")
        val target = (flat.length * fraction).toInt().coerceIn(1, flat.length - 1)
        var cut = flat.lastIndexOf(' ', target)
        if (cut <= 0) cut = flat.indexOf(' ', target).takeIf { it > 0 } ?: target
        val head = flat.substring(0, cut).trim()
        val tail = flat.substring(cut).trim()
        updateCue(cue.copy(endMs = split, text = head.ifBlank { flat }))
        if (tail.isNotBlank()) {
            insertCue(cue.copy(id = 0, startMs = split, text = tail))
        }
    }

    /** Merges [second] into [first], keeping both texts on separate lines. */
    suspend fun mergeCues(first: Cue, second: Cue) {
        updateCue(
            first.copy(
                endMs = maxOf(first.endMs, second.endMs),
                text = "${first.text.replace("\n", " ")} ${second.text.replace("\n", " ")}".trim()
            )
        )
        deleteCue(second.id)
    }

    /** Shifts every cue by [deltaMs]; used for global sync correction. */
    suspend fun shiftAll(projectId: Long, deltaMs: Long) {
        val cues = cueDao.forProject(projectId)
        for (e in cues) {
            cueDao.update(
                e.copy(
                    startMs = (e.startMs + deltaMs).coerceAtLeast(0),
                    endMs = (e.endMs + deltaMs).coerceAtLeast(1),
                )
            )
        }
    }

    suspend fun replaceAllCues(projectId: Long, cues: List<Cue>) {
        cueDao.deleteForProject(projectId)
        cueDao.insertAll(
            cues.map {
                CueEntity(0, projectId, it.startMs, it.endMs, it.text, it.chunkIndex, it.translatedText)
            }
        )
    }
}

fun ProjectEntity.toDomain() = SubtitleProject(
    id = id,
    name = name,
    sourceUri = sourceUri,
    durationMs = durationMs,
    sizeBytes = sizeBytes,
    widthPx = widthPx,
    heightPx = heightPx,
    audioTrackIndex = audioTrackIndex,
    language = language,
    detectedLanguage = detectedLanguage,
    providerKey = providerKey,
    status = runCatching { ProjectStatus.valueOf(status) }.getOrDefault(ProjectStatus.DRAFT),
    task = runCatching { TranscriptionTask.valueOf(task) }.getOrDefault(TranscriptionTask.TRANSCRIBE),
    translationLanguage = translationLanguage,
    translatedCues = translatedCues,
    processedMs = processedMs,
    totalChunks = totalChunks,
    completedChunks = completedChunks,
    errorMessage = errorMessage,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun CueEntity.toDomain(index: Int) = Cue(
    id = id,
    projectId = projectId,
    index = index,
    startMs = startMs,
    endMs = endMs,
    text = text,
    chunkIndex = chunkIndex,
    translatedText = translatedText,
)
