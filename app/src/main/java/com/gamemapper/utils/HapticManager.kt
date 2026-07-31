package com.gamemapper.utils

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Unified haptic feedback manager.
 * Supports both legacy Vibrator and new VibratorManager (API 31+).
 */
object HapticManager {

    enum class Feedback {
        BUTTON_PRESS,    // Short sharp click
        BUTTON_RELEASE,  // Very short release
        STICK_EDGE,      // Hit analog stick edge
        FARM_START,      // Farm began
        FARM_STOP,       // Farm ended
        ERROR,           // Error occurred
        COIN_EARNED,     // Coins detected
        HEAVY,           // Heavy thud
        DOUBLE,          // Double tap
        LONG_PRESS       // Long vibration
    }

    private var strengthPercent = 100 // 0-100

    fun setStrength(percent: Int) {
        strengthPercent = percent.coerceIn(0, 100)
    }

    fun vibrate(context: Context, feedback: Feedback, enabled: Boolean = true) {
        if (!enabled || strengthPercent == 0) return
        val amplitudeScale = strengthPercent / 100f

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            val vibrator = manager?.defaultVibrator ?: return
            val effect = buildEffect(feedback, amplitudeScale)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = buildEffect(feedback, amplitudeScale)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(legacyDuration(feedback))
            }
        }
    }

    private fun buildEffect(feedback: Feedback, scale: Float): VibrationEffect {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
        }

        val baseAmplitude = (255 * scale).toInt().coerceIn(1, 255)

        return when (feedback) {
            Feedback.BUTTON_PRESS -> VibrationEffect.createOneShot(
                28, (baseAmplitude * 0.75f).toInt().coerceIn(1, 255)
            )
            Feedback.BUTTON_RELEASE -> VibrationEffect.createOneShot(
                12, (baseAmplitude * 0.35f).toInt().coerceIn(1, 255)
            )
            Feedback.STICK_EDGE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 20, 10, 12),
                        intArrayOf(0, (baseAmplitude * 0.5f).toInt(), 0, (baseAmplitude * 0.3f).toInt()),
                        -1
                    )
                } else VibrationEffect.createOneShot(30, baseAmplitude)
            }
            Feedback.FARM_START -> VibrationEffect.createWaveform(
                longArrayOf(0, 40, 30, 80),
                intArrayOf(0, baseAmplitude, 0, (baseAmplitude * 0.6f).toInt()),
                -1
            )
            Feedback.FARM_STOP -> VibrationEffect.createWaveform(
                longArrayOf(0, 80, 30, 40),
                intArrayOf(0, (baseAmplitude * 0.6f).toInt(), 0, baseAmplitude),
                -1
            )
            Feedback.ERROR -> VibrationEffect.createWaveform(
                longArrayOf(0, 60, 40, 60, 40, 60),
                intArrayOf(0, baseAmplitude, 0, baseAmplitude, 0, baseAmplitude),
                -1
            )
            Feedback.COIN_EARNED -> VibrationEffect.createWaveform(
                longArrayOf(0, 20, 15, 20),
                intArrayOf(0, (baseAmplitude * 0.8f).toInt(), 0, (baseAmplitude * 0.5f).toInt()),
                -1
            )
            Feedback.HEAVY -> VibrationEffect.createOneShot(120, baseAmplitude)
            Feedback.DOUBLE -> VibrationEffect.createWaveform(
                longArrayOf(0, 30, 40, 30),
                intArrayOf(0, baseAmplitude, 0, baseAmplitude),
                -1
            )
            Feedback.LONG_PRESS -> VibrationEffect.createOneShot(350, (baseAmplitude * 0.7f).toInt())
        }
    }

    private fun legacyDuration(feedback: Feedback): Long = when (feedback) {
        Feedback.BUTTON_PRESS -> 28
        Feedback.BUTTON_RELEASE -> 12
        Feedback.STICK_EDGE -> 30
        Feedback.FARM_START, Feedback.FARM_STOP -> 120
        Feedback.ERROR -> 250
        Feedback.COIN_EARNED -> 40
        Feedback.HEAVY -> 120
        Feedback.DOUBLE -> 80
        Feedback.LONG_PRESS -> 350
    }
}
