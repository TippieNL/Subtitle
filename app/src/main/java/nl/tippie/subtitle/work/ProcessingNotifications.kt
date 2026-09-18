package nl.tippie.subtitle.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import nl.tippie.subtitle.MainActivity
import nl.tippie.subtitle.R

object ProcessingNotifications {

    const val CHANNEL_ID = "processing"
    const val NOTIFICATION_ID = 4711

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_processing_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.channel_processing_desc)
                setShowBadge(false)
            }
        )
    }

    fun build(
        context: Context,
        title: String,
        text: String,
        progressPercent: Int?,
        cancelIntent: PendingIntent?,
    ) = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(title)
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setContentIntent(
            PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .apply {
            if (progressPercent != null) setProgress(100, progressPercent, false)
            else setProgress(0, 0, true)
            if (cancelIntent != null) {
                addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)
            }
        }
        .build()
}
