package com.gamemapper.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.gamemapper.models.FarmSession
import com.gamemapper.models.MinigameType

/**
 * Compact floating overlay showing current farm status.
 * Renders: active minigame icon, coins earned, time running, status indicator.
 */
class FarmStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var minigame: MinigameType = MinigameType.NONE
    private var coinsEarned: Int = 0
    private var sessionStartMs: Long = 0L
    private var isActive: Boolean = false
    private var pulsePhase: Float = 0f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.LEFT; isFakeBoldText = true
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val pulseRunnable = object : Runnable {
        override fun run() {
            if (!isActive) return
            pulsePhase = (pulsePhase + 0.08f) % (2f * Math.PI.toFloat())
            invalidate()
            postDelayed(this, 50)
        }
    }

    fun update(type: MinigameType, coins: Int, startMs: Long, active: Boolean) {
        minigame = type
        coinsEarned = coins
        sessionStartMs = startMs
        val wasActive = isActive
        isActive = active
        if (active && !wasActive) { removeCallbacks(pulseRunnable); post(pulseRunnable) }
        if (!active) removeCallbacks(pulseRunnable)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = h * 0.12f
        val cornerR = h * 0.35f

        // Background pill
        bgPaint.apply {
            color = Color.parseColor("#E0101822")
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(0f, 0f, w, h, cornerR, cornerR, bgPaint)

        // Border
        bgPaint.apply {
            color = if (isActive) Color.parseColor("#FF00B4FF") else Color.parseColor("#44AAAAAA")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRoundRect(1f, 1f, w - 1f, h - 1f, cornerR, cornerR, bgPaint)

        if (minigame == MinigameType.NONE) {
            textPaint.textSize = h * 0.28f
            textPaint.color = Color.parseColor("#88FFFFFF")
            canvas.drawText("Auto-Farm desativado", pad * 2, h * 0.62f, textPaint)
            return
        }

        // Pulse dot
        val dotR = h * 0.1f + if (isActive) kotlin.math.sin(pulsePhase) * 2f else 0f
        dotPaint.apply {
            color = if (isActive) Color.parseColor("#FF00E676") else Color.parseColor("#FFFF4444")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(pad + dotR, h / 2f, dotR, dotPaint)

        // Game icon
        iconPaint.textSize = h * 0.4f
        canvas.drawText(minigame.icon, pad * 2 + dotR * 3, h * 0.65f, iconPaint)

        // Game name
        textPaint.textSize = h * 0.26f
        textPaint.color = Color.WHITE
        val nameX = pad * 2 + dotR * 5 + iconPaint.textSize
        canvas.drawText(minigame.displayName, nameX, h * 0.42f, textPaint)

        // Coins
        textPaint.textSize = h * 0.24f
        textPaint.color = Color.parseColor("#FFFFD54F")
        canvas.drawText("🪙 ${formatCoins(coinsEarned)}", nameX, h * 0.72f, textPaint)

        // Time
        if (sessionStartMs > 0) {
            val elapsed = (System.currentTimeMillis() - sessionStartMs) / 1000
            val timeStr = "%02d:%02d".format(elapsed / 60, elapsed % 60)
            textPaint.textSize = h * 0.22f
            textPaint.color = Color.parseColor("#88FFFFFF")
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(timeStr, w - pad, h * 0.62f, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }
    }

    private fun formatCoins(c: Int) = when {
        c >= 1_000_000 -> "%.1fM".format(c / 1_000_000f)
        c >= 1_000 -> "%.1fk".format(c / 1_000f)
        else -> c.toString()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(pulseRunnable)
        super.onDetachedFromWindow()
    }
}
