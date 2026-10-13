package org.jellyfin.mobile.downloads

import android.app.PendingIntent
import android.text.format.Formatter
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.getSystemService
import androidx.work.ForegroundInfo
import org.jellyfin.mobile.R
import org.jellyfin.mobile.utils.AndroidVersion

class DownloadNotificationManager(
    val context: Context,
) {
    companion object {
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 67
        const val PAUSED_NOTIFICATION_ID = 68
    }

    private val notificationManager = requireNotNull(context.getSystemService<NotificationManager>())

    init {
        if (AndroidVersion.isAtLeastO) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.downloads),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun createForegroundInfo() = ForegroundInfo(
        67,
        NotificationCompat.Builder(context, CHANNEL_ID).apply {
            setContentTitle(context.getString(R.string.downloads))
            setSmallIcon(android.R.drawable.stat_sys_download)
        }.build(),
        if (AndroidVersion.isAtLeastQ) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )

    fun downloadFile(id: Long, name: String) = NotificationProgressCallback(context, notificationManager, id, name)

    /** "N downloads paused" with Resume and Cancel all. */
    fun showPaused(count: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID).apply {
            setContentTitle(context.getString(R.string.downloads_paused_title))
            setContentText(context.getString(R.string.downloads_paused_count, count))
            setSmallIcon(android.R.drawable.stat_sys_download_done)
            setPriority(NotificationCompat.PRIORITY_LOW)
            setOnlyAlertOnce(true)
            addAction(action(R.string.download_resume, DownloadBroadcastReceiver.resumeAllIntent(context), REQUEST_RESUME))
            addAction(action(R.string.download_cancel_all, DownloadBroadcastReceiver.cancelAllIntent(context), REQUEST_CANCEL_ALL))
        }.build()
        notificationManager.cancel(NOTIFICATION_ID)
        notificationManager.notify(PAUSED_NOTIFICATION_ID, notification)
    }

    fun hidePaused() = notificationManager.cancel(PAUSED_NOTIFICATION_ID)

    fun hideProgress() = notificationManager.cancel(NOTIFICATION_ID)

    private fun action(title: Int, intent: android.content.Intent, requestCode: Int) = NotificationCompat.Action.Builder(
        null,
        context.getString(title),
        PendingIntentCompat.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT, false),
    ).build()
}

class NotificationProgressCallback(
    private val context: Context,
    private val notificationManager: NotificationManager,
    private val downloadId: Long,
    private val name: String,
) : FileDownloader.ProgressCallback {

    private val builder by lazy {
        NotificationCompat.Builder(context, DownloadNotificationManager.CHANNEL_ID).apply {
            setContentTitle(context.getString(R.string.downloading_title, name))
            setSmallIcon(android.R.drawable.stat_sys_download)
            setPriority(NotificationCompat.PRIORITY_LOW)
            setOnlyAlertOnce(true)
            setOngoing(true)
            setProgress(100, 0, true)

            val cancelPendingIntent = PendingIntentCompat.getBroadcast(
                context,
                0,
                DownloadBroadcastReceiver.cancelDownloadIntent(context, downloadId),
                0,
                false,
            )
            addAction(
                NotificationCompat.Action.Builder(
                    null,
                    context.getString(R.string.download_pause),
                    PendingIntentCompat.getBroadcast(context, REQUEST_PAUSE, DownloadBroadcastReceiver.pauseAllIntent(context), PendingIntent.FLAG_UPDATE_CURRENT, false),
                ).build(),
            )
            addAction(
                NotificationCompat.Action.Builder(
                    null,
                    context.getString(R.string.download_cancel),
                    cancelPendingIntent,
                ).build(),
            )
        }
    }

    private val speed = DownloadProgressTracker.SpeedMeter()

    override suspend fun onProgress(downloaded: Long, total: Long) {
        if (downloaded == Long.MAX_VALUE) {
            DownloadProgressTracker.remove(downloadId)
            builder.apply {
                setContentText(context.getString(R.string.download_completed))
                setSubText(null)
                setProgress(0, 0, false)
                setSmallIcon(android.R.drawable.stat_sys_download_done)
                setOngoing(false)
            }
            notificationManager.notify(DownloadNotificationManager.NOTIFICATION_ID, builder.build())
            return
        }

        // About once a second: downloaded / total and the speed, in the notification and the downloads screen
        if (!speed.sample(downloaded)) return
        DownloadProgressTracker.update(downloadId, DownloadProgressTracker.Live(downloaded, total, speed.bytesPerSecond))

        val progress = if (total > 0) (downloaded.toFloat() / total * 100).toInt().coerceIn(0, 100) else 0
        builder.apply {
            setContentText(describe(context, downloaded, total, speed.bytesPerSecond))
            setSubText(context.getString(R.string.download_progress, progress))
            setProgress(100, progress, total <= 0)
            setOngoing(true)
        }

        notificationManager.notify(DownloadNotificationManager.NOTIFICATION_ID, builder.build())
    }

    companion object {
        /** "120 MB of 1.4 GB · 5.2 MB/s" */
        fun describe(context: Context, downloaded: Long, total: Long, bytesPerSecond: Long): String {
            val done = Formatter.formatShortFileSize(context, downloaded)
            val size = if (total > 0) context.getString(R.string.download_of_total, done, Formatter.formatShortFileSize(context, total)) else done
            return if (bytesPerSecond > 0) {
                size + " · " + context.getString(R.string.download_speed, Formatter.formatShortFileSize(context, bytesPerSecond))
            } else {
                size
            }
        }
    }

    suspend fun onEnd() = onProgress(Long.MAX_VALUE, Long.MAX_VALUE)
}

private const val REQUEST_PAUSE = 1
private const val REQUEST_RESUME = 2
private const val REQUEST_CANCEL_ALL = 3
