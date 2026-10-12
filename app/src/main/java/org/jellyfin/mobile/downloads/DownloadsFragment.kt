package org.jellyfin.mobile.downloads

import androidx.compose.runtime.Composable
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.mobile.MainViewModel
import org.jellyfin.mobile.ui.ComposeFragment
import org.jellyfin.mobile.ui.screens.downloads.DownloadsScreen
import org.jellyfin.mobile.ui.screens.downloads.OfflineReason
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class DownloadsFragment : ComposeFragment() {
    private val mainViewModel: MainViewModel by activityViewModel()

    @Composable
    override fun Content() {
        val args = arguments
        val offlineReason = args?.getString(EXTRA_OFFLINE)?.let { runCatching { OfflineReason.valueOf(it) }.getOrNull() }
        DownloadsScreen(
            onBackPressed = {
                when {
                    // Opened instead of the server: back leaves the app, as from the web client
                    offlineReason == OfflineReason.WIFI_ONLY -> {
                        activity?.finish()
                        true
                    }
                    else -> isAdded && !parentFragmentManager.isStateSaved && parentFragmentManager.popBackStackImmediate()
                }
            },
            offlineReason = offlineReason,
            onRetryConnection = { retryConnection() },
            startInStorage = args?.getBoolean(EXTRA_STORAGE) == true,
            openCleanup = args?.getBoolean(EXTRA_CLEANUP) == true,
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
        private const val EXTRA_STORAGE = "storage"
        private const val EXTRA_CLEANUP = "cleanup"

        /** Opened because the server did not answer. */
        fun unreachableArguments() = bundleOf(EXTRA_OFFLINE to OfflineReason.UNREACHABLE.name)

        /** Opened instead of the server on mobile data. */
        fun wifiOnlyArguments() = bundleOf(EXTRA_OFFLINE to OfflineReason.WIFI_ONLY.name)

        /** Opened on the storage page, [cleanup] - with the deletion dialog of watched downloads. */
        fun storageArguments(cleanup: Boolean = false) = bundleOf(EXTRA_STORAGE to true, EXTRA_CLEANUP to cleanup)
    }
}
