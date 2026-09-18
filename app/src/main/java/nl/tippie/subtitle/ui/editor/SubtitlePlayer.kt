package nl.tippie.subtitle.ui.editor

import android.net.Uri
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

/**
 * Video preview with the generated subtitles side-loaded as a real text track.
 *
 * Side-loading a VTT file rather than drawing our own overlay means the preview uses
 * ExoPlayer's own subtitle renderer — the same positioning and timing path a normal
 * player would use, so what you see is what a player will show.
 */
@Composable
fun SubtitlePlayer(
    sourceUri: String,
    subtitleFile: File?,
    onPositionChange: (Long) -> Unit,
    seekToMs: Long?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val player = remember {
        ExoPlayer.Builder(context).build().apply { playWhenReady = false }
    }

    DisposableEffect(sourceUri, subtitleFile?.lastModified()) {
        val builder = MediaItem.Builder().setUri(Uri.parse(sourceUri))
        if (subtitleFile != null && subtitleFile.exists()) {
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(subtitleFile))
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setLanguage("und")
                        .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            )
        }
        val position = player.currentPosition
        player.setMediaItem(builder.build())
        player.prepare()
        if (position > 0) player.seekTo(position)
        onDispose { }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    LaunchedEffect(seekToMs) {
        seekToMs?.let { player.seekTo(it) }
    }

    LaunchedEffect(player) {
        while (true) {
            onPositionChange(player.currentPosition)
            kotlinx.coroutines.delay(200)
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                setShowNextButton(false)
                setShowPreviousButton(false)
            }
        },
        modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f),
    )
}
