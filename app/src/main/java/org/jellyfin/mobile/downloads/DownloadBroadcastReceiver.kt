package org.jellyfin.mobile.downloads

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class DownloadBroadcastReceiver : BroadcastReceiver(), KoinComponent {
    companion object {
        private const val ACTION_DOWNLOAD_CANCEL = "download_cancel"
        private const val ACTION_PAUSE_ALL = "downloads_pause"
        private const val ACTION_RESUME_ALL = "downloads_resume"
        private const val ACTION_CANCEL_ALL = "downloads_cancel_all"
        private const val EXTRA_DOWNLOAD_ID = "download_id"

        fun pauseAllIntent(context: Context) = Intent(context, DownloadBroadcastReceiver::class.java).setAction(ACTION_PAUSE_ALL)

        fun resumeAllIntent(context: Context) = Intent(context, DownloadBroadcastReceiver::class.java).setAction(ACTION_RESUME_ALL)

        fun cancelAllIntent(context: Context) = Intent(context, DownloadBroadcastReceiver::class.java).setAction(ACTION_CANCEL_ALL)

        fun cancelDownloadIntent(context: Context, downloadId: Long) = Intent(
            context,
            DownloadBroadcastReceiver::class.java,
        ).apply {
            action = ACTION_DOWNLOAD_CANCEL
            putExtra(EXTRA_DOWNLOAD_ID, downloadId)
        }
    }

    private val downloadManager by inject<DownloadManager>()
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        val all: (suspend () -> Unit)? = when (intent.action) {
            ACTION_PAUSE_ALL -> downloadManager::pauseAll
            ACTION_RESUME_ALL -> downloadManager::resumeAll
            ACTION_CANCEL_ALL -> downloadManager::cancelAll
            else -> null
        }
        if (all != null) {
            val pendingResult = goAsync()
            coroutineScope.launch { all() }.invokeOnCompletion { pendingResult.finish() }
            return
        }
        if (intent.action == ACTION_DOWNLOAD_CANCEL) {
            val id = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
            if (id == -1L) return

            val pendingResult = goAsync()
            coroutineScope.launch {
                downloadManager.cancel(id)
            }.invokeOnCompletion {
                pendingResult.finish()
            }
        }
    }
}
