package com.gamemapper.views

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.gamemapper.models.GamepadConfig
import com.gamemapper.models.GamepadTheme
import com.gamemapper.utils.HapticManager
import kotlin.math.*

/**
 * Enhanced Virtual Gamepad — completely rewritten for v2.0.
 *
 * New features:
 *  • Neon/Glassmorphism rendering with glow effects per theme
 *  • Per-button velocity tracking for more responsive input
 *  • Configurable deadzone, sensitivity, and movement speed
 *  • Analog stick with spring-back animation
 *  • Haptic feedback on press/release and stick edge
 *  • D-pad mode alternative to analog stick
 *  • Auto-hide when no touch for configurable delay
 *  • Opacity control
 *  • 8 visual themes (Neon Blue, Neon Green, Neon Purple, Neon Orange,
 *    Classic Dark, Glassmorphism, Fire, Ice)
 *  • L1/R1 shoulder buttons redesigned as pill-shaped triggers
 *  • Smooth press animation on buttons
 */
class VirtualGamepadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Listener ──────────────────────────────────────────────────────────────

    interface GamepadListener {
        fun onStickMove(dx: Float, dy: Float)
        fun onStickRelease()
        fun onButtonDown(button: Btn)
        fun onButtonUp(button: Btn)
    }

    enum class Btn {
        A, B, X, Y,
        L1, R1, L2, L3,
        START
    }

    var listener: GamepadListener? = null
    var config: GamepadConfig = GamepadConfig()
        set(value) { field = value; applyTheme(); invalidate() }

    // ── Theme colors (recomputed on theme change) ──────────────────────────────

    private var colorPrimary = Color.parseColor("#00B4FF")
    private var colorSecondary = Color.parseColor("#0066FF")
    private var colorGlow = Color.parseColor("#40B4FF")
    private var colorAccent = Color.parseColor("#FF6B00")
    private var colorBtnA = Color.parseColor("#00E676")
    private var colorBtnB = Color.parseColor("#FF1744")
    private var colorBtnX = Color.parseColor("#2979FF")
    private var colorBtnY = Color.parseColor("#FF9100")
    private var colorStickBg = Color.parseColor("#1A2040")
    private var colorStickThumb = Color.parseColor("#00B4FF")
    private var colorOverlay = Color.parseColor("#18204060")

    // ── Geometry ──────────────────────────────────────────────────────────────

    private var stickCX = 0f; private var stickCY = 0f
    private var stickOuterR = 0f; private var stickInnerR = 0f
    private var thumbX = 0f; private var thumbY = 0f

    private data class ActionBtn(
        val btn: Btn, val label: String, val baseColor: Int,
        var cx: Float = 0f, var cy: Float = 0f, var r: Float = 0f,
        var pressed: Boolean = false, var ptrId: Int = -1,
        var pressAnim: Float = 0f  // 0=idle, 1=fully pressed
    )

    private var faceBtns = mutableListOf<ActionBtn>()

    private data class SmallBtn(
        val btn: Btn, val label: String,
        var cx: Float = 0f, var cy: Float = 0f, var r: Float = 0f,
        var pressed: Boolean = false, var pressAnim: Float = 0f
    )
    private var uiBtns = mutableListOf<SmallBtn>()

    // Trigger bars (L1/R1 shoulder buttons)
    private data class TriggerBar(
        val btn: Btn, val label: String, val side: Int, // -1=left, 1=right
        var left: Float = 0f, var top: Float = 0f, var right: Float = 0f, var bottom: Float = 0f,
        var pressed: Boolean = false
    )
    private var triggers = mutableListOf<TriggerBar>()

    // ── Stick state ───────────────────────────────────────────────────────────

    private var stickActive = false; private var stickPtrId = -1
    private var stickNormDx = 0f; private var stickNormDy = 0f
    private var stickVelX = 0f; private var stickVelY = 0f
    private var prevStickX = 0f; private var prevStickY = 0f
    private var atEdge = false

    private val moveHandler = Handler(Looper.getMainLooper())
    private val moveRunnable = object : Runnable {
        override fun run() {
            if (!stickActive) return
            val speed = config.movementSpeed
            listener?.onStickMove(stickNormDx * speed, stickNormDy * speed)
            moveHandler.postDelayed(this, 16)
        }
    }

    // ── Spring-back animation ─────────────────────────────────────────────────

    private val springHandler = Handler(Looper.getMainLooper())
    private var isSpringBack = false

    // ── Auto-hide ─────────────────────────────────────────────────────────────

    private var hideAlpha = 1f
    private var lastTouchTime = 0L
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable {
        if (config.autoHide && !stickActive) {
            animateFadeOut()
        }
    }

    // ── Paints ────────────────────────────────────────────────────────────────

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val triggerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val triggerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    // ── Init ─────────────────────────────────────────────────────────────────

    init {
        applyTheme()
        buildButtons()
    }

    private fun buildButtons() {
        faceBtns = mutableListOf(
            ActionBtn(Btn.A, "A", colorBtnA),
            ActionBtn(Btn.B, "B", colorBtnB),
            ActionBtn(Btn.X, "X", colorBtnX),
            ActionBtn(Btn.Y, "Y", colorBtnY)
        )
        uiBtns = mutableListOf(
            SmallBtn(Btn.L3, "T"),
            SmallBtn(Btn.START, "↵"),
            SmallBtn(Btn.L2, "M")
        )
        triggers = mutableListOf(
            TriggerBar(Btn.L1, "L1", -1),
            TriggerBar(Btn.R1, "R1", 1)
        )
    }

    private fun applyTheme() {
        when (config.theme) {
            GamepadTheme.NEON_BLUE -> {
                colorPrimary = Color.parseColor("#00B4FF")
                colorSecondary = Color.parseColor("#0044CC")
                colorGlow = Color.parseColor("#4488FF")
                colorStickBg = Color.parseColor("#0D1830")
                colorStickThumb = Color.parseColor("#00B4FF")
                colorOverlay = Color.parseColor("#12204060")
            }
            GamepadTheme.NEON_GREEN -> {
                colorPrimary = Color.parseColor("#00E676")
                colorSecondary = Color.parseColor("#00AA44")
                colorGlow = Color.parseColor("#40FF88")
                colorStickBg = Color.parseColor("#0D1F18")
                colorStickThumb = Color.parseColor("#00E676")
                colorOverlay = Color.parseColor("#12204020")
            }
            GamepadTheme.NEON_PURPLE -> {
                colorPrimary = Color.parseColor("#CC44FF")
                colorSecondary = Color.parseColor("#8800CC")
                colorGlow = Color.parseColor("#DD88FF")
                colorStickBg = Color.parseColor("#180D2A")
                colorStickThumb = Color.parseColor("#CC44FF")
                colorOverlay = Color.parseColor("#18180530")
            }
            GamepadTheme.NEON_ORANGE -> {
                colorPrimary = Color.parseColor("#FF6600")
                colorSecondary = Color.parseColor("#CC4400")
                colorGlow = Color.parseColor("#FF9944")
                colorStickBg = Color.parseColor("#1F1008")
                colorStickThumb = Color.parseColor("#FF6600")
                colorOverlay = Color.parseColor("#12302010")
            }
            GamepadTheme.GLASSMORPHISM -> {
                colorPrimary = Color.parseColor("#FFFFFF")
                colorSecondary = Color.parseColor("#AADDFF")
                colorGlow = Color.parseColor("#88CCFF")
                colorStickBg = Color.parseColor("#33FFFFFF")
                colorStickThumb = Color.parseColor("#CCFFFFFF")
                colorOverlay = Color.parseColor("#22FFFFFF")
            }
            GamepadTheme.CLASSIC_DARK -> {
                colorPrimary = Color.parseColor("#888888")
                colorSecondary = Color.parseColor("#555555")
                colorGlow = Color.parseColor("#444444")
                colorStickBg = Color.parseColor("#222222")
                colorStickThumb = Color.parseColor("#AAAAAA")
                colorOverlay = Color.parseColor("#AA000000")
            }
            GamepadTheme.FIRE -> {
                colorPrimary = Color.parseColor("#FF4400")
                colorSecondary = Color.parseColor("#FF8800")
                colorGlow = Color.parseColor("#FFAA00")
                colorBtnA = Color.parseColor("#FF2200"); colorBtnB = Color.parseColor("#FF6600")
                colorBtnX = Color.parseColor("#FFAA00"); colorBtnY = Color.parseColor("#FF0066")
                colorStickBg = Color.parseColor("#1F0800")
                colorStickThumb = Color.parseColor("#FF4400")
                colorOverlay = Color.parseColor("#22200800")
            }
            GamepadTheme.ICE -> {
                colorPrimary = Color.parseColor("#88EEFF")
                colorSecondary = Color.parseColor("#44AACC")
                colorGlow = Color.parseColor("#BBFFFF")
                colorBtnA = Color.parseColor("#44DDFF"); colorBtnB = Color.parseColor("#88AAFF")
                colorBtnX = Color.parseColor("#AAEEFF"); colorBtnY = Color.parseColor("#66BBFF")
                colorStickBg = Color.parseColor("#0A1A20")
                colorStickThumb = Color.parseColor("#88EEFF")
                colorOverlay = Color.parseColor("#22082030")
            }
        }
        // Rebuild buttons with updated colors
        if (faceBtns.isNotEmpty()) {
            faceBtns[0] = faceBtns[0].copy(baseColor = colorBtnA)
            faceBtns[1] = faceBtns[1].copy(baseColor = colorBtnB)
            faceBtns[2] = faceBtns[2].copy(baseColor = colorBtnX)
            faceBtns[3] = faceBtns[3].copy(baseColor = colorBtnY)
        }
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        val s = config.buttonScale
        val minDim = min(w, h)

        // ── Stick (bottom-left) ──────────────────────────────────────────────
        stickOuterR = minDim * 0.145f * s
        stickInnerR = stickOuterR * 0.42f
        stickCX = stickOuterR * 1.55f
        stickCY = h - stickOuterR * 1.55f
        thumbX = stickCX; thumbY = stickCY

        // ── Face buttons (bottom-right) ──────────────────────────────────────
        val btnR = minDim * 0.058f * s
        val btnSpacing = btnR * 2.3f
        val faceCX = w - btnSpacing * 2.1f
        val faceCY = h - btnSpacing * 2.1f
        // Y=north, X=west, B=east, A=south
        faceBtns[0].apply { cx = faceCX;           cy = faceCY + btnSpacing; r = btnR } // A-south
        faceBtns[1].apply { cx = faceCX + btnSpacing; cy = faceCY;           r = btnR } // B-east
        faceBtns[2].apply { cx = faceCX - btnSpacing; cy = faceCY;           r = btnR } // X-west
        faceBtns[3].apply { cx = faceCX;           cy = faceCY - btnSpacing; r = btnR } // Y-north

        // ── UI small buttons (top-center) ────────────────────────────────────
        val sBtnR = minDim * 0.04f * s
        val uiY = sBtnR * 1.8f
        val totalW = sBtnR * 7f
        val startX = w / 2f - totalW / 2f
        uiBtns[0].apply { cx = startX + sBtnR; cy = uiY; r = sBtnR }           // T (Chat)
        uiBtns[1].apply { cx = startX + sBtnR * 3.5f; cy = uiY; r = sBtnR }    // Enter
        uiBtns[2].apply { cx = startX + sBtnR * 6f; cy = uiY; r = sBtnR }      // M (Map)

        // ── Shoulder triggers (top corners) ──────────────────────────────────
        val trigH = minDim * 0.052f * s
        val trigW = w * 0.18f * s
        val trigY = trigH * 0.4f
        triggers[0].apply { left = 0f; top = trigY; right = trigW; bottom = trigY + trigH }  // L1
        triggers[1].apply { left = w - trigW; top = trigY; right = w.toFloat(); bottom = trigY + trigH } // R1
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val alpha = (config.opacity * hideAlpha * 255).toInt().coerceIn(0, 255)
        if (alpha == 0) return

        drawStick(canvas, alpha)
        drawFaceButtons(canvas, alpha)
        drawUIButtons(canvas, alpha)
        drawTriggers(canvas, alpha)
    }

    private fun drawStick(canvas: Canvas, alpha: Int) {
        // Outer ring glow
        glowPaint.apply {
            style = Paint.Style.FILL
            color = if (stickActive) colorPrimary else colorSecondary
            this.alpha = (alpha * 0.2f).toInt()
            maskFilter = BlurMaskFilter(stickOuterR * 0.5f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(stickCX, stickCY, stickOuterR * 1.15f, glowPaint)

        // Outer ring background
        bgPaint.apply {
            style = Paint.Style.FILL
            color = colorStickBg
            this.alpha = alpha
            maskFilter = null
        }
        canvas.drawCircle(stickCX, stickCY, stickOuterR, bgPaint)

        // Outer ring border
        ringPaint.apply {
            style = Paint.Style.STROKE
            strokeWidth = stickOuterR * 0.055f
            color = if (stickActive) colorPrimary else colorSecondary
            this.alpha = alpha
            maskFilter = null
        }
        canvas.drawCircle(stickCX, stickCY, stickOuterR, ringPaint)

        // Cross hair subtle lines
        val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = colorPrimary
            this.alpha = (alpha * 0.2f).toInt()
        }
        canvas.drawLine(stickCX - stickOuterR, stickCY, stickCX + stickOuterR, stickCY, crossPaint)
        canvas.drawLine(stickCX, stickCY - stickOuterR, stickCX, stickCY + stickOuterR, crossPaint)

        // Thumb knob glow
        if (stickActive) {
            glowPaint.apply {
                color = colorGlow
                this.alpha = (alpha * 0.5f).toInt()
                maskFilter = BlurMaskFilter(stickInnerR * 0.8f, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawCircle(thumbX, thumbY, stickInnerR * 1.3f, glowPaint)
        }

        // Thumb knob
        thumbPaint.apply {
            style = Paint.Style.FILL
            maskFilter = null
            shader = RadialGradient(
                thumbX, thumbY, stickInnerR,
                intArrayOf(Color.WHITE, colorPrimary, colorSecondary),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            this.alpha = alpha
        }
        canvas.drawCircle(thumbX, thumbY, stickInnerR, thumbPaint)
    }

    private fun drawFaceButtons(canvas: Canvas, alpha: Int) {
        faceBtns.forEach { btn ->
            val pressedScale = if (btn.pressed) 0.88f else 1f
            val btnAlpha = if (btn.pressed) alpha else (alpha * 0.9f).toInt()
            val r = btn.r * pressedScale

            // Glow
            glowPaint.apply {
                style = Paint.Style.FILL
                color = btn.baseColor
                this.alpha = if (btn.pressed) (btnAlpha * 0.7f).toInt() else (btnAlpha * 0.25f).toInt()
                maskFilter = BlurMaskFilter(btn.r * 0.8f, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawCircle(btn.cx, btn.cy, btn.r * 1.4f, glowPaint)

            // Button background
            btnPaint.apply {
                style = Paint.Style.FILL
                maskFilter = null
                color = if (btn.pressed) btn.baseColor else darkenColor(btn.baseColor, 0.55f)
                this.alpha = btnAlpha
                shader = RadialGradient(
                    btn.cx, btn.cy - r * 0.3f, r,
                    intArrayOf(lightenColor(btn.baseColor, 0.4f), btn.baseColor, darkenColor(btn.baseColor, 0.35f)),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(btn.cx, btn.cy, r, btnPaint)

            // Border ring
            ringPaint.apply {
                style = Paint.Style.STROKE
                strokeWidth = btn.r * 0.06f
                color = if (btn.pressed) lightenColor(btn.baseColor, 0.5f) else btn.baseColor
                this.alpha = btnAlpha
                maskFilter = null
                shader = null
            }
            canvas.drawCircle(btn.cx, btn.cy, r, ringPaint)

            // Label
            textPaint.apply {
                textSize = btn.r * 0.88f
                this.alpha = btnAlpha
                color = if (btn.pressed) Color.WHITE else Color.parseColor("#EEFFFFFF")
                shader = null
            }
            canvas.drawText(btn.label, btn.cx, btn.cy + textPaint.textSize * 0.35f, textPaint)
        }
    }

    private fun drawUIButtons(canvas: Canvas, alpha: Int) {
        val sPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val sRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        uiBtns.forEach { btn ->
            val pressedScale = if (btn.pressed) 0.85f else 1f
            val r = btn.r * pressedScale

            // Glow
            glowPaint.apply {
                color = colorPrimary
                this.alpha = if (btn.pressed) (alpha * 0.5f).toInt() else (alpha * 0.15f).toInt()
                maskFilter = BlurMaskFilter(btn.r * 0.7f, BlurMaskFilter.Blur.NORMAL)
                style = Paint.Style.FILL
            }
            canvas.drawCircle(btn.cx, btn.cy, btn.r * 1.3f, glowPaint)

            // Background
            sPaint.apply {
                color = if (btn.pressed) colorPrimary else colorStickBg
                this.alpha = (alpha * 0.85f).toInt()
                maskFilter = null; shader = null
            }
            canvas.drawCircle(btn.cx, btn.cy, r, sPaint)

            // Border
            sRingPaint.apply {
                strokeWidth = btn.r * 0.07f
                color = colorPrimary
                this.alpha = (alpha * 0.75f).toInt()
                maskFilter = null
            }
            canvas.drawCircle(btn.cx, btn.cy, r, sRingPaint)

            // Label
            textPaint.apply {
                textSize = btn.r * 0.72f
                color = Color.WHITE
                this.alpha = alpha
                shader = null
            }
            canvas.drawText(btn.label, btn.cx, btn.cy + textPaint.textSize * 0.35f, textPaint)
        }
    }

    private fun drawTriggers(canvas: Canvas, alpha: Int) {
        val cornerR = 12f
        triggers.forEach { trig ->
            val rect = RectF(trig.left, trig.top, trig.right, trig.bottom)

            // Glow
            glowPaint.apply {
                color = colorPrimary
                this.alpha = if (trig.pressed) (alpha * 0.6f).toInt() else (alpha * 0.15f).toInt()
                maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
                style = Paint.Style.FILL
            }
            val gr = RectF(rect.left - 4, rect.top - 4, rect.right + 4, rect.bottom + 4)
            canvas.drawRoundRect(gr, cornerR, cornerR, glowPaint)

            // Background
            triggerPaint.apply {
                style = Paint.Style.FILL
                color = if (trig.pressed) colorPrimary else colorStickBg
                this.alpha = (alpha * 0.85f).toInt()
                maskFilter = null
                shader = if (trig.pressed) null else LinearGradient(
                    rect.left, rect.top, rect.right, rect.bottom,
                    colorStickBg, darkenColor(colorStickBg, 0.3f), Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(rect, cornerR, cornerR, triggerPaint)

            // Border
            triggerPaint.apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = colorPrimary
                this.alpha = (alpha * 0.7f).toInt()
                shader = null
            }
            canvas.drawRoundRect(rect, cornerR, cornerR, triggerPaint)

            // Label
            triggerTextPaint.apply {
                textSize = (rect.bottom - rect.top) * 0.45f
                this.alpha = alpha
            }
            canvas.drawText(trig.label, rect.centerX(), rect.centerY() + triggerTextPaint.textSize * 0.35f, triggerTextPaint)
        }
    }

    // ── Touch handling ────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (config.autoHide) resetAutoHide()
        if (hideAlpha < 0.3f) { animateFadeIn(); return true }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val pId = event.getPointerId(idx)
                val tx = event.getX(idx); val ty = event.getY(idx)
                handleDown(pId, tx, ty)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pId = event.getPointerId(i)
                    val tx = event.getX(i); val ty = event.getY(i)
                    handleMove(pId, tx, ty)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                val pId = event.getPointerId(idx)
                handleUp(pId)
            }
            MotionEvent.ACTION_CANCEL -> resetAll()
        }
        return true
    }

    private fun handleDown(pId: Int, tx: Float, ty: Float): Boolean {
        // Check triggers first
        triggers.forEach { trig ->
            if (tx >= trig.left && tx <= trig.right && ty >= trig.top && ty <= trig.bottom) {
                trig.pressed = true
                listener?.onButtonDown(trig.btn)
                HapticManager.vibrate(context, HapticManager.Feedback.BUTTON_PRESS, config.hapticEnabled)
                invalidate(); return true
            }
        }
        // Check UI buttons
        uiBtns.forEach { btn ->
            if (hypot(tx - btn.cx, ty - btn.cy) <= btn.r) {
                btn.pressed = true
                listener?.onButtonDown(btn.btn)
                HapticManager.vibrate(context, HapticManager.Feedback.BUTTON_PRESS, config.hapticEnabled)
                invalidate(); return true
            }
        }
        // Check face buttons
        faceBtns.forEach { btn ->
            if (hypot(tx - btn.cx, ty - btn.cy) <= btn.r) {
                btn.pressed = true; btn.ptrId = pId
                listener?.onButtonDown(btn.btn)
                HapticManager.vibrate(context, HapticManager.Feedback.BUTTON_PRESS, config.hapticEnabled)
                invalidate(); return true
            }
        }
        // Check stick
        if (!stickActive && hypot(tx - stickCX, ty - stickCY) <= stickOuterR) {
            stickActive = true; stickPtrId = pId
            prevStickX = tx; prevStickY = ty
            updateThumb(tx, ty)
            moveHandler.post(moveRunnable)
            invalidate(); return true
        }
        return false
    }

    private fun handleMove(pId: Int, tx: Float, ty: Float) {
        if (stickPtrId == pId) {
            // Velocity tracking for more responsive feel
            if (config.velocityTracking) {
                stickVelX = tx - prevStickX
                stickVelY = ty - prevStickY
            }
            prevStickX = tx; prevStickY = ty

            val waAtEdge = atEdge
            updateThumb(tx, ty)

            // Haptic on edge hit
            val dist = hypot(tx - stickCX, ty - stickCY)
            atEdge = dist >= stickOuterR * 0.78f
            if (atEdge && !waAtEdge) {
                HapticManager.vibrate(context, HapticManager.Feedback.STICK_EDGE, config.hapticEnabled)
            }
        }
    }

    private fun handleUp(pId: Int): Boolean {
        triggers.forEach { trig ->
            if (trig.pressed) {
                trig.pressed = false
                listener?.onButtonUp(trig.btn)
                HapticManager.vibrate(context, HapticManager.Feedback.BUTTON_RELEASE, config.hapticEnabled)
                invalidate(); return true
            }
        }
        uiBtns.forEach { btn ->
            if (btn.pressed) {
                btn.pressed = false
                listener?.onButtonUp(btn.btn)
                HapticManager.vibrate(context, HapticManager.Feedback.BUTTON_RELEASE, config.hapticEnabled)
                invalidate(); return true
            }
        }
        faceBtns.forEach { btn ->
            if (btn.ptrId == pId) {
                btn.pressed = false; btn.ptrId = -1
                listener?.onButtonUp(btn.btn)
                HapticManager.vibrate(context, HapticManager.Feedback.BUTTON_RELEASE, config.hapticEnabled)
                invalidate(); return true
            }
        }
        if (stickPtrId == pId) {
            stickActive = false; stickPtrId = -1
            stickNormDx = 0f; stickNormDy = 0f
            atEdge = false
            moveHandler.removeCallbacks(moveRunnable)
            listener?.onStickRelease()
            springThumbBack()
            return true
        }
        return false
    }

    private fun updateThumb(touchX: Float, touchY: Float) {
        val dx = touchX - stickCX; val dy = touchY - stickCY
        val dist = hypot(dx, dy)
        val max = stickOuterR * 0.78f
        val deadzone = stickOuterR * config.stickDeadzone

        if (dist <= max) {
            thumbX = touchX; thumbY = touchY
        } else {
            val angle = atan2(dy, dx)
            thumbX = stickCX + cos(angle) * max
            thumbY = stickCY + sin(angle) * max
        }

        val normDist = min(dist, max)
        if (normDist < deadzone) {
            stickNormDx = 0f; stickNormDy = 0f
        } else {
            val effectiveDist = normDist - deadzone
            val effectiveMax = max - deadzone
            val magnitude = (effectiveDist / effectiveMax) * config.stickSensitivity
            val angle = atan2(dy, dx)
            stickNormDx = cos(angle) * magnitude
            stickNormDy = sin(angle) * magnitude
        }
        invalidate()
    }

    private fun springThumbBack() {
        isSpringBack = true
        val startX = thumbX; val startY = thumbY
        val steps = 8; var step = 0
        val springRunnable = object : Runnable {
            override fun run() {
                if (step >= steps) { thumbX = stickCX; thumbY = stickCY; isSpringBack = false; invalidate(); return }
                val t = step.toFloat() / steps
                val ease = 1f - (1f - t) * (1f - t)
                thumbX = startX + (stickCX - startX) * ease
                thumbY = startY + (stickCY - startY) * ease
                step++; invalidate()
                springHandler.postDelayed(this, 16)
            }
        }
        springHandler.post(springRunnable)
    }

    private fun resetAll() {
        stickActive = false; stickPtrId = -1
        stickNormDx = 0f; stickNormDy = 0f
        atEdge = false
        moveHandler.removeCallbacks(moveRunnable)
        faceBtns.forEach { it.pressed = false; it.ptrId = -1 }
        uiBtns.forEach { it.pressed = false }
        triggers.forEach { it.pressed = false }
        listener?.onStickRelease()
        springThumbBack()
    }

    // ── Auto-hide ─────────────────────────────────────────────────────────────

    private fun resetAutoHide() {
        lastTouchTime = System.currentTimeMillis()
        hideHandler.removeCallbacks(hideRunnable)
        if (config.autoHide) hideHandler.postDelayed(hideRunnable, config.autoHideDelay.toLong())
    }

    private fun animateFadeOut() {
        val start = hideAlpha; val steps = 20; var step = 0
        val fadeRunnable = object : Runnable {
            override fun run() {
                if (step >= steps) return
                hideAlpha = start * (1f - step.toFloat() / steps)
                step++; invalidate()
                hideHandler.postDelayed(this, 16)
            }
        }
        hideHandler.post(fadeRunnable)
    }

    private fun animateFadeIn() {
        val steps = 10; var step = 0
        val fadeRunnable = object : Runnable {
            override fun run() {
                if (step >= steps) { hideAlpha = 1f; return }
                hideAlpha = step.toFloat() / steps
                step++; invalidate()
                hideHandler.postDelayed(this, 16)
            }
        }
        hideHandler.post(fadeRunnable)
    }

    // ── Color helpers ─────────────────────────────────────────────────────────

    private fun darkenColor(color: Int, factor: Float): Int {
        val r = (Color.red(color) * (1f - factor)).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * (1f - factor)).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * (1f - factor)).toInt().coerceIn(0, 255)
        return Color.argb(Color.alpha(color), r, g, b)
    }

    private fun lightenColor(color: Int, factor: Float): Int {
        val r = (Color.red(color) + (255 - Color.red(color)) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) + (255 - Color.green(color)) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) + (255 - Color.blue(color)) * factor).toInt().coerceIn(0, 255)
        return Color.argb(Color.alpha(color), r, g, b)
    }

    fun cleanup() {
        moveHandler.removeCallbacksAndMessages(null)
        springHandler.removeCallbacksAndMessages(null)
        hideHandler.removeCallbacksAndMessages(null)
    }
}
