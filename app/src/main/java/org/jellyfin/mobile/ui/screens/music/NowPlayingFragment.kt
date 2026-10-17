package org.jellyfin.mobile.ui.screens.music

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.getSystemService
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jellyfin.mobile.MainActivity
import org.jellyfin.mobile.R
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.downloads.DownloadManager
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.mobile.downloads.DownloadedMusicPlayer
import org.jellyfin.mobile.downloads.MetadataFields
import org.jellyfin.mobile.downloads.OfflineStore
import org.jellyfin.mobile.downloads.ServerAccess
import org.jellyfin.mobile.downloads.TrackServerActions
import org.jellyfin.mobile.ui.ComposeFragment
import org.jellyfin.mobile.ui.screens.downloads.MetadataDialog
import org.jellyfin.mobile.utils.extensions.addFragment
import org.jellyfin.mobile.utils.requestDownload
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** The music player in full, opened from the mini player (web client and downloads) and the notification. */
class NowPlayingFragment : ComposeFragment() {
    private val musicPlayer: DownloadedMusicPlayer by inject()
    private val downloadDao: DownloadDao by inject()
    private val downloadManager: DownloadManager by inject()
    private val offlineStore: OfflineStore by inject()
    private val apiClient: ApiClient by inject()
    private val okHttpClient: OkHttpClient by inject()
    private val serverActions by lazy { TrackServerActions(apiClient, okHttpClient) }

    /** Asked when the menu opens; kept a while when the server answered. */
    private val serverAccess = MutableStateFlow<ServerAccess?>(null)
    private var accessCheckedAt = 0L

    /** The details being edited: of a download on the phone, or of the item on the server. */
    private sealed interface Editing {
        data class OnPhone(val download: DownloadEntity, val edited: Boolean) : Editing
        data class OnServer(val item: BaseItemDto) : Editing
    }

