package com.gamemapper.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.gamemapper.R
import com.gamemapper.adapters.ControlGroupAdapter
import com.gamemapper.databinding.ActivityControlMapBinding
import com.gamemapper.models.ControlCategory
import com.gamemapper.models.ControlModel
import com.gamemapper.utils.Constants
import com.gamemapper.utils.ColorUtils
import com.gamemapper.utils.ProfileStorage

class ControlMapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityControlMapBinding
    private var profileId: String = ""
    private var currentLayout: Int = 0 // 0=grouped list, 1=grid, 2=gamepad style
    private val LAYOUT_COUNT = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityControlMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        profileId = intent.getStringExtra(Constants.EXTRA_PROFILE_ID) ?: ""
        if (profileId.isEmpty()) { finish(); return }

        setupToolbar()
        loadAndDisplay()
        setupButtons()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun loadAndDisplay() {
        val profile = ProfileStorage.getProfile(this, profileId) ?: run {
            Toast.makeText(this, "Perfil não encontrado", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.tvGameTitle.text = profile.name
        binding.tvGameUrl.text = profile.gameUrl
        binding.tvControlCount.text = "${profile.controls.size} controles mapeados"

        val relativeTime = android.text.format.DateUtils.getRelativeTimeSpanString(
            profile.updatedAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS
        )
        binding.tvAutoSaved.text = "✓ Salvo automaticamente • $relativeTime"

        // Stats bar
        val movements = profile.controls.count { it.category == ControlCategory.MOVEMENT }
        val actions = profile.controls.count { it.category == ControlCategory.ACTION }
        val ui = profile.controls.count { it.category == ControlCategory.UI }
        val other = profile.controls.size - movements - actions - ui
        binding.tvStatMovement.text = "$movements\nMov."
        binding.tvStatAction.text = "$actions\nAção"
        binding.tvStatUi.text = "$ui\nUI"
        binding.tvStatOther.text = "$other\nOutros"

        renderLayout(profile.controls)
    }

    private fun renderLayout(controls: List<ControlModel>) {
        val grouped = controls.groupBy { it.category }
            .entries.sortedBy { it.key.ordinal }

        when (currentLayout) {
            0 -> renderGroupedList(grouped)
            1 -> renderGrid(controls)
            2 -> renderGamepadStyle(controls)
        }

        val labels = listOf("Lista Agrupada", "Grade", "Gamepad")
        binding.tvLayoutName.text = "Layout: ${labels[currentLayout]}"
    }

    private fun renderGroupedList(grouped: List<Map.Entry<ControlCategory, List<ControlModel>>>) {
        binding.rvControls.layoutManager = LinearLayoutManager(this)
        val adapter = ControlGroupAdapter(grouped)
        binding.rvControls.adapter = adapter
    }

    private fun renderGrid(controls: List<ControlModel>) {
        val cols = if (controls.size > 6) 3 else 2
        binding.rvControls.layoutManager = GridLayoutManager(this, cols)
        val adapter = ControlGroupAdapter(
            listOf(object : Map.Entry<ControlCategory, List<ControlModel>> {
                override val key = ControlCategory.ACTION
                override val value = controls
            }),
            gridMode = true
        )
        binding.rvControls.adapter = adapter
    }

    private fun renderGamepadStyle(controls: List<ControlModel>) {
        // Group by category with special ordering for gamepad feel
        val movement = controls.filter { it.category == ControlCategory.MOVEMENT }
        val actions = controls.filter { it.category == ControlCategory.ACTION }
        val rest = controls.filter { it.category != ControlCategory.MOVEMENT && it.category != ControlCategory.ACTION }
        val reordered = movement + actions + rest

        binding.rvControls.layoutManager = GridLayoutManager(this, 2)
        val adapter = ControlGroupAdapter(
            reordered.groupBy { it.category }.entries.sortedBy {
                when (it.key) {
                    ControlCategory.MOVEMENT -> 0
                    ControlCategory.ACTION -> 1
                    else -> 2
                }
            },
            gamepadMode = true
        )
        binding.rvControls.adapter = adapter
    }

    private fun setupButtons() {
        // Remap button – re-analyze with alternative strategy
        binding.btnRemap.setOnClickListener {
            val profile = ProfileStorage.getProfile(this, profileId) ?: return@setOnClickListener
            val intent = Intent(this, AnalyzerActivity::class.java)
            intent.putExtra(Constants.EXTRA_GAME_URL, profile.gameUrl)
            intent.putExtra(Constants.EXTRA_ANALYSIS_MODE, Constants.ANALYSIS_MODE_REMAP)
            intent.putExtra(Constants.EXTRA_SOURCE_PROFILE_ID, profile.id)
            startActivity(intent)
            finish()
        }

        // Cycle layout button
        binding.btnCycleLayout.setOnClickListener {
            currentLayout = (currentLayout + 1) % LAYOUT_COUNT
            val profile = ProfileStorage.getProfile(this, profileId) ?: return@setOnClickListener
            renderLayout(profile.controls)
            val names = listOf("Lista Agrupada", "Grade", "Gamepad")
            Toast.makeText(this, "Layout: ${names[currentLayout]}", Toast.LENGTH_SHORT).show()
        }

        // Share button
        binding.btnShare.setOnClickListener {
            val profile = ProfileStorage.getProfile(this, profileId) ?: return@setOnClickListener
            val text = buildShareText(profile.name, profile.controls)
            val shareIntent = Intent(Intent.ACTION_SEND)
            shareIntent.type = "text/plain"
            shareIntent.putExtra(Intent.EXTRA_TEXT, text)
            startActivity(Intent.createChooser(shareIntent, "Compartilhar mapeamento"))
        }
    }

    private fun buildShareText(gameName: String, controls: List<ControlModel>): String {
        val sb = StringBuilder()
        sb.appendLine("🎮 Mapeamento de controles – $gameName")
        sb.appendLine("Gerado pelo GameMapper\n")
        controls.groupBy { it.category }.forEach { (cat, list) ->
            sb.appendLine("${ColorUtils.getLabelForCategory(cat)}:")
            list.forEach { ctrl ->
                sb.appendLine("  • ${ctrl.label} — ${ctrl.description}")
            }
        }
        return sb.toString()
    }
}
