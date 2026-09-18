package nl.tippie.subtitle.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ProjectEntity::class, ChunkEntity::class, CueEntity::class],
    version = 1,
    exportSchema = true
)
abstract class SubtitleDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun chunkDao(): ChunkDao
    abstract fun cueDao(): CueDao

    companion object {
        fun build(context: Context): SubtitleDatabase =
            Room.databaseBuilder(context, SubtitleDatabase::class.java, "subtitle.db")
                .build()
    }
}
