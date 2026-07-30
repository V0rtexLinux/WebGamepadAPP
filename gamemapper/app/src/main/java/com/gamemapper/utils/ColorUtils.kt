package com.gamemapper.utils

import android.graphics.Color
import com.gamemapper.models.ControlCategory
import com.gamemapper.models.ControlType

object ColorUtils {
    fun getColorForCategory(category: ControlCategory): Int = when (category) {
        ControlCategory.MOVEMENT   -> Color.parseColor("#4CAF50")
        ControlCategory.ACTION     -> Color.parseColor("#F44336")
        ControlCategory.UI         -> Color.parseColor("#2196F3")
        ControlCategory.INTERACTION -> Color.parseColor("#FF9800")
        ControlCategory.UNKNOWN    -> Color.parseColor("#9E9E9E")
    }

    fun getColorForType(type: ControlType): Int = when (type) {
        ControlType.KEYBOARD        -> Color.parseColor("#7C4DFF")
        ControlType.MOUSE_CLICK     -> Color.parseColor("#0097A7")
        ControlType.MOUSE_MOVE      -> Color.parseColor("#00796B")
        ControlType.TOUCH           -> Color.parseColor("#E91E63")
        ControlType.CANVAS_ZONE     -> Color.parseColor("#FF5722")
        ControlType.BUTTON_ELEMENT  -> Color.parseColor("#3F51B5")
        ControlType.JOYSTICK        -> Color.parseColor("#795548")
    }

    fun getLabelForType(type: ControlType): String = when (type) {
        ControlType.KEYBOARD        -> "Teclado"
        ControlType.MOUSE_CLICK     -> "Clique"
        ControlType.MOUSE_MOVE      -> "Mover Mouse"
        ControlType.TOUCH           -> "Toque"
        ControlType.CANVAS_ZONE     -> "Zona Canvas"
        ControlType.BUTTON_ELEMENT  -> "Botão"
        ControlType.JOYSTICK        -> "Joystick"
    }

    fun getLabelForCategory(category: ControlCategory): String = when (category) {
        ControlCategory.MOVEMENT    -> "Movimento"
        ControlCategory.ACTION      -> "Ação"
        ControlCategory.UI          -> "Interface"
        ControlCategory.INTERACTION -> "Interação"
        ControlCategory.UNKNOWN     -> "Outro"
    }
}
