package org.jellyfin.mobile.downloads

enum class DownloadFileType {
    /**
     * The main file for an item (e.g. the video file for episodes/movies, or the audio file for music).
     */
    ITEM,

    /**
     * The primary image for the item.
     */
    IMAGE_PRIMARY,

    /**
     * The series poster of an episode, shared by all episodes of the series.
     */
    IMAGE_SERIES,

    /**
     * The season poster of an episode, shared by all episodes of the season.
     */
    IMAGE_SEASON,
}
