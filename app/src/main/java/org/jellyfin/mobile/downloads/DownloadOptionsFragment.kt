package org.jellyfin.mobile.downloads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber
import org.jellyfin.mobile.MainViewModel
import org.jellyfin.mobile.ui.ComposeFragment
import org.jellyfin.mobile.ui.screens.downloads.DownloadOptionsScreen
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.UUID

/** Which episodes and how to download them (see [DownloadOptionsViewModel]). */
class DownloadOptionsFragment : ComposeFragment() {
    private val mainViewModel: MainViewModel by activityViewModel()
    private val viewModel: DownloadOptionsViewModel by viewModel()
    private val downloadManager: DownloadManager by inject()

    @Composable
    override fun Content() {
        val itemIds = arguments?.getStringArray(EXTRA_ITEMS).orEmpty().mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
        LaunchedEffect(Unit) { viewModel.load(itemIds) }
        DownloadOptionsScreen(
            viewModel = viewModel,
            onBack = ::close,
            onStart = ::start,
        )
    }

    private fun start() {
        val server = mainViewModel.serverState.value.server ?: return
        val user = mainViewModel.userState.value.user ?: return
        val items = viewModel.selectedItems
        val options = items.associate { it.id to viewModel.optionsFor(it) }
        // Out of the screen's life: the screen closes right away
        val activity = requireActivity()
        activity.lifecycleScope.launch {
            try {
                downloadManager.enqueueItems(server, user, items.map { it.id }, options)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Enqueueing downloads failed")
                Toast.makeText(activity, e.message ?: e.toString(), Toast.LENGTH_LONG).show()
            }
        }
        close()
    }

    private fun close() {
        if (isAdded && !parentFragmentManager.isStateSaved) parentFragmentManager.popBackStack()
    }

    companion object {
        private const val EXTRA_ITEMS = "items"

        fun arguments(itemIds: Collection<UUID>) = bundleOf(EXTRA_ITEMS to itemIds.map { it.toString() }.toTypedArray())
    }
}