    private val editing = MutableStateFlow<Editing?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opened from the notification right after a cold start: the player is not connected yet
        musicPlayer.connect()
    }

    @Composable
    override fun Content() {
        val downloads by remember { downloadDao.getAllDownloads() }.collectAsState(initial = emptyList())
        val access by serverAccess.collectAsState()
        NowPlayingScreen(
            player = musicPlayer,
            onClose = ::close,
            downloadStatus = { itemId -> downloads.firstOrNull { it.itemId.toString() == itemId }?.status },
            onDownload = ::download,
            serverAccess = access,
            onMenuOpened = ::refreshAccess,
            onShare = ::share,
            onCopyStreamLink = ::copyStreamLink,
            onEdit = { itemId, onServer -> if (onServer) editOnServer(itemId) else editOnPhone(itemId) },
        )

        when (val target = editing.collectAsState().value) {
            is Editing.OnPhone -> MetadataDialog(
                downloads = listOf(target.download),
                edited = target.edited,
                onSave = { changes -> saveOnPhone(target.download, changes) },
                onReset = { resetOnPhone(target.download) },
                onDismiss = { editing.value = null },
            )
            is Editing.OnServer -> MetadataDialog(
                items = listOf(target.item),
                edited = false,
                onSave = { changes -> saveOnServer(target.item, changes) },
                onReset = {},
                onDismiss = { editing.value = null },
                title = getString(R.string.music_edit_on_server),
                hint = getString(R.string.music_edit_server_hint),
                albumEditable = false,
            )
            null -> Unit
        }
    }

    private fun refreshAccess() {
        val fresh = SystemClock.elapsedRealtime() - accessCheckedAt < ACCESS_KEPT_MS
        if (fresh && serverAccess.value?.online == true) return
        serverAccess.value = null
        lifecycleScope.launch {
            serverAccess.value = serverActions.access()
            accessCheckedAt = SystemClock.elapsedRealtime()
        }
    }

    /**
     * The current track to the downloads (then it plays from the phone), the same way as from the web
     * client: the folder is asked for when none is chosen yet.
     */
    private fun download(itemId: String) {
        val id = itemId.toUUIDOrNull() ?: return
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

    /** A link to the track's page for 24 hours, sent with Android's share sheet. */
    private fun share(itemId: String) {
        val id = itemId.toUUIDOrNull() ?: return
        val state = musicPlayer.nowPlaying.value
        launchWithToast(R.string.music_link_failed) {
            val link = serverActions.shareLink(id)
            val text = listOfNotNull(
                listOfNotNull(state?.title, state?.subtitle).joinToString(" — ").ifEmpty { null },
                link.url,
            ).joinToString("\n")
            val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            startActivity(Intent.createChooser(intent, getString(R.string.music_share_title)))
        }
    }

    /** A link to the audio file for 24 hours (signed in as the link's guest), to the clipboard. */
    private fun copyStreamLink(itemId: String) {
        val id = itemId.toUUIDOrNull() ?: return
        launchWithToast(R.string.music_link_failed) {
            val link = serverActions.streamLink(id)
            val context = requireContext()
            val clip = ClipData.newPlainText(musicPlayer.nowPlaying.value?.title, link.url)
            context.getSystemService<ClipboardManager>()?.setPrimaryClip(clip)
            Toast.makeText(context, getString(R.string.music_stream_link_copied, formatUntil(link.expiresAt)), Toast.LENGTH_LONG).show()
        }
    }

    private fun editOnPhone(itemId: String) {
        val id = itemId.toUUIDOrNull() ?: return
        lifecycleScope.launch {
            val download = withContext(Dispatchers.IO) { downloadDao.getDownloadsByItemIds(listOf(id)) }
                .firstOrNull { it.status == DownloadStatus.DOWNLOADED } ?: return@launch
            editing.value = Editing.OnPhone(download, edited = offlineStore.overrideOf(id) != null)
        }
    }

    private fun saveOnPhone(download: DownloadEntity, changes: MetadataFields) {
        editing.value = null
        lifecycleScope.launch {
            downloadManager.editMetadata(listOf(download), changes)
            showUpdated(download)
        }
    }

    private fun resetOnPhone(download: DownloadEntity) {
        editing.value = null
        lifecycleScope.launch {
            downloadManager.resetMetadata(listOf(download))
            showUpdated(download)
        }
    }

    /** The player shows the download as it is now. */
    private suspend fun showUpdated(download: DownloadEntity) {
        val updated = downloadDao.getDownload(download.id) ?: return
        musicPlayer.updateMetadata(updated.itemId.toString(), updated.item)
    }

    private fun editOnServer(itemId: String) {
        val id = itemId.toUUIDOrNull() ?: return
        launchWithToast(R.string.music_edit_load_failed) {
            editing.value = Editing.OnServer(serverActions.item(id))
        }
    }

    private fun saveOnServer(item: BaseItemDto, changes: MetadataFields) {
        editing.value = null
        if (changes.isEmpty) return
        launchWithToast(R.string.music_edit_failed) {
            val updated = serverActions.edit(item, changes)
            // Edits made on the phone stay over what the server says
            musicPlayer.updateMetadata(item.id.toString(), offlineStore.overrideOf(item.id)?.applyTo(updated) ?: updated)
            Toast.makeText(requireContext(), R.string.music_edit_saved, Toast.LENGTH_SHORT).show()
            // A downloaded copy takes the new details too
            downloadManager.refreshMetadata()
        }
    }

    private fun launchWithToast(failure: Int, block: suspend () -> Unit) {
        lifecycleScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Music player action failed")
                context?.let { Toast.makeText(it, failure, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    /** "2026-10-18T12:00:00.0000000Z" -> the date and time here. */
    private fun formatUntil(expiresAt: String): String {
        val millis = runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(expiresAt.take(UTC_DATE_LENGTH))?.time
        }.getOrNull() ?: (System.currentTimeMillis() + DateUtils.DAY_IN_MILLIS)
        return DateUtils.formatDateTime(requireContext(), millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)
    }

    private fun String.toUUIDOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

    private fun close() {
        if (isAdded && !parentFragmentManager.isStateSaved) parentFragmentManager.popBackStack()
    }

    companion object {
        private const val ACCESS_KEPT_MS = 60_000L
        private const val UTC_DATE_LENGTH = 19

        fun open(fragmentManager: FragmentManager) {
            if (fragmentManager.isStateSaved) return
            if (fragmentManager.fragments.any { it is NowPlayingFragment }) return
            fragmentManager.addFragment<NowPlayingFragment>()
        }
    }
}
