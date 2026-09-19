package nl.tippie.subtitle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import nl.tippie.subtitle.data.db.SubtitleDatabase
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Upgrades a genuine v1 database to v2 and checks the data survives.
 *
 * This matters more than a typical migration test: a v1 database can hold hours of paid
 * transcription, so a destructive migration would be an expensive, irreversible bug.
 *
 * The v1 database is created from the DDL Room itself exported for schema 1, then opened
 * through the production [SubtitleDatabase.build] path — so Room's own schema validation
 * runs, and a migration that produces tables not matching the v2 entities fails here
 * rather than on a user's phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var database: SubtitleDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        context.getDatabasePath(DB_NAME).delete()
    }

    private fun createV1DatabaseWithData() {
        context.getDatabasePath(DB_NAME).parentFile?.mkdirs()
        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath(DB_NAME), null
        )
        db.use { v1 ->
            v1.execSQL(V1_PROJECTS)
            v1.execSQL(V1_CHUNKS)
            v1.execSQL(V1_CUES)
            v1.execSQL("CREATE INDEX IF NOT EXISTS `index_chunks_projectId` ON `chunks` (`projectId`)")
            v1.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_chunks_projectId_chunkIndex` ON `chunks` (`projectId`, `chunkIndex`)")
            v1.execSQL("CREATE INDEX IF NOT EXISTS `index_cues_projectId` ON `cues` (`projectId`)")
            v1.execSQL("CREATE INDEX IF NOT EXISTS `index_cues_projectId_startMs` ON `cues` (`projectId`, `startMs`)")

            // Room's own bookkeeping table; without the right identity hash Room refuses
            // to treat this as a valid version 1 database.
            v1.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            v1.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, '$V1_IDENTITY_HASH')")
            v1.version = 1

            v1.execSQL(
                """INSERT INTO projects
                   (id, name, sourceUri, durationMs, sizeBytes, widthPx, heightPx,
                    audioTrackIndex, language, detectedLanguage, providerKey, status,
                    processedMs, totalChunks, completedChunks, errorMessage, createdAt, updatedAt)
                   VALUES (1, 'film.mkv', 'content://media/1', 7200000, 4000000000, 1920, 1080,
                           0, 'nl', 'dutch', 'openai_whisper', 'COMPLETED',
                           7200000, 24, 24, NULL, 100, 200)"""
            )
            v1.execSQL(
                """INSERT INTO cues (id, projectId, startMs, endMs, text, chunkIndex)
                   VALUES (1, 1, 1000, 3000, 'Goedemorgen allemaal', 0),
                          (2, 1, 3200, 5400, 'Vandaag bespreken we iets anders', 0)"""
            )
            v1.execSQL(
                """INSERT INTO chunks
                   (id, projectId, chunkIndex, startMs, endMs, overlapMs, boundaryKind,
                    state, filePath, attempts, lastError, transcriptTail)
                   VALUES (1, 1, 0, 0, 300000, 0, 'SILENCE', 'TRANSCRIBED', NULL, 1, NULL, 'iets anders')"""
            )
        }
    }

    @Test
    fun `v1 database upgrades to v2 and Room accepts the resulting schema`() {
        createV1DatabaseWithData()
        // Opening through the production builder runs MIGRATION_1_2 and then Room's
        // schema validation. No exception here is the core assertion.
        database = SubtitleDatabase.build(context)
        assertThat(database!!.openHelper.writableDatabase.version).isEqualTo(2)
    }

    @Test
    fun `existing cues survive the upgrade with their text and timings intact`() = runTest {
        createV1DatabaseWithData()
        database = SubtitleDatabase.build(context)

        val cues = database!!.cueDao().forProject(1)
        assertThat(cues).hasSize(2)
        assertThat(cues.map { it.text })
            .containsExactly("Goedemorgen allemaal", "Vandaag bespreken we iets anders").inOrder()
        assertThat(cues.map { it.startMs }).containsExactly(1000L, 3200L).inOrder()
        assertThat(cues.map { it.endMs }).containsExactly(3000L, 5400L).inOrder()
    }

    @Test
    fun `the new translation column starts empty rather than blank text`() = runTest {
        createV1DatabaseWithData()
        database = SubtitleDatabase.build(context)

        // Null, not "" — the untranslated queries key off IS NULL, and an empty string
        // would make every pre-existing cue look already translated.
        assertThat(database!!.cueDao().forProject(1).map { it.translatedText })
            .containsExactly(null, null)
        assertThat(database!!.cueDao().translatedCount(1)).isEqualTo(0)
        assertThat(database!!.cueDao().untranslated(1)).hasSize(2)
    }

    @Test
    fun `migrated projects default to transcribing, not translating`() = runTest {
        createV1DatabaseWithData()
        database = SubtitleDatabase.build(context)

        val project = database!!.projectDao().get(1)
        assertThat(project).isNotNull()
        assertThat(project!!.task).isEqualTo("TRANSCRIBE")
        assertThat(project.translationLanguage).isNull()
        assertThat(project.translatedCues).isEqualTo(0)
        // Untouched v1 data.
        assertThat(project.name).isEqualTo("film.mkv")
        assertThat(project.durationMs).isEqualTo(7_200_000L)
        assertThat(project.sizeBytes).isEqualTo(4_000_000_000L)
    }

    @Test
    fun `chunk bookkeeping survives so a paused job can still resume`() = runTest {
        createV1DatabaseWithData()
        database = SubtitleDatabase.build(context)

        val chunks = database!!.chunkDao().forProject(1)
        assertThat(chunks).hasSize(1)
        assertThat(chunks[0].state).isEqualTo("TRANSCRIBED")
        assertThat(chunks[0].transcriptTail).isEqualTo("iets anders")
        assertThat(database!!.chunkDao().completedCount(1)).isEqualTo(1)
    }

    @Test
    fun `a fresh install creates v2 directly`() = runTest {
        database = SubtitleDatabase.build(context)
        assertThat(database!!.openHelper.writableDatabase.version).isEqualTo(2)
        assertThat(database!!.projectDao().get(1)).isNull()
    }

    private companion object {
        const val DB_NAME = "subtitle.db"

        /** From app/schemas/…/1.json — Room rejects the database without the exact hash. */
        const val V1_IDENTITY_HASH = "80540048022e46b3b12a87eaece50ef9"

        const val V1_PROJECTS = "CREATE TABLE IF NOT EXISTS `projects` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `sourceUri` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `sizeBytes` INTEGER NOT NULL, `widthPx` INTEGER, `heightPx` INTEGER, `audioTrackIndex` INTEGER NOT NULL, `language` TEXT, `detectedLanguage` TEXT, `providerKey` TEXT NOT NULL, `status` TEXT NOT NULL, `processedMs` INTEGER NOT NULL, `totalChunks` INTEGER NOT NULL, `completedChunks` INTEGER NOT NULL, `errorMessage` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)"
        const val V1_CHUNKS = "CREATE TABLE IF NOT EXISTS `chunks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `projectId` INTEGER NOT NULL, `chunkIndex` INTEGER NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, `overlapMs` INTEGER NOT NULL, `boundaryKind` TEXT NOT NULL, `state` TEXT NOT NULL, `filePath` TEXT, `attempts` INTEGER NOT NULL, `lastError` TEXT, `transcriptTail` TEXT, FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        const val V1_CUES = "CREATE TABLE IF NOT EXISTS `cues` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `projectId` INTEGER NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, `text` TEXT NOT NULL, `chunkIndex` INTEGER NOT NULL, FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
    }
}
