package nl.tippie.subtitle

import com.google.common.truth.Truth.assertThat
import nl.tippie.subtitle.domain.model.Cue
import nl.tippie.subtitle.domain.model.SubtitleTrack
import org.junit.Test

class TranslationTrackTest {

    private fun cue(text: String, translated: String? = null) =
        Cue(id = 1, projectId = 1, index = 0, startMs = 0, endMs = 2_000,
            text = text, translatedText = translated)

    @Test
    fun `original track always returns the source text`() {
        val c = cue("Goedemorgen", "Good morning")
        assertThat(c.textFor(SubtitleTrack.ORIGINAL)).isEqualTo("Goedemorgen")
    }

    @Test
    fun `translation track returns the translation`() {
        val c = cue("Goedemorgen", "Good morning")
        assertThat(c.textFor(SubtitleTrack.TRANSLATION)).isEqualTo("Good morning")
    }

    @Test
    fun `translation track falls back to the original when untranslated`() {
        // A partially translated file must never export blank subtitles.
        val c = cue("Goedemorgen", null)
        assertThat(c.textFor(SubtitleTrack.TRANSLATION)).isEqualTo("Goedemorgen")
    }

    @Test
    fun `bilingual puts the translation above the original`() {
        val c = cue("Goedemorgen", "Good morning")
        assertThat(c.textFor(SubtitleTrack.BILINGUAL)).isEqualTo("Good morning\nGoedemorgen")
    }

    @Test
    fun `bilingual flattens existing line breaks so it stays two lines`() {
        val c = cue("Goede\nmorgen", "Good\nmorning")
        assertThat(c.textFor(SubtitleTrack.BILINGUAL)).isEqualTo("Good morning\nGoede morgen")
    }

    @Test
    fun `bilingual degrades to the original alone when there is no translation`() {
        val c = cue("Goedemorgen", null)
        assertThat(c.textFor(SubtitleTrack.BILINGUAL)).isEqualTo("Goedemorgen")
    }

    @Test
    fun `blank translation is treated as absent`() {
        val c = cue("Goedemorgen", "   ")
        assertThat(c.textFor(SubtitleTrack.BILINGUAL)).isEqualTo("Goedemorgen")
    }
}
