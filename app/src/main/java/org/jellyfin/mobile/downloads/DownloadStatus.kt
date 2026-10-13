package org.jellyfin.mobile.downloads

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    DOWNLOADED,
    ERROR,
    CANCELLED,

    /** Stopped by the user ("Pause"), continues with "Resume". */
    PAUSED,
}
