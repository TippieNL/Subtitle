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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import nl.tippie.subtitle.AppContainer
import nl.tippie.subtitle.SubtitleApplication
import nl.tippie.subtitle.data.db.ChunkEntity
import nl.tippie.subtitle.data.db.CueEntity
import nl.tippie.subtitle.domain.model.BoundaryKind
import nl.tippie.subtitle.domain.model.ChunkState
import nl.tippie.subtitle.domain.model.ProcessingError
import nl.tippie.subtitle.domain.model.ProjectStatus
import nl.tippie.subtitle.media.AudioDecoder
import nl.tippie.subtitle.media.ChunkRecord
import nl.tippie.subtitle.media.ProcessingErrorException
import nl.tippie.subtitle.media.SilenceSeekingChunker
import nl.tippie.subtitle.media.sink.ChunkEncoding
import nl.tippie.subtitle.media.sink.ChunkSink
import nl.tippie.subtitle.media.sink.M4aChunkSink
import nl.tippie.subtitle.media.sink.PcmChunkSink
import nl.tippie.subtitle.media.sink.WavChunkSink
import nl.tippie.subtitle.subtitle.CueSegmenter
import nl.tippie.subtitle.subtitle.TimelineMerger
import nl.tippie.subtitle.transcription.ChunkRequest
import nl.tippie.subtitle.transcription.TranscriptionException
import nl.tippie.subtitle.transcription.TranscriptionProvider
import java.io.File

/**
 * The long-running job: extract audio, chunk it, transcribe every chunk, merge the
 * timeline, write cues.
 *
 * **Resume model.** Transcription is the expensive half (minutes per chunk) and extraction
 * is the cheap half (50-100x realtime), so they are checkpointed differently:
 *
 *  - Every chunk's transcription state lives in Room. A chunk that is already TRANSCRIBED
 *    is never transcribed again.
 *  - Extraction is re-run from the start on resume, but [SilenceSeekingChunker] is
 *    deterministic: same input, same settings, same boundaries. So chunk N on the second
 *    run covers exactly the audio chunk N covered on the first. Already-transcribed chunks
 *    are streamed into a null sink, costing decode time but no disk and no upload.
 *
 * That combination means an interrupted 68-chunk job resumes having lost at most one
 * chunk's work, without needing to seek into a compressed stream at a non-sync sample.
 */
