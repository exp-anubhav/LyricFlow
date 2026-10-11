package com.lyricflow.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs

/**
 * Unofficial YouTube Music timed-lyrics provider.
 *
 * Flow:
 *   track metadata -> YouTube Music search -> best videoId
 *   -> /next -> lyrics browseId -> /browse -> timedLyricsData
 *
 * This intentionally has no external dependency.  The client API key is a
 * public YouTube client key used by the open-source lrclib-cache-proxy project;
 * it is not a user-specific/private API credential.
 */
object YoutubeMusicLyricsProvider {

    private const val TAG = "LyricFlow-YTMusic"
    private const val API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    private const val BASE_URL = "https://music.youtube.com/youtubei/v1"

    private const val SEARCH_CLIENT_NAME = "WEB_REMIX"
    private const val SEARCH_CLIENT_VERSION = "1.20240101.01.00"
    private const val LYRICS_CLIENT_NAME = "ANDROID_MUSIC"
    private const val LYRICS_CLIENT_VERSION = "7.21.50"

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 8_000

    private data class Candidate(
        val videoId: String,
        val title: String,
        val artists: List<String>,
        val album: String,
        val durationSeconds: Int,
        val score: Double
    )

    private data class QueryProfile(
        val title: String,
        val artist: String,
        val album: String,
        val credits: List<String> = emptyList()
    )

    fun fetch(track: TrackInfo): LyricsResult? {
        if (!isYouTubeLikePackage(track.packageName)) return null
        if (track.title.isBlank() || track.title.equals("Nothing playing", true)) return null

        val profiles = buildProfiles(track)
        if (profiles.isEmpty()) return null
        val targetDurationSeconds = (track.durationMs.takeIf { it > 0L } ?: 0L) / 1000L

        for (profile in profiles.take(3)) {
            // The song changed while we were searching: stop wasting requests.
            if (Thread.currentThread().isInterrupted) return null

            Log.d(
                TAG,
                "Searching: title=\"${profile.title}\" | artist=\"${profile.artist}\" | " +
                    "album=\"${profile.album}\" | credits=${profile.credits.take(6)} | duration=${track.durationMs}ms"
            )

            val candidates = search(profile, targetDurationSeconds.toInt())
            if (candidates.isEmpty()) {
                Log.d(TAG, "No YouTube Music search candidates")
                continue
            }

            val best = candidates.maxByOrNull { it.score } ?: continue

            Log.d(
                TAG,
                "Best candidate: videoId=${best.videoId} | title=\"${best.title}\" | " +
                    "artists=\"${best.artists.joinToString()}\" | album=\"${best.album}\" | " +
                    "duration=${best.durationSeconds}s | score=${"%.2f".format(Locale.US, best.score)}"
            )

            // Never fetch lyrics for a weak match. This is important because
            // YouTube Music search can return covers, remixes or related songs.
            if (best.score < 6.0) {
                Log.d(TAG, "Best candidate rejected: score too low")
                continue
            }

            val lrc = fetchTimedLyrics(best.videoId)
            if (lrc != null) {
                return LyricsResult(
                    title = best.title.ifBlank { profile.title },
                    artist = best.artists.firstOrNull().orEmpty().ifBlank { profile.artist },
                    synced = LrcParser.parse(lrc),
                    plain = null
                ).takeIf { it.synced.isNotEmpty() }
            }

            // We found the right song, but YouTube Music has no timed lyrics for
            // it. Do not try the other title guesses: they could be a different
            // song, and wrong lyrics are worse than none. LRCLIB is tried next.
            return null
        }

        return null
    }

    private fun isYouTubeLikePackage(packageName: String): Boolean {
        val p = packageName.lowercase(Locale.ROOT)
        return p.contains("youtube") || p == "anddea.yrf.tube"
    }

    private fun buildProfiles(track: TrackInfo): List<QueryProfile> {
        val parsed = YtTitleParser.parse(collapseWhitespace(track.title))
        val rawArtist = cleanUnknown(track.artist)
        val rawAlbum = cleanUnknown(track.album)

        // The MediaSession "artist" on YouTube is usually the channel
        // (Sony Music India, Zee Music Company...). Only use it when it does
        // not look like a publisher.
        val artistHint =
            if (rawArtist.isNotBlank() && !looksLikePublisher(rawArtist)) cleanArtist(rawArtist) else ""

        return parsed.guesses
            .map { guess ->
                QueryProfile(
                    title = guess.title,
                    artist = artistHint,
                    album = rawAlbum,
                    credits = guess.credits
                )
            }
            .filter { it.title.isNotBlank() }
            .distinctBy { normalize(it.title) }
            // A guess that is just the channel/artist name is the least likely song title.
            .sortedBy { artistHint.isNotBlank() && similarity(it.title, artistHint) >= 0.9 }
            .take(3)
    }

