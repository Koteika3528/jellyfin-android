package org.jellyfin.mobile.downloads

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.guava.await
import timber.log.Timber
import java.util.concurrent.TimeUnit
import org.jellyfin.mobile.app.AppPreferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.IOException

class DownloadWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters), KoinComponent {
    companion object {
        private val tag = DownloadWorker::class.qualifiedName!!

        /**
         * "Download now over mobile data" with Wi-Fi only set: lasts until the queue is done (or the app is
         * closed), downloads added meanwhile do not go back to waiting for Wi-Fi.
         */
        @Volatile
        var meteredAllowedOnce: Boolean = false

        suspend fun start(context: Context, appPreferences: AppPreferences) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>().apply {
                addTag(tag)
                setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                // Server unreachable: tried again in a minute, two, three... (not hours later)
                setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_DELAY_SECONDS, TimeUnit.SECONDS)
                setConstraints(
                    Constraints.Builder().apply {
                        val method = appPreferences.downloadMethod
                            .takeUnless { it == DownloadMethod.WIFI_ONLY && meteredAllowedOnce } ?: DownloadMethod.MOBILE_DATA
                        when (method) {
                            DownloadMethod.WIFI_ONLY -> setRequiredNetworkType(NetworkType.UNMETERED)
                            DownloadMethod.MOBILE_DATA -> setRequiredNetworkType(NetworkType.NOT_ROAMING)
                            DownloadMethod.MOBILE_AND_ROAMING -> setRequiredNetworkType(NetworkType.CONNECTED)
                        }
                    }.build(),
                )
            }.build()

            WorkManager.getInstance(context).enqueueUniqueWork(tag, ExistingWorkPolicy.REPLACE, request).await()
        }

        suspend fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(tag).await()
        }

        suspend fun restart(context: Context, appPreferences: AppPreferences) {
            stop(context)
            start(context, appPreferences)
        }

        suspend fun isActive(context: Context): Boolean = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(tag)
            .await()
            .any { workInfo -> workInfo.state == WorkInfo.State.RUNNING }

        /** Running, or waiting for its network or a retry. */
        suspend fun isScheduled(context: Context): Boolean = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(tag)
            .await()
            .any { workInfo -> !workInfo.state.isFinished }
    }

    private val downloadNotificationManager by inject<DownloadNotificationManager>()
    private val downloadQueue by inject<DownloadQueue>()

    override suspend fun getForegroundInfo(): ForegroundInfo = downloadNotificationManager.createForegroundInfo()

    override suspend fun doWork(): Result {
        val canProcess = downloadQueue.prepare()
        if (!canProcess) {
            meteredAllowedOnce = false
            return Result.success()
        }

        // Restarted by the system in the background (after the app was killed), Android 12+ may not let
        // the work become a foreground service: download on without it instead of failing the whole queue
        try {
            setForeground(getForegroundInfo())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Downloading without a foreground service")
        }
        return try {
            downloadQueue.process()
            meteredAllowedOnce = false
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            Result.retry()
        } catch (_: Exception) {
            Result.failure()
        }
    }
}

private const val RETRY_DELAY_SECONDS = 60L
