package nl.tippie.subtitle.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.tippie.subtitle.AppContainer
import nl.tippie.subtitle.SubtitleApplication
import nl.tippie.subtitle.data.prefs.AppSettings
import nl.tippie.subtitle.data.security.ApiKeyStore
import nl.tippie.subtitle.domain.model.Cue
import nl.tippie.subtitle.domain.model.ProcessingError
import nl.tippie.subtitle.domain.model.ProjectStatus
import nl.tippie.subtitle.domain.model.ProviderId
import nl.tippie.subtitle.domain.model.SubtitleProject
import nl.tippie.subtitle.domain.model.SubtitleStyle
import nl.tippie.subtitle.domain.model.SubtitleTrack
import nl.tippie.subtitle.domain.model.TranscriptionTask
import nl.tippie.subtitle.domain.usecase.ExportSubtitles
import nl.tippie.subtitle.media.sink.ChunkEncoding
import nl.tippie.subtitle.subtitle.format.SubtitleFormat
import nl.tippie.subtitle.transcription.openai.WhisperApiProvider
import nl.tippie.subtitle.ui.editor.EditorUiState
import nl.tippie.subtitle.ui.importscreen.ImportUiState
import nl.tippie.subtitle.util.Languages
import nl.tippie.subtitle.work.TranscriptionScheduler
import nl.tippie.subtitle.work.TranslationScheduler
import nl.tippie.subtitle.work.WorkProgress

private val SubtitleApplication.appContainer: AppContainer get() = container

abstract class ContainerViewModel(app: Application) : AndroidViewModel(app) {
    protected val container: AppContainer = (app as SubtitleApplication).container
}

// ---- Home -------------------------------------------------------------------

class HomeViewModel(app: Application) : ContainerViewModel(app) {
    val projects: StateFlow<List<SubtitleProject>> =
        container.repository.observeProjects()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun delete(project: SubtitleProject) = viewModelScope.launch {
        TranscriptionScheduler.cancel(getApplication(), project.id)
        container.clearProjectCache(project.id)
        container.repository.deleteProject(project.id)
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { HomeViewModel(app()) }
        }
    }
}

// ---- Import -----------------------------------------------------------------

class ImportViewModel(app: Application) : ContainerViewModel(app) {

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    private var settings: AppSettings = AppSettings()
    private var uri: Uri? = null

    fun load(uriString: String) {
        if (uri != null) return
        val parsed = Uri.parse(uriString)
        uri = parsed
        viewModelScope.launch {
            settings = container.settingsStore.current()
            val result = container.mediaInfoReader.read(parsed)
            result.fold(
                onSuccess = { info ->
                    val provider = runCatching { container.providerFor(settings) }.getOrNull()
                    val ready = provider?.isAvailable() ?: false
                    _state.update {
                        it.copy(
                            loading = false,
                            info = info,
                            selectedTrackIndex = info.audioTracks.firstOrNull()?.trackIndex,
                            language = settings.defaultLanguage,
                            providerKey = settings.providerKey,
                            providerLabel = ProviderId.fromKey(settings.providerKey).displayName,
                            privacyNote = provider?.capabilities?.privacyNote
                                ?: "This provider is not available in this build.",
                            sendsAudioOffDevice = provider?.capabilities?.sendsAudioOffDevice ?: false,
                            providerReady = ready && info.audioTracks.isNotEmpty(),
                            providerBlockedReason = when {
                                info.audioTracks.isEmpty() -> ProcessingError.NoAudioTrack().userMessage
                                provider == null -> ProcessingError.ProviderUnavailable(
                                    ProviderId.fromKey(settings.providerKey).displayName
                                ).userMessage
                                !ready -> ProcessingError.ApiKeyMissing().userMessage
                                else -> null
                            },
                            estimatedUploadBytes = estimateUpload(info.durationMs, settings.uploadEncoding),
                            supportsAudioTranslation =
                                provider?.capabilities?.supportsTranslationToEnglish == true,
                        )
                    }
                },
                onFailure = { t ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = container.mediaInfoReader.classifyProbeFailure(t).userMessage
                        )
                    }
                }
            )
        }
    }

    /** WAV is 32 kB/s of audio; AAC-LC at 32 kbps is 4 kB/s. */
    private fun estimateUpload(durationMs: Long, encoding: ChunkEncoding): Long {
        val bytesPerSecond = when (encoding) {
            ChunkEncoding.WAV -> 32_000L
            ChunkEncoding.M4A -> 4_000L
            ChunkEncoding.PCM -> 0L
        }
        return durationMs / 1000 * bytesPerSecond
    }

    fun selectTrack(index: Int) = _state.update { it.copy(selectedTrackIndex = index) }
    fun selectLanguage(code: String) = _state.update { it.copy(language = code) }
    fun selectTask(task: TranscriptionTask) = _state.update { it.copy(task = task) }

    /** Creates the project and enqueues the job. Returns the new project id. */
    suspend fun start(): Long? {
        val current = _state.value
        val info = current.info ?: return null
        val track = current.selectedTrackIndex ?: return null
        val source = uri ?: return null

        val projectId = container.repository.createProject(
            uri = source.toString(),
            info = info,
            audioTrackIndex = track,
            language = current.language.takeUnless { it == Languages.AUTO },
            providerKey = current.providerKey,
            task = current.task,
        )
        if (current.language != settings.defaultLanguage) {
            container.settingsStore.setLanguage(current.language)
        }
        TranscriptionScheduler.start(
            context = getApplication(),
            projectId = projectId,
            requiresNetwork = current.sendsAudioOffDevice,
            requireUnmetered = settings.requireUnmetered,
        )
        return projectId
    }

    companion object {
        val Factory = viewModelFactory { initializer { ImportViewModel(app()) } }
    }
}

