package nl.tippie.subtitle.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sourceUri: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val widthPx: Int?,
    val heightPx: Int?,
    val audioTrackIndex: Int,
    val language: String?,
    val detectedLanguage: String?,
    val providerKey: String,
    val status: String,
    // Defaults are declared on the column, not just the constructor, so the schema and
    // MIGRATION_1_2 state the same thing and Room can verify it.
    @ColumnInfo(defaultValue = "TRANSCRIBE") val task: String = "TRANSCRIBE",
    val translationLanguage: String? = null,
    @ColumnInfo(defaultValue = "0") val translatedCues: Int = 0,
    val processedMs: Long = 0,
    val totalChunks: Int = 0,
    val completedChunks: Int = 0,
    val errorMessage: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "chunks",
    foreignKeys = [ForeignKey(
        entity = ProjectEntity::class,
        parentColumns = ["id"],
        childColumns = ["projectId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("projectId"), Index(value = ["projectId", "chunkIndex"], unique = true)]
)
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val chunkIndex: Int,
    val startMs: Long,
    val endMs: Long,
    /** Milliseconds of audio at the head of this chunk that repeat the previous chunk's tail. */
    val overlapMs: Long,
    val boundaryKind: String,
    val state: String,
    val filePath: String?,
    val attempts: Int = 0,
    val lastError: String? = null,
    /** Tail of this chunk's transcript, fed to the next chunk as a context prompt. */
    val transcriptTail: String? = null,
)

@Entity(
    tableName = "cues",
    foreignKeys = [ForeignKey(
        entity = ProjectEntity::class,
        parentColumns = ["id"],
        childColumns = ["projectId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("projectId"), Index(value = ["projectId", "startMs"])]
)
data class CueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val chunkIndex: Int,
    val translatedText: String? = null,
)
