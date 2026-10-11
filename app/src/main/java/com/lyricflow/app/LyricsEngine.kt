package com.lyricflow.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Loads synchronized lyrics without blocking media-session callbacks.
 *
 * Successful results are cached. A transient provider/network failure is not
 * cached as a permanent miss: the current track is retried twice with a delay.
 */
class LyricsEngine {

    companion object {
        private const val TAG = "LeRisk-LyricsEngine"
        private const val MAX_CACHE_SIZE = 12
        private val RETRY_DELAYS_MS = longArrayOf(4_000L, 12_000L)
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var lastKey = ""
    private var activeTrack: TrackInfo? = null
    private var currentTask: Future<*>? = null
    private var retryRunnable: Runnable? = null

    private val retryAttempts = LinkedHashMap<String, Int>()

    private val cache = object : LinkedHashMap<String, LyricsResult>(
        MAX_CACHE_SIZE, 0.75f, true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, LyricsResult>?
        ): Boolean = size > MAX_CACHE_SIZE
    }

    @Synchronized
    private fun cached(key: String): LyricsResult? = cache[key]

    @Synchronized
    private fun putCache(key: String, value: LyricsResult) {
        cache[key] = value
    }

    private fun keyFor(track: TrackInfo): String {
        // Position and playback state change frequently; they must not trigger
        // another network request. Metadata fields help detect settled metadata.
        return listOf(
            track.packageName.trim(),
            track.title.trim(),
            track.artist.trim(),
            track.album.trim(),
            track.mediaId.trim(),
            track.durationMs.coerceAtLeast(0L).toString()
        ).joinToString("|")
    }

    fun loadFor(track: TrackInfo) {
        val title = track.title.trim()
        if (
            title.isBlank() ||
            title.equals("Nothing playing", ignoreCase = true) ||
            title.equals("Unknown title", ignoreCase = true)
        ) {
            return
        }

        activeTrack = track
        val key = keyFor(track)
        if (key == lastKey) return

        cancelScheduledRetry()
        currentTask?.cancel(true)
        currentTask = null
        lastKey = key

        LyricsStore.current = null
        LyricsStore.error = null
        LyricsStore.loading = true
        LyricsStore.trackKey = key

        val cachedResult = cached(key)
        if (cachedResult != null) {
            LyricsStore.current = cachedResult
            LyricsStore.loading = false
            LyricsStore.error = null
            return
        }

        currentTask = executor.submit {
            // Always deliver a completion, even if a provider unexpectedly throws.
            // Otherwise the UI could remain stuck in "loading" until app restart.
            val result = try {
                LyricsRepository.fetch(track)
            } catch (t: Throwable) {
                if (t is ThreadDeath || t is VirtualMachineError) throw t
                Log.w(TAG, "Lyrics lookup failed unexpectedly for key=$key", t)
                null
            }

            if (result != null && result.synced.isNotEmpty()) {
                putCache(key, result)
            }

            main.post {
                // Ignore results from a song that has since changed.
                if (LyricsStore.trackKey != key || lastKey != key) return@post

                val usableResult = result?.takeIf { it.synced.isNotEmpty() }
                LyricsStore.current = usableResult
                LyricsStore.loading = false
                LyricsStore.error = if (usableResult == null) {
                    "Lyrics not found — retrying…"
                } else {
                    retryAttempts.remove(key)
                    null
                }

                if (usableResult == null) {
                    scheduleRetry(key)
                }
            }
        }
    }

    private fun scheduleRetry(key: String) {
        cancelScheduledRetry()

        val attempt = retryAttempts[key] ?: 0
        if (attempt >= RETRY_DELAYS_MS.size) {
            LyricsStore.error = "Lyrics not found"
            return
        }

        retryAttempts[key] = attempt + 1
        val delay = RETRY_DELAYS_MS[attempt]
        val task = Runnable {
            retryRunnable = null
            val track = activeTrack
            if (
                track == null ||
                LyricsStore.trackKey != key ||
                lastKey != key ||
                keyFor(track) != key
            ) {
                return@Runnable
            }

            // Allow loadFor to retry this same track. Failure results are never
            // placed in the success cache.
            lastKey = ""
            loadFor(track)
        }
        retryRunnable = task
        main.postDelayed(task, delay)
        Log.d(TAG, "Scheduled lyrics retry ${attempt + 1}/${RETRY_DELAYS_MS.size} in ${delay}ms")
    }

    private fun cancelScheduledRetry() {
        retryRunnable?.let(main::removeCallbacks)
        retryRunnable = null
    }

    /**
     * Clears active lyrics when media disappears. Successful cached results are
     * kept during normal track changes, but a fully cleared session resets retries.
     */
    fun clearCurrent() {
        cancelScheduledRetry()
        currentTask?.cancel(true)
        currentTask = null
        activeTrack = null
        lastKey = ""
        retryAttempts.clear()

        LyricsStore.current = null
        LyricsStore.loading = false
        LyricsStore.error = null
        LyricsStore.trackKey = ""
    }

    fun shutdown() {
        clearCurrent()
        executor.shutdownNow()
    }
}
