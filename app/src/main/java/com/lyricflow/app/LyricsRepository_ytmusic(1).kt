package com.lyricflow.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Locale

/**
 * Provider order:
 * 1. YouTube Music for YouTube/ReVanced playback.
 * 2. LRCLIB search/get fallback for everything else or when YouTube Music
 *    has no timed lyrics.
 */
object LyricsRepository {

    private const val BASE_URL = "https://lrclib.net"
    private const val USER_AGENT = "LyricFlow/0.4.0 (Android synchronized lyrics app)"
    private const val TIMEOUT_MS = 6_000

    fun fetch(track: TrackInfo): LyricsResult? {
        // YouTube Music first. This uses title/artist/album/duration to resolve
        // a YouTube Music video ID, then asks that video for timed lyrics.
        YoutubeMusicLyricsProvider.fetch(track)?.let { return it }

        return fetchFromLrclib(track)
    }

    private data class Candidate(
        val title: String,
        val artist: String,
        val album: String?
    )

    private fun fetchFromLrclib(track: TrackInfo): LyricsResult? {
        val candidates = buildCandidates(track)

        // Fast search path first.
        for (candidate in candidates) {
            val results = searchLyrics(candidate.title, candidate.artist)
            val best = chooseBest(results, candidate)
            if (best != null) return best
        }

        // Precise endpoint last, because it can be slower.
        for (candidate in candidates.take(2)) {
            val precise = getLyrics(
                candidate.title,
                candidate.artist,
                candidate.album,
                track.durationMs.takeIf { it > 0L }
            )
            if (precise != null) return precise
        }

        return null
    }

    private fun buildCandidates(track: TrackInfo): List<Candidate> {
        val rawTitle = collapseWhitespace(track.title)
        val rawArtist = cleanArtist(track.artist)
        val rawAlbum = cleanAlbum(track.album)

        val firstBlock = rawTitle.substringBefore("|").trim()
        val main = stripPresentation(removePublisherPrefix(firstBlock))
        val list = mutableListOf<Candidate>()

        fun add(title: String, artist: String = rawArtist, album: String? = rawAlbum) {
            if (title.isBlank()) return
            list += Candidate(title.trim(), artist.trim(), album)
        }

        val dash = Regex("\\s-\\s").find(main)
        if (dash != null) {
            val left = main.substring(0, dash.range.first).trim()
            val right = main.substring(dash.range.last + 1).trim()
            if (right.isNotBlank()) {
                add(right, rawArtist.ifBlank { cleanArtist(left) })
            }
        }

        add(main)

        return list
            .distinctBy { Triple(normalize(it.title), normalize(it.artist), normalize(it.album.orEmpty())) }
            .take(4)
    }

    private fun getLyrics(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?
    ): LyricsResult? {
        if (artist.isBlank()) return null

        return try {
            val params = linkedMapOf(
                "track_name" to title,
                "artist_name" to artist
            )
            if (!album.isNullOrBlank()) params["album_name"] = album
            if (durationMs != null && durationMs > 0L) {
                params["duration"] = (durationMs / 1000L).toString()
            }

            val json = requestJson("$BASE_URL/api/get", params) ?: return null
            parseResult(json)
        } catch (_: Exception) {
            null
        }
    }

    private fun searchLyrics(title: String, artist: String): List<LyricsResult> {
        if (artist.isBlank()) return emptyList()

        return try {
            val json = requestJson(
                "$BASE_URL/api/search",
                linkedMapOf(
                    "track_name" to title,
                    "artist_name" to artist
                )
            ) ?: return emptyList()

            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    parseResult(item)?.let { add(it) }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun requestJson(base: String, params: Map<String, String>): String? {
        val query = params.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }

        val connection = (URL("$base?$query").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }

        return try {
            if (connection.responseCode !in 200..299) return null
            BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResult(json: String): LyricsResult? =
        runCatching { parseResult(JSONObject(json)) }.getOrNull()

    private fun parseResult(obj: JSONObject): LyricsResult? {
        val title = obj.optString("trackName").trim()
        val artist = obj.optString("artistName").trim()
        if (title.isBlank() || artist.isBlank()) return null

        val syncedText = obj.optString("syncedLyrics").trim()
        if (syncedText.isBlank()) return null

        val synced = LrcParser.parse(syncedText)
        if (synced.isEmpty()) return null

        return LyricsResult(
            title = title,
            artist = artist,
            synced = synced,
            plain = obj.optString("plainLyrics").takeIf { it.isNotBlank() }
        )
    }

    private fun chooseBest(results: List<LyricsResult>, candidate: Candidate): LyricsResult? {
        if (results.isEmpty()) return null

        val scored = results.map { result ->
            val titleScore = similarity(candidate.title, result.title)
            val artistScore = similarity(candidate.artist, result.artist)
            result to (titleScore * 0.65 + artistScore * 0.35)
        }

        return scored
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= 0.60 }
            ?.first
    }

    private fun removePublisherPrefix(value: String): String {
        val s = value.trim()
        val dot = s.indexOf('·')
        return if (dot in 1..59) s.substring(dot + 1).trim() else s
    }

    private fun cleanArtist(value: String): String {
        val s = collapseWhitespace(value)
        if (
            s.isBlank() ||
            s.equals("unknown", true) ||
            s.equals("unknown artist", true) ||
            s == "<unknown>" ||
            s == "—"
        ) return ""
        return s.substringBefore("|").trim()
    }

    private fun cleanAlbum(value: String): String? {
        val s = collapseWhitespace(value)
        if (s.isBlank() || s.equals("unknown album", true) || s == "—") return null
        return s
    }

    private fun stripPresentation(value: String): String {
        var s = collapseWhitespace(value)
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

    private fun collapseWhitespace(value: String): String =
        value.replace(Regex("\\s+"), " ").trim()

    private fun normalize(value: String): String =
        collapseWhitespace(value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}\\s]"), " "))

    private fun similarity(a: String, b: String): Double {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isBlank() || nb.isBlank()) return 0.0
        if (na == nb) return 1.0
        if (na.contains(nb) || nb.contains(na)) return 0.9

        val aw = na.split(' ').filter { it.isNotBlank() }.toSet()
        val bw = nb.split(' ').filter { it.isNotBlank() }.toSet()
        if (aw.isEmpty() || bw.isEmpty()) return 0.0
        return aw.intersect(bw).size.toDouble() / aw.union(bw).size.toDouble()
    }
}