// ---- Processing -------------------------------------------------------------

class ProcessingViewModel(app: Application, private val projectId: Long) : ContainerViewModel(app) {

    val project: StateFlow<SubtitleProject?> =
        container.repository.observeProject(projectId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val cueCount: StateFlow<Int> =
        container.repository.observeCues(projectId).map { it.size }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val workInfo = TranscriptionScheduler.observe(app, projectId)

    val progress: StateFlow<WorkProgress?> = workInfo
        .map { info -> info?.progress?.let(WorkProgress::from) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val errorMessage: StateFlow<String?> = combine(project, workInfo) { p, info ->
        info?.outputData?.getString(WorkProgress.KEY_ERROR) ?: p?.errorMessage
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun cancel() = viewModelScope.launch {
        TranscriptionScheduler.cancel(getApplication(), projectId)
        container.repository.setStatus(projectId, ProjectStatus.PAUSED)
    }

    fun resume() = viewModelScope.launch {
        val settings = container.settingsStore.current()
        val sendsOffDevice = runCatching {
            container.providerFor(settings).capabilities.requiresNetwork
        }.getOrDefault(true)
        TranscriptionScheduler.resume(getApplication(), projectId, sendsOffDevice, settings.requireUnmetered)
    }

    fun retryFailed() = viewModelScope.launch {
        container.repository.retryFailedChunks(projectId)
        resume()
    }

    companion object {
        fun factory(projectId: Long) = viewModelFactory {
            initializer { ProcessingViewModel(app(), projectId) }
        }
    }
}

// ---- Editor -----------------------------------------------------------------

class EditorViewModel(app: Application, private val projectId: Long) : ContainerViewModel(app) {

    private val exporter = ExportSubtitles(app)
    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.repository.observeProject(projectId).collect { project ->
                _state.update {
                    it.copy(
                        projectName = project?.name.orEmpty(),
                        sourceUri = project?.sourceUri.orEmpty(),
                        translationLanguage = project?.translationLanguage,
                    )
                }
            }
        }
        viewModelScope.launch {
            container.settingsStore.settings.collect { settings ->
                _state.update { it.copy(translationTarget = settings.translationTarget) }
            }
        }
        viewModelScope.launch {
            container.repository.observeCues(projectId).collect { cues ->
                val hasTranslation = cues.any { !it.translatedText.isNullOrBlank() }
                val track = _state.value.track.takeIf { hasTranslation } ?: SubtitleTrack.ORIGINAL
                val preview = exporter.toPreviewFile(cues, projectId, track)
                _state.update {
                    it.copy(
                        cues = cues,
                        previewFile = preview,
                        hasTranslation = hasTranslation,
                        track = track,
                    )
                }
            }
        }
        viewModelScope.launch {
            TranslationScheduler.observe(getApplication(), projectId).collect { info ->
                val running = info?.state == androidx.work.WorkInfo.State.RUNNING ||
                    info?.state == androidx.work.WorkInfo.State.ENQUEUED
                val progress = info?.progress?.let(WorkProgress::from)
                _state.update {
                    it.copy(
                        translating = running,
                        translationProgress = progress
                            ?.takeIf { p -> p.chunkCount > 0 }
                            ?.let { p -> p.chunkIndex to p.chunkCount },
                    )
                }
            }
        }
    }

    fun selectTrack(track: SubtitleTrack) = viewModelScope.launch {
        val preview = exporter.toPreviewFile(_state.value.cues, projectId, track)
        _state.update { it.copy(track = track, previewFile = preview) }
    }

    fun openTranslateDialog(open: Boolean) = _state.update { it.copy(translateDialogOpen = open) }

    fun translate(target: String) = viewModelScope.launch {
        container.settingsStore.setTranslationTarget(target)
        val settings = container.settingsStore.current()
        TranslationScheduler.start(getApplication(), projectId, target, settings.requireUnmetered)
    }

    fun cancelTranslation() {
        TranslationScheduler.cancel(getApplication(), projectId)
    }

    fun setQuery(value: String) = _state.update { it.copy(query = value) }
    fun select(cue: Cue) = _state.update {
        it.copy(selectedCueId = if (it.selectedCueId == cue.id) null else cue.id)
    }
    fun onPosition(ms: Long) = _state.update { it.copy(playbackMs = ms) }
    fun seek(ms: Long) = _state.update { it.copy(seekTarget = ms) }
    fun openShiftDialog(open: Boolean) = _state.update { it.copy(shiftDialogOpen = open) }

    fun edit(cue: Cue) = viewModelScope.launch {
        container.repository.updateCue(cue.copy(endMs = maxOf(cue.endMs, cue.startMs + 100)))
    }

    fun delete(cue: Cue) = viewModelScope.launch { container.repository.deleteCue(cue.id) }

    fun split(cue: Cue) = viewModelScope.launch {
        val at = _state.value.playbackMs.takeIf { it in (cue.startMs + 100) until cue.endMs }
            ?: ((cue.startMs + cue.endMs) / 2)
        container.repository.splitCue(cue, at)
    }

    fun mergeWithNext(cue: Cue) = viewModelScope.launch {
        val cues = _state.value.cues
        val index = cues.indexOfFirst { it.id == cue.id }
        if (index in 0 until cues.lastIndex) {
            container.repository.mergeCues(cue, cues[index + 1])
        }
    }

    fun addCue() = viewModelScope.launch {
        val at = _state.value.playbackMs
        container.repository.insertCue(
            Cue(projectId = projectId, index = 0, startMs = at, endMs = at + 2000, text = "New subtitle")
        )
    }

    fun shiftAll(deltaMs: Long) = viewModelScope.launch {
        container.repository.shiftAll(projectId, deltaMs)
    }

    companion object {
        fun factory(projectId: Long) = viewModelFactory {
            initializer { EditorViewModel(app(), projectId) }
        }
    }
}

// ---- Settings ---------------------------------------------------------------

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val maskedKey: String? = null,
    val validating: Boolean = false,
    val validationMessage: String? = null,
    val cacheBytes: Long = 0,
)

