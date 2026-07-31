package com.gamemapper.views

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/**
 * Full-screen transparent overlay that renders a virtual gamepad and translates
 * touch events into game actions via [GamepadListener].
 *
 * Layout (portrait-safe, adapts to any screen size):
 *
 *   [T] [M] [I]  ← small UI strip (top-center)
 *
 *   ┌────────────────────────────────────────┐
 *   │                                        │
 *   │           (game visible here)          │
 *   │                                        │
 *   │   ⊙ stick        [Y]                   │
 *   │              [X]    [B]                │
 *   │                  [A]                   │
 *   └────────────────────────────────────────┘
 *
 * Touch pass-through: if the finger lands outside every control, onTouchEvent
 * returns false so the underlying WebView receives the event directly.
 */
class VirtualGamepadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Listener ──────────────────────────────────────────────────────────────

    interface GamepadListener {
        /** Joystick dragged — normalized dx/dy already multiplied by speed. */
        fun onStickMove(dx: Float, dy: Float)
        /** Finger lifted off the joystick. */
        fun onStickRelease()
        /** Button pressed (finger down). */
        fun onButtonDown(button: Btn)
        /** Button released (finger up). */
        fun onButtonUp(button: Btn)
    }

    enum class Btn {
        /** South / confirm / click */          A,
        /** East  / cancel / Esc   */           B,
        /** West  / interact / E   */           X,
        /** North / overlay toggle / Y */       Y,
        /** L1 → T (Chat)  */                  L1,
        /** R1 → M (Map)   */                  R1,
        /** L2 → I (Inv)   */                  L2,
        /** Start → Enter  */                   START
    }

    var listener: GamepadListener? = null

    // Speed multiplier applied to the normalized stick value
    var movementSpeed: Float = 38f

    // ── Geometry (recomputed in onSizeChanged) ────────────────────────────────

    private var stickCX = 0f
    private var stickCY = 0f
    private var stickOuterR = 0f   // outer ring (deadzone boundary)
    private var stickInnerR = 0f   // thumb knob radius
    private var thumbX = 0f
    private var thumbY = 0f

    private data class ActionBtn(
        val btn: Btn,
        val label: String,
        val baseColor: Int,
        var cx: Float = 0f,
        var cy: Float = 0f,
        var r: Float = 0f,
        var pressed: Boolean = false,
        var ptrId: Int = -1
    )

    private val faceBtns = listOf(
        ActionBtn(Btn.A, "A", Color.parseColor("#4CAF50")),   // south – green
        ActionBtn(Btn.B, "B", Color.parseColor("#F44336")),   // east  – red
        ActionBtn(Btn.X, "X", Color.parseColor("#2196F3")),   // west  – blue
        ActionBtn(Btn.Y, "Y", Color.parseColor("#FF9800"))    // north – orange
    )

    private data class SmallBtn(
        val btn: Btn,
        val label: String,
        var cx: Float = 0f,
        var cy: Float = 0f,
        var r: Float = 0f,
        var pressed: Boolean = false
    )

    private val uiBtns = listOf(
        SmallBtn(Btn.L1,    "T"),    // chat
        SmallBtn(Btn.START, "↵"),    // enter
        SmallBtn(Btn.R1,    "M"),    // map
        SmallBtn(Btn.L2,    "I")     // inventory
    )

    // ── Stick state ───────────────────────────────────────────────────────────

    private var stickActive    = false
    private var stickPtrId     = -1
    private var stickNormDx    = 0f
    private var stickNormDy    = 0f

    // Repeat-move handler (fires while stick is held)
    private val moveHandler = Handler(Looper.getMainLooper())
    private val moveRunnable = object : Runnable {
        override fun run() {
            if (stickActive) {
                listener?.onStickMove(stickNormDx * movementSpeed, stickNormDy * movementSpeed)
                moveHandler.postDelayed(this, 40L)  // ~25 Hz
            }
        }
    }

    // ── Paints ────────────────────────────────────────────────────────────────

    private val outerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(45, 255, 255, 255)
    }
    private val outerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(110, 255, 255, 255)
        strokeWidth = 3f
    }
    private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(200, 255, 255, 255)
    }
    private val arrowTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(130, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val faceFill  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val faceRing  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f
        color = Color.argb(180, 255, 255, 255)
    }
    private val faceLbl   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val uiFill    = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.argb(55, 200, 200, 200)
    }
    private val uiRing    = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f; color = Color.argb(100, 255, 255, 255)
    }
    private val uiLbl     = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 255, 255); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    // Cross lines inside D-pad outer ring
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f; color = Color.argb(60, 255, 255, 255)
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val W = w.toFloat()
        val H = h.toFloat()
        val dp = resources.displayMetrics.density

        // ── Joystick (lower-left) ─────────────────────────────────────────
        stickOuterR = (W * 0.13f).coerceIn(56f * dp / 3f, 110f * dp / 3f)
        stickInnerR = stickOuterR * 0.40f
        val stickMargin = stickOuterR * 1.25f
        stickCX = stickMargin
        stickCY = H - stickMargin
        thumbX  = stickCX
        thumbY  = stickCY
        arrowTxt.textSize = stickOuterR * 0.36f

        // ── Face buttons (lower-right, diamond) ───────────────────────────
        val btnR    = (W * 0.062f).coerceIn(22f * dp / 3f, 50f * dp / 3f)
        val bSpacing = btnR * 2.25f
        val bCX     = W - btnR * 2.7f
        val bCY     = H - btnR * 2.7f
        faceBtns[0].apply { cx = bCX;            cy = bCY + bSpacing; r = btnR } // A south
        faceBtns[1].apply { cx = bCX + bSpacing; cy = bCY;            r = btnR } // B east
        faceBtns[2].apply { cx = bCX - bSpacing; cy = bCY;            r = btnR } // X west
        faceBtns[3].apply { cx = bCX;            cy = bCY - bSpacing; r = btnR } // Y north
        faceLbl.textSize = btnR * 0.62f

        // ── Small UI strip (top-center) ───────────────────────────────────
        val sR      = (W * 0.036f).coerceIn(14f * dp / 3f, 32f * dp / 3f)
        val sSpacing = sR * 2.7f
        val n       = uiBtns.size
        val sStartX = W / 2f - (n - 1) * sSpacing / 2f
        uiBtns.forEachIndexed { i, sb ->
            sb.cx = sStartX + i * sSpacing
            sb.cy = sR * 1.8f
            sb.r  = sR
        }
        uiLbl.textSize = sR * 0.58f
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        drawStick(canvas)
        drawFaceButtons(canvas)
        drawUiButtons(canvas)
    }

    private fun drawStick(canvas: Canvas) {
        // Outer ring + fill
        canvas.drawCircle(stickCX, stickCY, stickOuterR, outerFill)
        canvas.drawCircle(stickCX, stickCY, stickOuterR, outerRing)
        // Cross-hair lines
        canvas.drawLine(stickCX - stickOuterR, stickCY, stickCX + stickOuterR, stickCY, crossPaint)
        canvas.drawLine(stickCX, stickCY - stickOuterR, stickCX, stickCY + stickOuterR, crossPaint)
        // Arrow hints
        val ao = stickOuterR * 0.63f
        val ts = arrowTxt.textSize
        canvas.drawText("▲", stickCX, stickCY - ao + ts * 0.30f, arrowTxt)
        canvas.drawText("▼", stickCX, stickCY + ao + ts * 0.38f, arrowTxt)
        canvas.drawText("◀", stickCX - ao, stickCY + ts * 0.35f, arrowTxt)
        canvas.drawText("▶", stickCX + ao, stickCY + ts * 0.35f, arrowTxt)
        // Thumb knob
        thumbFill.alpha = if (stickActive) 230 else 160
        canvas.drawCircle(thumbX, thumbY, stickInnerR, thumbFill)
    }

    private fun drawFaceButtons(canvas: Canvas) {
        faceBtns.forEach { b ->
            val alpha = if (b.pressed) 230 else 130
            faceFill.color = (b.baseColor and 0x00FFFFFF) or (alpha shl 24)
            canvas.drawCircle(b.cx, b.cy, b.r, faceFill)
            canvas.drawCircle(b.cx, b.cy, b.r, faceRing)
            canvas.drawText(b.label, b.cx, b.cy + faceLbl.textSize * 0.36f, faceLbl)
        }
    }

    private fun drawUiButtons(canvas: Canvas) {
        uiBtns.forEach { sb ->
            val fill = if (sb.pressed) Paint(uiFill).apply { alpha = 150 } else uiFill
            canvas.drawCircle(sb.cx, sb.cy, sb.r, fill)
            canvas.drawCircle(sb.cx, sb.cy, sb.r, uiRing)
            canvas.drawText(sb.label, sb.cx, sb.cy + uiLbl.textSize * 0.36f, uiLbl)
        }
    }

    // ── Touch handling ────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action  = event.actionMasked
        val pIdx    = event.actionIndex
        val pId     = event.getPointerId(pIdx)
        val x       = event.getX(pIdx)
        val y       = event.getY(pIdx)

        return when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                handleDown(pId, x, y)

            MotionEvent.ACTION_MOVE -> {
                var consumed = false
                for (i in 0 until event.pointerCount) {
                    if (handleMove(event.getPointerId(i), event.getX(i), event.getY(i)))
                        consumed = true
                }
                consumed
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                handleUp(pId, x, y)

            MotionEvent.ACTION_CANCEL -> { resetAll(); true }

            else -> false
        }
    }

    /** Returns true if any control claimed this pointer-down. */
    private fun handleDown(pId: Int, x: Float, y: Float): Boolean {
        // Joystick zone: generous hit-test (1.3× radius)
        if (stickPtrId == -1 && hypot(x - stickCX, y - stickCY) <= stickOuterR * 1.3f) {
            stickPtrId  = pId
            stickActive = true
            updateThumb(x, y)
            moveHandler.post(moveRunnable)
            return true
        }
        // Face buttons
        faceBtns.forEach { b ->
            if (b.ptrId == -1 && hypot(x - b.cx, y - b.cy) <= b.r * 1.2f) {
                b.pressed = true
                b.ptrId   = pId
                listener?.onButtonDown(b.btn)
                invalidate()
                return true
            }
        }
        // UI strip (single-tap, auto-release)
        uiBtns.forEach { sb ->
            if (hypot(x - sb.cx, y - sb.cy) <= sb.r * 1.3f) {
                sb.pressed = true
                invalidate()
                listener?.onButtonDown(sb.btn)
                postDelayed({
                    sb.pressed = false
                    listener?.onButtonUp(sb.btn)
                    invalidate()
                }, 140L)
                return true
            }
        }
        return false   // let WebView handle taps outside controls
    }

    private fun handleMove(pId: Int, x: Float, y: Float): Boolean {
        if (pId == stickPtrId) {
            updateThumb(x, y)
            return true
        }
        return false
    }

    private fun handleUp(pId: Int, x: Float, y: Float): Boolean {
        if (pId == stickPtrId) {
            stickActive = false
            stickPtrId  = -1
            stickNormDx = 0f
            stickNormDy = 0f
            thumbX = stickCX
            thumbY = stickCY
            moveHandler.removeCallbacks(moveRunnable)
            listener?.onStickRelease()
            invalidate()
            return true
        }
        faceBtns.forEach { b ->
            if (b.ptrId == pId) {
                b.pressed = false
                b.ptrId   = -1
                listener?.onButtonUp(b.btn)
                invalidate()
                return true
            }
        }
        return false
    }

    private fun updateThumb(touchX: Float, touchY: Float) {
        val dx   = touchX - stickCX
        val dy   = touchY - stickCY
        val dist = hypot(dx, dy)
        val max  = stickOuterR * 0.82f   // thumb can't reach the very edge

        if (dist <= max) {
            thumbX      = touchX
            thumbY      = touchY
            stickNormDx = dx / max
            stickNormDy = dy / max
        } else {
            val angle   = atan2(dy, dx)
            thumbX      = stickCX + cos(angle) * max
            thumbY      = stickCY + sin(angle) * max
            stickNormDx = cos(angle)
            stickNormDy = sin(angle)
        }
        invalidate()
    }

    private fun resetAll() {
        stickActive = false; stickPtrId = -1
        stickNormDx = 0f;    stickNormDy = 0f
        thumbX = stickCX;    thumbY = stickCY
        moveHandler.removeCallbacks(moveRunnable)
        faceBtns.forEach { it.pressed = false; it.ptrId = -1 }
        uiBtns.forEach   { it.pressed = false }
        listener?.onStickRelease()
        invalidate()
    }
}
