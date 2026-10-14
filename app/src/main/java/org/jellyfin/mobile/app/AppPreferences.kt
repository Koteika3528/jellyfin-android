package org.jellyfin.mobile.app

import android.content.Context
import android.content.SharedPreferences
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
import androidx.core.content.edit
import org.jellyfin.mobile.downloads.AutoMode
import org.jellyfin.mobile.downloads.DownloadMethod
import org.jellyfin.mobile.downloads.MobileDataPolicy
import org.jellyfin.mobile.player.mediasegments.MediaSegmentAction
import org.jellyfin.mobile.player.mediasegments.toMediaSegmentActionsString
import org.jellyfin.mobile.settings.ExternalPlayerPackage
import org.jellyfin.mobile.settings.VideoPlayerType
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.sdk.model.api.MediaSegmentType

class AppPreferences(context: Context) {
    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)

    var currentServerId: Long?
        get() = sharedPreferences.getLong(Constants.PREF_SERVER_ID, -1).takeIf { it >= 0 }
        set(value) {
            sharedPreferences.edit {
                if (value != null) putLong(Constants.PREF_SERVER_ID, value) else remove(Constants.PREF_SERVER_ID)
            }
        }

    var currentUserId: Long?
        get() = sharedPreferences.getLong(Constants.PREF_USER_ID, -1).takeIf { it >= 0 }
        set(value) {
            sharedPreferences.edit {
                if (value != null) putLong(Constants.PREF_USER_ID, value) else remove(Constants.PREF_USER_ID)
            }
        }

    var ignoreBatteryOptimizations: Boolean
        get() = sharedPreferences.getBoolean(Constants.PREF_IGNORE_BATTERY_OPTIMIZATIONS, false)
        set(value) {
            sharedPreferences.edit {
                putBoolean(Constants.PREF_IGNORE_BATTERY_OPTIMIZATIONS, value)
            }
        }

    var ignoreWebViewChecks: Boolean
        get() = sharedPreferences.getBoolean(Constants.PREF_IGNORE_WEBVIEW_CHECKS, false)
        set(value) {
            sharedPreferences.edit {
                putBoolean(Constants.PREF_IGNORE_WEBVIEW_CHECKS, value)
            }
        }

    var ignoreBluetoothPermission: Boolean
        get() = sharedPreferences.getBoolean(Constants.PREF_IGNORE_BLUETOOTH_PERMISSION, false)
        set(value) {
            sharedPreferences.edit {
                putBoolean(Constants.PREF_IGNORE_BLUETOOTH_PERMISSION, value)
            }
        }

    var downloadMethod: DownloadMethod
        get() = DownloadMethod.fromInt(sharedPreferences.getInt(Constants.PREF_DOWNLOAD_METHOD, -1)) ?: DownloadMethod.DEFAULT
        set(value) {
            sharedPreferences.edit {
                putInt(Constants.PREF_DOWNLOAD_METHOD, value.intValue)
            }
        }

    var storageLocation: String?
        get() = sharedPreferences.getString(Constants.PREF_STORAGE_LOCATION, null)
        set(value) {
            sharedPreferences.edit {
                if (value == null) {
                    remove(Constants.PREF_STORAGE_LOCATION)
                } else {
                    putString(Constants.PREF_STORAGE_LOCATION, value)
                }
            }
        }

    /** Followed shows: new episodes are downloaded on their own, asked for first, or not at all (default). */
    val newEpisodesMode: AutoMode
        get() = AutoMode.fromInt(sharedPreferences.getInt(Constants.PREF_NEW_EPISODES_MODE, AutoMode.OFF.intValue))

    /** How many unwatched episodes of a followed show to keep downloaded ahead; 0 - all new ones. */
    val keepEpisodes: Int
        get() = sharedPreferences.getInt(Constants.PREF_KEEP_EPISODES, 0)

    /** Watched episodes and movies are deleted on their own, offered for deletion, or kept (default). */
    val deleteWatchedMode: AutoMode
        get() = AutoMode.fromInt(sharedPreferences.getInt(Constants.PREF_DELETE_WATCHED_MODE, AutoMode.OFF.intValue))

    /** Space the downloads should stay within, in GB; 0 - no limit. */
    val storageLimitGb: Int
        get() = sharedPreferences.getInt(Constants.PREF_STORAGE_LIMIT_GB, 0)

    val lowSpaceWarning: Boolean
        get() = sharedPreferences.getBoolean(Constants.PREF_LOW_SPACE_WARNING, false)

    var mobileDataPolicy: MobileDataPolicy
        get() = MobileDataPolicy.fromInt(sharedPreferences.getInt(Constants.PREF_MOBILE_DATA_POLICY, MobileDataPolicy.ASK.intValue))
        set(value) = sharedPreferences.edit { putInt(Constants.PREF_MOBILE_DATA_POLICY, value.intValue) }

    /** Streaming bitrate cap on mobile data for the integrated player, in bit/s; 0 - no cap. */
    /** Music from the web client plays in the app's own player (not in the web view). */
    val nativeMusicPlayer: Boolean
        get() = sharedPreferences.getBoolean(Constants.PREF_NATIVE_MUSIC_PLAYER, true)

    val meteredMaxBitrate: Int
        get() = sharedPreferences.getInt(Constants.PREF_METERED_MAX_BITRATE, 0)

    /**
     * The actions to take for each media segment type. Managed by the MediaSegmentRepository.
     */
    var mediaSegmentActions: String
        get() = sharedPreferences.getString(
            Constants.PREF_MEDIA_SEGMENT_ACTIONS,
            mapOf(
                MediaSegmentType.INTRO to MediaSegmentAction.ASK_TO_SKIP,
                MediaSegmentType.OUTRO to MediaSegmentAction.ASK_TO_SKIP,
            ).toMediaSegmentActionsString(),
        )!!
        set(value) = sharedPreferences.edit { putString(Constants.PREF_MEDIA_SEGMENT_ACTIONS, value) }

    val musicNotificationAlwaysDismissible: Boolean
        get() = sharedPreferences.getBoolean(Constants.PREF_MUSIC_NOTIFICATION_ALWAYS_DISMISSIBLE, false)

    @VideoPlayerType
    val videoPlayerType: String
        get() = sharedPreferences.getString(Constants.PREF_VIDEO_PLAYER_TYPE, VideoPlayerType.EXO_PLAYER)!!

    val exoPlayerStartLandscapeVideoInLandscape: Boolean
        get() = sharedPreferences.getBoolean(Constants.PREF_EXOPLAYER_START_LANDSCAPE_VIDEO_IN_LANDSCAPE, false)

    val exoPlayerAllowSwipeGestures: Boolean
        get() = sharedPreferences.getBoolean(Constants.PREF_EXOPLAYER_ALLOW_SWIPE_GESTURES, true)

    val exoPlayerAllowPressSpeedUp: Boolean
        get() = sharedPreferences.getBoolean(Constants.PREF_EXOPLAYER_ALLOW_PRESS_SPEED_UP, true)

    val exoPlayerRememberBrightness: Boolean
        get() = sharedPreferences.getBoolean(Constants.PREF_EXOPLAYER_REMEMBER_BRIGHTNESS, false)

    var exoPlayerBrightness: Float
        get() = sharedPreferences.getFloat(Constants.PREF_EXOPLAYER_BRIGHTNESS, BRIGHTNESS_OVERRIDE_NONE)
        set(value) {
            sharedPreferences.edit {
                putFloat(Constants.PREF_EXOPLAYER_BRIGHTNESS, value)
            }
        }

    val exoPlayerAllowBackgroundAudio: Boolean
        get() = sharedPreferences.getBoolean(Constants.PREF_EXOPLAYER_ALLOW_BACKGROUND_AUDIO, false)

    val exoPlayerAllowHorizontalGesture: Boolean
        get() = sharedPreferences.getBoolean(Constants.PREF_EXOPLAYER_ALLOW_HORIZONTAL_GESTURE, true)

    val exoPlayerDirectPlayAss: Boolean
        get() = sharedPreferences.getBoolean(Constants.PREF_EXOPLAYER_DIRECT_PLAY_ASS, false)

    val exoPlayerNetworkBuffer: String
        get() = sharedPreferences.getString(Constants.PREF_EXOPLAYER_NETWORK_BUFFER, Constants.NETWORK_BUFFER_AUTO)!!

    @ExternalPlayerPackage
    var externalPlayerApp: String
        get() = sharedPreferences.getString(Constants.PREF_EXTERNAL_PLAYER_APP, ExternalPlayerPackage.SYSTEM_DEFAULT)!!
        set(value) = sharedPreferences.edit { putString(Constants.PREF_EXTERNAL_PLAYER_APP, value) }
}
