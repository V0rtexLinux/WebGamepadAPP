package com.gamemapper.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gamemapper.R
import com.gamemapper.models.FarmSession
import com.gamemapper.models.FarmStats
import com.gamemapper.models.MinigameType
import com.gamemapper.utils.StatsManager

/**
 * Dashboard showing farm statistics and session history.
 */
class FarmDashboardActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_farm_dashboard)

        supportActionBar?.apply {
            title = "📊 Farm Dashboard"
            setDisplayHomeAsUpEnabled(true)
        }

        val stats = StatsManager.loadStats(this)
        val history = StatsManager.loadHistory(this)

        renderStats(stats)
        renderHistory(history)
    }

    private fun renderStats(stats: FarmStats) {
        findViewById<TextView>(R.id.tvTotalCoins)?.text = "🪙 ${StatsManager.formatCoins(stats.totalCoins)}"
        findViewById<TextView>(R.id.tvTotalSessions)?.text = "🎮 ${stats.totalSessions} sessões"
        findViewById<TextView>(R.id.tvTotalTime)?.text = "⏱ ${StatsManager.formatDuration(stats.totalMinutes)}"
        findViewById<TextView>(R.id.tvBestSession)?.text = "🏆 ${StatsManager.formatCoins(stats.bestSessionCoins)}"
        findViewById<TextView>(R.id.tvTotalRounds)?.text = "🔄 ${stats.totalRounds} rodadas"
        findViewById<TextView>(R.id.tvErrorsFixed)?.text = "🔧 ${stats.totalErrorsRecovered} erros corrigidos"

        val best = StatsManager.getBestMinigame(stats)
        findViewById<TextView>(R.id.tvBestMinigame)?.text = if (best != null)
            "${best.icon} ${best.displayName}" else "—"

        // Coins/hour estimate
        val coinsPerHour = if (stats.totalMinutes > 0)
            (stats.totalCoins / stats.totalMinutes * 60).toInt() else 0
        findViewById<TextView>(R.id.tvCoinsPerHour)?.text = "📈 ${StatsManager.formatCoins(coinsPerHour)}/h"
    }

    private fun renderHistory(history: List<FarmSession>) {
        val rv = findViewById<RecyclerView>(R.id.rvHistory) ?: return
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = HistoryAdapter(history)

        findViewById<TextView>(R.id.tvHistoryEmpty)?.visibility =
            if (history.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ── History Adapter ───────────────────────────────────────────────────────

    private class HistoryAdapter(
        private val items: List<FarmSession>
    ) : RecyclerView.Adapter<HistoryAdapter.VH>() {

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvGame   = itemView.findViewById<TextView>(R.id.tvSessionGame)
            val tvCoins  = itemView.findViewById<TextView>(R.id.tvSessionCoins)
            val tvTime   = itemView.findViewById<TextView>(R.id.tvSessionTime)
            val tvRounds = itemView.findViewById<TextView>(R.id.tvSessionRounds)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_farm_session, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = items[position]
            holder.tvGame.text   = "${s.minigame.icon} ${s.minigame.displayName}"
            holder.tvCoins.text  = "🪙 ${StatsManager.formatCoins(s.coinsEarned)}"
            holder.tvTime.text   = "⏱ ${StatsManager.formatDuration(s.durationMin)}"
            holder.tvRounds.text = "🔄 ${s.roundsPlayed}"
        }

        override fun getItemCount() = items.size
    }
}
