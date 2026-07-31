package com.gamemapper.models

import com.google.gson.annotations.SerializedName

// ─────────────────────────────────────────────────────────────────────────────
//  Enums
// ─────────────────────────────────────────────────────────────────────────────

enum class ControlType {
    KEYBOARD,
    MOUSE_CLICK,
    MOUSE_MOVE,
    TOUCH,
    /** A raw canvas zone (whole canvas or sub-area, no further subdivision). */
    CANVAS_ZONE,
    /**
     * A geometric quadrant mapped onto the game canvas.
     * The [quadrantZone] field identifies which zone (DPAD, ACTION, UI, CANVAS_CLICK).
     */
    CANVAS_QUADRANT,
    BUTTON_ELEMENT,
    JOYSTICK
}

enum class ControlCategory {
    MOVEMENT,
    ACTION,
    UI,
    INTERACTION,
    UNKNOWN
}

/**
 * A single mappable game control.
 *
 * Quadrant-specific fields (only set when type == CANVAS_QUADRANT):
 *   • [quadrantZone]  — "DPAD" | "ACTION" | "UI" | "CANVAS_CLICK"
 *   • [quadrantKeys]  — JSON string with the list of keys in this quadrant
 *   • [quadrantPriority] — render order (0 = fullscreen click, 1 = dpad, …)
 *   • [isCanvasQuadrant] — quick Boolean flag
 */
data class ControlModel(
    @SerializedName("id")          val id: String,
    @SerializedName("label")       var label: String,
    @SerializedName("type")        val type: ControlType,
    @SerializedName("category")    var category: ControlCategory,

    // Keyboard
    @SerializedName("keyCode")     val keyCode: String? = null,
    @SerializedName("keyLabel")    val keyLabel: String? = null,

    // Metadata
    @SerializedName("description") var description: String = "",
    @SerializedName("frequency")   val frequency: Int = 0,

    // Geometry (canvas coordinates or quadrant bounds)
    @SerializedName("canvasX")      val canvasX: Float? = null,
    @SerializedName("canvasY")      val canvasY: Float? = null,
    @SerializedName("canvasWidth")  val canvasWidth: Float? = null,
    @SerializedName("canvasHeight") val canvasHeight: Float? = null,

    // DOM targeting (not used in canvas-quadrant mode)
    @SerializedName("selector")    val selector: String? = null,
    @SerializedName("elementText") val elementText: String? = null,

    // Canvas quadrant fields
    @SerializedName("quadrantZone")     val quadrantZone: String? = null,
    @SerializedName("quadrantKeys")     val quadrantKeys: String? = null,   // JSON array string
    @SerializedName("quadrantPriority") val quadrantPriority: Int = 99,
    @SerializedName("isCanvasQuadrant") val isCanvasQuadrant: Boolean = false,

    // Rendering
    @SerializedName("color")      var color: Int = 0,
    @SerializedName("isSelected") var isSelected: Boolean = false
)

// ─────────────────────────────────────────────────────────────────────────────
//  Profile container
// ─────────────────────────────────────────────────────────────────────────────

data class ControlProfile(
    @SerializedName("id")          val id: String,
    @SerializedName("name")        val name: String,
    @SerializedName("gameUrl")     val gameUrl: String,
    @SerializedName("gameDomain")  val gameDomain: String,
    @SerializedName("controls")    val controls: List<ControlModel>,
    @SerializedName("createdAt")   val createdAt: Long = System.currentTimeMillis(),
    @SerializedName("updatedAt")   val updatedAt: Long = System.currentTimeMillis(),
    @SerializedName("layoutStyle") val layoutStyle: Int = 0,

    /** True when the primary mapping is canvas-quadrant based (no DOM elements). */
    @SerializedName("isCanvasMode") val isCanvasMode: Boolean = false
)

// ─────────────────────────────────────────────────────────────────────────────
//  Analysis intermediate
// ─────────────────────────────────────────────────────────────────────────────

data class AnalysisResult(
    val controls: List<ControlModel>,
    val gameTitle: String,
    val gameUrl: String,
    val screenshot: String? = null,
    val analysisMode: Int = 0,
    val isCanvasMode: Boolean = false,
    val isLoginState: Boolean = false
)
