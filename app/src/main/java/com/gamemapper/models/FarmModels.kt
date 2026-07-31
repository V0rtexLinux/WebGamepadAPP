package com.gamemapper.models

import com.google.gson.annotations.SerializedName

// ─────────────────────────────────────────────────────────────────────────────
//  Minigame Types — all minigames detectable in play.cpjourney.net
// ─────────────────────────────────────────────────────────────────────────────

enum class MinigameType(val displayName: String, val coinsPerHour: Int, val icon: String) {
    CART_SURFER("Cart Surfer", 60_000, "🛒"),
    MINING("Mineração", 30_000, "⛏️"),
    FISHING("Fishing", 20_000, "🎣"),
    PUFFLE_ROUNDUP("Puffle Roundup", 25_000, "🐧"),
    BEAN_COUNTERS("Bean Counters", 22_000, "☕"),
    JET_PACK("Jet Pack Adventure", 18_000, "🚀"),
    AQUA_GRABBER("Aqua Grabber", 20_000, "🤿"),
    PIZZATRON("Pizzatron 3000", 24_000, "🍕"),
    PIZZA_JOB("Job: Pizza Chef", 28_000, "👨‍🍳"),
    COFFEE_JOB("Job: Barista", 26_000, "☕"),
    ICE_DRILLING("Ice Berg Drilling", 30_000, "🧊"),
    DANCE_CONTEST("Dance Contest", 15_000, "💃"),
    THIN_ICE("Thin Ice", 16_000, "❄️"),
    ASTRO_BARRIER("Astro Barrier", 18_000, "🚀"),
    UNKNOWN("Desconhecido", 0, "❓"),
    NONE("Nenhum", 0, "⭕")
}

// ─────────────────────────────────────────────────────────────────────────────
//  Farm Session — tracks a single farming session
// ─────────────────────────────────────────────────────────────────────────────

data class FarmSession(
    @SerializedName("id") val id: String = java.util.UUID.randomUUID().toString(),
    @SerializedName("minigame") val minigame: MinigameType,
    @SerializedName("coinsEarned") var coinsEarned: Int = 0,
    @SerializedName("startTime") val startTime: Long = System.currentTimeMillis(),
    @SerializedName("endTime") var endTime: Long = 0,
    @SerializedName("roundsPlayed") var roundsPlayed: Int = 0,
    @SerializedName("errorsRecovered") var errorsRecovered: Int = 0,
    @SerializedName("active") var active: Boolean = true
) {
    val durationMs: Long get() = if (endTime > 0) endTime - startTime else System.currentTimeMillis() - startTime
    val durationMin: Float get() = durationMs / 60_000f
    val coinsPerMin: Float get() = if (durationMin > 0) coinsEarned / durationMin else 0f
}

// ─────────────────────────────────────────────────────────────────────────────
//  Farm Stats — aggregated lifetime statistics
// ─────────────────────────────────────────────────────────────────────────────

data class FarmStats(
    @SerializedName("totalCoins") var totalCoins: Int = 0,
    @SerializedName("totalSessions") var totalSessions: Int = 0,
    @SerializedName("totalMinutes") var totalMinutes: Float = 0f,
    @SerializedName("bestSessionCoins") var bestSessionCoins: Int = 0,
    @SerializedName("totalRounds") var totalRounds: Int = 0,
    @SerializedName("totalErrorsRecovered") var totalErrorsRecovered: Int = 0,
    @SerializedName("sessionsByGame") var sessionsByGame: MutableMap<String, Int> = mutableMapOf()
) {
    fun update(session: FarmSession) {
        totalCoins += session.coinsEarned
        totalSessions++
        totalMinutes += session.durationMin
        if (session.coinsEarned > bestSessionCoins) bestSessionCoins = session.coinsEarned
        totalRounds += session.roundsPlayed
        totalErrorsRecovered += session.errorsRecovered
        sessionsByGame[session.minigame.name] = (sessionsByGame[session.minigame.name] ?: 0) + 1
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Gamepad Config — user-configurable gamepad settings
// ─────────────────────────────────────────────────────────────────────────────

data class GamepadConfig(
    @SerializedName("stickDeadzone") var stickDeadzone: Float = 0.12f,
    @SerializedName("stickSensitivity") var stickSensitivity: Float = 1.0f,
    @SerializedName("movementSpeed") var movementSpeed: Float = 38f,
    @SerializedName("hapticEnabled") var hapticEnabled: Boolean = true,
    @SerializedName("hapticStrength") var hapticStrength: Int = 50, // 0-100
    @SerializedName("theme") var theme: GamepadTheme = GamepadTheme.NEON_BLUE,
    @SerializedName("opacity") var opacity: Float = 0.85f,
    @SerializedName("buttonScale") var buttonScale: Float = 1.0f,
    @SerializedName("stickPosition") var stickPosition: StickPosition = StickPosition.LEFT,
    @SerializedName("showDpad") var showDpad: Boolean = false,
    @SerializedName("showTriggers") var showTriggers: Boolean = true,
    @SerializedName("autoHide") var autoHide: Boolean = false,
    @SerializedName("autoHideDelay") var autoHideDelay: Int = 3000,
    @SerializedName("velocityTracking") var velocityTracking: Boolean = true
)

enum class GamepadTheme(val displayName: String) {
    NEON_BLUE("Neon Azul"),
    NEON_GREEN("Neon Verde"),
    NEON_PURPLE("Neon Roxo"),
    NEON_ORANGE("Neon Laranja"),
    CLASSIC_DARK("Clássico Escuro"),
    GLASSMORPHISM("Glassmorphism"),
    FIRE("Fogo"),
    ICE("Gelo")
}

enum class StickPosition {
    LEFT, RIGHT, BOTH
}

// ─────────────────────────────────────────────────────────────────────────────
//  Macro Model — recorded button sequences
// ─────────────────────────────────────────────────────────────────────────────

data class MacroStep(
    @SerializedName("action") val action: String,  // "key_down", "key_up", "wait", "click"
    @SerializedName("value") val value: String,    // keyCode, delay ms, or coordinates
    @SerializedName("delay") val delay: Long = 0
)

data class MacroModel(
    @SerializedName("id") val id: String = java.util.UUID.randomUUID().toString(),
    @SerializedName("name") val name: String,
    @SerializedName("steps") val steps: List<MacroStep>,
    @SerializedName("repeat") val repeat: Boolean = false,
    @SerializedName("repeatInterval") val repeatInterval: Long = 1000,
    @SerializedName("boundButton") var boundButton: String? = null
)
