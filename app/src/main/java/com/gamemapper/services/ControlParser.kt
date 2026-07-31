package com.gamemapper.services

import com.gamemapper.models.ControlCategory
import com.gamemapper.models.ControlModel
import com.gamemapper.models.ControlType
import com.gamemapper.utils.ColorUtils
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Parses the JSON emitted by [GameAnalyzerJS] into a clean list of [ControlModel] objects.
 *
 * Pipeline priority:
 *   1. canvasQuadrants  — geometric zones mapped onto the primary game canvas.
 *      These are the authoritative controls when in canvas mode (DPAD, ACTION, UI, CANVAS_CLICK).
 *   2. canvasZones      — raw canvas elements (fallback if no quadrants detected).
 *   3. keyboard         — keyboard keycodes confirmed via event hooks.
 *   4. clickableElements — SKIPPED. This list is always empty from the JS side by design.
 *      The blacklist in GameAnalyzerJS ensures no nav/footer/menu elements ever reach the parser.
 *   5. touchZones       — kept for non-canvas games only.
 *
 * Deduplication: by (label + type + quadrantZone). Sorted by quadrantPriority, then category.
 */
object ControlParser {

    fun parse(json: String, remapMode: Boolean = false): List<ControlModel> {
        val controls = mutableListOf<ControlModel>()
        try {
            val root = JSONObject(json)
            val isCanvasMode = root.optString("analysisMode", "").contains("canvas")

            // ── 1. Canvas Quadrants (primary path) ────────────────────────────
            val quadrantArray = root.optJSONArray("canvasQuadrants")
            if (quadrantArray != null && quadrantArray.length() > 0) {
                parseCanvasQuadrants(quadrantArray, controls, remapMode)
            }

            // ── 2. Raw Canvas Zones (fallback if no quadrants) ────────────────
            val canvasArray = root.optJSONArray("canvasZones")
            if (canvasArray != null && (quadrantArray == null || quadrantArray.length() == 0)) {
                parseCanvasZones(canvasArray, controls)
            }

            // ── 3. Keyboard ───────────────────────────────────────────────────
            val kbArray = root.optJSONArray("keyboard")
            if (kbArray != null) {
                parseKeyboard(kbArray, controls, remapMode)
            }

            // ── 4. clickableElements: INTENTIONALLY SKIPPED ───────────────────
            // The JS blacklist guarantees this array is always empty; we never
            // process it to prevent nav/header/footer pollution.

            // ── 5. Touch Zones (non-canvas games only) ────────────────────────
            if (!isCanvasMode) {
                val touchArray = root.optJSONArray("touchZones")
                if (touchArray != null) {
                    parseTouchZones(touchArray, controls)
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }

        return controls
            .distinctBy { "${it.label}|${it.type}|${it.quadrantZone}" }
            .sortedWith(
                compareBy(
                    { it.quadrantPriority },
                    { it.category.ordinal },
                    { it.label }
                )
            )
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Canvas Quadrants
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseCanvasQuadrants(
        arr: JSONArray,
        out: MutableList<ControlModel>,
        remapMode: Boolean
    ) {
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val zone     = item.optString("zone", "UNKNOWN")
            val label    = item.optString("label", zone)
            val catStr   = item.optString("category", "action")
            val category = categoryFromString(catStr)
            val priority = item.optInt("priority", 99)

            val w = item.optDouble("w", 0.0).toFloat()
            val h = item.optDouble("h", 0.0).toFloat()
            if (w < 10f || h < 10f) continue

            // Serialise the keys sub-array as a JSON string for GameplayActivity
            val keysJson = item.optJSONArray("keys")?.toString() ?: "[]"

            // Human-readable description based on zone type
            val description = when (zone) {
                "DPAD"         -> "Quadrante D-Pad: ${w.toInt()}×${h.toInt()}px " +
                                  "(↑↓←→ mapeados no quadrante inferior esquerdo do Canvas)"
                "ACTION"       -> "Quadrante de Ação: ${w.toInt()}×${h.toInt()}px " +
                                  "(Espaço/Enter/E/Esc no quadrante inferior direito)"
                "UI"           -> "Strip de UI: ${w.toInt()}×${h.toInt()}px " +
                                  "(T=Chat, M=Mapa, I=Inventário – canto superior direito)"
                "CANVAS_CLICK" -> "Canvas completo: ${w.toInt()}×${h.toInt()}px " +
                                  "(clique para mover — mecânica click-to-walk do CP)"
                else           -> "Zona do Canvas: ${w.toInt()}×${h.toInt()}px"
            }

            val displayLabel = if (remapMode) "[$label]" else label

            out.add(
                ControlModel(
                    id               = UUID.randomUUID().toString(),
                    label            = displayLabel,
                    type             = ControlType.CANVAS_QUADRANT,
                    category         = category,
                    description      = description,
                    canvasX          = item.optDouble("x", 0.0).toFloat(),
                    canvasY          = item.optDouble("y", 0.0).toFloat(),
                    canvasWidth      = w,
                    canvasHeight     = h,
                    quadrantZone     = zone,
                    quadrantKeys     = keysJson,
                    quadrantPriority = priority,
                    isCanvasQuadrant = true,
                    frequency        = priority,          // lower priority # = higher importance
                    color            = colorForZone(zone)
                )
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Raw Canvas Zones (fallback)
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseCanvasZones(arr: JSONArray, out: MutableList<ControlModel>) {
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val w = item.optDouble("w", 0.0).toFloat()
            val h = item.optDouble("h", 0.0).toFloat()
            if (w < 10f || h < 10f) continue

            val hasTouch = item.optBoolean("hasTouch", false)
            val hasMouse = item.optBoolean("hasMouse", false)
            val type     = when {
                hasTouch -> ControlType.TOUCH
                hasMouse -> ControlType.CANVAS_ZONE
                else     -> ControlType.MOUSE_CLICK
            }

            out.add(
                ControlModel(
                    id          = UUID.randomUUID().toString(),
                    label       = "Canvas ${item.optString("id", "#$i")}",
                    type        = type,
                    category    = ControlCategory.INTERACTION,
                    description = "Área interativa ${w.toInt()}×${h.toInt()}px",
                    canvasX     = item.optDouble("x", 0.0).toFloat(),
                    canvasY     = item.optDouble("y", 0.0).toFloat(),
                    canvasWidth  = w,
                    canvasHeight = h,
                    color       = ColorUtils.getColorForType(type)
                )
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Keyboard
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseKeyboard(
        arr: JSONArray,
        out: MutableList<ControlModel>,
        remapMode: Boolean
    ) {
        for (i in 0 until arr.length()) {
            val item    = arr.getJSONObject(i)
            val keyCode = item.optInt("keyCode", 0).toString()
            val label   = item.optString("label", "Key $keyCode")
            val catStr  = item.optString("category", "action")
            val category = categoryFromString(catStr)

            out.add(
                ControlModel(
                    id          = UUID.randomUUID().toString(),
                    label       = if (remapMode) "[$label]" else label,
                    type        = ControlType.KEYBOARD,
                    category    = category,
                    keyCode     = keyCode,
                    keyLabel    = label,
                    description = "Tecla $label detectada via eventos de teclado",
                    frequency   = item.optInt("freq", 1),
                    color       = ColorUtils.getColorForCategory(category)
                )
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Touch Zones
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseTouchZones(arr: JSONArray, out: MutableList<ControlModel>) {
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val sel  = item.optString("selector", "zona_$i")
            val w    = item.optDouble("w", 0.0).toFloat()
            val h    = item.optDouble("h", 0.0).toFloat()
            if (w < 4f || h < 4f) continue

            out.add(
                ControlModel(
                    id          = UUID.randomUUID().toString(),
                    label       = "Toque $sel",
                    type        = ControlType.TOUCH,
                    category    = ControlCategory.INTERACTION,
                    selector    = sel,
                    description = "Zona de toque detectada",
                    canvasX     = item.optDouble("x", 0.0).toFloat(),
                    canvasY     = item.optDouble("y", 0.0).toFloat(),
                    canvasWidth  = w,
                    canvasHeight = h,
                    color       = ColorUtils.getColorForType(ControlType.TOUCH)
                )
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun categoryFromString(s: String): ControlCategory = when (s.lowercase()) {
        "movement", "move"                         -> ControlCategory.MOVEMENT
        "action", "attack", "fire", "shoot", "jump"-> ControlCategory.ACTION
        "ui", "menu", "pause", "chat"              -> ControlCategory.UI
        "interaction", "interact"                  -> ControlCategory.INTERACTION
        else                                       -> ControlCategory.UNKNOWN
    }

    /** Distinct accent colour per quadrant zone for the mapping overlay. */
    private fun colorForZone(zone: String): Int = when (zone) {
        "DPAD"         -> android.graphics.Color.parseColor("#4CAF50")  // green
        "ACTION"       -> android.graphics.Color.parseColor("#F44336")  // red
        "UI"           -> android.graphics.Color.parseColor("#2196F3")  // blue
        "CANVAS_CLICK" -> android.graphics.Color.parseColor("#FF9800")  // orange
        else           -> android.graphics.Color.parseColor("#9E9E9E")  // grey
    }
}
