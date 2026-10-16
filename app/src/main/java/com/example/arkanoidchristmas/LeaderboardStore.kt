package com.example.arkanoidchristmas

import android.content.SharedPreferences
import com.example.arkanoidchristmas.game.Leaderboard
import com.example.arkanoidchristmas.game.LeaderboardEntry

/** Local, on-device leaderboard persisted in [prefs]. */
class LeaderboardStore(private val prefs: SharedPreferences) {
    fun load(): List<LeaderboardEntry> = Leaderboard.decode(prefs.getString(KEY, null))

    fun add(entry: LeaderboardEntry): List<LeaderboardEntry> {
        val updated = Leaderboard.add(load(), entry)
        prefs.edit().putString(KEY, Leaderboard.encode(updated)).apply()
        return updated
    }

    private companion object {
        const val KEY = "entries"
    }
}
