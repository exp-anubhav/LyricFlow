package com.lyricflow.app

object LrcParser {
    private val tag = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\]""")

    fun parse(lrc: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()

        lrc.lineSequence().forEach { raw ->
            val matches = tag.findAll(raw).toList()
            if (matches.isEmpty()) return@forEach

            val text = raw.replace(tag, "").trim()
            if (text.isBlank()) return@forEach

            matches.forEach { match ->
                val minutes = match.groupValues[1].toLong()
                val seconds = match.groupValues[2].toLong()
                val fraction = match.groupValues[3]

                val milliseconds = when {
                    fraction.isBlank() -> 0L
                    fraction.length == 1 -> fraction.toLong() * 100L
                    fraction.length == 2 -> fraction.toLong() * 10L
                    else -> fraction.take(3).toLong()
                }

                result += LyricLine(
                    startMs = minutes * 60_000L + seconds * 1_000L + milliseconds,
                    text = text
                )
            }
        }

        return result.sortedBy { it.startMs }
    }
}
