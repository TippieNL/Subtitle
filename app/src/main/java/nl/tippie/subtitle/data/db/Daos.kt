package nl.tippie.subtitle.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Insert suspend fun insert(project: ProjectEntity): Long
    @Update suspend fun update(project: ProjectEntity)
    @Query("DELETE FROM projects WHERE id = :id") suspend fun delete(id: Long)

    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observe(id: Long): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: Long): ProjectEntity?

    @Query("UPDATE projects SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE projects SET status = :status, errorMessage = :message, updatedAt = :now WHERE id = :id")
    suspend fun setError(id: Long, status: String, message: String?, now: Long = System.currentTimeMillis())

    @Query(
        """UPDATE projects SET processedMs = :processedMs, completedChunks = :completed,
           totalChunks = :total, updatedAt = :now WHERE id = :id"""
    )
    suspend fun setProgress(
        id: Long, processedMs: Long, completed: Int, total: Int,
        now: Long = System.currentTimeMillis()
    )

    @Query("UPDATE projects SET detectedLanguage = :lang WHERE id = :id AND detectedLanguage IS NULL")
    suspend fun setDetectedLanguage(id: Long, lang: String?)

    @Query(
        """UPDATE projects SET translationLanguage = :language, translatedCues = :translated,
           updatedAt = :now WHERE id = :id"""
    )
    suspend fun setTranslationProgress(
        id: Long, language: String?, translated: Int, now: Long = System.currentTimeMillis()
    )
}

@Dao
interface ChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<ChunkEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(chunk: ChunkEntity): Long

    @Update suspend fun update(chunk: ChunkEntity)

    @Query("SELECT * FROM chunks WHERE projectId = :projectId ORDER BY chunkIndex")
    suspend fun forProject(projectId: Long): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE projectId = :projectId AND chunkIndex = :index")
    suspend fun get(projectId: Long, index: Int): ChunkEntity?

    @Query("SELECT * FROM chunks WHERE projectId = :projectId AND state != 'TRANSCRIBED' ORDER BY chunkIndex")
    suspend fun pending(projectId: Long): List<ChunkEntity>

    @Query("SELECT COUNT(*) FROM chunks WHERE projectId = :projectId AND state = 'TRANSCRIBED'")
    suspend fun completedCount(projectId: Long): Int

    @Query("SELECT COUNT(*) FROM chunks WHERE projectId = :projectId")
    suspend fun totalCount(projectId: Long): Int

    @Query("SELECT COUNT(*) FROM chunks WHERE projectId = :projectId AND state = 'FAILED'")
    suspend fun failedCount(projectId: Long): Int

    @Query("DELETE FROM chunks WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: Long)

    @Query("UPDATE chunks SET state = 'PENDING', attempts = 0, lastError = NULL WHERE projectId = :projectId AND state = 'FAILED'")
    suspend fun resetFailed(projectId: Long)
}

@Dao
interface CueDao {
    @Insert suspend fun insertAll(cues: List<CueEntity>): List<Long>
    @Insert suspend fun insert(cue: CueEntity): Long
    @Update suspend fun update(cue: CueEntity)
    @Query("DELETE FROM cues WHERE id = :id") suspend fun delete(id: Long)

    @Query("SELECT * FROM cues WHERE projectId = :projectId ORDER BY startMs, id")
    fun observeForProject(projectId: Long): Flow<List<CueEntity>>

    @Query("SELECT * FROM cues WHERE projectId = :projectId ORDER BY startMs, id")
    suspend fun forProject(projectId: Long): List<CueEntity>

    @Query("SELECT * FROM cues WHERE projectId = :projectId AND chunkIndex = :chunkIndex ORDER BY startMs")
    suspend fun forChunk(projectId: Long, chunkIndex: Int): List<CueEntity>

    @Query("DELETE FROM cues WHERE projectId = :projectId AND chunkIndex = :chunkIndex")
    suspend fun deleteForChunk(projectId: Long, chunkIndex: Int)

    @Query("DELETE FROM cues WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: Long)

    @Query("SELECT COUNT(*) FROM cues WHERE projectId = :projectId")
    suspend fun count(projectId: Long): Int

    // ---- translation -------------------------------------------------------

    /** Cues still needing translation, oldest first. Resume simply re-reads this. */
    @Query(
        """SELECT * FROM cues WHERE projectId = :projectId
           AND (translatedText IS NULL OR translatedText = '')
           ORDER BY startMs, id"""
    )
    suspend fun untranslated(projectId: Long): List<CueEntity>

    @Query("SELECT COUNT(*) FROM cues WHERE projectId = :projectId AND translatedText IS NOT NULL AND translatedText != ''")
    suspend fun translatedCount(projectId: Long): Int

    @Query("UPDATE cues SET translatedText = :text WHERE id = :id")
    suspend fun setTranslation(id: Long, text: String?)

    @Query("UPDATE cues SET translatedText = NULL WHERE projectId = :projectId")
    suspend fun clearTranslations(projectId: Long)
}
