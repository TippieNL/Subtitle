package nl.tippie.subtitle.media.burnin

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.tippie.subtitle.domain.model.Cue
import nl.tippie.subtitle.domain.model.ProcessingError
import nl.tippie.subtitle.domain.model.SubtitleStyle
import java.io.File

/**
 * Renders a new video with the subtitles permanently drawn into the frames.
 *
 * Uses media3 [Transformer], which decodes, applies the GL overlay and re-encodes through
 * MediaCodec. The audio track is passed through untouched, so only the video is re-encoded.
 *
 * Honest cost: this is the one operation whose time scales with video *resolution*, not
 * just duration — it is bounded by the device's hardware encoder, typically 2-6x realtime
 * for 1080p on a mid-range phone and slower for 4K. The output is roughly the size of the
 * source.
 */
class BurnInEngine(private val context: Context) {

    suspend fun render(
        sourceUri: Uri,
        cues: List<Cue>,
        style: SubtitleStyle,
        outputFile: File,
        onProgress: (Int) -> Unit,
    ): Result<File> = withContext(Dispatchers.Main) {
        val done = CompletableDeferred<Result<File>>()

        val overlay = SubtitleOverlay(cues, style)
        val effects = Effects(
            /* audioProcessors = */ emptyList(),
            /* videoEffects = */ listOf(OverlayEffect(listOf<androidx.media3.effect.TextureOverlay>(overlay)))
        )
        val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(sourceUri))
            .setEffects(effects)
            .build()

        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    done.complete(Result.success(outputFile))
                }

                override fun onError(
                    composition: Composition,
                    result: ExportResult,
                    exception: ExportException,
                ) {
                    done.complete(
                        Result.failure(
                            BurnInException(
                                when (exception.errorCode) {
                                    ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
                                    ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED ->
                                        ProcessingError.EncoderUnavailable(0, 0)
                                    ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
                                    ExportException.ERROR_CODE_IO_NO_PERMISSION ->
                                        ProcessingError.UriPermissionLost()
                                    else -> ProcessingError.Unexpected("video export", exception)
                                }
                            )
                        )
                    )
                }
            })
            .build()

        try {
            transformer.start(editedItem, outputFile.absolutePath)
        } catch (e: Throwable) {
            return@withContext Result.failure(
                BurnInException(ProcessingError.Unexpected("video export", e))
            )
        }

        val holder = androidx.media3.transformer.ProgressHolder()
        while (!done.isCompleted) {
            val state = transformer.getProgress(holder)
            if (state == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
            kotlinx.coroutines.delay(500)
        }
        runCatching { transformer.cancel() }
        done.await()
    }
}

class BurnInException(val error: ProcessingError) : Exception(error.userMessage, error.cause)
