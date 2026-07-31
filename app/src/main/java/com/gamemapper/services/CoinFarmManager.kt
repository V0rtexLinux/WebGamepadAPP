package com.gamemapper.services

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.gamemapper.models.FarmSession
import com.gamemapper.models.FarmStats
import com.gamemapper.models.MinigameType

/**
 * Orchestrates all auto-farm sessions.
 * Polls the WebView every second to detect the active minigame,
 * automatically starts the correct farm script, and tracks stats.
 */
class CoinFarmManager(
    private val webView: WebView,
    private val listener: FarmListener
) {

    interface FarmListener {
        fun onMinigameDetected(type: MinigameType)
        fun onFarmStarted(type: MinigameType)
        fun onFarmStopped(type: MinigameType, session: FarmSession)
        fun onCoinsUpdated(sessionCoins: Int, totalCoins: Int)
        fun onError(type: MinigameType, message: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var autoFarmEnabled = false
    private var currentMinigame = MinigameType.NONE
    private var currentSession: FarmSession? = null
    val stats = FarmStats()
    private val sessionHistory = mutableListOf<FarmSession>()

    // Polling interval for minigame detection
    private val DETECTION_INTERVAL_MS = 1500L
    // Status poll interval when farm is running
    private val STATUS_INTERVAL_MS = 3000L

    private val detectionRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            detectMinigame()
            handler.postDelayed(this, DETECTION_INTERVAL_MS)
        }
    }

    private val statusRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            pollFarmStatus()
            handler.postDelayed(this, STATUS_INTERVAL_MS)
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    fun start(autoFarm: Boolean = true) {
        isRunning = true
        autoFarmEnabled = autoFarm
        handler.post(detectionRunnable)
        handler.post(statusRunnable)
        // Inject the detector script once
        webView.evaluateJavascript(FarmScripts.MINIGAME_DETECTOR) { _ -> }
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(detectionRunnable)
        handler.removeCallbacks(statusRunnable)
        stopCurrentFarm()
    }

    fun setAutoFarm(enabled: Boolean) {
        autoFarmEnabled = enabled
        if (!enabled) stopCurrentFarm()
    }

    fun startFarmManually(type: MinigameType) {
        stopCurrentFarm()
        launchFarm(type)
    }

    fun stopCurrentFarm() {
        webView.evaluateJavascript(FarmScripts.STOP_ALL_FARMS) { result ->
            val session = currentSession
            if (session != null) {
                session.active = false
                session.endTime = System.currentTimeMillis()
                stats.update(session)
                sessionHistory.add(0, session)
                listener.onFarmStopped(currentMinigame, session)
            }
        }
        currentSession = null
        currentMinigame = MinigameType.NONE
    }

    fun getSessionHistory(): List<FarmSession> = sessionHistory.toList()

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun detectMinigame() {
        webView.evaluateJavascript(FarmScripts.MINIGAME_DETECTOR) { raw ->
            if (raw == null || raw == "null") return@evaluateJavascript
            try {
                val json = raw.trim().removePrefix("\"").removeSuffix("\"")
                    .replace("\\\"", "\"").replace("\\\\", "\\")
                val detected = parseMinigameFromJson(json)

                if (detected != currentMinigame) {
                    listener.onMinigameDetected(detected)

                    if (autoFarmEnabled) {
                        if (currentMinigame != MinigameType.NONE) stopCurrentFarm()
                        if (detected != MinigameType.NONE && detected != MinigameType.UNKNOWN) {
                            handler.postDelayed({ launchFarm(detected) }, 500)
                        }
                    }
                    currentMinigame = detected
                }
            } catch (e: Exception) {
                // Parse error - ignore
            }
        }
    }

    private fun launchFarm(type: MinigameType) {
        val script = FarmScripts.scriptForMinigame(type) ?: return
        webView.evaluateJavascript(script) { result ->
            if (result?.contains("started") == true || result?.contains("running") == true) {
                currentSession = FarmSession(minigame = type)
                listener.onFarmStarted(type)
            } else {
                listener.onError(type, "Falha ao iniciar farm: $result")
            }
        }
    }

    private fun pollFarmStatus() {
        webView.evaluateJavascript(FarmScripts.GET_FARM_STATUS) { raw ->
            if (raw == null || raw == "null") return@evaluateJavascript
            try {
                val json = raw.trim().removePrefix("\"").removeSuffix("\"")
                    .replace("\\\"", "\"").replace("\\\\", "\\")
                val totalCoins = extractIntFromJson(json, "totalCoins") ?: 0
                val session = currentSession
                if (session != null) {
                    // Estimate coins for this session
                    val sessionCoins = when (currentMinigame) {
                        MinigameType.CART_SURFER ->
                            extractIntFromJson(json, "cartSurfer.coins") ?: 0
                        MinigameType.MINING, MinigameType.ICE_DRILLING ->
                            extractIntFromJson(json, "mining.coins") ?: 0
                        else -> 0
                    }
                    session.coinsEarned = sessionCoins
                    listener.onCoinsUpdated(sessionCoins, totalCoins)
                }
            } catch (e: Exception) { /* ignore */ }
        }
    }

    private fun parseMinigameFromJson(json: String): MinigameType {
        val minigameStr = extractStringFromJson(json, "minigame") ?: return MinigameType.NONE
        return try {
            MinigameType.valueOf(minigameStr)
        } catch (e: IllegalArgumentException) {
            MinigameType.NONE
        }
    }

    private fun extractStringFromJson(json: String, key: String): String? {
        val pattern = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"")
        return pattern.find(json)?.groupValues?.get(1)
    }

    private fun extractIntFromJson(json: String, key: String): Int? {
        val lastKey = key.substringAfterLast('.')
        val pattern = Regex("\"$lastKey\"\\s*:\\s*(\\d+)")
        return pattern.find(json)?.groupValues?.get(1)?.toIntOrNull()
    }
}
