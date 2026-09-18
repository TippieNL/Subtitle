package nl.tippie.subtitle

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import nl.tippie.subtitle.domain.model.ProcessingError
import nl.tippie.subtitle.subtitle.SubtitleTranslator
import nl.tippie.subtitle.translation.TranslationCapabilities
import nl.tippie.subtitle.translation.TranslationException
import nl.tippie.subtitle.translation.TranslationProvider
import nl.tippie.subtitle.translation.TranslationProviderId
import nl.tippie.subtitle.translation.TranslationRequest
import org.junit.Test

class SubtitleTranslatorTest {

    /** Records every call so we can assert on batching behaviour. */
    private class FakeProvider(
        val behaviour: (TranslationRequest) -> Result<List<String>>,
    ) : TranslationProvider {
        val calls = mutableListOf<TranslationRequest>()
        override val id = TranslationProviderId.OPENAI_CHAT
        override val capabilities = TranslationCapabilities(true, true, "", listOf("en"))
        override suspend fun isAvailable() = true
        override suspend fun translate(request: TranslationRequest): Result<List<String>> {
            calls += request
            return behaviour(request)
        }
    }

    private fun lines(n: Int) = (1..n).map { "line $it" }

    private fun echo(prefix: String = "T:") = FakeProvider { req ->
        Result.success(req.lines.map { "$prefix$it" })
    }

    // ---- planning ----------------------------------------------------------

    @Test
    fun `plan splits into batches of the requested size`() {
        val batches = SubtitleTranslator.plan(lines(50), batchSize = 20, contextSize = 3)
        assertThat(batches.map { it.texts.size }).containsExactly(20, 20, 10).inOrder()
        assertThat(batches.map { it.startIndex }).containsExactly(0, 20, 40).inOrder()
    }

    @Test
    fun `plan gives each batch the preceding lines as context`() {
        val batches = SubtitleTranslator.plan(lines(50), batchSize = 20, contextSize = 3)
        assertThat(batches[0].context).isEmpty()
        assertThat(batches[1].context).containsExactly("line 18", "line 19", "line 20").inOrder()
        assertThat(batches[2].context).containsExactly("line 38", "line 39", "line 40").inOrder()
    }

    @Test
    fun `plan covers every line exactly once`() {
        val source = lines(47)
        val batches = SubtitleTranslator.plan(source, batchSize = 7, contextSize = 2)
        assertThat(batches.flatMap { it.texts }).isEqualTo(source)
    }

    @Test
    fun `plan of empty input produces no batches`() {
        assertThat(SubtitleTranslator.plan(emptyList(), 20, 3)).isEmpty()
    }

    // ---- happy path --------------------------------------------------------

    @Test
    fun `every line comes back translated and in order`() = runTest {
        val provider = echo()
        val results = SubtitleTranslator(provider, batchSize = 10)
            .translateAll(lines(25), sourceLanguage = "nl", targetLanguage = "en")

        assertThat(results).hasSize(25)
        results.forEachIndexed { index, result ->
            assertThat(result).isInstanceOf(SubtitleTranslator.LineResult.Translated::class.java)
            result as SubtitleTranslator.LineResult.Translated
            assertThat(result.index).isEqualTo(index)
            assertThat(result.text).isEqualTo("T:line ${index + 1}")
        }
    }

    @Test
    fun `progress is reported as batches complete`() = runTest {
        val seen = mutableListOf<Pair<Int, Int>>()
        SubtitleTranslator(echo(), batchSize = 10)
            .translateAll(lines(25), null, "en", onProgress = { done, total -> seen += done to total })
        assertThat(seen).containsExactly(10 to 25, 20 to 25, 25 to 25).inOrder()
    }

    // ---- the important part: miscounts must never shift the timeline -------

    @Test
    fun `a batch that returns the wrong count is split and retried`() = runTest {
        // Fails (drops a line) for anything bigger than 5, succeeds otherwise.
        val provider = FakeProvider { req ->
            if (req.lines.size > 5) Result.success(req.lines.drop(1).map { "T:$it" })
            else Result.success(req.lines.map { "T:$it" })
        }

        val results = SubtitleTranslator(provider, batchSize = 20)
            .translateAll(lines(20), null, "en")

        assertThat(results).hasSize(20)
        results.forEachIndexed { index, result ->
            assertThat(result).isInstanceOf(SubtitleTranslator.LineResult.Translated::class.java)
            assertThat((result as SubtitleTranslator.LineResult.Translated).text)
                .isEqualTo("T:line ${index + 1}")
        }
        // 20 -> 10+10 -> 5+5 each: the splitting actually happened.
        assertThat(provider.calls.size).isAtLeast(4)
    }

    @Test
    fun `a line that always miscounts fails alone without affecting its neighbours`() = runTest {
        val provider = FakeProvider { req ->
            if (req.lines.any { it == "line 3" } && req.lines.size == 1) {
                Result.failure(TranslationException(ProcessingError.TranslationMiscount(1, 0), true))
            } else if (req.lines.any { it == "line 3" }) {
                Result.success(req.lines.dropLast(1).map { "T:$it" })   // miscount
            } else {
                Result.success(req.lines.map { "T:$it" })
            }
        }

        val results = SubtitleTranslator(provider, batchSize = 4)
            .translateAll(lines(8), null, "en")

        assertThat(results).hasSize(8)
        val failed = results.filterIsInstance<SubtitleTranslator.LineResult.Failed>()
        assertThat(failed).hasSize(1)
        assertThat(failed.single().index).isEqualTo(2)   // zero-based "line 3"
        // Everything else still translated, and still at the right index.
        val translated = results.filterIsInstance<SubtitleTranslator.LineResult.Translated>()
        assertThat(translated).hasSize(7)
        assertThat(translated.first { it.index == 7 }.text).isEqualTo("T:line 8")
    }

    @Test
    fun `a non-retryable failure does not waste calls splitting`() = runTest {
        val provider = FakeProvider {
            Result.failure(TranslationException(ProcessingError.InvalidApiKey(), retryable = false))
        }
        val results = SubtitleTranslator(provider, batchSize = 20)
            .translateAll(lines(20), null, "en")

        assertThat(results).hasSize(20)
        assertThat(results.all { it is SubtitleTranslator.LineResult.Failed }).isTrue()
        // One call, not a binary-search tree of 39 doomed ones.
        assertThat(provider.calls).hasSize(1)
        assertThat((results.first() as SubtitleTranslator.LineResult.Failed).reason)
            .contains("API key")
    }

    @Test
    fun `cancellation stops issuing requests`() = runTest {
        val provider = echo()
        var calls = 0
        val results = SubtitleTranslator(provider, batchSize = 5)
            .translateAll(lines(50), null, "en", isCancelled = { calls++ > 2 })
        assertThat(results.size).isLessThan(50)
    }

    @Test
    fun `retry only asks for the lines that are still missing`() = runTest {
        // Simulates re-running translation after a partial failure: the worker passes in
        // only the untranslated cues, so indices must be relative to that list.
        val provider = echo()
        val results = SubtitleTranslator(provider, batchSize = 10)
            .translateAll(listOf("only", "these", "three"), null, "en")
        assertThat(results.map { (it as SubtitleTranslator.LineResult.Translated).index })
            .containsExactly(0, 1, 2).inOrder()
    }
}
