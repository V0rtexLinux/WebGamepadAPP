package com.gamemapper.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.gamemapper.R
import com.gamemapper.databinding.ActivityGameplayBinding
import com.gamemapper.models.*
import com.gamemapper.services.*
import com.gamemapper.utils.*
import com.gamemapper.views.VirtualGamepadView
import org.json.JSONArray

/**
 * v2.0 — Full-screen gameplay activity.
 * New in v2:
 *  • CoinFarmManager — auto-detects minigames and runs farm scripts
 *  • ErrorRecoveryManager — auto-recovers from JS/page errors
 *  • Enhanced VirtualGamepadView — neon/glass themes, haptics, spring-back
 *  • Farm status overlay (FarmStatusView)
 *  • Gamepad settings shortcut
 *  • Per-session stats tracking
 */
@SuppressLint("SetJavaScriptEnabled")
class GameplayActivity : AppCompatActivity(),
    VirtualGamepadView.GamepadListener,
    CoinFarmManager.FarmListener,
    ErrorRecoveryManager.RecoveryListener {

    private lateinit var binding: ActivityGameplayBinding
    private var gameUrl: String = ""
    private var profileId: String = ""
    private val handler = Handler(Looper.getMainLooper())

    private val CURSOR_STEP = 22f
    private val CURSOR_FAST_STEP = 55f

    private var overlayVisible = false
    private var gamepadVisible = true
    private var cursorInjected = false
    private var loginOffered = false

    private var cppsInfo: CppsLoginHandler.CppsInfo? = null
    private var movementControls = listOf<ControlModel>()
    private var actionControls   = listOf<ControlModel>()
    private var uiControls       = listOf<ControlModel>()
    private var dpadQuadrant:   ControlModel? = null
    private var actionQuadrant: ControlModel? = null
    private var uiQuadrant:     ControlModel? = null
    private var clickQuadrant:  ControlModel? = null
    private var isCanvasMode = false

    // ── v2: Farm & Error managers ──────────────────────────────────────────────
    private lateinit var farmManager: CoinFarmManager
    private lateinit var errorRecovery: ErrorRecoveryManager
    private var autoFarmEnabled = false
    private var currentFarmSession: FarmSession? = null
    private var farmSessionStartMs = 0L
    private var farmCoinsThisSession = 0

    // ── v2: Gamepad config ────────────────────────────────────────────────────
    private var gamepadConfig: GamepadConfig = GamepadConfig()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        binding = ActivityGameplayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        gameUrl   = intent.getStringExtra(Constants.EXTRA_GAME_URL)   ?: ""
        profileId = intent.getStringExtra(Constants.EXTRA_PROFILE_ID) ?: ""
        autoFarmEnabled = intent.getBooleanExtra(Constants.EXTRA_AUTO_FARM, false)

        loadGamepadConfig()
        setupProfile()
        setupWebView()
        setupGamepad()
        setupFarmManager()
        setupErrorRecovery()
        setupControls()
        setupFarmStatusView()

        if (gameUrl.isNotEmpty()) binding.webView.loadUrl(gameUrl)
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun loadGamepadConfig() {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        val json = prefs.getString(Constants.KEY_GAMEPAD_CONFIG, null)
        if (json != null) {
            try {
                gamepadConfig = com.google.gson.Gson().fromJson(json, GamepadConfig::class.java)
            } catch (_: Exception) {}
        }
        HapticManager.setStrength(gamepadConfig.hapticStrength)
    }

    private fun setupProfile() {
        if (profileId.isEmpty()) return
        val profile = ProfileStorage.getProfile(this, profileId) ?: return
        isCanvasMode = profile.isCanvasMode

        val sorted = profile.controls.sortedBy { it.quadrantPriority }
        if (isCanvasMode) {
            dpadQuadrant   = sorted.firstOrNull { it.quadrantZone == "DPAD" }
            actionQuadrant = sorted.firstOrNull { it.quadrantZone == "ACTION" }
            uiQuadrant     = sorted.firstOrNull { it.quadrantZone == "UI" }
            clickQuadrant  = sorted.firstOrNull { it.quadrantZone == "CANVAS_CLICK" }
        } else {
            movementControls = profile.controls.filter { it.category == ControlCategory.MOVEMENT }
            actionControls   = profile.controls.filter { it.category == ControlCategory.ACTION }
            uiControls       = profile.controls.filter { it.category == ControlCategory.UI }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(binding.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = false
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/125.0.0.0 Safari/537.36"
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.loadingBar.visibility = View.VISIBLE
                cursorInjected = false
                loginOffered = false
                // Inject game analysis hooks early
                view.evaluateJavascript(GameAnalyzerJS.EARLY_HOOK_SCRIPT) { _ -> }
                // CRITICAL: patch WebGL preserveDrawingBuffer BEFORE Ruffle creates its context
                // so that pixel-based turn arrow detection in Cart Surfer works correctly.
                farmManager.onPageStarted(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                binding.loadingBar.visibility = View.GONE
                errorRecovery.onPageFinished(url)

                val info = CppsLoginHandler.detect(url)
                if (info != null) {
                    cppsInfo = info
                    handler.postDelayed({ handleLoginDetection(url, info) }, 1500)
                } else {
                    handler.postDelayed({ injectVirtualCursor() }, 1000)
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    errorRecovery.onPageLoadError(
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
                            error.errorCode else -1,
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
                            error.description.toString() else "Load error",
                        request.url.toString()
                    )
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                return if (CppsLoginHandler.isCpps(url)) { view.loadUrl(url); true } else false
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress == 100) binding.loadingBar.visibility = View.GONE
            }
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                result.confirm(); return true
            }
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean = true
        }
    }

    private fun setupGamepad() {
        binding.virtualGamepad.config = gamepadConfig
        binding.virtualGamepad.listener = this
    }

    private fun setupFarmManager() {
        farmManager = CoinFarmManager(binding.webView, this)
        if (autoFarmEnabled) {
            farmManager.start(autoFarm = true)
        }
    }

    private fun setupErrorRecovery() {
        errorRecovery = ErrorRecoveryManager(binding.webView, this)
        errorRecovery.startMonitoring(gameUrl)
    }

    private fun setupFarmStatusView() {
        // Farm status updates happen via FarmListener callbacks
        binding.farmStatusView.update(MinigameType.NONE, 0, 0, false)
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { confirmExit() }

        binding.btnToggleGamepad.setOnClickListener {
            gamepadVisible = !gamepadVisible
            binding.virtualGamepad.visibility = if (gamepadVisible) View.VISIBLE else View.INVISIBLE
            val icon = if (gamepadVisible) R.drawable.ic_gamepad else android.R.drawable.ic_menu_close_clear_cancel
            binding.btnToggleGamepad.setImageResource(icon)
        }

        binding.btnToggleOverlay.setOnClickListener { toggleOverlay() }

        binding.btnFarm.setOnClickListener { showFarmDialog() }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, GamepadSettingsActivity::class.java))
        }
    }

    // ── VirtualGamepadView.GamepadListener ────────────────────────────────────

    override fun onStickMove(dx: Float, dy: Float) {
        val js = buildMoveCursorJs(dx, dy)
        binding.webView.evaluateJavascript(js) { _ -> }
    }

    override fun onStickRelease() {
        // No-op — cursor stays where it was
    }

    override fun onButtonDown(button: VirtualGamepadView.Btn) {
        when (button) {
            VirtualGamepadView.Btn.A     -> injectClick()
            VirtualGamepadView.Btn.B     -> injectKey(27) // Esc
            VirtualGamepadView.Btn.X     -> injectKey(69) // E
            VirtualGamepadView.Btn.Y     -> toggleOverlay()
            VirtualGamepadView.Btn.L1    -> injectKey(84) // T (Chat)
            VirtualGamepadView.Btn.R1    -> injectKey(77) // M (Map)
            VirtualGamepadView.Btn.L2    -> injectKey(73) // I (Inventory)
            VirtualGamepadView.Btn.L3    -> injectKey(84) // T
            VirtualGamepadView.Btn.START -> injectKey(13) // Enter
        }
    }

    override fun onButtonUp(button: VirtualGamepadView.Btn) {
        // Fire keyup events for held keys
        val keyCode = when (button) {
            VirtualGamepadView.Btn.B  -> 27
            VirtualGamepadView.Btn.X  -> 69
            VirtualGamepadView.Btn.L1 -> 84
            VirtualGamepadView.Btn.R1 -> 77
            VirtualGamepadView.Btn.L2 -> 73
            else -> return
        }
        injectKeyUp(keyCode)
    }

    // ── CoinFarmManager.FarmListener ──────────────────────────────────────────

    override fun onMinigameDetected(type: MinigameType) {
        runOnUiThread {
            if (type != MinigameType.NONE && type != MinigameType.UNKNOWN) {
                val msg = "🎮 ${type.displayName} detectado!"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onFarmStarted(type: MinigameType) {
        runOnUiThread {
            farmSessionStartMs = System.currentTimeMillis()
            farmCoinsThisSession = 0
            binding.farmStatusView.update(type, 0, farmSessionStartMs, true)
            binding.btnFarm.setColorFilter(android.graphics.Color.parseColor("#FF00E676"))
            HapticManager.vibrate(this, HapticManager.Feedback.FARM_START, gamepadConfig.hapticEnabled)
            Toast.makeText(this, "🌾 Farm iniciado: ${type.displayName}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onFarmStopped(type: MinigameType, session: FarmSession) {
        runOnUiThread {
            StatsManager.saveStats(this, farmManager.stats)
            binding.farmStatusView.update(MinigameType.NONE, 0, 0, false)
            binding.btnFarm.clearColorFilter()
            HapticManager.vibrate(this, HapticManager.Feedback.FARM_STOP, gamepadConfig.hapticEnabled)
            val coins = StatsManager.formatCoins(session.coinsEarned)
            Toast.makeText(this, "⏹ Farm parado. Coins: $coins", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCoinsUpdated(sessionCoins: Int, totalCoins: Int) {
        runOnUiThread {
            farmCoinsThisSession = sessionCoins
            val type = MinigameType.values().firstOrNull { farmManager.stats.sessionsByGame.containsKey(it.name) }
                ?: MinigameType.NONE
            binding.farmStatusView.update(type, sessionCoins, farmSessionStartMs, true)
            HapticManager.vibrate(this, HapticManager.Feedback.COIN_EARNED, gamepadConfig.hapticEnabled)
        }
    }

    override fun onError(type: MinigameType, message: String) {
        runOnUiThread {
            Toast.makeText(this, "⚠️ Farm: $message", Toast.LENGTH_SHORT).show()
        }
    }

    // ── ErrorRecoveryManager.RecoveryListener ────────────────────────────────

    override fun onErrorDetected(type: ErrorRecoveryManager.ErrorType, message: String) {
        runOnUiThread {
            currentFarmSession?.errorsRecovered = (currentFarmSession?.errorsRecovered ?: 0) + 1
        }
    }

    override fun onRecovering(attempt: Int) {
        runOnUiThread {
            Toast.makeText(this, "🔄 Recuperando jogo (tentativa $attempt)...", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRecovered() {
        runOnUiThread {
            Toast.makeText(this, "✅ Jogo recuperado com sucesso!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRecoveryFailed(totalAttempts: Int) {
        runOnUiThread {
            HapticManager.vibrate(this, HapticManager.Feedback.ERROR, gamepadConfig.hapticEnabled)
            AlertDialog.Builder(this, R.style.Theme_GameMapper_Dialog)
                .setTitle("⚠️ Falha na Recuperação")
                .setMessage("Não foi possível recuperar o jogo após $totalAttempts tentativas.\n\nDeseja recarregar manualmente?")
                .setPositiveButton("Recarregar") { _, _ -> binding.webView.loadUrl(gameUrl) }
                .setNegativeButton("Sair") { _, _ -> finish() }
                .show()
        }
    }

    // ── Farm Dialog ──────────────────────────────────────────────────────────

    private fun showFarmDialog() {
        val items = arrayOf(
            "🌾 Auto-Farm (detectar minigame)",
            "🛒 Cart Surfer (farm manual)",
            "⛏️ Mineração (farm manual)",
            "🎣 Pesca (farm manual)",
            "🐧 Puffle Roundup (farm manual)",
            "👨‍🍳 Pizza Job (farm manual)",
            "⏹ Parar todos os farms",
            "📊 Ver estatísticas"
        )
        AlertDialog.Builder(this, R.style.Theme_GameMapper_Dialog)
            .setTitle("Auto-Farm de Coins")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> toggleAutoFarm()
                    1 -> farmManager.startFarmManually(MinigameType.CART_SURFER)
                    2 -> farmManager.startFarmManually(MinigameType.MINING)
                    3 -> farmManager.startFarmManually(MinigameType.FISHING)
                    4 -> farmManager.startFarmManually(MinigameType.PUFFLE_ROUNDUP)
                    5 -> farmManager.startFarmManually(MinigameType.PIZZA_JOB)
                    6 -> { farmManager.stopCurrentFarm(); Toast.makeText(this, "Farms parados", Toast.LENGTH_SHORT).show() }
                    7 -> startActivity(Intent(this, FarmDashboardActivity::class.java))
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun toggleAutoFarm() {
        autoFarmEnabled = !autoFarmEnabled
        farmManager.setAutoFarm(autoFarmEnabled)
        if (autoFarmEnabled) farmManager.start(true)
        val msg = if (autoFarmEnabled) "🌾 Auto-Farm ATIVADO" else "⏹ Auto-Farm DESATIVADO"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ── JS injection helpers ─────────────────────────────────────────────────

    private fun injectVirtualCursor() {
        if (cursorInjected) return
        cursorInjected = true
        val js = """
(function() {
    if (window.__gmapper_cursor) return;
    window.__gmapper_cursor = { x: window.innerWidth / 2, y: window.innerHeight / 2 };
    var cur = document.createElement('div');
    cur.id = '__gmapper_cursor_el';
    cur.style.cssText = 'position:fixed;width:14px;height:14px;border-radius:50%;' +
        'background:rgba(0,180,255,0.85);border:2px solid white;box-shadow:0 0 8px #00B4FF;' +
        'pointer-events:none;z-index:999999;transform:translate(-50%,-50%);' +
        'transition:left 0.05s,top 0.05s;left:50%;top:50%;';
    document.body.appendChild(cur);
})();
""".trimIndent()
        binding.webView.evaluateJavascript(js) { _ -> }
    }

    private fun buildMoveCursorJs(dx: Float, dy: Float): String = """
(function() {
    if (!window.__gmapper_cursor) return;
    var c = window.__gmapper_cursor;
    c.x = Math.max(0, Math.min(window.innerWidth,  c.x + ${dx}));
    c.y = Math.max(0, Math.min(window.innerHeight, c.y + ${dy}));
    var el = document.getElementById('__gmapper_cursor_el');
    if (el) { el.style.left = c.x + 'px'; el.style.top = c.y + 'px'; }
})();
""".trimIndent()

    private fun injectClick() {
        val js = """
(function() {
    var c = window.__gmapper_cursor || { x: window.innerWidth/2, y: window.innerHeight/2 };
    var el = document.elementFromPoint(c.x, c.y) || document.querySelector('canvas') || document.body;
    ['mousedown','mouseup','click'].forEach(function(type) {
        el.dispatchEvent(new MouseEvent(type, {clientX:c.x, clientY:c.y, bubbles:true, cancelable:true}));
    });
    // Visual feedback
    var ring = document.createElement('div');
    ring.style.cssText = 'position:fixed;width:30px;height:30px;border-radius:50%;' +
        'border:2px solid #00B4FF;pointer-events:none;z-index:1000000;' +
        'left:'+(c.x-15)+'px;top:'+(c.y-15)+'px;animation:none;opacity:1;';
    document.body.appendChild(ring);
    setTimeout(function() { ring.style.transition='all 0.3s'; ring.style.opacity='0'; ring.style.transform='scale(2)'; }, 10);
    setTimeout(function() { document.body.removeChild(ring); }, 350);
})();
""".trimIndent()
        binding.webView.evaluateJavascript(js) { _ -> }
    }

    private fun injectKey(keyCode: Int) {
        val js = """
(function() {
    var target = document.querySelector('canvas') || document.activeElement || document.body;
    target.dispatchEvent(new KeyboardEvent('keydown', {keyCode:$keyCode,which:$keyCode,bubbles:true,cancelable:true}));
    setTimeout(function() {
        target.dispatchEvent(new KeyboardEvent('keyup', {keyCode:$keyCode,which:$keyCode,bubbles:true,cancelable:true}));
    }, 80);
})();
""".trimIndent()
        binding.webView.evaluateJavascript(js) { _ -> }
    }

    private fun injectKeyUp(keyCode: Int) {
        val js = """
(function() {
    var target = document.querySelector('canvas') || document.activeElement || document.body;
    target.dispatchEvent(new KeyboardEvent('keyup', {keyCode:$keyCode,which:$keyCode,bubbles:true,cancelable:true}));
})();
""".trimIndent()
        binding.webView.evaluateJavascript(js) { _ -> }
    }

    // ── Overlay ───────────────────────────────────────────────────────────────

    private fun toggleOverlay() {
        overlayVisible = !overlayVisible
        binding.overlayContainer.visibility = if (overlayVisible) View.VISIBLE else View.GONE
    }

    // ── Login detection ───────────────────────────────────────────────────────

    private fun domainFromUrl(url: String): String {
        return try {
            Uri.parse(url).host ?: url
        } catch (e: Exception) {
            url
        }
    }

    private fun handleLoginDetection(url: String, info: CppsLoginHandler.CppsInfo) {
        if (loginOffered || info.loginType == CppsLoginHandler.LoginType.CANVAS_BASED) {
            handler.postDelayed({ injectVirtualCursor() }, 2000)
            return
        }
        loginOffered = true
        val domain  = domainFromUrl(url)
        val userSel = info.usernameSelector ?: "input[name='username']"
        val passSel = info.passwordSelector  ?: "input[type='password']"
        val subSel  = info.submitSelector    ?: "button[type='submit']"

        val saved = CredentialStorage.load(this, domain)
        if (saved != null) {
            AlertDialog.Builder(this, R.style.Theme_GameMapper_Dialog)
                .setTitle("Login — ${info.displayName}")
                .setMessage("Credenciais salvas para ${saved.username}.\n\nEntrar automaticamente?")
                .setPositiveButton("Sim") { _, _ ->
                    injectAndLogin(saved.username, saved.decryptedPassword(), userSel, passSel, subSel)
                }
                .setNegativeButton("Não") { _, _ -> injectVirtualCursor() }
                .show()
        } else {
            handler.postDelayed({ injectVirtualCursor() }, 3000)
        }
    }

    private fun injectAndLogin(username: String, password: String,
                               userSel: String, passSel: String, subSel: String) {
        val js = CppsLoginHandler.buildInjectCredentialsJS(username, password, userSel, passSel, subSel)
        binding.webView.evaluateJavascript(js) { result ->
            runOnUiThread {
                if (result?.contains("\"injected\":true") == true) {
                    Toast.makeText(this, "Login automático enviado!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Falha no login automático.", Toast.LENGTH_SHORT).show()
                    injectVirtualCursor()
                }
            }
        }
    }

    // ── Hardware gamepad (physical controller) ────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.repeatCount ?: 0 > 0) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_THUMBL -> { injectClick(); true }
            KeyEvent.KEYCODE_BUTTON_B  -> { injectKey(27); true }
            KeyEvent.KEYCODE_BUTTON_X  -> { injectKey(69); true }
            KeyEvent.KEYCODE_BUTTON_Y  -> { toggleOverlay(); true }
            KeyEvent.KEYCODE_BUTTON_L1 -> { injectKey(84); true }
            KeyEvent.KEYCODE_BUTTON_R1 -> { injectKey(77); true }
            KeyEvent.KEYCODE_BUTTON_START -> { injectKey(13); true }
            KeyEvent.KEYCODE_BUTTON_SELECT -> { injectKey(84); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_B  -> { injectKeyUp(27); true }
            KeyEvent.KEYCODE_BUTTON_X  -> { injectKeyUp(69); true }
            KeyEvent.KEYCODE_BUTTON_L1 -> { injectKeyUp(84); true }
            KeyEvent.KEYCODE_BUTTON_R1 -> { injectKeyUp(77); true }
            else -> super.onKeyUp(keyCode, event)
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event == null) return super.onGenericMotionEvent(event)
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK &&
            event.action == MotionEvent.ACTION_MOVE) {
            val dx = event.getAxisValue(MotionEvent.AXIS_X)
            val dy = event.getAxisValue(MotionEvent.AXIS_Y)
            val deadzone = gamepadConfig.stickDeadzone
            val speed = gamepadConfig.movementSpeed
            if (kotlin.math.hypot(dx.toDouble(), dy.toDouble()) > deadzone) {
                onStickMove(dx * speed, dy * speed)
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    private fun confirmExit() {
        AlertDialog.Builder(this, R.style.Theme_GameMapper_Dialog)
            .setTitle("Sair do jogo?")
            .setMessage("Isso vai fechar o jogo e voltar ao mapeamento.")
            .setPositiveButton("Sair") { _, _ -> finish() }
            .setNegativeButton("Continuar jogando", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        loadGamepadConfig()
        binding.virtualGamepad.config = gamepadConfig
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        binding.webView.onResume()
        if (autoFarmEnabled) farmManager.start(true)
    }

    override fun onPause() {
        super.onPause()
        binding.webView.onPause()
        farmManager.stop()
        errorRecovery.stopMonitoring()
        StatsManager.saveStats(this, farmManager.stats)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        farmManager.stop()
        errorRecovery.stopMonitoring()
        binding.virtualGamepad.cleanup()
        binding.webView.destroy()
        super.onDestroy()
    }

    override fun onBackPressed() {
        when {
            overlayVisible -> toggleOverlay()
            binding.webView.canGoBack() -> binding.webView.goBack()
            else -> confirmExit()
        }
    }
}
