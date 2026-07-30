package com.gamemapper.models

import com.google.gson.annotations.SerializedName

enum class ControlType {
    KEYBOARD, MOUSE_CLICK, MOUSE_MOVE, TOUCH, CANVAS_ZONE, BUTTON_ELEMENT, JOYSTICK
}

enum class ControlCategory {
    MOVEMENT, ACTION, UI, INTERACTION, UNKNOWN
}

data class ControlModel(
    @SerializedName("id") val id: String,
    @SerializedName("label") var label: String,
    @SerializedName("type") val type: ControlType,
    @SerializedName("category") var category: ControlCategory,
    @SerializedName("keyCode") val keyCode: String? = null,
    @SerializedName("keyLabel") val keyLabel: String? = null,
    @SerializedName("description") var description: String = "",
    @SerializedName("frequency") val frequency: Int = 0,
    @SerializedName("canvasX") val canvasX: Float? = null,
    @SerializedName("canvasY") val canvasY: Float? = null,
    @SerializedName("canvasWidth") val canvasWidth: Float? = null,
    @SerializedName("canvasHeight") val canvasHeight: Float? = null,
    @SerializedName("selector") val selector: String? = null,
    @SerializedName("elementText") val elementText: String? = null,
    @SerializedName("color") var color: Int = 0,
    @SerializedName("isSelected") var isSelected: Boolean = false
)

data class ControlProfile(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("gameUrl") val gameUrl: String,
    @SerializedName("gameDomain") val gameDomain: String,
    @SerializedName("controls") val controls: List<ControlModel>,
    @SerializedName("createdAt") val createdAt: Long = System.currentTimeMillis(),
    @SerializedName("layoutStyle") val layoutStyle: Int = 0
)

data class AnalysisResult(
    val controls: List<ControlModel>,
    val gameTitle: String,
    val gameUrl: String,
    val screenshot: String? = null,
    val analysisMode: Int = 0
)
