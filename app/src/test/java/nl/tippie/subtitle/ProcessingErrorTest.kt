package nl.tippie.subtitle

import com.google.common.truth.Truth.assertThat
import nl.tippie.subtitle.domain.model.ProcessingError
import org.junit.Test

class ProcessingErrorTest {

    @Test
    fun `codec errors name the codec in human terms`() {
        assertThat(ProcessingError.UnsupportedAudioCodec("audio/ac3").userMessage)
            .contains("AC-3 (Dolby Digital)")
        assertThat(ProcessingError.UnsupportedAudioCodec("audio/eac3").userMessage)
            .contains("E-AC-3")
    }

    @Test
    fun `storage errors quote real numbers`() {
        val message = ProcessingError.InsufficientStorage(1_288_490_188, 356_515_840).userMessage
        assertThat(message).contains("1.2 GB")
        assertThat(message).contains("340 MB")
    }

    @Test
    fun `chunk failure explains that the rest survived`() {
        val message = ProcessingError.ChunkFailed(11, 68, null).userMessage
        assertThat(message).contains("Chunk 12 of 68")
        assertThat(message).contains("rest of the video was still transcribed")
    }

    @Test
    fun `foreground timeout tells the user how to continue`() {
        val message = ProcessingError.ForegroundServiceTimeout(41, 68).userMessage
        assertThat(message).contains("41 of 68")
        assertThat(message).contains("reopen")
    }

    @Test
    fun `unexpected errors still carry the concrete cause`() {
        val message = ProcessingError.Unexpected("extraction", IllegalStateException("codec died")).userMessage
        assertThat(message).contains("extraction")
        assertThat(message).contains("codec died")
        assertThat(message).doesNotContain("Something went wrong")
    }

    @Test
    fun `byte formatting is readable at every scale`() {
        assertThat(ProcessingError.formatBytes(512)).isEqualTo("512 B")
        assertThat(ProcessingError.formatBytes(1536)).isEqualTo("1.5 KB")
        assertThat(ProcessingError.formatBytes(25L * 1024 * 1024)).isEqualTo("25.0 MB")
        assertThat(ProcessingError.formatBytes(3L * 1024 * 1024 * 1024)).isEqualTo("3.0 GB")
    }
}
