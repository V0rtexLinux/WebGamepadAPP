package com.gamemapper.services

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.gamemapper.models.FarmSession
import com.gamemapper.models.FarmStats
import com.gamemapper.models.MinigameType

/**
 * Orchestrates fully-automatic farm sessions.
 *
 * Flow:
 *  1. GameplayActivity calls [onPageStarted] from WebViewClient.onPageStarted
 *     → injects the WebGL preserveDrawingBuffer patch EARLY (before Ruffle
 *       creates its context) so pixel-based turn detection works.
 *  2. Once the page loads, [start] begins polling every 1.5 s for the
 *     active minigame via the MINIGAME_DETECTOR JS script.
 *  3. When a supported minigame is detected, [launchFarm] auto-injects the
 *     corresponding farm script (fully automatic — no user action needed).
 *  4. [pollFarmStatus] reads live stats from the running JS farm.
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
    private var autoFarmEnabled = true
    private var currentMinigame = MinigameType.NONE
    private var currentSession: FarmSession? = null
    val stats = FarmStats()
    private val sessionHistory = mutableListOf<FarmSession>()

    private val DETECTION_INTERVAL_MS = 1500L
    private val STATUS_INTERVAL_MS    = 2500L

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

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Must be called from WebViewClient.onPageStarted — injects the WebGL
     * context interceptor BEFORE Ruffle creates its WebGL context.
     * This is critical for canvas pixel turn-detection in Cart Surfer.
     */
    fun onPageStarted(url: String) {
        if (url.contains("cpjourney") || url.contains("cpps") || url.contains("clubpenguin")) {
            webView.evaluateJavascript(FarmScripts.CART_SURFER_EARLY_INJECT) { _ -> }
        }
    }

    /** Begin automatic minigame detection and farm management. */
    fun start(autoFarm: Boolean = true) {
        isRunning = true
        autoFarmEnabled = autoFarm
        handler.postDelayed(detectionRunnable, 2000) // 2s head start for page load
        handler.postDelayed(statusRunnable, 5000)
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

    /** Manually start a specific farm (overrides auto-detection). */
    fun startFarmManually(type: MinigameType) {
        stopCurrentFarm()
        launchFarm(type)
    }

    fun stopCurrentFarm() {
        webView.evaluateJavascript(FarmScripts.STOP_ALL_FARMS) { _ ->
            val session = currentSession
            if (session != null) {
                session.active   = false
                session.endTime  = System.currentTimeMillis()
                stats.update(session)
                sessionHistory.add(0, session)
                listener.onFarmStopped(currentMinigame, session)
            }
        }
        currentSession  = null
        currentMinigame = MinigameType.NONE
    }

    fun getSessionHistory(): List<FarmSession> = sessionHistory.toList()

    // ── Internal ──────────────────────────────────────────────────────────

    private fun detectMinigame() {
        webView.evaluateJavascript(FarmScripts.MINIGAME_DETECTOR) { raw ->
            if (raw == null || raw == "null") return@evaluateJavascript
            try {
                val json     = raw.unescape()
                val detected = parseMinigameFromJson(json)

                if (detected != currentMinigame) {
                    listener.onMinigameDetected(detected)

                    if (autoFarmEnabled) {
                        if (currentMinigame != MinigameType.NONE) stopCurrentFarm()
                        if (detected != MinigameType.NONE && detected != MinigameType.UNKNOWN) {
                            handler.postDelayed({ launchFarm(detected) }, 600)
                        }
                    }
                    currentMinigame = detected
                }
            } catch (_: Exception) {}
        }
    }

    private fun launchFarm(type: MinigameType) {
        val script = FarmScripts.scriptForMinigame(type) ?: return
        webView.evaluateJavascript(script) { result ->
            val clean = result?.unescape() ?: ""
            if (clean.contains("started") || clean.contains("running")) {
                currentSession = FarmSession(minigame = type)
                listener.onFarmStarted(type)
            } else {
                listener.onError(type, "Falha ao iniciar farm: $clean")
            }
        }
    }

    private fun pollFarmStatus() {
        if (currentMinigame == MinigameType.NONE) return
        webView.evaluateJavascript(FarmScripts.GET_FARM_STATUS) { raw ->
            if (raw == null || raw == "null") return@evaluateJavascript
            try {
                val json    = raw.unescape()
                val session = currentSession ?: return@evaluateJavascript

                // Read Cart Surfer stats
                val csStats = extractObject(json, "cartSurfer")
                if (csStats != null) {
                    val tricks  = extractInt(csStats, "tricks") ?: 0
                    val turns   = extractInt(csStats, "turns")  ?: 0
                    val lives   = extractInt(csStats, "livesUsed") ?: 0
                    // Estimate coins: avg 85pts/trick → coins ≈ pts/10 * 1.0
                    val estCoins = tricks * 85 / 10
                    session.coinsEarned = estCoins
                    session.roundsPlayed = turns
                    listener.onCoinsUpdated(estCoins, stats.totalCoins + estCoins)
                }
            } catch (_: Exception) {}
        }
    }

    // ── JSON mini-helpers (no Gson needed for small status payloads) ──────

    private fun String.unescape() = trim()
        .removePrefix("\"").removeSuffix("\"")
        .replace("\\\"", "\"").replace("\\\\", "\\")

    private fun parseMinigameFromJson(json: String): MinigameType {
        val v = extractString(json, "minigame") ?: return MinigameType.NONE
        return try { MinigameType.valueOf(v) } catch (_: Exception) { MinigameType.NONE }
    }

    private fun extractString(json: String, key: String): String? {
        val m = Regex(""""$key"\s*:\s*"([^"]+)"""").find(json)
        return m?.groupValues?.get(1)
    }

    private fun extractInt(json: String, key: String): Int? {
        val m = Regex(""""$key"\s*:\s*(\d+)""").find(json)
        return m?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractObject(json: String, key: String): String? {
        val start = json.indexOf("\"$key\"")
        if (start < 0) return null
        val brace = json.indexOf('{', start)
        if (brace < 0) return null
        var depth = 0; var i = brace
        while (i < json.length) {
            when (json[i]) { '{' -> depth++; '}' -> { depth--; if (depth == 0) return json.substring(brace, i+1) } }
            i++
        }
        return null
    }
}
