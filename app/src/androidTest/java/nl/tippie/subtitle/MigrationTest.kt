package nl.tippie.subtitle

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.tippie.subtitle.data.db.SubtitleDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that upgrading an existing install keeps its data.
 *
 * This matters more than usual here: a v1 database can hold hours of paid transcription,
 * so a destructive migration would be an expensive bug. Run with
 * `./gradlew :app:connectedDebugAndroidTest` on a device or emulator.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SubtitleDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2_keepsProjectsAndCues() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                """INSERT INTO projects
                   (id, name, sourceUri, durationMs, sizeBytes, widthPx, heightPx,
                    audioTrackIndex, language, detectedLanguage, providerKey, status,
                    processedMs, totalChunks, completedChunks, errorMessage, createdAt, updatedAt)
                   VALUES (1, 'film.mkv', 'content://x', 7200000, 4000000000, 1920, 1080,
                           0, 'nl', 'dutch', 'openai_whisper', 'COMPLETED',
                           7200000, 24, 24, NULL, 1, 2)"""
            )
            execSQL(
                """INSERT INTO cues (id, projectId, startMs, endMs, text, chunkIndex)
                   VALUES (1, 1, 1000, 3000, 'Goedemorgen', 0)"""
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 2, true, SubtitleDatabase.MIGRATION_1_2
        )

        db.query("SELECT text, translatedText FROM cues WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Goedemorgen", cursor.getString(0))
            assertTrue("new column must start null", cursor.isNull(1))
        }

        db.query("SELECT task, translationLanguage, translatedCues FROM projects WHERE id = 1")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("TRANSCRIBE", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertEquals(0, cursor.getInt(2))
            }
    }

    private companion object { const val TEST_DB = "migration-test.db" }
}
