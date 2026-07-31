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
import com.gamemapper.models.ControlCategory
import com.gamemapper.models.ControlModel
import com.gamemapper.models.ControlProfile
import com.gamemapper.models.ControlType
import org.json.JSONArray
import com.gamemapper.services.CppsLoginHandler
import com.gamemapper.utils.Constants
import com.gamemapper.utils.ProfileStorage
import com.gamemapper.views.VirtualGamepadView

/**
 * Full-screen WebView activity that:
 *  1. Loads the game URL (full-screen, visible WebView)
 *  2. Detects CPPS login pages and offers credential injection (HTML-form servers)
 *  3. Injects a virtual cursor for click-to-move Club Penguin games
 *  4. Translates Android gamepad button events → JS keyboard / mouse events in the game
 *  5. Shows a toggleable mapping overlay (Y / Options / Start)
 *
 * Gamepad button → game action mapping:
 *  D-pad / Left stick   → move virtual cursor (for CP click-to-move)
 *  A (96)               → click at cursor position (move penguin)
 *  B (97)               → mapped ACTION control #2 (or Esc)
 *  X (99)               → mapped INTERACTION control (or E key)
 *  Y (100)              → toggle mapping overlay
 *  L1 (102)             → mapped UI control #1
 *  R1 (103)             → mapped UI control #2
 *  Start (108)          → Enter / open map (M)
 *  Select (109)         → T key (open chat in CP)
 *  Left stick btn (106) → same as A (click)
 */
class GameplayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGameplayBinding
    private var gameUrl: String = ""
    private var profileId: String = ""
    private val handler = Handler(Looper.getMainLooper())

    // Virtual cursor step size in pixels per gamepad event
    private val CURSOR_STEP = 22f
    private val CURSOR_FAST_STEP = 55f

    // Whether the mapping overlay is currently visible
    private var overlayVisible = false

    // Whether the virtual on-screen gamepad is visible
    private var gamepadVisible = true

    // Whether the virtual cursor has been injected
    private var cursorInjected = false

    // Whether we already offered login credentials
    private var loginOffered = false

    // Detected CPPS info
    private var cppsInfo: CppsLoginHandler.CppsInfo? = null

    // Controls from profile, sorted by priority for gamepad assignment
    private var movementControls = listOf<ControlModel>()
    private var actionControls   = listOf<ControlModel>()
    private var uiControls       = listOf<ControlModel>()

    // Canvas-quadrant specific controls (set when profile.isCanvasMode == true)
    private var dpadQuadrant:   ControlModel? = null
    private var actionQuadrant: ControlModel? = null
    private var uiQuadrant:     ControlModel? = null
    private var clickQuadrant:  ControlModel? = null
    private var isCanvasMode = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full-screen immersive
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        binding = ActivityGameplayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        gameUrl   = intent.getStringExtra(Constants.EXTRA_GAME_URL) ?: ""
        profileId = intent.getStringExtra(Constants.EXTRA_PROFILE_ID) ?: ""

        if (gameUrl.isEmpty()) { finish(); return }

        loadProfile()
        setupWebView()
        setupUI()
        binding.webView.loadUrl(gameUrl)
    }

    // ── Profile ──────────────────────────────────────────────────────────────

    private fun loadProfile() {
        if (profileId.isEmpty()) return
        val profile = ProfileStorage.getProfile(this, profileId) ?: return
        isCanvasMode = profile.isCanvasMode

        if (isCanvasMode) {
            // Canvas-quadrant mode: keycodes are embedded in quadrantKeys JSON per zone
            dpadQuadrant   = profile.controls.firstOrNull { it.quadrantZone == "DPAD" }
            actionQuadrant = profile.controls.firstOrNull { it.quadrantZone == "ACTION" }
            uiQuadrant     = profile.controls.firstOrNull { it.quadrantZone == "UI" }
            clickQuadrant  = profile.controls.firstOrNull { it.quadrantZone == "CANVAS_CLICK" }

            // Also populate the legacy lists from keyboard controls in the profile
            // so the existing gamepad dispatch logic works without changes
            val kbControls = profile.controls.filter { it.type == ControlType.KEYBOARD }
            movementControls = kbControls.filter { it.category == ControlCategory.MOVEMENT }
                .sortedByDescending { it.frequency }
            actionControls   = kbControls.filter { it.category == ControlCategory.ACTION }
                .sortedByDescending { it.frequency }
            uiControls       = kbControls.filter { it.category == ControlCategory.UI }
                .sortedByDescending { it.frequency }
        } else {
            // Legacy DOM-element mode
            movementControls = profile.controls.filter { it.category == ControlCategory.MOVEMENT }
                .sortedByDescending { it.frequency }
            actionControls   = profile.controls.filter { it.category == ControlCategory.ACTION }
                .sortedByDescending { it.frequency }
            uiControls       = profile.controls.filter { it.category == ControlCategory.UI }
                .sortedByDescending { it.frequency }
        }
    }

    // ── WebView setup ─────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val ws = binding.webView.settings
        ws.javaScriptEnabled       = true
        ws.domStorageEnabled       = true
        ws.loadWithOverviewMode    = true
        ws.useWideViewPort         = true
        ws.mixedContentMode        = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        ws.mediaPlaybackRequiresUserGesture = false
        ws.allowContentAccess      = true
        ws.allowFileAccess         = true
        ws.setSupportZoom(true)
        ws.builtInZoomControls     = false
        ws.displayZoomControls     = false
        // Desktop user-agent so CP servers send the full game
        ws.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        WebView.setWebContentsDebuggingEnabled(false)

        cppsInfo = CppsLoginHandler.detect(gameUrl)

        binding.webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.loadingBar.visibility = View.VISIBLE
                cursorInjected = false
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                binding.loadingBar.visibility = View.GONE
                handler.postDelayed({ onGamePageReady(url) }, 1500)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) binding.loadingBar.visibility = View.GONE
            }

            @Deprecated("Deprecated")
            override fun onReceivedError(view: WebView, code: Int, desc: String, url: String) {
                binding.loadingBar.visibility = View.GONE
            }

            // Allow navigation within the same CPPS (login → game redirect)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url.toString()
                // Only override if navigating away from the CPPS entirely
                val isSameDomain = cppsInfo?.let { uri.contains(it.domain) } ?: true
                return if (!isSameDomain) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                    true
                } else false
            }
        }
    }

    private fun onGamePageReady(url: String) {
        val info = cppsInfo

        // Step 1 – Detect login state
        binding.webView.evaluateJavascript(CppsLoginHandler.DETECT_LOGIN_STATE_JS) { json ->
            val hasHtmlForm = json.contains("\"hasHtmlForm\":true")
            val hasCanvas   = json.contains("\"hasCanvas\":true")

            when {
                // HTML form login page detected → offer credential injection
                hasHtmlForm && !loginOffered -> {
                    loginOffered = true
                    runOnUiThread { offerHtmlLogin(info, json) }
                }
                // Canvas game (no HTML form) → inject virtual cursor
                hasCanvas -> {
                    runOnUiThread { injectVirtualCursor() }
                }
                else -> {
                    // Generic game page — inject cursor anyway
                    runOnUiThread { injectVirtualCursor() }
                }
            }
        }
    }

    // ── Login handling ────────────────────────────────────────────────────────

    private fun offerHtmlLogin(info: CppsLoginHandler.CppsInfo?, detectedJson: String) {
        // Extract selectors detected by JS (fall back to CPPS-specific ones)
        val userSel   = extractJsonString(detectedJson, "usernameSel").ifEmpty {
            info?.usernameSelector ?: "input[type='text']" }
        val passSel   = extractJsonString(detectedJson, "passwordSel").ifEmpty {
            info?.passwordSelector ?: "input[type='password']" }
        val submitSel = extractJsonString(detectedJson, "submitSel").ifEmpty {
            info?.submitSelector ?: "button[type='submit']" }

        val serverName = info?.displayName ?: "este servidor"

        AlertDialog.Builder(this, R.style.Theme_GameMapper_Dialog)
            .setTitle("Login — $serverName")
            .setMessage(
                "Uma tela de login foi detectada.\n\n" +
                "Quer que o GameMapper preencha suas credenciais automaticamente?"
            )
            .setPositiveButton("Sim, inserir dados") { _, _ ->
                showCredentialInputDialog(userSel, passSel, submitSel, serverName)
            }
            .setNegativeButton("Não, vou digitar") { _, _ ->
                // User will type manually; inject cursor after they log in
                handler.postDelayed({ injectVirtualCursor() }, 5000)
            }
            .setCancelable(false)
            .show()
    }

    private fun showCredentialInputDialog(
        userSel: String, passSel: String, submitSel: String, serverName: String
    ) {
        val inflater = layoutInflater
        val view = inflater.inflate(R.layout.dialog_login_input, null)
        val etUser = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etUsername)
        val etPass = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPassword)

        AlertDialog.Builder(this, R.style.Theme_GameMapper_Dialog)
            .setTitle("Credenciais — $serverName")
            .setView(view)
            .setPositiveButton("Entrar") { _, _ ->
                val username = etUser?.text?.toString()?.trim() ?: ""
                val password = etPass?.text?.toString() ?: ""
                if (username.isNotEmpty() && password.isNotEmpty()) {
                    val js = CppsLoginHandler.buildInjectCredentialsJS(
                        username, password, userSel, passSel, submitSel
                    )
                    binding.webView.evaluateJavascript(js) { result ->
                        if (result.contains("\"injected\":true")) {
                            runOnUiThread {
                                Toast.makeText(this, "Login enviado…", Toast.LENGTH_SHORT).show()
                                // Inject cursor after redirect to game
                                handler.postDelayed({ injectVirtualCursor() }, 4000)
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ── Virtual cursor ────────────────────────────────────────────────────────

    private fun injectVirtualCursor() {
        if (cursorInjected) return
        binding.webView.evaluateJavascript(CppsLoginHandler.VIRTUAL_CURSOR_JS) { result ->
            if (result != null && result != "null") {
                cursorInjected = true
            }
        }
    }

    // ── UI / overlay ──────────────────────────────────────────────────────────

    private fun setupUI() {
        binding.btnBack.setOnClickListener { confirmExit() }
        binding.btnToggleOverlay.setOnClickListener { toggleOverlay() }
        binding.btnToggleGamepad.setOnClickListener { toggleVirtualGamepad() }

        wireVirtualGamepad()

        // Build overlay content from profile
        buildOverlayContent()
        binding.overlayCard.visibility = View.GONE
    }

    private fun toggleVirtualGamepad() {
        gamepadVisible = !gamepadVisible
        binding.virtualGamepad.visibility = if (gamepadVisible) View.VISIBLE else View.GONE
    }

    private fun wireVirtualGamepad() {
        binding.virtualGamepad.listener = object : VirtualGamepadView.GamepadListener {

            override fun onStickMove(dx: Float, dy: Float) {
                // VirtualGamepadView already multiplied by movementSpeed — call JS directly
                // to avoid double-scaling (do NOT route through moveCursor()).
                moveCursorDirect(dx, dy)
            }

            override fun onStickRelease() {
                // Cursor movement stops naturally; nothing to do.
            }

            override fun onButtonDown(button: VirtualGamepadView.Btn) {
                when (button) {
                    VirtualGamepadView.Btn.A     -> triggerCursorClick()
                    VirtualGamepadView.Btn.B     -> injectKeyEvent(27, "keydown")   // Esc
                    VirtualGamepadView.Btn.X     -> injectKeyEvent(69, "keydown")   // E – interact
                    VirtualGamepadView.Btn.Y     -> toggleOverlay()
                    VirtualGamepadView.Btn.L1    -> injectKeyEvent(84, "keydown")   // T – chat
                    VirtualGamepadView.Btn.R1    -> injectKeyEvent(77, "keydown")   // M – map
                    VirtualGamepadView.Btn.L2    -> injectKeyEvent(73, "keydown")   // I – inventory
                    VirtualGamepadView.Btn.START -> injectKeyEvent(13, "keydown")   // Enter
                }
            }

            override fun onButtonUp(button: VirtualGamepadView.Btn) {
                when (button) {
                    VirtualGamepadView.Btn.B     -> injectKeyEvent(27, "keyup")
                    VirtualGamepadView.Btn.X     -> injectKeyEvent(69, "keyup")
                    VirtualGamepadView.Btn.L1    -> injectKeyEvent(84, "keyup")
                    VirtualGamepadView.Btn.R1    -> injectKeyEvent(77, "keyup")
                    VirtualGamepadView.Btn.L2    -> injectKeyEvent(73, "keyup")
                    VirtualGamepadView.Btn.START -> injectKeyEvent(13, "keyup")
                    else -> { /* A and Y have no keyup action */ }
                }
            }
        }
    }

    private fun buildOverlayContent() {
        val lines = StringBuilder()

        if (isCanvasMode) {
            // ── Canvas-quadrant mode overlay ────────────────────────────────
            lines.appendLine("🎮 Modo Canvas Ativo")
            lines.appendLine()

            // D-Pad zone
            val dpad = dpadQuadrant
            if (dpad != null) {
                lines.appendLine("Analógico esquerdo / D-pad")
                lines.appendLine("  → Move cursor virtual na ilha")
                val dpadKeys = parseFlatKeys(dpad.quadrantKeys)
                dpadKeys.forEach { k -> lines.appendLine("    ${k.first} = ${k.second}") }
                lines.appendLine()
            }

            // Action buttons zone
            val act = actionQuadrant
            if (act != null) {
                lines.appendLine("Botões de Ação (quadrante inferior direito)")
                val actKeys = parseFlatKeys(act.quadrantKeys)
                val btnLabels = listOf("Sul [A]", "Leste [B]", "Norte [X]", "Oeste [Y]")
                actKeys.forEachIndexed { i, k ->
                    val btn = btnLabels.getOrElse(i) { "[?]" }
                    lines.appendLine("  $btn → ${k.second} (${k.first})")
                }
                lines.appendLine()
            }

            // UI zone
            val ui = uiQuadrant
            if (ui != null) {
                lines.appendLine("Interface (canto superior direito)")
                val uiKeys = parseFlatKeys(ui.quadrantKeys)
                val uiBtns = listOf("[L1]", "[R1]", "[L2]")
                uiKeys.forEachIndexed { i, k ->
                    lines.appendLine("  ${uiBtns.getOrElse(i){ "[?]" }} → ${k.second}")
                }
                lines.appendLine()
            }

            // Click-to-move
            if (clickQuadrant != null) {
                lines.appendLine("[A] → Clique no canvas (mover pinguim)")
            }

            lines.appendLine()
            lines.appendLine("[Y]     → Fechar este painel")
            lines.appendLine("[Start] → Enter")
            lines.appendLine("[Sel]   → T (Chat)")

        } else {
            // ── Legacy DOM-element mode overlay ────────────────────────────
            lines.appendLine("🎮 Mapeamento ativo")
            lines.appendLine()

            val movKeys = movementControls.take(4)
            if (movKeys.isNotEmpty()) {
                lines.appendLine("D-pad / Analógico → Cursor")
                lines.appendLine("  ↑↓←→  mover cursor na tela")
                lines.appendLine("  [A] → Clique (mover pinguim)")
            } else {
                lines.appendLine("  [A]  → Clique / Ação principal")
            }

            actionControls.forEachIndexed { i, ctrl ->
                if (i < 2) {
                    val btn = listOf("[B]", "[X]")[i]
                    lines.appendLine("  $btn  → ${ctrl.label}")
                }
            }
            uiControls.forEachIndexed { i, ctrl ->
                if (i < 4) {
                    val btn = listOf("[L1]", "[R1]", "[Start]", "[Select]")[i]
                    lines.appendLine("  $btn  → ${ctrl.label}")
                }
            }

            lines.appendLine()
            lines.appendLine("[Y]      → Fechar este painel")
            lines.appendLine("[Start]  → Enter / Mapa (M)")
            lines.appendLine("[Select] → Chat (T)")
        }

        binding.tvOverlayContent.text = lines.toString()
    }

    /**
     * Parses the quadrantKeys JSON string (stored as "[{keyCode,label,direction},…]")
     * into a list of (direction, label) pairs for overlay display.
     */
    private fun parseFlatKeys(quadrantKeysJson: String?): List<Pair<String, String>> {
        if (quadrantKeysJson.isNullOrEmpty()) return emptyList()
        return try {
            val arr = JSONArray(quadrantKeysJson)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                obj.optString("direction", "?") to obj.optString("label", "?")
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun toggleOverlay() {
        overlayVisible = !overlayVisible
        binding.overlayCard.visibility = if (overlayVisible) View.VISIBLE else View.GONE
    }

    // ── Gamepad input ─────────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val kc     = event.keyCode
        val isDown = event.action == KeyEvent.ACTION_DOWN
        val isUp   = event.action == KeyEvent.ACTION_UP

        // Let the WebView handle regular key input when it has focus
        // (typing in CP chat box, etc.) — only intercept gamepad buttons
        if (!isGamepadButton(kc)) return super.dispatchKeyEvent(event)

        when (kc) {
            // ── D-pad ─────────────────────────────────────────────
            KeyEvent.KEYCODE_DPAD_UP    -> if (isDown) moveCursor(0f, -CURSOR_STEP)
            KeyEvent.KEYCODE_DPAD_DOWN  -> if (isDown) moveCursor(0f, +CURSOR_STEP)
            KeyEvent.KEYCODE_DPAD_LEFT  -> if (isDown) moveCursor(-CURSOR_STEP, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (isDown) moveCursor(+CURSOR_STEP, 0f)

            // ── Face buttons ──────────────────────────────────────
            KeyEvent.KEYCODE_BUTTON_A -> {
                // A = click at cursor (move penguin / interact)
                if (isDown) triggerCursorClick()
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                // B = second action control, or Esc
                val ctrl = actionControls.getOrNull(1)
                val code = ctrl?.keyCode?.toIntOrNull() ?: 27 // Esc default
                if (isDown) injectKeyEvent(code, "keydown")
                if (isUp)   injectKeyEvent(code, "keyup")
            }
            KeyEvent.KEYCODE_BUTTON_X -> {
                // X = interaction control, or E
                val ctrl = actionControls.getOrNull(0)
                    ?: movementControls.getOrNull(0)
                val code = ctrl?.keyCode?.toIntOrNull() ?: 69 // E default
                if (isDown) injectKeyEvent(code, "keydown")
                if (isUp)   injectKeyEvent(code, "keyup")
            }
            KeyEvent.KEYCODE_BUTTON_Y -> {
                // Y = toggle overlay
                if (isDown) toggleOverlay()
            }

            // ── Bumpers / Triggers ────────────────────────────────
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                val ctrl = uiControls.getOrNull(0)
                val code = ctrl?.keyCode?.toIntOrNull() ?: 77 // M (map) default
                if (isDown) injectKeyEvent(code, "keydown")
                if (isUp)   injectKeyEvent(code, "keyup")
            }
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                val ctrl = uiControls.getOrNull(1)
                val code = ctrl?.keyCode?.toIntOrNull() ?: 73 // I (inventory) default
                if (isDown) injectKeyEvent(code, "keydown")
                if (isUp)   injectKeyEvent(code, "keyup")
            }

            // ── Start / Select ────────────────────────────────────
            KeyEvent.KEYCODE_BUTTON_START -> {
                // Start = Enter (confirm actions / open map menu)
                if (isDown) injectKeyEvent(13, "keydown")
                if (isUp)   injectKeyEvent(13, "keyup")
            }
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BACK -> {
                // Select = T (open chat in CP) / Back on non-gamepad = go back
                if (kc == KeyEvent.KEYCODE_BACK) {
                    if (isDown) { confirmExit(); return true }
                } else {
                    if (isDown) injectKeyEvent(84, "keydown") // T
                    if (isUp)   injectKeyEvent(84, "keyup")
                }
            }

            // ── Left stick button ─────────────────────────────────
            KeyEvent.KEYCODE_BUTTON_THUMBL -> {
                if (isDown) triggerCursorClick()
            }
        }
        return true
    }

    /**
     * Handle analog stick axis events (continuous D-pad movement).
     * Repeated fast movement when stick is pushed.
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK)
            return super.onGenericMotionEvent(event)

        val x = event.getAxisValue(MotionEvent.AXIS_X)
        val y = event.getAxisValue(MotionEvent.AXIS_Y)
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

        val deadzone = 0.25f
        val effectiveX = if (Math.abs(x) > deadzone) x else hatX
        val effectiveY = if (Math.abs(y) > deadzone) y else hatY

        if (Math.abs(effectiveX) > deadzone || Math.abs(effectiveY) > deadzone) {
            moveCursor(effectiveX * CURSOR_FAST_STEP, effectiveY * CURSOR_FAST_STEP)
        }
        return true
    }

    // ── JS helpers ────────────────────────────────────────────────────────────

    private fun moveCursor(dx: Float, dy: Float) {
        if (!cursorInjected) {
            injectVirtualCursor()
            handler.postDelayed({ moveCursor(dx, dy) }, 600)
            return
        }
        binding.webView.evaluateJavascript(
            "if(window.gmMoveCursor) gmMoveCursor(${dx.toInt()}, ${dy.toInt()});", null
        )
    }

    private fun triggerCursorClick() {
        if (!cursorInjected) {
            injectVirtualCursor()
            handler.postDelayed({ triggerCursorClick() }, 600)
            return
        }
        binding.webView.evaluateJavascript("if(window.gmClick) gmClick();", null)
    }

    private fun injectKeyEvent(keyCode: Int, type: String) {
        binding.webView.evaluateJavascript(
            "if(window.gmKey) gmKey($keyCode, '$type');", null
        )
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private fun isGamepadButton(kc: Int): Boolean = kc in listOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
        KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR
    )

    private fun extractJsonString(json: String, key: String): String {
        val pattern = Regex("\"$key\"\\s*:\\s*\"([^\"\\\\]*)\"")
        return pattern.find(json)?.groupValues?.get(1) ?: ""
    }

    private fun confirmExit() {
        AlertDialog.Builder(this, R.style.Theme_GameMapper_Dialog)
            .setTitle("Sair do jogo?")
            .setMessage("Isso vai fechar o jogo e voltar para o mapeamento.")
            .setPositiveButton("Sair") { _, _ -> finish() }
            .setNegativeButton("Continuar jogando", null)
            .show()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        // Re-enter fullscreen after dialogs etc.
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        binding.webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.webView.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        binding.webView.destroy()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (overlayVisible) {
            toggleOverlay()
        } else if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            confirmExit()
        }
    }
}
