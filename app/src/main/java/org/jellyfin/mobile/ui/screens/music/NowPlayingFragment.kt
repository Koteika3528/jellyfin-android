package org.jellyfin.mobile.ui.screens.music

import androidx.compose.runtime.Composable
import androidx.fragment.app.FragmentManager
import org.jellyfin.mobile.downloads.DownloadedMusicPlayer
import org.jellyfin.mobile.ui.ComposeFragment
import org.jellyfin.mobile.utils.extensions.addFragment
import org.koin.android.ext.android.inject

/** The music player in full, opened from the mini player (web client and downloads). */
class NowPlayingFragment : ComposeFragment() {
    private val musicPlayer: DownloadedMusicPlayer by inject()

    @Composable
    override fun Content() {
        NowPlayingScreen(player = musicPlayer, onClose = ::close)
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
