package com.gamemapper.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.gamemapper.R
import com.gamemapper.databinding.ActivityAnalyzerBinding
import com.gamemapper.models.ControlProfile
import com.gamemapper.services.ControlParser
import com.gamemapper.services.GameAnalyzerJS
import com.gamemapper.utils.Constants
import com.gamemapper.utils.ProfileStorage
import org.json.JSONObject
import java.util.UUID
import android.webkit.*

/**
 * Hidden WebView that runs the 3-stage game-control analysis pipeline.
 *
 * Pipeline:
 *   [onPageStarted]  → inject EARLY_HOOK_SCRIPT (rAF counter + WebGL hook + event spy)
 *   [onPageFinished] → start polling READINESS_PROBE every 500 ms (up to 12 s)
 *   [engine ready]   → run DEEP_ANALYSIS_SCRIPT (canvas-quadrant mapping, no DOM nav)
 *   [result]         → ControlParser.parse() → save ControlProfile → launch ControlMapActivity
 *
 * Login state awareness:
 *   If LOGIN_STATE_PROBE indicates we're on a login page (no WebGL + input fields visible),
 *   the activity still runs the full scan but stamps the profile with isLoginState = true
 *   so GameplayActivity can suppress the overlay until gameplay begins.
 */
class AnalyzerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAnalyzerBinding

    private var gameUrl       = ""
    private var analysisMode  = Constants.ANALYSIS_MODE_DEEP
    private var sourceProfileId: String? = null

    private var pageLoaded    = false
    private var analysisRan   = false

    private val handler = Handler(Looper.getMainLooper())

    // Readiness polling state
    private var probeAttempts = 0
    private val maxProbeAttempts = 24   // 24 × 500 ms = 12 seconds max wait

    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAnalyzerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        gameUrl      = intent.getStringExtra(Constants.EXTRA_GAME_URL) ?: ""
        analysisMode = intent.getIntExtra(Constants.EXTRA_ANALYSIS_MODE, Constants.ANALYSIS_MODE_DEEP)
        sourceProfileId = intent.getStringExtra(Constants.EXTRA_SOURCE_PROFILE_ID)

        if (gameUrl.isEmpty()) {
            Toast.makeText(this, "URL inválida", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupWebView()
        setupUI()
        loadGame()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  WebView setup
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(binding.webView.settings) {
            javaScriptEnabled        = true
            domStorageEnabled        = true
            loadWithOverviewMode     = true
            useWideViewPort          = true
            mixedContentMode         = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            allowContentAccess       = true
            allowFileAccess          = true
            // Desktop UA so game engines don't redirect to a mobile splash
            userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"
        }
        WebView.setWebContentsDebuggingEnabled(false)

        binding.webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                pageLoaded   = false
                analysisRan  = false
                probeAttempts = 0
                updateStatus("Conectando ao jogo…", 10)
                // Inject EARLY hooks BEFORE game scripts execute
                view.evaluateJavascript(GameAnalyzerJS.EARLY_HOOK_SCRIPT, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (pageLoaded) return          // guard duplicate calls
                pageLoaded = true
                updateStatus("Página carregada — aguardando motor gráfico…", 35)

                // For remap mode skip the wait and scan immediately
                if (analysisMode == Constants.ANALYSIS_MODE_REMAP) {
                    handler.postDelayed({ runDeepAnalysis() }, 1200)
                } else {
                    // Give the page a minimal head-start then begin readiness polling
                    handler.postDelayed({ pollForReadiness() }, 1500)
                }
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    updateStatus("Erro ao carregar — analisando mesmo assim…", 40)
                    handler.postDelayed({ runDeepAnalysis() }, 1000)
                }
            }

            @Deprecated("Deprecated")
            override fun onReceivedError(
                view: WebView, errorCode: Int, description: String, failingUrl: String
            ) {
                updateStatus("Erro — analisando parcialmente…", 40)
                handler.postDelayed({ runDeepAnalysis() }, 1000)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Stage 2 — Readiness polling (WebGL / rAF detection)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Polls READINESS_PROBE every 500 ms.
     * Proceeds to analysis when:
     *   (a) WebGL context detected, OR
     *   (b) rAF count > 8 (continuous rendering loop active), OR
     *   (c) maxProbeAttempts reached (timeout → run analysis anyway).
     */
    private fun pollForReadiness() {
        if (analysisRan) return

        binding.webView.evaluateJavascript(GameAnalyzerJS.READINESS_PROBE) { raw ->
            val ready = try {
                val obj = JSONObject(cleanJson(raw))
                obj.optBoolean("ready", false)
            } catch (e: Exception) { false }

            val progress = 35 + (probeAttempts * 2).coerceAtMost(25)
            val rafCount = try {
                JSONObject(cleanJson(raw)).optInt("raf", 0)
            } catch (e: Exception) { 0 }

            updateStatus(
                if (ready) "Motor detectado (WebGL/rAF=$rafCount) — analisando…"
                else "Aguardando motor… tentativa ${probeAttempts + 1}/$maxProbeAttempts (rAF=$rafCount)",
                progress
            )

            probeAttempts++

            when {
                ready || probeAttempts >= maxProbeAttempts -> {
                    // Check login state before running deep analysis
                    checkLoginStateThenAnalyze()
                }
                else -> handler.postDelayed({ pollForReadiness() }, 500)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Stage 2b — Login state check
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Runs LOGIN_STATE_PROBE to stamp whether we're at a login screen.
     * Always proceeds to the deep analysis regardless of the result —
     * the flag is forwarded to ControlProfile so GameplayActivity can act on it.
     */
    private fun checkLoginStateThenAnalyze() {
        binding.webView.evaluateJavascript(GameAnalyzerJS.LOGIN_STATE_PROBE) { raw ->
            val loginState = try {
                JSONObject(cleanJson(raw)).optBoolean("isLoginState", false)
            } catch (e: Exception) { false }

            if (loginState) {
                updateStatus("Tela de login detectada — mapeando canvas assim que entrar…", 55)
                // Even on login screens we generate the profile so the user can
                // launch the game from ControlMapActivity and get the overlay later.
            } else {
                updateStatus("Mapeando controles do motor gráfico…", 60)
            }

            runDeepAnalysis(isLoginState = loginState)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Stage 3 — Deep analysis
    // ─────────────────────────────────────────────────────────────────────────

    private fun runDeepAnalysis(isLoginState: Boolean = false) {
        if (analysisRan) return
        analysisRan = true
        updateStatus("Executando análise de quadrantes…", 70)

        val script = if (analysisMode == Constants.ANALYSIS_MODE_REMAP)
            GameAnalyzerJS.REMAP_ANALYSIS_SCRIPT
        else
            GameAnalyzerJS.DEEP_ANALYSIS_SCRIPT

        binding.webView.evaluateJavascript(script) { raw ->
            if (raw.isNullOrBlank() || raw == "null") {
                updateStatus("JS retornou nulo — usando fallback…", 80)
                runFallbackAnalysis(isLoginState)
                return@evaluateJavascript
            }
            val json = cleanJson(raw)
            if (json.isEmpty()) {
                runFallbackAnalysis(isLoginState)
                return@evaluateJavascript
            }
            processResult(json, isLoginState)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Fallback (URL-pattern based)
    // ─────────────────────────────────────────────────────────────────────────

    private fun runFallbackAnalysis(isLoginState: Boolean = false) {
        val domain = safeDomain(gameUrl)
        processResult(buildFallbackJson(domain), isLoginState)
    }

    private fun buildFallbackJson(domain: String): String {
        // CP-style default: click-to-move + standard hotkeys, full canvas
        val isCp = domain.contains("penguin") || domain.contains("cpps") ||
                   domain.contains("cpjourney") || domain.contains("icer") ||
                   domain.contains("cprewritten") || domain.contains("cplegacy")

        val quadrants = if (isCp) """[
            {"zone":"CANVAS_CLICK","label":"Clique no Canvas",
             "x":0,"y":0,"w":800,"h":600,
             "keys":[{"keyCode":-1,"label":"Click","direction":"click"}],
             "category":"interaction","priority":0},
            {"zone":"DPAD","label":"D-Pad / Movimento",
             "x":0,"y":342,"w":304,"h":228,
             "keys":[{"keyCode":38,"label":"↑","direction":"up"},
                     {"keyCode":40,"label":"↓","direction":"down"},
                     {"keyCode":37,"label":"←","direction":"left"},
                     {"keyCode":39,"label":"→","direction":"right"}],
             "category":"movement","priority":1},
            {"zone":"ACTION","label":"Botões de Ação",
             "x":496,"y":342,"w":288,"h":228,
             "keys":[{"keyCode":32,"label":"Espaço","direction":"south"},
                     {"keyCode":13,"label":"Enter","direction":"east"},
                     {"keyCode":69,"label":"E","direction":"north"},
                     {"keyCode":27,"label":"Esc","direction":"west"}],
             "category":"action","priority":2},
            {"zone":"UI","label":"Interface / UI",
             "x":496,"y":0,"w":288,"h":150,
             "keys":[{"keyCode":84,"label":"T  Chat","direction":"l1"},
                     {"keyCode":77,"label":"M  Mapa","direction":"r1"},
                     {"keyCode":73,"label":"I  Inv","direction":"l2"}],
             "category":"ui","priority":3}
        ]""" else "[]"

        val keys = """[
            {"keyCode":38,"label":"↑","category":"movement","freq":8},
            {"keyCode":40,"label":"↓","category":"movement","freq":8},
            {"keyCode":37,"label":"←","category":"movement","freq":8},
            {"keyCode":39,"label":"→","category":"movement","freq":8},
            {"keyCode":87,"label":"W","category":"movement","freq":6},
            {"keyCode":65,"label":"A","category":"movement","freq":6},
            {"keyCode":83,"label":"S","category":"movement","freq":6},
            {"keyCode":68,"label":"D","category":"movement","freq":6},
            {"keyCode":32,"label":"Space","category":"action","freq":7},
            {"keyCode":13,"label":"Enter","category":"action","freq":5},
            {"keyCode":27,"label":"Esc","category":"ui","freq":4},
            {"keyCode":84,"label":"T","category":"ui","freq":3},
            {"keyCode":77,"label":"M","category":"ui","freq":3},
            {"keyCode":73,"label":"I","category":"ui","freq":2}
        ]"""

        return """{
            "title":"$domain","url":"$gameUrl",
            "analysisMode":"canvas_quadrant_fallback",
            "isLoginState":false,"isGameplayActive":true,
            "isWebGLActive":false,"rafCount":0,
            "primaryCanvas":null,
            "canvasZones":[],
            "canvasQuadrants":$quadrants,
            "keyboard":$keys,
            "clickableElements":[],
            "touchZones":[]
        }"""
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Result processing
    // ─────────────────────────────────────────────────────────────────────────

    private fun processResult(json: String, isLoginState: Boolean) {
        updateStatus("Organizando mapeamento…", 88)

        val isRemap   = analysisMode == Constants.ANALYSIS_MODE_REMAP
        val controls  = ControlParser.parse(json, isRemap)

        if (controls.isEmpty()) {
            runOnUiThread {
                Toast.makeText(
                    this,
                    "Nenhum controle detectado — usando fallback de CP",
                    Toast.LENGTH_SHORT
                ).show()
                val fallback = ControlParser.parse(
                    buildFallbackJson(safeDomain(gameUrl)), isRemap
                )
                saveAndNavigate(fallback, json, isLoginState)
            }
            return
        }

        runOnUiThread { saveAndNavigate(controls, json, isLoginState) }
    }

    private fun saveAndNavigate(
        controls: List<com.gamemapper.models.ControlModel>,
        json: String,
        isLoginState: Boolean
    ) {
        updateStatus("Salvando perfil…", 96)

        val domain    = safeDomain(gameUrl)
        val gameTitle = try {
            JSONObject(json).optString("title", domain).ifEmpty { domain }
        } catch (e: Exception) { domain }

        val isCanvasMode = controls.any { it.isCanvasQuadrant }

        // Preserve existing profile when remapping
        val existing = sourceProfileId?.let { ProfileStorage.getProfile(this, it) }
        val profile  = ControlProfile(
            id          = existing?.id ?: sourceProfileId ?: UUID.randomUUID().toString(),
            name        = gameTitle,
            gameUrl     = gameUrl,
            gameDomain  = domain,
            controls    = controls,
            createdAt   = existing?.createdAt ?: System.currentTimeMillis(),
            updatedAt   = System.currentTimeMillis(),
            layoutStyle = analysisMode,
            isCanvasMode = isCanvasMode
        )
        ProfileStorage.saveProfile(this, profile)

        val modeLabel = when {
            isLoginState    -> "login screen"
            isCanvasMode    -> "canvas quadrant"
            else            -> "generic"
        }
        updateStatus("✓ Perfil salvo ($modeLabel, ${controls.size} controles)", 100)

        handler.postDelayed({
            val intent = Intent(this, ControlMapActivity::class.java)
            intent.putExtra(Constants.EXTRA_PROFILE_ID,    profile.id)
            intent.putExtra(Constants.EXTRA_ANALYSIS_MODE, analysisMode)
            startActivity(intent)
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
            finish()
        }, 450)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  UI helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupUI() {
        binding.tvStatusUrl.text = gameUrl
        binding.btnCancel.setOnClickListener { finish() }
    }

    private fun loadGame() {
        updateStatus("Conectando…", 5)
        binding.webView.loadUrl(gameUrl)
    }

    private fun updateStatus(message: String, progress: Int) {
        runOnUiThread {
            binding.tvStatus.text       = message
            binding.progressBar.progress = progress
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Utility
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * WebView.evaluateJavascript wraps string results in outer quotes and
     * escapes interior quotes. This strips both layers.
     */
    private fun cleanJson(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("\"") && trimmed.endsWith("\""))
            trimmed.substring(1, trimmed.length - 1)
                .replace("\\\"", "\"")
                .replace("\\n", "")
                .replace("\\\\", "\\")
        else trimmed
    }

    private fun safeDomain(url: String): String =
        try { java.net.URI(url).host ?: url } catch (e: Exception) { url }

    // ─────────────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) binding.webView.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        binding.webView.destroy()
        super.onDestroy()
    }
}
