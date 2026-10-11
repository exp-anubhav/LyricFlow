package com.lyricflow.app

import java.util.Locale

/**
 * Splits a messy YouTube video title into possible SONG names, and for each
 * guess the "credits" (names / movie / album) that can be used as evidence.
 *
 * Examples:
 *   "Nazm Nazm - Lyrical | Bareilly Ki Barfi | Kriti Sanon, Ayushmann Khurrana & Rajkummar Rao | Arko"
 *     guess "Nazm Nazm"  credits [Bareilly Ki Barfi, Kriti Sanon, Ayushmann Khurrana, Rajkummar Rao, Arko]
 *
 *   "Makeen - Salem Sandhu ft Taimour Baig | Prod. Nimit (Official Visualizer)"
 *     guess "Makeen"                        credits [Salem Sandhu, Taimour Baig, Nimit]
 *     guess "Salem Sandhu ft Taimour Baig"  credits [Makeen, Nimit]
 *
 *   "The Local Train - Aalas Ka Pedh - Dil Mere (Official Audio)"
 *     guess "Dil Mere"       credits [The Local Train, Aalas Ka Pedh]
 *     guess "The Local Train" credits [Aalas Ka Pedh, Dil Mere]
 *     guess "Aalas Ka Pedh"   credits [The Local Train, Dil Mere]
 *
 * This file uses NO Android classes on purpose, so it can be unit-tested on a PC.
 */
object YtTitleParser {

    data class Guess(
        val title: String,
        val credits: List<String>
    )

    data class Parsed(
        val guesses: List<Guess>
    )

    // Words that describe the VIDEO, not the song.
    private val noiseWords = setOf(
        "lyrical", "lyrics", "lyric", "video", "videos", "full", "song", "songs",
        "audio", "official", "music", "hd", "4k", "visualizer", "visualiser", "hq",
        "teaser", "promo"
    )

    private val publisherTerms = listOf(
        "t series", "tseries", "sony music", "zee music", "tips official", "tips music",
        "saregama", "speed records", "universal music", "warner music", "vishesh films",
        "music company", "music india", "official", "vevo", "entertainment", "records"
    )

    private val dashRegex = Regex("\\s[-\\u2013\\u2014]\\s")

    private val rolePrefixRegex = Regex(
        "^(?:prod(?:uced)?|music|lyrics?|lyricist|composer|singers?|vocals?|directed|written)" +
            "\\b\\.?(?:\\s+by)?\\s*:?\\s+",
        RegexOption.IGNORE_CASE
    )

    fun parse(rawTitle: String): Parsed {
        val parts = rawTitle
            .split("|")
            .map { collapse(it) }
            .filter { it.isNotBlank() }

        var head = parts.firstOrNull().orEmpty()
        head = removePublisherPrefix(head)
        head = removeNoiseBrackets(head)

        // Everything after the first "|": movie, singers, actors, composer ...
        val pipeCredits = LinkedHashSet<String>()
        parts.drop(1).forEach { part ->
            splitNames(cleanCreditPart(part)).forEach { addCredit(pipeCredits, it) }
        }

        // The first block, split on " - ", without the "Lyrical Video" style noise.
        val segments = head
            .split(dashRegex)
            .map { collapse(it) }
            .filter { it.isNotBlank() && !isNoise(it) }

        val ordered: List<String> = when {
            segments.isEmpty() -> listOf(head)
            segments.size <= 2 -> segments                         // "Song - Movie" / "Artist - Song": try left first
            else -> listOf(segments.last()) + segments.dropLast(1) // "Artist - Album - Song"
        }

        val baseGuesses = ArrayList<Guess>()
        val bracketlessGuesses = ArrayList<Guess>()

        for (segment in ordered) {
            val credits = LinkedHashSet<String>()

            // The OTHER parts of the title are evidence for this guess
            // (for "Makeen - Salem Sandhu ft Taimour Baig" the artist is on the right).
            segments.filter { it != segment }.forEach { other ->
                splitNames(other).forEach { addCredit(credits, it) }
            }
            credits.addAll(pipeCredits)
            val creditList = credits.toList()

            val title = stripTrailingNoise(segment)
            if (title.isNotBlank()) baseGuesses += Guess(title, creditList)

            // Also try without "(From ...)" style brackets.
            val withoutBrackets = collapse(
                title.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), " ")
            )
            if (withoutBrackets.isNotBlank() && withoutBrackets != title) {
                bracketlessGuesses += Guess(withoutBrackets, creditList)
            }
        }

        return Parsed(baseGuesses + bracketlessGuesses)
    }

    fun looksLikePublisher(value: String): Boolean {
        val s = normalize(value)
        if (s.isBlank()) return true
        return publisherTerms.any { s.contains(it) }
    }

    private fun addCredit(into: MutableSet<String>, name: String) {
        if (name.length >= 2 && !isNoise(name) && !looksLikePublisher(name)) {
            into += name
        }
    }

    private fun cleanCreditPart(part: String): String =
        collapse(removeNoiseBrackets(part).replace(rolePrefixRegex, ""))

    private fun splitNames(part: String): List<String> =
        part.split(
            Regex(
                "\\s*(?:,|&|/|\\band\\b|\\bfeat\\.?|\\bft\\.?|\\bx\\b)\\s*",
                RegexOption.IGNORE_CASE
            )
        ).map { collapse(it) }.filter { it.isNotBlank() }

    private fun removePublisherPrefix(value: String): String {
        val dot = value.indexOf('\u00B7')
        return if (dot in 1..59) value.substring(dot + 1).trim() else value.trim()
    }

    private fun removeNoiseBrackets(value: String): String =
        collapse(
            Regex("\\s*[(\\[]([^)\\]]*)[)\\]]").replace(value) { m ->
                if (isNoise(m.groupValues[1])) " " else m.value
            }
        )

    private fun stripTrailingNoise(value: String): String {
        val words = collapse(value).split(" ").toMutableList()
        while (words.size > 1 && normalize(words.last()) in noiseWords) {
            words.removeAt(words.lastIndex)
        }
        return words.joinToString(" ").trim('-', ' ', '\u2013', '\u2014')
    }

    private fun isNoise(value: String): Boolean {
        val tokens = normalize(value).split(' ').filter { it.isNotBlank() }
        return tokens.isNotEmpty() && tokens.all { it in noiseWords }
    }

    private fun collapse(value: String): String =
        value.replace(Regex("\\s+"), " ").trim()

    private fun normalize(value: String): String =
        collapse(
            value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        )
}
