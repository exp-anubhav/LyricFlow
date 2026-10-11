package com.lyricflow.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Information about the currently playing media item.
 */
data class TrackInfo(
    val title: String = "Nothing playing",
    val artist: String = "—",
    val album: String = "—",
    val packageName: String = "",
    val mediaId: String = "",
    val mediaUri: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
)

object PlaybackStore {
    var track by mutableStateOf(TrackInfo())
        private set

    fun update(value: TrackInfo) {
        track = value
    }

    fun clear() {
        track = TrackInfo()
    }
}