    private fun search(profile: QueryProfile, targetDurationSeconds: Int): List<Candidate> {
        val queryExtra = profile.artist.ifBlank { profile.credits.firstOrNull().orEmpty() }
        val query = buildString {
            append(profile.title)
            if (queryExtra.isNotBlank()) {
                append(' ')
                append(queryExtra)
            }
        }.trim()

        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", SEARCH_CLIENT_NAME)
                    put("clientVersion", SEARCH_CLIENT_VERSION)
                })
            })
            put("query", query)
            put("params", "EgWKAQIIAWoMEA4QChADEAQQCRAF")
        }

        val json = postJson("$BASE_URL/search?alt=json&key=$API_KEY", body.toString())
            ?: return emptyList()

        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val shelf = findFirstObjectWithKey(root, "musicShelfRenderer") ?: return emptyList()
        val contents = shelf.optJSONArray("contents") ?: return emptyList()

        val candidates = mutableListOf<Candidate>()
        for (i in 0 until minOf(contents.length(), 8)) {
            val wrapper = contents.optJSONObject(i) ?: continue
            val item = wrapper.optJSONObject("musicResponsiveListItemRenderer") ?: continue

            val videoId = extractVideoId(item)
            if (videoId.isBlank()) continue

            val title = extractTitle(item)
            if (title.isBlank()) continue

            val secondaryRuns = item
                .optJSONArray("flexColumns")
                ?.optJSONObject(1)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs")

            val artists = mutableListOf<String>()
            var album = ""
            var durationSeconds = 0

            if (secondaryRuns != null) {
                for (j in 0 until secondaryRuns.length()) {
                    val run = secondaryRuns.optJSONObject(j) ?: continue
                    val text = run.optString("text").trim()
                    if (text.isBlank()) continue

                    val endpoint = run.optJSONObject("navigationEndpoint")
                    val browse = endpoint?.optJSONObject("browseEndpoint")
                    val browseId = browse?.optString("browseId").orEmpty()
                    val pageType = browse
                        ?.optJSONObject("browseEndpointContextSupportedConfigs")
                        ?.optJSONObject("browseEndpointContextMusicConfig")
                        ?.optString("pageType")
                        .orEmpty()

                    when {
                        pageType == "MUSIC_PAGE_TYPE_ARTIST" ||
                            browseId.startsWith("UC") ||
                            browseId.startsWith("FVC") -> artists += text

                        pageType == "MUSIC_PAGE_TYPE_ALBUM" || browseId.startsWith("MPRE") -> {
                            album = text
                        }

                        durationSeconds == 0 -> {
                            durationSeconds = parseDurationSeconds(text)
                        }
                    }
                }
            }

            val score = scoreCandidate(
                candidateTitle = title,
                candidateArtists = artists,
                candidateAlbum = album,
                candidateDurationSeconds = durationSeconds,
                targetDurationSeconds = targetDurationSeconds,
                profile = profile
            )

            candidates += Candidate(
                videoId = videoId,
                title = title,
                artists = artists,
                album = album,
                durationSeconds = durationSeconds,
                score = score
            )
        }

        return candidates.sortedByDescending { it.score }
    }

    private fun extractVideoId(item: JSONObject): String {
        item.optJSONObject("playlistItemData")?.optString("videoId")?.let {
            if (it.isNotBlank()) return it
        }

        val flex = item.optJSONArray("flexColumns")
        val firstRuns = flex
            ?.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")

        if (firstRuns != null && firstRuns.length() > 0) {
            val endpoint = firstRuns.optJSONObject(0)
                ?.optJSONObject("navigationEndpoint")
                ?.optJSONObject("watchEndpoint")
            endpoint?.optString("videoId")?.let {
                if (it.isNotBlank()) return it
            }
        }

        val overlay = item.optJSONObject("overlay")
            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("playNavigationEndpoint")
            ?.optJSONObject("watchEndpoint")

        return overlay?.optString("videoId").orEmpty()
    }

    private fun extractTitle(item: JSONObject): String {
        return item
            .optJSONArray("flexColumns")
            ?.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text")
            ?.trim()
            .orEmpty()
    }

    private fun scoreCandidate(
        candidateTitle: String,
        candidateArtists: List<String>,
        candidateAlbum: String,
        candidateDurationSeconds: Int,
        targetDurationSeconds: Int,
        profile: QueryProfile
    ): Double {
        var score = 0.0

        val titleScore = similarity(profile.title, candidateTitle)
        score += titleScore * 5.0

        // Names from the video title ("|" parts) plus the channel/artist hint.
        val nameHints = (profile.credits + listOf(profile.artist))
            .filter { it.isNotBlank() }

        var artistScore = 0.0
        if (nameHints.isNotEmpty() && candidateArtists.isNotEmpty()) {
            artistScore = candidateArtists.maxOf { candidateArtist ->
                nameHints.maxOf { hint -> similarity(hint, candidateArtist) }
            }
            score += artistScore * 3.0
        }

        // The movie / album name is usually one of the "|" parts.
        var albumScore = 0.0
        val albumHints = (profile.credits + listOf(profile.album))
            .filter { it.isNotBlank() }
        if (albumHints.isNotEmpty() && candidateAlbum.isNotBlank()) {
            albumScore = albumHints.maxOf { hint -> similarity(hint, candidateAlbum) }
            score += albumScore * 1.5
        }

        // The video title listed credits, but this candidate matches none of
        // them (no artist, no movie): probably another song with the same name.
        if (
            profile.credits.isNotEmpty() &&
            candidateArtists.isNotEmpty() &&
            artistScore < 0.5 &&
            albumScore < 0.5
        ) {
            score -= 3.0
        }

        if (targetDurationSeconds > 0 && candidateDurationSeconds > 0) {
            val diff = abs(targetDurationSeconds - candidateDurationSeconds)
            score += when {
                diff <= 3 -> 2.0
                diff <= 10 -> 1.5
                diff <= 20 -> 0.75
                else -> 0.0
            }
        }

        return score
    }

    private fun fetchTimedLyrics(videoId: String): String? {
        val nextBody = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", SEARCH_CLIENT_NAME)
                    put("clientVersion", SEARCH_CLIENT_VERSION)
                })
            })
            put("videoId", videoId)
        }

        val nextJson = postJson("$BASE_URL/next?alt=json&key=$API_KEY", nextBody.toString())
            ?: return null

        val nextRoot = runCatching { JSONObject(nextJson) }.getOrNull() ?: return null
        val browseId = findLyricsBrowseId(nextRoot)
        if (browseId.isNullOrBlank()) {
            Log.d(TAG, "No lyrics browseId for videoId=$videoId")
            return null
        }

        val browseBody = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", LYRICS_CLIENT_NAME)
                    put("clientVersion", LYRICS_CLIENT_VERSION)
                })
            })
            put("browseId", browseId)
        }

        val browseJson = postJson("$BASE_URL/browse?alt=json&key=$API_KEY", browseBody.toString())
            ?: return null

        val browseRoot = runCatching { JSONObject(browseJson) }.getOrNull() ?: return null
        val timed = findTimedLyricsArray(browseRoot) ?: return null
        if (timed.length() == 0) return null

        val lrc = StringBuilder()
        for (i in 0 until timed.length()) {
            val line = timed.optJSONObject(i) ?: continue
            val cue = line.optJSONObject("cueRange") ?: continue
            val start = anyLong(cue.opt("startTimeMilliseconds")) ?: continue
            val text = line.optString("lyricLine").trim()
            if (text.isBlank()) continue

            val minutes = start / 60_000L
            val seconds = (start % 60_000L) / 1_000L
            val centiseconds = (start % 1_000L) / 10L
            lrc.append(String.format(Locale.US, "[%02d:%02d.%02d] %s\n", minutes, seconds, centiseconds, text))
        }

        return lrc.toString().trim().ifBlank { null }
    }

    private fun postJson(url: String, body: String): String? {
        val connection = runCatching {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "LyricFlow/0.4.0")
            }
        }.getOrNull() ?: return null

        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.d(TAG, "HTTP $code from ${connection.url.path}")
                return null
            }

            BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
        } catch (e: Exception) {
            Log.d(TAG, "Request failed: ${e.javaClass.simpleName}: ${e.message}")
            // Android clears the interrupted flag when it throws this; set it
            // again so the rest of this cancelled lookup stops too.
            if (e is java.io.InterruptedIOException) Thread.currentThread().interrupt()
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun findFirstObjectWithKey(root: JSONObject, key: String): JSONObject? {
        val direct = root.optJSONObject(key)
        if (direct != null) return direct

        val iterator = root.keys()
        while (iterator.hasNext()) {
            val child = root.opt(iterator.next())
            val found = when (child) {
                is JSONObject -> findFirstObjectWithKey(child, key)
                is JSONArray -> findFirstObjectWithKeyInArray(child, key)
                else -> null
            }
            if (found != null) return found
        }
        return null
    }

    private fun findFirstObjectWithKeyInArray(array: JSONArray, key: String): JSONObject? {
        for (i in 0 until array.length()) {
            when (val child = array.opt(i)) {
                is JSONObject -> findFirstObjectWithKey(child, key)?.let { return it }
                is JSONArray -> findFirstObjectWithKeyInArray(child, key)?.let { return it }
            }
        }
        return null
    }

    private fun findLyricsBrowseId(value: Any?): String? {
        when (value) {
            is JSONObject -> {
                val browse = value.optJSONObject("browseEndpoint")
                if (browse != null) {
                    val config = browse.optJSONObject("browseEndpointContextSupportedConfigs")
                        ?.optJSONObject("browseEndpointContextMusicConfig")
                    val pageType = config?.optString("pageType").orEmpty()
                    val browseId = browse.optString("browseId").trim()
                    if (pageType == "MUSIC_PAGE_TYPE_TRACK_LYRICS" && browseId.isNotBlank()) {
                        return browseId
                    }
                }

                val keys = value.keys()
                while (keys.hasNext()) {
                    val child = value.opt(keys.next())
                    val found = findLyricsBrowseId(child)
                    if (found != null) return found
                }
            }

            is JSONArray -> {
                for (i in 0 until value.length()) {
                    val found = findLyricsBrowseId(value.opt(i))
                    if (found != null) return found
                }
            }
        }
        return null
    }

    private fun findTimedLyricsArray(value: Any?): JSONArray? {
        when (value) {
            is JSONObject -> {
                val direct = value.optJSONArray("timedLyricsData")
                if (direct != null) return direct

                val keys = value.keys()
                while (keys.hasNext()) {
                    val found = findTimedLyricsArray(value.opt(keys.next()))
                    if (found != null) return found
                }
            }

            is JSONArray -> {
                for (i in 0 until value.length()) {
                    val found = findTimedLyricsArray(value.opt(i))
                    if (found != null) return found
                }
            }
        }
        return null
    }

    private fun anyLong(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }

    private fun parseDurationSeconds(value: String): Int {
        val parts = value.split(":")
        if (parts.size !in 2..3 || parts.any { it.toIntOrNull() == null }) return 0
        return if (parts.size == 2) {
            parts[0].toInt() * 60 + parts[1].toInt()
        } else {
            parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toInt()
        }
    }

    private fun removePublisherPrefix(value: String): String {
        var s = value.trim()
        val dot = s.indexOf('·')
        if (dot >= 0 && dot < 60) {
            s = s.substring(dot + 1).trim()
        }
        return s
    }

    private fun stripPresentation(value: String): String {
        var s = collapseWhitespace(value)

        s = s.replace(
            Regex(
                """\\s*[\\[(]\\s*(official(\\s+(audio|video))?|audio|video|lyrics?|lyric\\s+video|lyrical\\s+video|visualizer|4k|hd)\\s*[\\])]\\s*""",
                RegexOption.IGNORE_CASE
            ),
            " "
        )

        val patterns = listOf(
            "\\b4k\\s+music\\s+video\\b",
            "\\b4k\\s+video\\b",
            "\\bofficial\\s+music\\s+video\\b",
            "\\bofficial\\s+video\\b",
            "\\bofficial\\s+audio\\b",
            "\\blyrical\\s+video\\b",
            "\\blyrics?\\s+video\\b",
            "\\blyric\\s+video\\b",
            "\\bmusic\\s+video\\b"
        )
        for (pattern in patterns) {
            s = s.replace(Regex(pattern, RegexOption.IGNORE_CASE), " ")
        }

        return collapseWhitespace(s).trim('-', '·', '|', ' ')
    }

    private fun looksLikePublisher(value: String): Boolean {
        val s = normalize(value)
        if (s.isBlank()) return true
        val publisherTerms = listOf(
            "vishesh films", "sony music", "sony music india", "t series",
            "saregama", "zee music", "zee music company", "tips official",
            "tips music", "speed records", "universal music", "warner music",
            "official", "music india", "films official"
        )
        return publisherTerms.any { s == normalize(it) || s.contains(normalize(it)) }
    }

    private fun cleanArtist(value: String): String {
        val s = cleanUnknown(value)
        if (s.isBlank()) return ""
        return stripPresentation(s).substringBefore("|").trim()
    }

    private fun cleanUnknown(value: String): String {
        val s = collapseWhitespace(value)
        if (
            s.isBlank() ||
            s.equals("unknown", true) ||
            s.equals("unknown artist", true) ||
            s.equals("unknown album", true) ||
            s == "<unknown>" ||
            s == "—"
        ) return ""
        return s
    }

    private fun collapseWhitespace(value: String): String =
        value.replace(Regex("\\s+"), " ").trim()

    private fun normalize(value: String): String =
        collapseWhitespace(value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}\\s]"), " "))

    private fun similarity(a: String, b: String): Double {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isBlank() || nb.isBlank()) return 0.0
        if (na == nb) return 1.0
        if (" $na ".contains(" $nb ") || " $nb ".contains(" $na ")) return 0.9

        val aw = na.split(' ').filter { it.isNotBlank() }.toSet()
        val bw = nb.split(' ').filter { it.isNotBlank() }.toSet()
        if (aw.isEmpty() || bw.isEmpty()) return 0.0

        return aw.intersect(bw).size.toDouble() / aw.union(bw).size.toDouble()
    }
}
