package com.example.arkanoidchristmas.game

/** One saved attempt: [score] is the sum over all cleared levels plus the final level's score. */
data class LeaderboardEntry(val name: String, val score: Int, val level: Int)

/** Pure leaderboard rules and (de)serialisation; storage lives in the Android layer. */
object Leaderboard {
    const val MAX_ENTRIES = 10
    const val MAX_NAME_LENGTH = 16
    const val DEFAULT_NAME = "Игрок"

    /** Trims, collapses control characters, limits the length; blank becomes [DEFAULT_NAME]. */
    fun normalizeName(raw: String?): String {
        val cleaned = (raw ?: "").map { if (it.isISOControl()) ' ' else it }.joinToString("").trim()
        return cleaned.take(MAX_NAME_LENGTH).trim().ifEmpty { DEFAULT_NAME }
    }

    /** Keeps one (best) entry per name, ignoring case, sorted best first and cut to the top 10. */
    fun add(entries: List<LeaderboardEntry>, entry: LeaderboardEntry): List<LeaderboardEntry> {
        val candidate = entry.copy(name = normalizeName(entry.name))
        val best = (entries + candidate)
            .groupBy { it.name.lowercase() }
            .values
            .map { sameName -> sameName.maxWith(compareBy({ it.score }, { it.level })) }
        return best
            .sortedWith(compareByDescending<LeaderboardEntry> { it.score }.thenByDescending { it.level }
                .thenBy { it.name.lowercase() })
            .take(MAX_ENTRIES)
    }

    fun encode(entries: List<LeaderboardEntry>): String =
        entries.joinToString("\n") { "${it.score}\t${it.level}\t${it.name}" }

    /** Ignores malformed lines so damaged storage never crashes the game. */
    fun decode(text: String?): List<LeaderboardEntry> =
        (text ?: "").lines().mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            val score = parts.getOrNull(0)?.toIntOrNull()
            val level = parts.getOrNull(1)?.toIntOrNull()
            val name = parts.getOrNull(2)
            if (parts.size == 3 && score != null && level != null && score >= 0 && name != null) {
                LeaderboardEntry(normalizeName(name), score, level)
            } else null
        }.take(MAX_ENTRIES)
}
