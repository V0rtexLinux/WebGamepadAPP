package com.gamemapper.services

import android.graphics.Color
import com.gamemapper.models.ControlCategory
import com.gamemapper.models.ControlModel
import com.gamemapper.models.ControlType
import com.gamemapper.utils.ColorUtils
import org.json.JSONObject
import java.util.UUID

/**
 * Parses the raw JSON from GameAnalyzerJS and produces a clean list of ControlModel objects.
 */
object ControlParser {

    fun parse(json: String, remapMode: Boolean = false): List<ControlModel> {
        val controls = mutableListOf<ControlModel>()
        try {
            val root = JSONObject(json)

            // ── Keyboard controls ─────────────────────────────────────────────
            val kbArray = root.optJSONArray("keyboard")
            if (kbArray != null) {
                for (i in 0 until kbArray.length()) {
                    val item = kbArray.getJSONObject(i)
                    val keyCode = item.optInt("keyCode", 0).toString()
                    val label = item.optString("label", "Key $keyCode")
                    val catStr = item.optString("category", "action")
                    val category = categoryFromString(catStr)
                    controls.add(
                        ControlModel(
                            id = UUID.randomUUID().toString(),
                            label = if (remapMode) "[$label]" else label,
                            type = ControlType.KEYBOARD,
                            category = category,
                            keyCode = keyCode,
                            keyLabel = label,
                            description = "Tecla ${label} detectada em eventos de teclado",
                            frequency = item.optInt("freq", 1),
                            color = ColorUtils.getColorForCategory(category)
                        )
                    )
                }
            }

            // ── Canvas zones ──────────────────────────────────────────────────
            val canvasArray = root.optJSONArray("canvasZones")
            if (canvasArray != null) {
                for (i in 0 until canvasArray.length()) {
                    val item = canvasArray.getJSONObject(i)
                    val w = item.optDouble("w", 0.0).toFloat()
                    val h = item.optDouble("h", 0.0).toFloat()
                    if (w < 10 || h < 10) continue
                    val hasTouch = item.optBoolean("hasTouch", false)
                    val hasMouse = item.optBoolean("hasMouse", false)
                    val type = if (hasTouch) ControlType.TOUCH else if (hasMouse) ControlType.CANVAS_ZONE else ControlType.MOUSE_CLICK
                    val category = ControlCategory.INTERACTION
                    controls.add(
                        ControlModel(
                            id = UUID.randomUUID().toString(),
                            label = "Canvas ${item.optString("id", "#$i")}",
                            type = type,
                            category = category,
                            description = "Área interativa ${w.toInt()}×${h.toInt()}px",
                            canvasX = item.optDouble("x", 0.0).toFloat(),
                            canvasY = item.optDouble("y", 0.0).toFloat(),
                            canvasWidth = w,
                            canvasHeight = h,
                            color = ColorUtils.getColorForType(type)
                        )
                    )
                }
            }

            // ── Clickable elements ────────────────────────────────────────────
            val elemArray = root.optJSONArray("clickableElements")
            if (elemArray != null) {
                for (i in 0 until elemArray.length()) {
                    val item = elemArray.getJSONObject(i)
                    val text = item.optString("text", "").trim()
                    val cls = item.optString("className", "")
                    val catStr = item.optString("inferredCategory", guessCategoryFromText(text, cls))
                    val category = categoryFromString(catStr)
                    val label = when {
                        text.isNotEmpty() -> text.take(20)
                        item.optString("id").isNotEmpty() -> "#${item.optString("id").take(16)}"
                        cls.isNotEmpty() -> ".${cls.split(" ").first().take(16)}"
                        else -> "${item.optString("tag", "el").uppercase()} ${i + 1}"
                    }
                    controls.add(
                        ControlModel(
                            id = UUID.randomUUID().toString(),
                            label = label,
                            type = ControlType.BUTTON_ELEMENT,
                            category = category,
                            selector = item.optString("id").let { if (it.isNotEmpty()) "#$it" else ".${cls.split(" ").firstOrNull() ?: "el"}" },
                            elementText = text.ifEmpty { null },
                            description = "Elemento interativo <${item.optString("tag","?")}>",
                            canvasX = item.optDouble("x", 0.0).toFloat(),
                            canvasY = item.optDouble("y", 0.0).toFloat(),
                            canvasWidth = item.optDouble("w", 0.0).toFloat(),
                            canvasHeight = item.optDouble("h", 0.0).toFloat(),
                            color = ColorUtils.getColorForCategory(category)
                        )
                    )
                }
            }

            // ── Touch zones ───────────────────────────────────────────────────
            val touchArray = root.optJSONArray("touchZones")
            if (touchArray != null) {
                for (i in 0 until touchArray.length()) {
                    val item = touchArray.getJSONObject(i)
                    val sel = item.optString("selector", "zona_$i")
                    controls.add(
                        ControlModel(
                            id = UUID.randomUUID().toString(),
                            label = "Toque $sel",
                            type = ControlType.TOUCH,
                            category = ControlCategory.INTERACTION,
                            selector = sel,
                            description = "Zona de toque detectada",
                            canvasX = item.optDouble("x", 0.0).toFloat(),
                            canvasY = item.optDouble("y", 0.0).toFloat(),
                            canvasWidth = item.optDouble("w", 0.0).toFloat(),
                            canvasHeight = item.optDouble("h", 0.0).toFloat(),
                            color = ColorUtils.getColorForType(ControlType.TOUCH)
                        )
                    )
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Deduplicate by label+type and sort by category
        return controls
            .distinctBy { "${it.label}|${it.type}" }
            .sortedWith(compareBy({ it.category.ordinal }, { it.label }))
    }

    private fun categoryFromString(s: String): ControlCategory = when (s.lowercase()) {
        "movement", "move" -> ControlCategory.MOVEMENT
        "action", "attack", "fire", "shoot", "jump" -> ControlCategory.ACTION
        "ui", "menu", "pause", "chat" -> ControlCategory.UI
        "interaction", "interact" -> ControlCategory.INTERACTION
        else -> ControlCategory.UNKNOWN
    }

    private fun guessCategoryFromText(text: String, cls: String): String {
        val combined = (text + " " + cls).lowercase()
        return when {
            combined.contains(Regex("up|down|left|right|walk|run|move|arrow|dpad|wasd|north|south|east|west")) -> "movement"
            combined.contains(Regex("attack|fire|shoot|jump|action|btn|button|click")) -> "action"
            combined.contains(Regex("menu|pause|esc|chat|settings|inventory|bag|map|shop")) -> "ui"
            combined.contains(Regex("interact|use|pick|grab|open|door|npc|talk")) -> "interaction"
            else -> "action"
        }
    }
}
