package com.lyricflow.app

data class LyricLine(
    val startMs: Long,
    val text: String
)

data class LyricsResult(
    val title: String,
    val artist: String,
    val synced: List<LyricLine>,
    val plain: String?,
    val album: String? = null,
    val durationMs: Long = 0L
)

object LyricsStore {
    @Volatile var current: LyricsResult? = null
    @Volatile var loading: Boolean = false
    @Volatile var error: String? = null
    @Volatile var trackKey: String = ""
}
