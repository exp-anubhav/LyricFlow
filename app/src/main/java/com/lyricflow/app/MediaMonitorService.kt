package com.lyricflow.app

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.service.notification.NotificationListenerService

class MediaMonitorService : NotificationListenerService() {

    companion object {
        private const val TAG = "LyricFlow-Media"
    }

    private lateinit var sessionManager: MediaSessionManager
    private var activeController: MediaController? = null

    private val handler = Handler(Looper.getMainLooper())
    private val lyricsEngine = LyricsEngine()
    private var lastLoggedMetadataSignature: String = ""

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            activeController?.let { publish(it) }
        }

        override fun onPlaybackStateChanged(
            state: android.media.session.PlaybackState?
        ) {
            activeController?.let { publish(it) }
        }
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            selectController(controllers.orEmpty())
        }

    private val positionTicker = object : Runnable {
        override fun run() {
            activeController?.let { publish(it) }
            handler.postDelayed(this, 250L)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()

        sessionManager =
            getSystemService(MediaSessionManager::class.java)

        sessionManager.addOnActiveSessionsChangedListener(
            sessionsListener,
            ComponentName(this, MediaMonitorService::class.java)
        )

        selectController(
            sessionManager.getActiveSessions(
                ComponentName(this, MediaMonitorService::class.java)
            )
        )

        handler.post(positionTicker)
    }

    private fun selectController(
        controllers: List<MediaController>
    ) {
        val controller = controllers
            .sortedByDescending {
                it.playbackState?.state ==
                    android.media.session.PlaybackState.STATE_PLAYING
            }
            .firstOrNull()

        if (controller === activeController) {
            if (controller == null) {
                clearCurrentPlayback()
            }
            return
        }

        activeController?.unregisterCallback(controllerCallback)
        activeController = controller

        if (activeController == null) {
            clearCurrentPlayback()
            return
        }

        activeController?.registerCallback(controllerCallback)

        activeController?.let {
            Log.d(TAG, "Selected media package=${it.packageName}")
            publish(it)
        }
    }

    private fun clearCurrentPlayback() {
        Log.d(TAG, "No active media session. Clearing current track and lyrics.")
        PlaybackStore.clear()
        lyricsEngine.clearCurrent()
        lastLoggedMetadataSignature = ""
    }

    private fun publish(controller: MediaController) {
        val metadata = controller.metadata
        val state = controller.playbackState

        val title = metadata
            ?.getString(MediaMetadata.METADATA_KEY_TITLE)
            .orEmpty()
            .ifBlank { "Unknown title" }

        val artist = metadata
            ?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            .orEmpty()
            .ifBlank { "Unknown artist" }

        val album = metadata
            ?.getString(MediaMetadata.METADATA_KEY_ALBUM)
            .orEmpty()
            .ifBlank { "Unknown album" }

        val mediaId = metadata
            ?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            .orEmpty()

        val mediaUri = metadata
            ?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
            .orEmpty()

        val duration =
            metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)
                ?: 0L

        val position = state?.let {
            if (it.position < 0L) {
                0L
            } else {
                val elapsed =
                    (SystemClock.elapsedRealtime() -
                        it.lastPositionUpdateTime)
                        .coerceAtLeast(0L)

                if (
                    it.state ==
                    android.media.session.PlaybackState.STATE_PLAYING
                ) {
                    it.position +
                        (elapsed * it.playbackSpeed).toLong()
                } else {
                    it.position
                }
            }
        } ?: 0L

        val metadataSignature = listOf(
            controller.packageName,
            title,
            artist,
            album,
            mediaId,
            mediaUri,
            duration
        ).joinToString("|")

        if (metadataSignature != lastLoggedMetadataSignature) {
            lastLoggedMetadataSignature = metadataSignature

            Log.d(
                TAG,
                "TRACK title=\"$title\" | artist=\"$artist\" | album=\"$album\" | " +
                    "mediaId=\"$mediaId\" | mediaUri=\"$mediaUri\" | durationMs=$duration"
            )

            logMediaSessionMetadata(controller, metadata)
        }

        val track = TrackInfo(
            title = title,
            artist = artist,
            album = album,
            packageName = controller.packageName ?: "",
            mediaId = mediaId,
            mediaUri = mediaUri,
            isPlaying =
                state?.state ==
                    android.media.session.PlaybackState.STATE_PLAYING,
            positionMs = position.coerceAtLeast(0L),
            durationMs = duration.coerceAtLeast(0L)
        )

        PlaybackStore.update(track)
        lyricsEngine.loadFor(track)
    }

    private fun logMediaSessionMetadata(
        controller: MediaController,
        metadata: MediaMetadata?
    ) {
        if (metadata == null) {
            Log.d(TAG, "No MediaMetadata available for package=${controller.packageName}")
            return
        }

        val keys = metadata.keySet().sorted()

        Log.d(
            TAG,
            "Metadata keys for ${controller.packageName}: ${keys.joinToString()}"
        )

        for (key in keys) {
            val value = runCatching {
                metadata.getString(key)
            }.getOrNull()

            Log.d(
                TAG,
                "META key=$key | value=${value ?: "<non-string-or-null>"}"
            )
        }

        val description = controller.metadata?.description
        if (description != null) {
            Log.d(
                TAG,
                "DESCRIPTION mediaId=${description.mediaId} | " +
                    "mediaUri=${description.mediaUri} | " +
                    "title=${description.title} | subtitle=${description.subtitle} | " +
                    "description=${description.description}"
            )
        }
    }

    override fun onListenerDisconnected() {
        handler.removeCallbacks(positionTicker)

        activeController?.unregisterCallback(controllerCallback)
        activeController = null

        if (::sessionManager.isInitialized) {
            sessionManager.removeOnActiveSessionsChangedListener(
                sessionsListener
            )
        }

        clearCurrentPlayback()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        handler.removeCallbacks(positionTicker)
        activeController?.unregisterCallback(controllerCallback)
        activeController = null
        lyricsEngine.shutdown()
        clearCurrentPlayback()
        super.onDestroy()
    }
}