class SettingsViewModel(app: Application) : ContainerViewModel(app) {

    private val _ui = MutableStateFlow(SettingsUiState())
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            container.settingsStore.settings.collect { s ->
                _ui.update {
                    it.copy(
                        settings = s,
                        maskedKey = container.apiKeyStore.masked(ApiKeyStore.OPENAI),
                        cacheBytes = container.cacheSizeBytes(),
                    )
                }
            }
        }
    }

    fun setApiKey(value: String) {
        container.apiKeyStore.put(ApiKeyStore.OPENAI, value.trim())
        _ui.update {
            it.copy(
                maskedKey = container.apiKeyStore.masked(ApiKeyStore.OPENAI),
                validationMessage = null,
            )
        }
    }

    fun clearApiKey() {
        container.apiKeyStore.put(ApiKeyStore.OPENAI, null)
        _ui.update { it.copy(maskedKey = null, validationMessage = null) }
    }

    fun validateKey() = viewModelScope.launch {
        _ui.update { it.copy(validating = true, validationMessage = null) }
        val provider = WhisperApiProvider(
            apiKeyProvider = { container.apiKeyStore.get(ApiKeyStore.OPENAI) },
            encoding = _ui.value.settings.uploadEncoding,
        )
        val result = provider.validate()
        _ui.update {
            it.copy(
                validating = false,
                validationMessage = result.fold(
                    onSuccess = { "Key works." },
                    onFailure = { t ->
                        (t as? nl.tippie.subtitle.transcription.TranscriptionException)?.error?.userMessage
                            ?: "Couldn't verify the key: ${t.message}"
                    }
                )
            )
        }
    }

    fun setProvider(key: String) = viewModelScope.launch { container.settingsStore.setProvider(key) }
    fun setLanguage(code: String) = viewModelScope.launch { container.settingsStore.setLanguage(code) }
    fun setChunkMinutes(value: Int) = viewModelScope.launch { container.settingsStore.setChunkMinutes(value) }
    fun setEncoding(value: ChunkEncoding) = viewModelScope.launch { container.settingsStore.setEncoding(value) }
    fun setParallel(value: Int) = viewModelScope.launch { container.settingsStore.setParallel(value) }
    fun setRequireUnmetered(value: Boolean) = viewModelScope.launch {
        container.settingsStore.setRequireUnmetered(value)
    }
    fun setStyle(style: SubtitleStyle) = viewModelScope.launch { container.settingsStore.setStyle(style) }
    fun setTranslationTarget(code: String) = viewModelScope.launch {
        container.settingsStore.setTranslationTarget(code)
    }
    fun setTranslationModel(model: String) = viewModelScope.launch {
        container.settingsStore.setTranslationModel(model)
    }
    fun setTranslationBatchSize(value: Int) = viewModelScope.launch {
        container.settingsStore.setTranslationBatchSize(value)
    }

    fun clearCache() = viewModelScope.launch {
        container.clearAllCache()
        _ui.update { it.copy(cacheBytes = container.cacheSizeBytes()) }
    }

    companion object {
        val Factory = viewModelFactory { initializer { SettingsViewModel(app()) } }
    }
}

