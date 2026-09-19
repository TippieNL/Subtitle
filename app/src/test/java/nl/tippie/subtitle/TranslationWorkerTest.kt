package nl.tippie.subtitle

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import nl.tippie.subtitle.data.ProjectRepository
import nl.tippie.subtitle.data.db.SubtitleDatabase
import nl.tippie.subtitle.data.prefs.AppSettings
import nl.tippie.subtitle.domain.model.AudioTrackInfo
import nl.tippie.subtitle.domain.model.Cue
import nl.tippie.subtitle.domain.model.MediaInfo
import nl.tippie.subtitle.domain.model.ProcessingError
import nl.tippie.subtitle.translation.TranslationCapabilities
import nl.tippie.subtitle.translation.TranslationException
import nl.tippie.subtitle.translation.TranslationProvider
import nl.tippie.subtitle.translation.TranslationProviderId
import nl.tippie.subtitle.translation.TranslationRequest
import nl.tippie.subtitle.work.TranslationWorker
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs [TranslationWorker] end to end against a real database and a fake provider.
 *
 * The unit tests cover the batching algorithm; this covers the wiring around it — that
 * translations land on the right cues, that the original text is untouched, and that
 * re-running only retries what failed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslationWorkerTest {

    private lateinit var context: Context
    private lateinit var db: SubtitleDatabase
    private lateinit var repository: ProjectRepository
    private var projectId = 0L

    /** Translates by prefixing, or fails for lines matching [failOn]. */
    private class FakeProvider(
        private val failOn: (String) -> Boolean = { false },
    ) : TranslationProvider {
        var requestCount = 0
        override val id = TranslationProviderId.OPENAI_CHAT
        override val capabilities = TranslationCapabilities(true, true, "fake", listOf("en"))
        override suspend fun isAvailable() = true
        override suspend fun translate(request: TranslationRequest): Result<List<String>> {
            requestCount++
            if (request.lines.any(failOn)) {
                return Result.failure(
                    TranslationException(ProcessingError.TranslationMiscount(request.lines.size, 0), true)
                )
            }
            return Result.success(request.lines.map { "EN:$it" })
        }
    }

    private class TestContainer(
        context: Context,
        override val database: SubtitleDatabase,
        private val provider: TranslationProvider,
    ) : AppContainer(context) {
        override val repository: ProjectRepository by lazy { ProjectRepository(database) }
        override fun translationProviderFor(settings: AppSettings): TranslationProvider = provider
    }

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, SubtitleDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ProjectRepository(db)
        projectId = repository.createProject(
            uri = "content://media/1",
            info = MediaInfo("clip.mp4", 1_000, 30_000, 1920, 1080,
                listOf(AudioTrackInfo(0, "audio/mp4a-latm", 48_000, 2, "nl", 128_000)), true),
            audioTrackIndex = 0,
            language = "nl",
            providerKey = "openai_whisper",
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedCues(count: Int) {
        repeat(count) { i ->
            repository.insertCue(
                Cue(projectId = projectId, index = i, startMs = i * 2_000L,
                    endMs = i * 2_000L + 1_800, text = "regel $i")
            )
        }
    }

    private suspend fun runWorker(provider: TranslationProvider): ListenableWorker.Result {
        val container = TestContainer(context, db, provider)
        val worker = TestListenableWorkerBuilder<TranslationWorker>(context)
            .setInputData(
                Data.Builder()
                    .putLong(TranslationWorker.KEY_PROJECT_ID, projectId)
                    .putString(TranslationWorker.KEY_TARGET_LANGUAGE, "en")
                    .build()
            )
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = TranslationWorker(appContext, workerParameters, container)
            })
            .build()
        return worker.doWork()
    }

    @Test
    fun `every cue gets a translation and the original survives`() = runTest {
        seedCues(25)

        val result = runWorker(FakeProvider())

        assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
        val cues = repository.getCues(projectId)
        assertThat(cues).hasSize(25)
        cues.forEachIndexed { i, cue ->
            assertThat(cue.text).isEqualTo("regel $i")
            assertThat(cue.translatedText).isEqualTo("EN:regel $i")
        }
    }

    @Test
    fun `translations land on the matching cue, not shifted by one`() = runTest {
        seedCues(45)
        runWorker(FakeProvider())

        // Every translation must correspond to its own source line. A batching bug shows
        // up here as an off-by-one that would desync the whole file.
        repository.getCues(projectId).forEach { cue ->
            assertThat(cue.translatedText).isEqualTo("EN:${cue.text}")
        }
    }

    @Test
    fun `the project records the target language and the translated count`() = runTest {
        seedCues(10)
        runWorker(FakeProvider())

        val project = repository.getProject(projectId)
        assertThat(project!!.translationLanguage).isEqualTo("en")
        assertThat(project.translatedCues).isEqualTo(10)
    }

    @Test
    fun `a project with no cues fails with a specific message`() = runTest {
        val result = runWorker(FakeProvider())

        assertThat(result).isInstanceOf(ListenableWorker.Result.Failure::class.java)
        val output = (result as ListenableWorker.Result.Failure).outputData
        assertThat(output.getString(nl.tippie.subtitle.work.WorkProgress.KEY_ERROR))
            .contains("no subtitles to translate")
    }

    @Test
    fun `a line that cannot be translated leaves its neighbours translated`() = runTest {
        seedCues(12)
        // "regel 5" always fails, even alone.
        runWorker(FakeProvider(failOn = { it == "regel 5" }))

        val cues = repository.getCues(projectId)
        assertThat(cues.first { it.text == "regel 5" }.translatedText).isNull()
        assertThat(cues.filter { it.text != "regel 5" }.all { it.translatedText != null }).isTrue()
    }

    @Test
    fun `re-running only translates the lines that are still missing`() = runTest {
        seedCues(12)
        runWorker(FakeProvider(failOn = { it == "regel 5" }))

        // Second pass with a provider that can handle everything.
        val second = FakeProvider()
        runWorker(second)

        val cues = repository.getCues(projectId)
        assertThat(cues.all { it.translatedText != null }).isTrue()
        // Only the one missing line was sent, not all twelve.
        assertThat(second.requestCount).isEqualTo(1)
    }

    @Test
    fun `switching target language discards the previous translation`() = runTest {
        seedCues(5)
        runWorker(FakeProvider())
        assertThat(repository.getCues(projectId).all { it.translatedText != null }).isTrue()

        // Re-run targeting German: the English text must not survive as if it were German.
        val container = TestContainer(context, db, FakeProvider())
        val worker = TestListenableWorkerBuilder<TranslationWorker>(context)
            .setInputData(
                Data.Builder()
                    .putLong(TranslationWorker.KEY_PROJECT_ID, projectId)
                    .putString(TranslationWorker.KEY_TARGET_LANGUAGE, "de")
                    .build()
            )
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = TranslationWorker(appContext, workerParameters, container)
            })
            .build()
        worker.doWork()

        val project = repository.getProject(projectId)
        assertThat(project!!.translationLanguage).isEqualTo("de")
        assertThat(repository.getCues(projectId).all { it.translatedText != null }).isTrue()
    }

    @Test
    fun `translated text is re-wrapped to subtitle line lengths`() = runTest {
        repository.insertCue(
            Cue(projectId = projectId, index = 0, startMs = 0, endMs = 4_000, text = "kort")
        )
        val longProvider = object : TranslationProvider {
            override val id = TranslationProviderId.OPENAI_CHAT
            override val capabilities = TranslationCapabilities(true, true, "fake", listOf("en"))
            override suspend fun isAvailable() = true
            override suspend fun translate(request: TranslationRequest) = Result.success(
                request.lines.map { "This translation is considerably longer than the source line was." }
            )
        }
        runWorker(longProvider)

        val cue = repository.getCues(projectId).single()
        assertThat(cue.translatedText).isNotNull()
        cue.translatedText!!.split("\n").forEach {
            assertThat(it.length).isAtMost(42)
        }
    }
}
