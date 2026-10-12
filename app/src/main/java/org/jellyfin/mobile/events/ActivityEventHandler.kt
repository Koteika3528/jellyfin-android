package org.jellyfin.mobile.events

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.text.format.Formatter
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.MainActivity
import org.jellyfin.mobile.R
import org.jellyfin.mobile.bridge.JavascriptCallback
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.downloads.DownloadsFragment
import org.jellyfin.mobile.downloads.OfflineMaintenance
import org.jellyfin.mobile.player.ui.PlayerFragment
import org.jellyfin.mobile.player.ui.PlayerFullscreenHelper
import org.jellyfin.mobile.settings.SettingsFragment
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.extensions.addFragment
import org.jellyfin.mobile.utils.requestDownload
import org.jellyfin.mobile.webapp.WebappFunctionChannel
import org.koin.android.ext.android.get
import timber.log.Timber

class ActivityEventHandler(
    private val webappFunctionChannel: WebappFunctionChannel,
) {
    private val eventsFlow = MutableSharedFlow<ActivityEvent>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    fun MainActivity.subscribe() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.CREATED) {
                eventsFlow.collect { event ->
                    handleEvent(event)
                }
            }
        }
    }

    /** "Ask first" of the downloads settings: new episodes to download, then watched ones to delete. */
    private fun MainActivity.offerMaintenance(event: ActivityEvent.OfferDownloadsMaintenance) {
        val maintenance: OfflineMaintenance = get()
        fun offerWatched() {
            if (event.watchedIds.isEmpty()) return
            AlertDialog.Builder(this)
                .setTitle(R.string.downloads_offer_watched_title)
                .setMessage(
                    resources.getQuantityString(
                        R.plurals.downloads_offer_watched_message,
                        event.watchedIds.size,
                        event.watchedIds.size,
                        Formatter.formatShortFileSize(this, event.watchedBytes),
                    ),
                )
                .setPositiveButton(R.string.downloads_offer_delete) { _, _ -> maintenance.deleteInBackground(event.watchedIds) }
                .setNeutralButton(R.string.downloads_offer_choose) { _, _ ->
                    if (!supportFragmentManager.isStateSaved) {
                        supportFragmentManager.addFragment<DownloadsFragment>(DownloadsFragment.storageArguments(cleanup = true))
                    }
                }
                .setNegativeButton(R.string.downloads_offer_later, null)
                .show()
        }

        if (event.newEpisodes.isEmpty()) {
            offerWatched()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.downloads_offer_new_title)
            .setMessage(event.newEpisodes.joinToString("\n") { it.second })
            .setPositiveButton(R.string.downloads_offer_download) { _, _ -> maintenance.downloadInBackground(event.newEpisodes.map { it.first }) }
            .setNegativeButton(R.string.downloads_offer_later, null)
            .setOnDismissListener { offerWatched() }
            .show()
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun MainActivity.handleEvent(event: ActivityEvent) {
        when (event) {
            is ActivityEvent.ChangeFullscreen -> {
                val fullscreenHelper = PlayerFullscreenHelper(window)
                if (event.isFullscreen) {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    fullscreenHelper.enableFullscreen()
                    window.setBackgroundDrawable(null)
                } else {
                    // Reset screen orientation
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    fullscreenHelper.disableFullscreen()
                    // Reset window background color
                    window.setBackgroundDrawableResource(R.color.theme_background)
                }
            }
            is ActivityEvent.LaunchNativePlayer -> {
                val args = Bundle().apply {
                    putParcelable(Constants.EXTRA_MEDIA_PLAY_OPTIONS, event.playOptions)
                    putParcelable(Constants.EXTRA_WEB_PREFERENCES, event.preferences)
                }
                supportFragmentManager.addFragment<PlayerFragment>(args)
            }
            is ActivityEvent.OpenUrl -> {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, event.uri.toUri())
                    if (event.grantReadPermission) intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    Timber.e("openIntent: %s", e.message)
                }
            }
            is ActivityEvent.DownloadItems -> {
                lifecycleScope.launch {
                    with(event) { requestDownload(itemIds) }
                }
            }
            ActivityEvent.OpenDownloads -> {
                supportFragmentManager.addFragment<DownloadsFragment>()
            }
            is ActivityEvent.OfferDownloadsMaintenance -> offerMaintenance(event)
            ActivityEvent.OpenDownloadsWifiOnly -> {
                if (!supportFragmentManager.isStateSaved && supportFragmentManager.findFragmentById(R.id.fragment_container) !is DownloadsFragment) {
                    supportFragmentManager.addFragment<DownloadsFragment>(DownloadsFragment.wifiOnlyArguments())
                }
            }
            ActivityEvent.OpenDownloadsStorage -> {
                if (!supportFragmentManager.isStateSaved) {
                    supportFragmentManager.addFragment<DownloadsFragment>(DownloadsFragment.storageArguments())
                }
            }
            ActivityEvent.ServerUnreachable -> {
                lifecycleScope.launch {
                    val downloadDao: DownloadDao = get()
                    val hasDownloads = withContext(Dispatchers.IO) { downloadDao.getCompletedDownloads().isNotEmpty() }
                    val alreadyOpen = supportFragmentManager.findFragmentById(R.id.fragment_container) is DownloadsFragment
                    if (hasDownloads && !alreadyOpen && !supportFragmentManager.isStateSaved) {
                        supportFragmentManager.addFragment<DownloadsFragment>(DownloadsFragment.unreachableArguments())
                    }
                }
            }
            is ActivityEvent.CastMessage -> {
                val action = event.action
                chromecast.execute(
                    action,
                    event.args,
                    object : JavascriptCallback() {
                        override fun callback(keep: Boolean, err: String?, result: String?) {
                            webappFunctionChannel.call(
                                """window.NativeShell.castCallback("$action", $keep, $err, $result);""",
                            )
                        }
                    },
                )
            }
            ActivityEvent.RequestBluetoothPermission -> {
                lifecycleScope.launch {
                    bluetoothPermissionHelper.requestBluetoothPermissionIfNecessary()
                }
            }
            ActivityEvent.OpenSettings -> {
                supportFragmentManager.addFragment<SettingsFragment>()
            }
            ActivityEvent.SelectServer -> {
                mainViewModel.resetServer()
            }
            ActivityEvent.ExitApp -> {
                if (serviceBinder?.isPlaying == true) {
                    moveTaskToBack(false)
                } else {
                    finish()
                }
            }
        }
    }

    fun emit(event: ActivityEvent) {
        eventsFlow.tryEmit(event)
    }
}
