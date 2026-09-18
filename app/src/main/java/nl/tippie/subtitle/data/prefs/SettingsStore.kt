package nl.tippie.subtitle.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import nl.tippie.subtitle.domain.model.ProviderId
import nl.tippie.subtitle.domain.model.SubtitleStyle
import nl.tippie.subtitle.media.sink.ChunkEncoding
import nl.tippie.subtitle.subtitle.SubtitleTranslator
import nl.tippie.subtitle.translation.openai.OpenAiTranslationProvider

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

data class AppSettings(
    val providerKey: String = ProviderId.OPENAI_WHISPER.key,
    val defaultLanguage: String = "auto",
    val chunkMinutes: Int = 5,
    val uploadEncoding: ChunkEncoding = ChunkEncoding.M4A,
    val parallelRequests: Int = 2,
    val wordTimestamps: Boolean = false,
    val cloudUploadAcknowledged: Boolean = false,
    val requireUnmetered: Boolean = false,
    val style: SubtitleStyle = SubtitleStyle(),
    // --- translation ---
    val translationTarget: String = "en",
    val translationModel: String = OpenAiTranslationProvider.DEFAULT_MODEL,
    val translationBatchSize: Int = SubtitleTranslator.DEFAULT_BATCH_SIZE,
) {
    val targetChunkMs: Long get() = chunkMinutes.coerceIn(1, 20) * 60_000L
}

class SettingsStore(private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            providerKey = p[PROVIDER] ?: ProviderId.OPENAI_WHISPER.key,
            defaultLanguage = p[LANGUAGE] ?: "auto",
            chunkMinutes = p[CHUNK_MINUTES] ?: 5,
            uploadEncoding = runCatching { ChunkEncoding.valueOf(p[ENCODING] ?: "M4A") }
                .getOrDefault(ChunkEncoding.M4A),
            parallelRequests = p[PARALLEL] ?: 2,
            wordTimestamps = p[WORD_TS] ?: false,
            cloudUploadAcknowledged = p[CLOUD_ACK] ?: false,
            requireUnmetered = p[UNMETERED] ?: false,
            style = SubtitleStyle(
                fontSizeSp = p[FONT_SIZE] ?: 20f,
                textColor = p[TEXT_COLOR] ?: 0xFFFFFFFF,
                backgroundColor = p[BG_COLOR] ?: 0x99000000,
                outline = p[OUTLINE] ?: true,
                verticalPosition = runCatching {
                    SubtitleStyle.VerticalPosition.valueOf(p[POSITION] ?: "BOTTOM")
                }.getOrDefault(SubtitleStyle.VerticalPosition.BOTTOM),
                bottomMarginPercent = p[MARGIN] ?: 6f,
            ),
            translationTarget = p[TRANSLATION_TARGET] ?: "en",
            translationModel = p[TRANSLATION_MODEL] ?: OpenAiTranslationProvider.DEFAULT_MODEL,
            translationBatchSize = p[TRANSLATION_BATCH] ?: SubtitleTranslator.DEFAULT_BATCH_SIZE,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setProvider(key: String) = edit { it[PROVIDER] = key }
    suspend fun setLanguage(code: String) = edit { it[LANGUAGE] = code }
    suspend fun setChunkMinutes(value: Int) = edit { it[CHUNK_MINUTES] = value.coerceIn(1, 20) }
    suspend fun setEncoding(value: ChunkEncoding) = edit { it[ENCODING] = value.name }
    suspend fun setParallel(value: Int) = edit { it[PARALLEL] = value.coerceIn(1, 4) }
    suspend fun setWordTimestamps(value: Boolean) = edit { it[WORD_TS] = value }
    suspend fun setCloudAcknowledged(value: Boolean) = edit { it[CLOUD_ACK] = value }
    suspend fun setRequireUnmetered(value: Boolean) = edit { it[UNMETERED] = value }

    suspend fun setTranslationTarget(code: String) = edit { it[TRANSLATION_TARGET] = code }
    suspend fun setTranslationModel(model: String) = edit {
        it[TRANSLATION_MODEL] = model.trim().ifBlank { OpenAiTranslationProvider.DEFAULT_MODEL }
    }
    suspend fun setTranslationBatchSize(value: Int) = edit {
        it[TRANSLATION_BATCH] = value.coerceIn(5, 40)
    }

    suspend fun setStyle(style: SubtitleStyle) = edit {
        it[FONT_SIZE] = style.fontSizeSp
        it[TEXT_COLOR] = style.textColor
        it[BG_COLOR] = style.backgroundColor
        it[OUTLINE] = style.outline
        it[POSITION] = style.verticalPosition.name
        it[MARGIN] = style.bottomMarginPercent
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private companion object {
        val PROVIDER = stringPreferencesKey("provider")
        val LANGUAGE = stringPreferencesKey("language")
        val CHUNK_MINUTES = intPreferencesKey("chunk_minutes")
        val ENCODING = stringPreferencesKey("encoding")
        val PARALLEL = intPreferencesKey("parallel")
        val WORD_TS = booleanPreferencesKey("word_timestamps")
        val CLOUD_ACK = booleanPreferencesKey("cloud_ack")
        val UNMETERED = booleanPreferencesKey("unmetered")
        val FONT_SIZE = floatPreferencesKey("font_size")
        val TEXT_COLOR = longPreferencesKey("text_color")
        val BG_COLOR = longPreferencesKey("bg_color")
        val OUTLINE = booleanPreferencesKey("outline")
        val POSITION = stringPreferencesKey("position")
        val MARGIN = floatPreferencesKey("margin")
        val TRANSLATION_TARGET = stringPreferencesKey("translation_target")
        val TRANSLATION_MODEL = stringPreferencesKey("translation_model")
        val TRANSLATION_BATCH = intPreferencesKey("translation_batch")
    }
}
