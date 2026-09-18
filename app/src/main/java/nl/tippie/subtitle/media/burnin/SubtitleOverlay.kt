package nl.tippie.subtitle.media.burnin

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import nl.tippie.subtitle.domain.model.Cue
import nl.tippie.subtitle.domain.model.SubtitleStyle

/**
 * Time-varying text overlay for burn-in.
 *
 * media3's [TextOverlay] is queried per frame with the presentation timestamp, which is
 * exactly the hook subtitles need: return the cue active at that instant and the renderer
 * does the rest. This is why the app needs no FFmpeg for hardcoded subtitles.
 *
 * Cue lookup is a binary search over a sorted array, so a 12-hour transcript with 20,000
 * cues costs ~15 comparisons per frame rather than a linear scan.
 */
class SubtitleOverlay(
    cues: List<Cue>,
    private val style: SubtitleStyle,
) : TextOverlay() {

    private val starts = LongArray(cues.size) { cues[it].startMs }
    private val ends = LongArray(cues.size) { cues[it].endMs }
    private val texts = Array(cues.size) { cues[it].text }

    private val empty = SpannableString("")

    // TextOverlay rasterises at a fixed TEXT_SIZE_PIXELS, so the user's font size is
    // applied as a uniform scale relative to the 20sp default rather than a text size.
    private val scale = (style.fontSizeSp / REFERENCE_FONT_SP).coerceIn(0.4f, 3f)

    private val settings: OverlaySettings = StaticOverlaySettings.Builder()
        .setOverlayFrameAnchor(0f, anchorForOverlay())
        .setBackgroundFrameAnchor(0f, anchorForBackground())
        .setScale(scale, scale)
        .build()

    override fun getText(presentationTimeUs: Long): SpannableString {
        val timeMs = presentationTimeUs / 1000
        val index = indexAt(timeMs)
        if (index < 0) return empty
        return styled(texts[index])
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings

    private fun indexAt(timeMs: Long): Int {
        var low = 0
        var high = starts.size - 1
        var candidate = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (starts[mid] <= timeMs) { candidate = mid; low = mid + 1 } else high = mid - 1
        }
        return if (candidate >= 0 && ends[candidate] > timeMs) candidate else -1
    }

    private fun styled(text: String): SpannableString {
        val span = SpannableString(text)
        val end = text.length
        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        span.setSpan(ForegroundColorSpan(style.textColor.toInt()), 0, end, flags)
        if (Color.alpha(style.backgroundColor.toInt()) > 0) {
            span.setSpan(BackgroundColorSpan(style.backgroundColor.toInt()), 0, end, flags)
        }
        return span
    }

    /** Anchors are normalised device coordinates: -1 is bottom/left, +1 is top/right. */
    private fun anchorForOverlay(): Float = when (style.verticalPosition) {
        SubtitleStyle.VerticalPosition.TOP -> 1f
        SubtitleStyle.VerticalPosition.CENTER -> 0f
        SubtitleStyle.VerticalPosition.BOTTOM -> -1f
    }

    private companion object {
        const val REFERENCE_FONT_SP = 20f
    }

    private fun anchorForBackground(): Float {
        val margin = (style.bottomMarginPercent / 100f * 2f).coerceIn(0f, 0.9f)
        return when (style.verticalPosition) {
            SubtitleStyle.VerticalPosition.TOP -> 1f - margin
            SubtitleStyle.VerticalPosition.CENTER -> 0f
            SubtitleStyle.VerticalPosition.BOTTOM -> -1f + margin
        }
    }
}
