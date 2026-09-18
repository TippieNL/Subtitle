package nl.tippie.subtitle.subtitle

import nl.tippie.subtitle.translation.TranslationException
import nl.tippie.subtitle.translation.TranslationProvider
import nl.tippie.subtitle.translation.TranslationRequest

/**
 * Drives a [TranslationProvider] over a whole subtitle track.
 *
 * The interesting part is failure handling. A language model asked for 20 lines will
 * occasionally return 19 — it merged two short cues into one sentence, which reads better
 * as prose and is catastrophic here, because line 19 onwards would inherit line 20's text
 * and every subtitle after the mistake would be wrong.
 *
 * So a miscount is never patched up. The batch is split in half and retried, recursively,
 * down to a single line. A single line cannot be miscounted, so the recursion always
 * terminates with either a correct translation or a specific failure for that one cue,
 * and the rest of the file is unaffected.
 */
class SubtitleTranslator(
    private val provider: TranslationProvider,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val contextSize: Int = DEFAULT_CONTEXT_SIZE,
) {

    /** One unit of work: [texts] are translated, [context] is only shown to the model. */
    data class Batch(val startIndex: Int, val texts: List<String>, val context: List<String>)

    /** Outcome for a single source line. */
    sealed interface LineResult {
        data class Translated(val index: Int, val text: String) : LineResult
        data class Failed(val index: Int, val reason: String) : LineResult
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 20
        const val DEFAULT_CONTEXT_SIZE = 3

        /**
         * Splits [texts] into batches, each carrying the preceding [contextSize] lines.
         * Pure, so the batching contract is testable without a network.
         */
        fun plan(texts: List<String>, batchSize: Int, contextSize: Int): List<Batch> {
            require(batchSize > 0) { "batchSize must be positive" }
            if (texts.isEmpty()) return emptyList()
            val batches = mutableListOf<Batch>()
            var start = 0
            while (start < texts.size) {
                val end = minOf(start + batchSize, texts.size)
                val contextStart = (start - contextSize).coerceAtLeast(0)
                batches += Batch(
                    startIndex = start,
                    texts = texts.subList(start, end).toList(),
                    context = texts.subList(contextStart, start).toList(),
                )
                start = end
            }
            return batches
        }
    }

    /**
     * Translates every line. [onProgress] reports completed line counts so a long job can
     * show real progress; [isCancelled] is polled between batches.
     */
    suspend fun translateAll(
        texts: List<String>,
        sourceLanguage: String?,
        targetLanguage: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): List<LineResult> {
        val results = mutableListOf<LineResult>()
        var done = 0
        for (batch in plan(texts, batchSize, contextSize)) {
            if (isCancelled()) break
            results += translateBatch(batch, sourceLanguage, targetLanguage, isCancelled)
            done += batch.texts.size
            onProgress(done, texts.size)
        }
        return results
    }

    private suspend fun translateBatch(
        batch: Batch,
        sourceLanguage: String?,
        targetLanguage: String,
        isCancelled: () -> Boolean,
    ): List<LineResult> {
        if (batch.texts.isEmpty() || isCancelled()) return emptyList()

        val result = provider.translate(
            TranslationRequest(
                lines = batch.texts,
                contextBefore = batch.context,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
            )
        )

        result.onSuccess { translated ->
            if (translated.size == batch.texts.size) {
                return translated.mapIndexed { offset, text ->
                    LineResult.Translated(batch.startIndex + offset, text)
                }
            }
        }

        val error = result.exceptionOrNull()
        val translationError = error as? TranslationException

        // A non-retryable failure (bad key, missing model) will fail identically for every
        // sub-batch, so stop splitting and report it once per line.
        if (translationError != null && !translationError.retryable) {
            return batch.texts.indices.map {
                LineResult.Failed(batch.startIndex + it, translationError.error.userMessage)
            }
        }

        if (batch.texts.size == 1) {
            return listOf(
                LineResult.Failed(
                    batch.startIndex,
                    translationError?.error?.userMessage ?: error?.message ?: "translation failed"
                )
            )
        }

        val half = batch.texts.size / 2
        val first = Batch(batch.startIndex, batch.texts.take(half), batch.context)
        val second = Batch(
            batch.startIndex + half,
            batch.texts.drop(half),
            (batch.context + batch.texts.take(half)).takeLast(contextSize),
        )
        return translateBatch(first, sourceLanguage, targetLanguage, isCancelled) +
            translateBatch(second, sourceLanguage, targetLanguage, isCancelled)
    }
}