// ---- Export -----------------------------------------------------------------

data class ExportUiState(
    val projectName: String = "",
    val cueCount: Int = 0,
    val format: SubtitleFormat = SubtitleFormat.SRT,
    val message: String? = null,
    val busy: Boolean = false,
    val canBurnIn: Boolean = false,
    val track: SubtitleTrack = SubtitleTrack.ORIGINAL,
    val hasTranslation: Boolean = false,
    val translationLanguage: String? = null,
    val untranslatedCount: Int = 0,
    /** Once the user chooses a track we stop auto-selecting one for them. */
    val userPickedTrack: Boolean = false,
)

class ExportViewModel(app: Application, private val projectId: Long) : ContainerViewModel(app) {

    private val exporter = ExportSubtitles(app)
    private val _ui = MutableStateFlow(ExportUiState())
    val ui: StateFlow<ExportUiState> = _ui.asStateFlow()

    private var cues: List<Cue> = emptyList()
    private var project: SubtitleProject? = null

    init {
        viewModelScope.launch {
            container.repository.observeProject(projectId).collect { p ->
                project = p
                _ui.update {
                    it.copy(
                        projectName = p?.name.orEmpty(),
                        canBurnIn = (p?.widthPx ?: 0) > 0,
                        translationLanguage = p?.translationLanguage,
                    )
                }
            }
        }
        viewModelScope.launch {
            container.repository.observeCues(projectId).collect { list ->
                cues = list
                val translated = list.count { !it.translatedText.isNullOrBlank() }
                _ui.update {
                    it.copy(
                        cueCount = list.size,
                        hasTranslation = translated > 0,
                        untranslatedCount = list.size - translated,
                        // Default to the translation when there is one: that is almost
                        // always why the user translated in the first place.
                        track = if (translated > 0 && !it.userPickedTrack) SubtitleTrack.TRANSLATION
                        else it.track,
                    )
                }
            }
        }
    }

    fun setFormat(format: SubtitleFormat) = _ui.update { it.copy(format = format) }

    fun setTrack(track: SubtitleTrack) = _ui.update {
        it.copy(track = track, userPickedTrack = true)
    }

    fun suggestedName(): String = exporter.suggestedFileName(
        _ui.value.projectName, _ui.value.format, _ui.value.track, _ui.value.translationLanguage
    )

    fun export(destination: Uri) = viewModelScope.launch {
        _ui.update { it.copy(busy = true, message = null) }
        val style = container.settingsStore.current().style
        val result = exporter.toUri(cues, _ui.value.format, destination, style, _ui.value.track)
        _ui.update {
            it.copy(
                busy = false,
                message = result.fold(
                    onSuccess = { "Exported ${cues.size} subtitles." },
                    onFailure = { t ->
                        (t as? nl.tippie.subtitle.domain.usecase.ExportException)?.error?.userMessage
                            ?: "Export failed: ${t.message}"
                    }
                )
            )
        }
    }

    fun burnIn(destination: Uri) {
        val request = androidx.work.OneTimeWorkRequestBuilder<nl.tippie.subtitle.work.BurnInWorker>()
            .setInputData(
                androidx.work.Data.Builder()
                    .putLong(nl.tippie.subtitle.work.BurnInWorker.KEY_PROJECT_ID, projectId)
                    .putString(nl.tippie.subtitle.work.BurnInWorker.KEY_DESTINATION_URI, destination.toString())
                    .putString(nl.tippie.subtitle.work.BurnInWorker.KEY_TRACK, _ui.value.track.name)
                    .build()
            )
            .build()
        androidx.work.WorkManager.getInstance(getApplication())
            .enqueueUniqueWork(
                nl.tippie.subtitle.work.BurnInWorker.uniqueName(projectId),
                androidx.work.ExistingWorkPolicy.REPLACE,
                request,
            )
        _ui.update {
            it.copy(message = "Rendering started — you can leave this screen, progress shows in the notification.")
        }
    }

    companion object {
        fun factory(projectId: Long) = viewModelFactory {
            initializer { ExportViewModel(app(), projectId) }
        }
    }
}

private fun CreationExtras.app(): Application =
    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
