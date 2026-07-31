package com.gamemapper.services

import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/**
 * Monitors WebView for errors and auto-recovers.
 * Strategies:
 *   • JS error capture via window.onerror hook
 *   • Page freeze detection via RAF counter stall
 *   • Network error detection via HTTP status
 *   • Auto-reload with back-off on repeated failures
 *   • State preservation across reloads
 */
class ErrorRecoveryManager(
    private val webView: WebView,
    private val listener: RecoveryListener
) {
    interface RecoveryListener {
        fun onErrorDetected(type: ErrorType, message: String)
        fun onRecovering(attempt: Int)
        fun onRecovered()
        fun onRecoveryFailed(totalAttempts: Int)
    }

    enum class ErrorType {
        JS_ERROR, PAGE_FREEZE, NETWORK_ERROR, WEBGL_CRASH, GAME_CRASH, LOAD_ERROR
    }

    private val handler = Handler(Looper.getMainLooper())
    private var errorCount = 0
    private var recoveryAttempt = 0
    private var lastRafCount = -1L
    private var rafStallCount = 0
    private var isRecovering = false
    private var lastUrl = ""
    private var isMonitoring = false

    // Back-off delays: 2s, 4s, 8s, 15s, 30s
    private val BACKOFF_DELAYS = longArrayOf(2000, 4000, 8000, 15000, 30000)
    private val MAX_ATTEMPTS = 5

    private val JS_ERROR_HOOK = """
(function() {
    if (window.__errRecovery_hooked) return;
    window.__errRecovery_hooked = true;
    window.__errRecovery_errors = [];
    window.__errRecovery_lastError = null;

    var origError = window.onerror;
    window.onerror = function(msg, src, line, col, err) {
        var info = { msg: msg, src: src, line: line, time: Date.now() };
        window.__errRecovery_errors.push(info);
        window.__errRecovery_lastError = info;
        if (origError) origError(msg, src, line, col, err);
        return false;
    };

    window.addEventListener('unhandledrejection', function(e) {
        window.__errRecovery_lastError = { msg: String(e.reason), type: 'promise', time: Date.now() };
        window.__errRecovery_errors.push(window.__errRecovery_lastError);
    });
})();
""".trimIndent()

    private val STATUS_PROBE = """
(function() {
    return JSON.stringify({
        raf: window.__gmapper_raf_count || 0,
        webgl: window.__gmapper_webgl_active || false,
        errors: (window.__errRecovery_errors || []).length,
        lastError: window.__errRecovery_lastError || null,
        hasCanvas: !!document.querySelector('canvas'),
        docReady: document.readyState
    });
})();
""".trimIndent()

    // ── Public API ───────────────────────────────────────────────────────────

    fun startMonitoring(url: String) {
        lastUrl = url
        isMonitoring = true
        errorCount = 0
        recoveryAttempt = 0
        injectHooks()
        scheduleCheck()
    }

    fun stopMonitoring() {
        isMonitoring = false
        handler.removeCallbacksAndMessages(null)
    }

    fun onPageLoadError(errorCode: Int, description: String, url: String) {
        if (!isMonitoring) return
        errorCount++
        listener.onErrorDetected(ErrorType.LOAD_ERROR, "HTTP $errorCode: $description")
        scheduleRecovery()
    }

    fun onPageFinished(url: String) {
        lastUrl = url
        recoveryAttempt = 0
        isRecovering = false
        injectHooks()
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun injectHooks() {
        webView.evaluateJavascript(JS_ERROR_HOOK) { _ -> }
    }

    private fun scheduleCheck() {
        if (!isMonitoring) return
        handler.postDelayed({
            if (!isMonitoring) return@postDelayed
            checkHealth()
            scheduleCheck()
        }, 5000)
    }

    private fun checkHealth() {
        webView.evaluateJavascript(STATUS_PROBE) { raw ->
            if (raw == null || raw == "null") return@evaluateJavascript
            try {
                val json = raw.trim().removePrefix("\"").removeSuffix("\"")
                    .replace("\\\"", "\"").replace("\\\\", "\\")

                val raf = extractLong(json, "raf") ?: 0L
                val errors = extractInt(json, "errors") ?: 0
                val hasCanvas = json.contains("\"hasCanvas\":true")
                val docReady = extractString(json, "docReady")

                // Detect page freeze: RAF counter not advancing
                if (lastRafCount >= 0 && raf == lastRafCount && hasCanvas) {
                    rafStallCount++
                    if (rafStallCount >= 3) { // 3 checks = 15s stall
                        rafStallCount = 0
                        listener.onErrorDetected(ErrorType.PAGE_FREEZE, "Game freezou por 15 segundos")
                        scheduleRecovery()
                        return@evaluateJavascript
                    }
                } else {
                    rafStallCount = 0
                }
                lastRafCount = raf

                // Detect JS error accumulation
                if (errors > 0 && errors > errorCount) {
                    errorCount = errors
                    val lastErr = extractString(json, "lastError")
                    listener.onErrorDetected(ErrorType.JS_ERROR, "JS error detectado")
                    // Don't auto-recover for minor JS errors, just log
                }

                // Detect page crash (no canvas on a known game URL)
                if (!hasCanvas && lastUrl.contains("cpjourney") && docReady == "complete") {
                    listener.onErrorDetected(ErrorType.GAME_CRASH, "Canvas desapareceu — possível crash do jogo")
                    scheduleRecovery()
                }
            } catch (e: Exception) { /* ignore */ }
        }
    }

    private fun scheduleRecovery() {
        if (isRecovering) return
        if (recoveryAttempt >= MAX_ATTEMPTS) {
            listener.onRecoveryFailed(recoveryAttempt)
            return
        }
        isRecovering = true
        val delay = BACKOFF_DELAYS.getOrElse(recoveryAttempt) { 30000L }
        recoveryAttempt++
        listener.onRecovering(recoveryAttempt)

        handler.postDelayed({
            if (!isMonitoring) return@postDelayed
            // Try soft recovery first (reload via JS)
            if (recoveryAttempt <= 2) {
                webView.evaluateJavascript("location.reload();") { _ -> }
            } else {
                // Hard reload
                val url = if (lastUrl.isNotEmpty()) lastUrl else webView.url ?: ""
                if (url.isNotEmpty()) webView.loadUrl(url)
            }
            isRecovering = false
        }, delay)
    }

    private fun extractLong(json: String, key: String): Long? =
        Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull()

    private fun extractInt(json: String, key: String): Int? =
        Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()

    private fun extractString(json: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
}
