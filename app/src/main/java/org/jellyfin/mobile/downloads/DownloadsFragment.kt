package org.jellyfin.mobile.downloads

import androidx.compose.runtime.Composable
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.mobile.MainViewModel
import org.jellyfin.mobile.ui.ComposeFragment
import org.jellyfin.mobile.ui.screens.downloads.DownloadsScreen
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class DownloadsFragment : ComposeFragment() {
    private val mainViewModel: MainViewModel by activityViewModel()

    @Composable
    override fun Content() {
        val offline = arguments?.getBoolean(EXTRA_OFFLINE) == true
        DownloadsScreen(
            onBackPressed = {
                isAdded && !parentFragmentManager.isStateSaved && parentFragmentManager.popBackStackImmediate()
            },
            offline = offline,
            onRetryConnection = { retryConnection() },
        )
    }

    /** Back to the web client of the same server. */
    private fun retryConnection() {
        val activity = activity ?: return
        val hostname = mainViewModel.serverState.value.server?.hostname
        if (isAdded && !parentFragmentManager.isStateSaved) parentFragmentManager.popBackStackImmediate()
        if (hostname != null) activity.lifecycleScope.launch { mainViewModel.switchServer(hostname) }
    }

    companion object {
        private const val EXTRA_OFFLINE = "offline"

        /** Opened because the server did not answer. */
        fun offlineArguments() = bundleOf(EXTRA_OFFLINE to true)
    }
}
