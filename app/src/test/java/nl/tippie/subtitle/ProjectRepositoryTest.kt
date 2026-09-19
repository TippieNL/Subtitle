package nl.tippie.subtitle

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import nl.tippie.subtitle.data.ProjectRepository
import nl.tippie.subtitle.data.db.SubtitleDatabase
import nl.tippie.subtitle.domain.model.AudioTrackInfo
import nl.tippie.subtitle.domain.model.Cue
import nl.tippie.subtitle.domain.model.MediaInfo
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the cue-editing operations against a real (in-memory) database.
 *
 * These are the operations a user triggers by hand in the editor, on data that took real
 * money to produce, so the invariants that matter are: no text is ever lost, cues stay in
 * order, and timings never invert.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProjectRepositoryTest {

    private lateinit var db: SubtitleDatabase
    private lateinit var repository: ProjectRepository
    private var projectId = 0L

    @Before
    fun setUp() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, SubtitleDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ProjectRepository(db)
        projectId = repository.createProject(
            uri = "content://media/1",
            info = MediaInfo(
                displayName = "clip.mp4",
                sizeBytes = 1_000_000,
                durationMs = 60_000,
                widthPx = 1920,
                heightPx = 1080,
                audioTracks = listOf(AudioTrackInfo(0, "audio/mp4a-latm", 48_000, 2, "nl", 128_000)),
                hasVideoTrack = true,
            ),
            audioTrackIndex = 0,
            language = "nl",
            providerKey = "openai_whisper",
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun addCue(
        startMs: Long,
        endMs: Long,
        text: String,
        translated: String? = null,
    ): Cue {
        repository.insertCue(
            Cue(projectId = projectId, index = 0, startMs = startMs, endMs = endMs,
                text = text, translatedText = translated)
        )
        return repository.getCues(projectId).last()
    }

    // ---- split --------------------------------------------------------------

    @Test
    fun `splitting a cue keeps every word and splits the time`() = runTest {
        addCue(1_000, 5_000, "first half of it and second half of it")
        val cue = repository.getCues(projectId).single()

        repository.splitCue(cue, atMs = 3_000)

        val after = repository.getCues(projectId)
        assertThat(after).hasSize(2)
        assertThat(after[0].startMs).isEqualTo(1_000L)
        assertThat(after[0].endMs).isEqualTo(3_000L)
        assertThat(after[1].startMs).isEqualTo(3_000L)
        assertThat(after[1].endMs).isEqualTo(5_000L)

        val words = after.joinToString(" ") { it.text }.split(' ').filter { it.isNotBlank() }
        assertThat(words).isEqualTo("first half of it and second half of it".split(' '))
    }

    @Test
    fun `splitting never breaks a word in half`() = runTest {
        addCue(0, 4_000, "alpha bravo charlie delta")
        val cue = repository.getCues(projectId).single()
        repository.splitCue(cue, atMs = 1_700)

        repository.getCues(projectId).forEach { c ->
            c.text.split(' ').filter { it.isNotBlank() }.forEach { word ->
                assertThat("alpha bravo charlie delta".split(' ')).contains(word)
            }
        }
    }

    @Test
    fun `splitting a one-character cue does not crash`() = runTest {
        // coerceIn(1, length - 1) is an empty range when length is 1.
        addCue(0, 2_000, "a")
        val cue = repository.getCues(projectId).single()
        repository.splitCue(cue, atMs = 1_000)
        assertThat(repository.getCues(projectId).joinToString("") { it.text }).contains("a")
    }

    @Test
    fun `splitting a very short cue does not crash`() = runTest {
        // endMs - 1 < startMs + 1, so coerceIn gets an empty range.
        addCue(1_000, 1_001, "hi there")
        val cue = repository.getCues(projectId).single()
        repository.splitCue(cue, atMs = 1_000)
        assertThat(repository.getCues(projectId)).isNotEmpty()
    }

    @Test
    fun `splitting a translated cue does not duplicate the translation`() = runTest {
        // The whole-cue translation cannot be cut at the same proportion — word order
        // differs between languages — so copying it onto both halves would export the
        // same English sentence twice.
        addCue(0, 4_000, "eerste helft en tweede helft", "first half and second half")
        val cue = repository.getCues(projectId).single()
        repository.splitCue(cue, atMs = 2_000)

        val after = repository.getCues(projectId)
        assertThat(after).hasSize(2)
        val translations = after.mapNotNull { it.translatedText }.filter { it.isNotBlank() }
        assertThat(translations).doesNotContain("first half and second half")
    }

    // ---- merge --------------------------------------------------------------

    @Test
    fun `merging joins text and spans both time ranges`() = runTest {
        addCue(1_000, 2_000, "first part")
        addCue(2_200, 4_000, "second part")
        val cues = repository.getCues(projectId)

        repository.mergeCues(cues[0], cues[1])

        val after = repository.getCues(projectId)
        assertThat(after).hasSize(1)
        assertThat(after[0].startMs).isEqualTo(1_000L)
        assertThat(after[0].endMs).isEqualTo(4_000L)
        assertThat(after[0].text).isEqualTo("first part second part")
    }

    @Test
    fun `merging keeps both translations instead of silently dropping one`() = runTest {
        addCue(0, 2_000, "eerste", "first")
        addCue(2_100, 4_000, "tweede", "second")
        val cues = repository.getCues(projectId)

        repository.mergeCues(cues[0], cues[1])

        val merged = repository.getCues(projectId).single()
        assertThat(merged.translatedText).isNotNull()
        assertThat(merged.translatedText).contains("first")
        assertThat(merged.translatedText).contains("second")
    }

    // ---- global shift -------------------------------------------------------

    @Test
    fun `shifting forward moves every cue by the delta`() = runTest {
        addCue(1_000, 2_000, "a")
        addCue(3_000, 4_000, "b")

        repository.shiftAll(projectId, 1_500)

        val after = repository.getCues(projectId)
        assertThat(after.map { it.startMs }).containsExactly(2_500L, 4_500L).inOrder()
        assertThat(after.map { it.endMs }).containsExactly(3_500L, 5_500L).inOrder()
    }

    @Test
    fun `shifting backwards past zero preserves gaps instead of collapsing cues`() = runTest {
        // Clamping each cue independently at 0 would stack several cues at 0 with
        // overlapping ranges, which is an invalid subtitle file.
        addCue(1_000, 2_000, "a")
        addCue(3_000, 4_000, "b")
        addCue(5_000, 6_000, "c")

        repository.shiftAll(projectId, -10_000)

        val after = repository.getCues(projectId)
        assertThat(after.first().startMs).isAtLeast(0L)
        // Relative spacing must be untouched.
        assertThat(after[1].startMs - after[0].startMs).isEqualTo(2_000L)
        assertThat(after[2].startMs - after[1].startMs).isEqualTo(2_000L)
        // Durations preserved, nothing inverted.
        after.forEach { assertThat(it.endMs).isGreaterThan(it.startMs) }
        after.forEach { assertThat(it.endMs - it.startMs).isEqualTo(1_000L) }
    }

    @Test
    fun `shifting leaves cues ordered and non-overlapping`() = runTest {
        addCue(500, 1_400, "a")
        addCue(1_500, 2_400, "b")

        repository.shiftAll(projectId, -5_000)

        val after = repository.getCues(projectId)
        for (i in 1 until after.size) {
            assertThat(after[i].startMs).isAtLeast(after[i - 1].endMs)
        }
    }

    // ---- translation bookkeeping -------------------------------------------

    @Test
    fun `clearing translations leaves the original text alone`() = runTest {
        addCue(0, 1_000, "origineel", "original")
        repository.clearTranslations(projectId)

        val cue = repository.getCues(projectId).single()
        assertThat(cue.text).isEqualTo("origineel")
        assertThat(cue.translatedText).isNull()
    }

    @Test
    fun `untranslated query returns only cues without a translation`() = runTest {
        addCue(0, 1_000, "een", "one")
        addCue(1_100, 2_000, "twee")
        addCue(2_100, 3_000, "drie", "")

        val pending = db.cueDao().untranslated(projectId)
        assertThat(pending.map { it.text }).containsExactly("twee", "drie").inOrder()
        assertThat(db.cueDao().translatedCount(projectId)).isEqualTo(1)
    }

    @Test
    fun `deleting a project removes its cues and chunks`() = runTest {
        addCue(0, 1_000, "a")
        repository.deleteProject(projectId)
        assertThat(repository.getCues(projectId)).isEmpty()
        assertThat(db.chunkDao().forProject(projectId)).isEmpty()
    }
}