class TranscriptionWorker(
    appContext: Context,
    params: WorkerParameters,
    private val container: AppContainer,
) : CoroutineWorker(appContext, params) {

    private val repository get() = container.repository
    private val chunkDao get() = container.database.chunkDao()
    private val cueDao get() = container.database.cueDao()
    private val projectDao get() = container.database.projectDao()

    private var totalMs = 0L
    private var chunkCount = 0
    private var completedChunks = 0
    private var failedChunks = 0
    private var projectName = ""
    private val transcriptionStartedAt = System.currentTimeMillis()
    private var transcribedMsSoFar = 0L

    override suspend fun getForegroundInfo(): ForegroundInfo = buildForegroundInfo(
        title = projectName.ifBlank { "Generating subtitles" },
        text = "Preparing…",
        percent = null,
    )

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val projectId = inputData.getLong(KEY_PROJECT_ID, -1L)
        if (projectId <= 0) return@withContext Result.failure(
            errorData(ProcessingError.Unexpected("startup", IllegalArgumentException("no project id")))
        )

        val project = repository.getProject(projectId)
            ?: return@withContext Result.failure(
                errorData(ProcessingError.Unexpected("startup", IllegalStateException("project missing")))
            )
        projectName = project.name
        totalMs = project.durationMs

        ProcessingNotifications.ensureChannel(applicationContext)
        runCatching { setForeground(getForegroundInfo()) }

        val settings = container.settingsStore.current()
        val provider = try {
            container.providerFor(settings)
        } catch (e: Exception) {
            return@withContext fail(projectId, ProcessingError.ProviderUnavailable(project.providerKey))
        }

        if (!provider.isAvailable()) {
            return@withContext fail(projectId, ProcessingError.ApiKeyMissing())
        }

        val chunkDir = container.chunkDir(projectId)

        try {
            repository.setStatus(projectId, ProjectStatus.EXTRACTING)
            report(Stage.PREPARING, 0)

            // ---- Phase A: extraction + transcription, pipelined ----------------
            val existing = chunkDao.forProject(projectId).associateBy { it.chunkIndex }
            val transcribedIndices = existing.filterValues { it.state == ChunkState.TRANSCRIBED.name }.keys
            val semaphore = Semaphore(settings.parallelRequests.coerceIn(1, 4))

            coroutineScope {
                val jobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

                val chunker = SilenceSeekingChunker(
                    targetChunkMs = settings.targetChunkMs,
                    openSink = { index ->
                        if (index in transcribedIndices) NullSink
                        else createSink(chunkDir, index, provider.audioRequirements.encoding)
                    },
                    onChunk = { record ->
                        chunkCount = record.index + 1
                        if (record.index in transcribedIndices) {
                            completedChunks++
                        } else {
                            val entity = persistChunkBlocking(projectId, record)
                            jobs += async {
                                semaphore.withPermit {
                                    transcribeChunk(projectId, entity, record, provider, settings.defaultLanguage, settings.wordTimestamps)
                                }
                            }
                        }
                    },
                )

                repository.setStatus(projectId, ProjectStatus.TRANSCRIBING)

                withContext(Dispatchers.IO) {
                    AudioDecoder(applicationContext).decode(
                        uri = Uri.parse(project.sourceUri),
                        trackIndex = project.audioTrackIndex,
                        consumer = chunker,
                        onProgressMs = { positionMs ->
                            if (positionMs - lastReportedExtractMs > 2_000) {
                                lastReportedExtractMs = positionMs
                                reportBlocking(Stage.EXTRACTING, positionMs)
                            }
                        },
                        isCancelled = { isStopped },
                    )
                }

                jobs.awaitAll()
            }

            if (isStopped) return@withContext stopped(projectId)

            // ---- Phase B: finalise --------------------------------------------
            report(Stage.FINALISING, totalMs)
            failedChunks = chunkDao.failedCount(projectId)
            completedChunks = chunkDao.completedCount(projectId)
            chunkCount = chunkDao.totalCount(projectId)
            projectDao.setProgress(projectId, totalMs, completedChunks, chunkCount)

            container.clearProjectCache(projectId)

            if (failedChunks > 0) {
                repository.setError(
                    projectId, ProjectStatus.COMPLETED,
                    "$failedChunks of $chunkCount chunks failed. Open the project and retry them."
                )
            } else {
                repository.setError(projectId, ProjectStatus.COMPLETED, null)
            }
            report(Stage.DONE, totalMs)
            Result.success(WorkProgress(Stage.DONE, totalMs, totalMs, chunkCount, chunkCount, failedChunks, 0).toData())

        } catch (e: CancellationException) {
            stopped(projectId)
            throw e
        } catch (e: ProcessingErrorException) {
            fail(projectId, e.error)
        } catch (e: TranscriptionException) {
            fail(projectId, e.error)
        } catch (e: Throwable) {
            fail(projectId, ProcessingError.Unexpected("processing", e))
        }
    }

    private var lastReportedExtractMs = 0L

    private fun createSink(dir: File, index: Int, encoding: ChunkEncoding): ChunkSink {
        val name = "chunk_%04d.%s".format(index, encoding.extension)
        val file = File(dir, name)
        return when (encoding) {
            ChunkEncoding.M4A -> runCatching { M4aChunkSink(file) as ChunkSink }
                // Device refused to create an AAC encoder: fall back to lossless WAV,
                // which costs upload bandwidth but always works.
                .getOrElse { WavChunkSink(File(dir, "chunk_%04d.wav".format(index))) }
            ChunkEncoding.WAV -> WavChunkSink(file)
            ChunkEncoding.PCM -> PcmChunkSink(file)
        }
    }

    private fun persistChunkBlocking(projectId: Long, record: ChunkRecord): ChunkEntity {
        val entity = ChunkEntity(
            projectId = projectId,
            chunkIndex = record.index,
            startMs = record.startMs,
            endMs = record.endMs,
            overlapMs = record.overlapMs,
            boundaryKind = record.boundary.name,
            state = ChunkState.EXTRACTED.name,
            filePath = record.file.absolutePath,
        )
        kotlinx.coroutines.runBlocking { chunkDao.insert(entity) }
        return entity
    }

    private suspend fun transcribeChunk(
        projectId: Long,
        entity: ChunkEntity,
        record: ChunkRecord,
        provider: TranscriptionProvider,
        language: String,
        wordTimestamps: Boolean,
    ) {
        if (isStopped) return

        val previousTail = if (record.index > 0) {
            chunkDao.get(projectId, record.index - 1)?.transcriptTail
        } else null

        var attempt = 0
        var lastError: ProcessingError? = null

        while (attempt < MAX_ATTEMPTS && !isStopped) {
            attempt++
            val result = provider.transcribe(
                ChunkRequest(
                    audioFile = record.file,
                    chunkIndex = record.index,
                    chunkDurationMs = record.endMs - record.startMs,
                    language = language.takeUnless { it == "auto" },
                    contextPrompt = previousTail,
                    wantWordTimestamps = wordTimestamps,
                )
            )

            result.onSuccess { transcript ->
                applyTranscript(projectId, record, transcript)
                chunkDao.update(
                    entity.copy(
                        state = ChunkState.TRANSCRIBED.name,
                        attempts = attempt,
                        filePath = null,
                        transcriptTail = transcript.segments
                            .joinToString(" ") { it.text }
                            .takeLast(CONTEXT_PROMPT_CHARS)
                            .ifBlank { null },
                    )
                )
                transcript.detectedLanguage?.let { projectDao.setDetectedLanguage(projectId, it) }
                record.file.delete()
                completedChunks++
                transcribedMsSoFar += (record.endMs - record.startMs)
                reportBlocking(Stage.TRANSCRIBING, record.endMs)
                return
            }

            val error = result.exceptionOrNull()
            val transcriptionError = error as? TranscriptionException
            lastError = transcriptionError?.error
                ?: ProcessingError.Unexpected("transcription", error)

            if (transcriptionError?.retryable != true) break

            val backoffMs = transcriptionError.retryAfterSeconds?.times(1000)
                ?: (BASE_BACKOFF_MS * (1L shl (attempt - 1)) + (0..1000).random())
            delay(backoffMs.coerceAtMost(MAX_BACKOFF_MS))
        }

        // A chunk failure must not destroy the whole job.
        failedChunks++
        chunkDao.update(
            entity.copy(
                state = ChunkState.FAILED.name,
                attempts = attempt,
                lastError = lastError?.userMessage,
            )
        )
        record.file.delete()
    }

    /** Steps 1-4 of the merge, then readable segmentation, then persist. */
    private suspend fun applyTranscript(
        projectId: Long,
        record: ChunkRecord,
        transcript: nl.tippie.subtitle.transcription.ChunkTranscript,
    ) {
        val ctx = TimelineMerger.ChunkContext(
            chunkStartMs = record.startMs,
            chunkEndMs = record.endMs,
            overlapMs = record.overlapMs,
            isLast = false,
        )
        val absolute = TimelineMerger.absolutize(transcript.segments, ctx)

        val seam = if (record.boundary == BoundaryKind.HARD || record.overlapMs > 0) {
            val previous = cueDao.forChunk(projectId, record.index - 1)
                .map { TimelineMerger.Placed(it.startMs, it.endMs, it.text) }
            TimelineMerger.resolveSeam(previous, absolute, ctx)
        } else {
            TimelineMerger.SeamResult(absolute, replacePrevious = false)
        }

        if (seam.replacePrevious) {
            val overlapStart = record.startMs
            cueDao.forChunk(projectId, record.index - 1)
                .filter { it.endMs > overlapStart }
                .forEach { cueDao.delete(it.id) }
        }

        val readable = CueSegmenter.segment(TimelineMerger.enforceMonotonic(seam.placed))
        if (readable.isEmpty()) return

        cueDao.deleteForChunk(projectId, record.index)
        cueDao.insertAll(
            readable.map {
                CueEntity(
                    projectId = projectId,
                    startMs = it.startMs,
                    endMs = it.endMs,
                    text = it.text,
                    chunkIndex = record.index,
                )
            }
        )
    }

    // ---- progress -----------------------------------------------------------

    private suspend fun report(stage: Stage, processedMs: Long) {
        val progress = buildProgress(stage, processedMs)
        setProgress(progress.toData())
        runCatching {
            setForeground(
                buildForegroundInfo(
                    title = projectName,
                    text = statusLine(progress),
                    percent = (progress.fraction * 100).toInt(),
                )
            )
        }
    }

    private fun reportBlocking(stage: Stage, processedMs: Long) {
        kotlinx.coroutines.runBlocking { report(stage, processedMs) }
    }

    private fun buildProgress(stage: Stage, processedMs: Long): WorkProgress {
        // Only estimate once there is real throughput to extrapolate from; a guess
        // derived from the first chunk is worse than showing nothing.
        val eta = if (stage == Stage.TRANSCRIBING && completedChunks >= MIN_CHUNKS_FOR_ETA && transcribedMsSoFar > 0) {
            val elapsed = (System.currentTimeMillis() - transcriptionStartedAt) / 1000.0
            val rate = transcribedMsSoFar / elapsed.coerceAtLeast(1.0)
            ((totalMs - transcribedMsSoFar) / rate).toLong().coerceAtLeast(0)
        } else null

        return WorkProgress(
            stage = stage,
            processedMs = processedMs.coerceIn(0, if (totalMs > 0) totalMs else processedMs),
            totalMs = totalMs,
            chunkIndex = completedChunks,
            chunkCount = chunkCount,
            failedChunks = failedChunks,
            etaSeconds = eta,
        )
    }

    private fun statusLine(p: WorkProgress): String = buildString {
        append(p.stage.label)
        if (p.chunkCount > 0 && p.stage == Stage.TRANSCRIBING) {
            append(" · chunk ${p.chunkIndex + 1} of ${p.chunkCount}")
        }
        p.etaSeconds?.let { append(" · ~${it / 60} min left") }
    }

    private fun buildForegroundInfo(title: String, text: String, percent: Int?): ForegroundInfo {
        ProcessingNotifications.ensureChannel(applicationContext)
        val notification = ProcessingNotifications.build(
            context = applicationContext,
            title = title.ifBlank { "Generating subtitles" },
            text = text,
            progressPercent = percent,
            cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                ProcessingNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(ProcessingNotifications.NOTIFICATION_ID, notification)
        }
    }

    // ---- terminal states ----------------------------------------------------

    private suspend fun stopped(projectId: Long): Result {
        // Stopped is not failed: every transcribed chunk is already in Room, so the next
        // run continues from here.
        repository.setStatus(projectId, ProjectStatus.PAUSED)
        return Result.success()
    }

    private suspend fun fail(projectId: Long, error: ProcessingError): Result {
        repository.setError(projectId, ProjectStatus.FAILED, error.userMessage)
        return Result.failure(errorData(error))
    }

    private fun errorData(error: ProcessingError): Data =
        Data.Builder().putString(WorkProgress.KEY_ERROR, error.userMessage).build()

    /** Sink used for chunks that are already transcribed: decode through, write nothing. */
    private object NullSink : ChunkSink {
        override val file: File = File("/dev/null")
        override fun write(samples: ShortArray, offset: Int, count: Int) = Unit
        override fun close(): Long = 0
        override fun abort() = Unit
    }

    companion object {
        const val KEY_PROJECT_ID = "projectId"
        private const val MAX_ATTEMPTS = 3
        private const val BASE_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val CONTEXT_PROMPT_CHARS = 200
        private const val MIN_CHUNKS_FOR_ETA = 3

        fun uniqueName(projectId: Long) = "transcribe:$projectId"
    }
}
