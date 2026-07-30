package com.gamemapper.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.gamemapper.R
import com.gamemapper.databinding.ActivityAnalyzerBinding
import com.gamemapper.models.AnalysisResult
import com.gamemapper.models.ControlProfile
import com.gamemapper.services.ControlParser
import com.gamemapper.services.GameAnalyzerJS
import com.gamemapper.utils.Constants
import com.gamemapper.utils.ProfileStorage
import java.util.UUID

class AnalyzerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAnalyzerBinding
    private var gameUrl: String = ""
    private var analysisMode: Int = Constants.ANALYSIS_MODE_DEEP
    private var sourceProfileId: String? = null
    private var pageLoaded = false
    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAnalyzerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        gameUrl = intent.getStringExtra(Constants.EXTRA_GAME_URL) ?: ""
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val ws = binding.webView.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.loadWithOverviewMode = true
        ws.useWideViewPort = true
        ws.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        ws.userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        ws.mediaPlaybackRequiresUserGesture = false
        ws.allowContentAccess = true
        ws.allowFileAccess = true

        WebView.setWebContentsDebuggingEnabled(false)

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                pageLoaded = false
                updateStatus("Carregando jogo…", 15)
                // Inject hook script EARLY – before game scripts execute
                view.evaluateJavascript(GameAnalyzerJS.EARLY_HOOK_SCRIPT, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                pageLoaded = true
                updateStatus("Analisando controles…", 60)

                // Give the game a moment to finish its own init
                handler.postDelayed({ runDeepAnalysis() }, 2500)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    updateStatus("Erro ao carregar – tentando mesmo assim…", 40)
                    handler.postDelayed({ runDeepAnalysis() }, 1000)
                }
            }

            @Deprecated("Deprecated")
            override fun onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
                updateStatus("Erro ao carregar – analisando parcialmente…", 40)
                handler.postDelayed({ runDeepAnalysis() }, 1000)
            }
        }
    }

    private fun setupUI() {
        binding.tvStatusUrl.text = gameUrl
        binding.btnCancel.setOnClickListener { finish() }
    }

    private fun loadGame() {
        updateStatus("Conectando ao jogo…", 5)
        binding.webView.loadUrl(gameUrl)
    }

    private fun runDeepAnalysis() {
        updateStatus("Detectando eventos e controles…", 70)

        val script = if (analysisMode == Constants.ANALYSIS_MODE_REMAP)
            GameAnalyzerJS.REMAP_ANALYSIS_SCRIPT
        else
            GameAnalyzerJS.DEEP_ANALYSIS_SCRIPT

        binding.webView.evaluateJavascript(script) { jsonResult ->
            if (jsonResult.isNullOrBlank() || jsonResult == "null") {
                updateStatus("Usando análise de fallback…", 80)
                runFallbackAnalysis()
                return@evaluateJavascript
            }

            // JS returns a JSON string – may be double-encoded (quoted)
            val cleaned = jsonResult.trim().let {
                if (it.startsWith("\"") && it.endsWith("\""))
                    it.substring(1, it.length - 1)
                        .replace("\\\"", "\"")
                        .replace("\\n", "")
                        .replace("\\\\", "\\")
                else it
            }

            processResult(cleaned)
        }
    }

    private fun runFallbackAnalysis() {
        // If JS fails, produce a sensible default mapping from URL patterns
        val domain = try { java.net.URI(gameUrl).host ?: gameUrl } catch (e: Exception) { gameUrl }
        val fallbackJson = buildFallbackJson(domain)
        processResult(fallbackJson)
    }

    private fun buildFallbackJson(domain: String): String {
        val isClubPenguin = domain.contains("cpjourney") || domain.contains("penguin") ||
                domain.contains("cprewritten") || domain.contains("cplegacy") ||
                domain.contains("cpps")
        val keys = if (isClubPenguin) {
            """[
                {"keyCode":87,"label":"W","category":"movement","freq":10},
                {"keyCode":65,"label":"A","category":"movement","freq":10},
                {"keyCode":83,"label":"S","category":"movement","freq":10},
                {"keyCode":68,"label":"D","category":"movement","freq":10},
                {"keyCode":32,"label":"Space","category":"action","freq":8},
                {"keyCode":84,"label":"T","category":"ui","freq":5},
                {"keyCode":77,"label":"M","category":"ui","freq":3},
                {"keyCode":13,"label":"Enter","category":"action","freq":6}
            ]"""
        } else {
            """[
                {"keyCode":87,"label":"W","category":"movement","freq":10},
                {"keyCode":65,"label":"A","category":"movement","freq":10},
                {"keyCode":83,"label":"S","category":"movement","freq":10},
                {"keyCode":68,"label":"D","category":"movement","freq":10},
                {"keyCode":32,"label":"Space","category":"action","freq":8},
                {"keyCode":13,"label":"Enter","category":"action","freq":6},
                {"keyCode":27,"label":"Esc","category":"ui","freq":4},
                {"keyCode":69,"label":"E","category":"interaction","freq":5}
            ]"""
        }
        return """{"title":"$domain","url":"$gameUrl","keyboard":$keys,"canvasZones":[],"clickableElements":[],"touchZones":[]}"""
    }

    private fun processResult(json: String) {
        updateStatus("Organizando mapeamento…", 90)

        val isRemap = analysisMode == Constants.ANALYSIS_MODE_REMAP
        val controls = ControlParser.parse(json, isRemap)

        if (controls.isEmpty()) {
            runOnUiThread {
                Toast.makeText(this, "Nenhum controle detectado – usando padrão", Toast.LENGTH_SHORT).show()
                val fallback = ControlParser.parse(buildFallbackJson(gameUrl), isRemap)
                saveAndContinue(fallback, json)
            }
            return
        }

        runOnUiThread { saveAndContinue(controls, json) }
    }

    private fun saveAndContinue(
        controls: List<com.gamemapper.models.ControlModel>,
        json: String
    ) {
        updateStatus("Pronto! Salvando automaticamente…", 100)

        val domain = try { java.net.URI(gameUrl).host ?: gameUrl } catch (e: Exception) { gameUrl }
        val gameTitle = try {
            val obj = org.json.JSONObject(json)
            obj.optString("title", domain).ifEmpty { domain }
        } catch (e: Exception) { domain }

        // Auto-save: if this analysis originated from an existing profile (e.g. Remap),
        // reuse its id so the saved profile is updated in place instead of duplicated.
        val existing = sourceProfileId?.let { ProfileStorage.getProfile(this, it) }
        val profile = ControlProfile(
            id = existing?.id ?: sourceProfileId ?: UUID.randomUUID().toString(),
            name = gameTitle,
            gameUrl = gameUrl,
            gameDomain = domain,
            controls = controls,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            layoutStyle = analysisMode
        )
        ProfileStorage.saveProfile(this, profile)
        updateStatus("Gamepad salvo automaticamente ✓", 100)

        handler.postDelayed({
            val intent = Intent(this, ControlMapActivity::class.java)
            intent.putExtra(Constants.EXTRA_PROFILE_ID, profile.id)
            intent.putExtra(Constants.EXTRA_ANALYSIS_MODE, analysisMode)
            startActivity(intent)
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
            finish()
        }, 500)
    }

    private fun updateStatus(message: String, progress: Int) {
        runOnUiThread {
            binding.tvStatus.text = message
            binding.progressBar.progress = progress
        }
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        binding.webView.destroy()
        super.onDestroy()
    }
}
