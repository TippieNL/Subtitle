package nl.tippie.subtitle.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProjectEntity::class, ChunkEntity::class, CueEntity::class],
    version = 2,
    exportSchema = true
)
abstract class SubtitleDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun chunkDao(): ChunkDao
    abstract fun cueDao(): CueDao

    companion object {

        /**
         * Adds the translation columns. Purely additive with defaults, so existing
         * projects keep their cues — destructive migration would throw away hours of
         * transcription for a feature the user has not even used yet.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cues ADD COLUMN translatedText TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE projects ADD COLUMN task TEXT NOT NULL DEFAULT 'TRANSCRIBE'")
                db.execSQL("ALTER TABLE projects ADD COLUMN translationLanguage TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE projects ADD COLUMN translatedCues INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun build(context: Context): SubtitleDatabase =
            Room.databaseBuilder(context, SubtitleDatabase::class.java, "subtitle.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
