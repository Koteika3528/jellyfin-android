package org.jellyfin.mobile.ui.screens.music

import android.os.Bundle
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jellyfin.mobile.MainActivity
import org.jellyfin.mobile.R
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.downloads.DownloadedMusicPlayer
import org.jellyfin.mobile.ui.ComposeFragment
import org.jellyfin.mobile.utils.extensions.addFragment
import org.jellyfin.mobile.utils.requestDownload
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.UUID

/** The music player in full, opened from the mini player (web client and downloads) and the notification. */
class NowPlayingFragment : ComposeFragment() {
    private val musicPlayer: DownloadedMusicPlayer by inject()
    private val downloadDao: DownloadDao by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opened from the notification right after a cold start: the player is not connected yet
        musicPlayer.connect()
    }

    @Composable
    override fun Content() {
        val downloads by remember { downloadDao.getAllDownloads() }.collectAsState(initial = emptyList())
        NowPlayingScreen(
            player = musicPlayer,
            onClose = ::close,
            downloadStatus = { itemId -> downloads.firstOrNull { it.itemId.toString() == itemId }?.status },
            onDownload = ::download,
        )
    }

    /**
     * The current track to the downloads (then it plays from the phone), the same way as from the web
     * client: the folder is asked for when none is chosen yet.
     */
    private fun download(itemId: String) {
        val id = runCatching { UUID.fromString(itemId) }.getOrNull() ?: return
        val activity = activity as? MainActivity ?: return
        activity.lifecycleScope.launch {
            try {
                activity.requestDownload(listOf(id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Downloading the current track failed")
                Toast.makeText(activity, R.string.music_download_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun close() {
        if (isAdded && !parentFragmentManager.isStateSaved) parentFragmentManager.popBackStack()
    }

    companion object {
        fun open(fragmentManager: FragmentManager) {
            if (fragmentManager.isStateSaved) return
            if (fragmentManager.fragments.any { it is NowPlayingFragment }) return
            fragmentManager.addFragment<NowPlayingFragment>()
        }
    }
}
