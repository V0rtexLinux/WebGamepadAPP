package com.gamemapper.utils

import android.content.Context
import com.gamemapper.models.FarmSession
import com.gamemapper.models.FarmStats
import com.gamemapper.models.MinigameType
import com.google.gson.Gson

/**
 * Persists and retrieves farm statistics across sessions.
 */
object StatsManager {
    private val gson = Gson()
    private const val KEY_STATS = "farm_stats"
    private const val KEY_HISTORY = "farm_history"
    private const val MAX_HISTORY = 50

    fun saveStats(context: Context, stats: FarmStats) {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_STATS, gson.toJson(stats)).apply()
    }

    fun loadStats(context: Context): FarmStats {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_STATS, null) ?: return FarmStats()
        return try { gson.fromJson(json, FarmStats::class.java) } catch (e: Exception) { FarmStats() }
    }

    fun saveHistory(context: Context, sessions: List<FarmSession>) {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val trimmed = sessions.take(MAX_HISTORY)
        prefs.edit().putString(KEY_HISTORY, gson.toJson(trimmed)).apply()
    }

    fun loadHistory(context: Context): List<FarmSession> {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<FarmSession>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) { emptyList() }
    }

    fun getBestMinigame(stats: FarmStats): MinigameType? {
        val best = stats.sessionsByGame.maxByOrNull { it.value } ?: return null
        return try { MinigameType.valueOf(best.key) } catch (e: Exception) { null }
    }

    fun formatCoins(coins: Int): String = when {
        coins >= 1_000_000 -> "%.1fM".format(coins / 1_000_000f)
        coins >= 1_000 -> "%.1fk".format(coins / 1_000f)
        else -> coins.toString()
    }

    fun formatDuration(minutes: Float): String {
        val h = (minutes / 60).toInt()
        val m = (minutes % 60).toInt()
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
