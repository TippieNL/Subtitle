package nl.tippie.subtitle.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import nl.tippie.subtitle.domain.model.AudioTrackInfo
import nl.tippie.subtitle.domain.model.MediaInfo
import nl.tippie.subtitle.domain.model.ProcessingError

/**
 * Reads container metadata without decoding anything and without reading the file body.
 * Cost is O(header size), so a 40 GB file probes as fast as a 40 MB one.
 */
class MediaInfoReader(private val context: Context) {

    fun read(uri: Uri): Result<MediaInfo> = runCatching {
        val (name, size) = queryNameAndSize(uri)

        var durationMs = 0L
        var width: Int? = null
        var height: Int? = null
        MediaMetadataRetriever().use { mmr ->
            mmr.setDataSource(context, uri)
            durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            width = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        }

        val tracks = mutableListOf<AudioTrackInfo>()
        var hasVideo = false
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    mime.startsWith("video/") -> hasVideo = true
                    mime.startsWith("audio/") -> tracks += AudioTrackInfo(
                        trackIndex = i,
                        mimeType = mime,
                        sampleRateHz = format.optInt(MediaFormat.KEY_SAMPLE_RATE) ?: 0,
                        channelCount = format.optInt(MediaFormat.KEY_CHANNEL_COUNT) ?: 0,
                        language = format.optString(MediaFormat.KEY_LANGUAGE)
                            ?.takeUnless { it.isBlank() || it == "und" },
                        bitrate = format.optInt(MediaFormat.KEY_BIT_RATE),
                    )
                }
            }
        } finally {
            extractor.release()
        }

        MediaInfo(
            displayName = name,
            sizeBytes = size,
            durationMs = durationMs,
            widthPx = width?.takeIf { it > 0 },
            heightPx = height?.takeIf { it > 0 },
            audioTracks = tracks,
            hasVideoTrack = hasVideo,
        )
    }

    /** Classifies a probe failure into a message we can actually show. */
    fun classifyProbeFailure(t: Throwable): ProcessingError = when {
        t is SecurityException -> ProcessingError.UriPermissionLost()
        t is java.io.FileNotFoundException -> ProcessingError.UriPermissionLost()
        else -> ProcessingError.CorruptContainer(t)
    }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "video"
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                }
            }
        }
        if (size == 0L) {
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { size = it.statSize }
            }
        }
        return name to size
    }

    private fun MediaFormat.optInt(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private fun MediaFormat.optString(key: String): String? =
        if (containsKey(key)) runCatching { getString(key) }.getOrNull() else null
}

private inline fun <R> MediaMetadataRetriever.use(block: (MediaMetadataRetriever) -> R): R =
    try { block(this) } finally { runCatching { release() } }
