package com.gamemapper.activities

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.gamemapper.R
import com.gamemapper.models.GamepadConfig
import com.gamemapper.models.GamepadTheme
import com.gamemapper.utils.Constants
import com.gamemapper.utils.HapticManager
import com.gamemapper.views.VirtualGamepadView
import com.google.gson.Gson

/**
 * Settings screen for the virtual gamepad.
 * Allows configuring theme, sensitivity, deadzone, haptics, opacity, etc.
 */
class GamepadSettingsActivity : AppCompatActivity() {

    private lateinit var config: GamepadConfig
    private lateinit var previewGamepad: VirtualGamepadView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gamepad_settings)

        supportActionBar?.apply {
            title = "🎮 Configurações do Gamepad"
            setDisplayHomeAsUpEnabled(true)
        }

        config = loadConfig()
        previewGamepad = findViewById(R.id.previewGamepad)
        previewGamepad.config = config

        setupThemeSpinner()
        setupSensitivitySlider()
        setupDeadzoneSlider()
        setupMovementSpeedSlider()
        setupOpacitySlider()
        setupHapticSwitch()
        setupHapticStrengthSlider()
        setupAutoHideSwitch()
        setupVelocitySwitch()
        setupResetButton()
        setupSaveButton()
    }

    private fun loadConfig(): GamepadConfig {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        val json = prefs.getString(Constants.KEY_GAMEPAD_CONFIG, null) ?: return GamepadConfig()
        return try { Gson().fromJson(json, GamepadConfig::class.java) } catch (_: Exception) { GamepadConfig() }
    }

    private fun saveConfig() {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putString(Constants.KEY_GAMEPAD_CONFIG, Gson().toJson(config)).apply()
    }

    private fun setupThemeSpinner() {
        val spinner = findViewById<Spinner>(R.id.spinnerTheme) ?: return
        val themes = GamepadTheme.values()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, themes.map { it.displayName })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(themes.indexOf(config.theme))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                config = config.copy(theme = themes[pos])
                previewGamepad.config = config
                HapticManager.vibrate(this@GamepadSettingsActivity, HapticManager.Feedback.BUTTON_PRESS, config.hapticEnabled)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun setupSensitivitySlider() {
        val slider = findViewById<SeekBar>(R.id.seekSensitivity) ?: return
        val label  = findViewById<TextView>(R.id.tvSensitivityValue)
        slider.max = 200
        slider.progress = (config.stickSensitivity * 100).toInt()
        label?.text = "%.1fx".format(config.stickSensitivity)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val v = p / 100f
                config = config.copy(stickSensitivity = v)
                label?.text = "%.1fx".format(v)
                previewGamepad.config = config
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun setupDeadzoneSlider() {
        val slider = findViewById<SeekBar>(R.id.seekDeadzone) ?: return
        val label  = findViewById<TextView>(R.id.tvDeadzoneValue)
        slider.max = 50
        slider.progress = (config.stickDeadzone * 100).toInt()
        label?.text = "${(config.stickDeadzone * 100).toInt()}%"
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val v = p / 100f
                config = config.copy(stickDeadzone = v)
                label?.text = "${p}%"
                previewGamepad.config = config
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun setupMovementSpeedSlider() {
        val slider = findViewById<SeekBar>(R.id.seekMovementSpeed) ?: return
        val label  = findViewById<TextView>(R.id.tvMovementSpeedValue)
        slider.max = 100
        slider.progress = config.movementSpeed.toInt()
        label?.text = "${config.movementSpeed.toInt()}"
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                config = config.copy(movementSpeed = p.toFloat())
                label?.text = "$p"
                previewGamepad.config = config
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun setupOpacitySlider() {
        val slider = findViewById<SeekBar>(R.id.seekOpacity) ?: return
        val label  = findViewById<TextView>(R.id.tvOpacityValue)
        slider.max = 100
        slider.progress = (config.opacity * 100).toInt()
        label?.text = "${(config.opacity * 100).toInt()}%"
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val v = p / 100f
                config = config.copy(opacity = v)
                label?.text = "${p}%"
                previewGamepad.config = config
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun setupHapticSwitch() {
        val sw = findViewById<Switch>(R.id.switchHaptic) ?: return
        sw.isChecked = config.hapticEnabled
        sw.setOnCheckedChangeListener { _, checked ->
            config = config.copy(hapticEnabled = checked)
            if (checked) HapticManager.vibrate(this, HapticManager.Feedback.BUTTON_PRESS, true)
        }
    }

    private fun setupHapticStrengthSlider() {
        val slider = findViewById<SeekBar>(R.id.seekHapticStrength) ?: return
        val label  = findViewById<TextView>(R.id.tvHapticStrengthValue)
        slider.max = 100
        slider.progress = config.hapticStrength
        label?.text = "${config.hapticStrength}%"
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                config = config.copy(hapticStrength = p)
                HapticManager.setStrength(p)
                label?.text = "${p}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                HapticManager.vibrate(this@GamepadSettingsActivity, HapticManager.Feedback.BUTTON_PRESS, config.hapticEnabled)
            }
        })
    }

    private fun setupAutoHideSwitch() {
        val sw = findViewById<Switch>(R.id.switchAutoHide) ?: return
        sw.isChecked = config.autoHide
        sw.setOnCheckedChangeListener { _, checked ->
            config = config.copy(autoHide = checked)
            previewGamepad.config = config
        }
    }

    private fun setupVelocitySwitch() {
        val sw = findViewById<Switch>(R.id.switchVelocity) ?: return
        sw.isChecked = config.velocityTracking
        sw.setOnCheckedChangeListener { _, checked ->
            config = config.copy(velocityTracking = checked)
            previewGamepad.config = config
        }
    }

    private fun setupResetButton() {
        findViewById<Button>(R.id.btnResetDefaults)?.setOnClickListener {
            config = GamepadConfig()
            previewGamepad.config = config
            recreate() // Simple reset by recreating activity
        }
    }

    private fun setupSaveButton() {
        findViewById<Button>(R.id.btnSaveSettings)?.setOnClickListener {
            saveConfig()
            Toast.makeText(this, "✅ Configurações salvas!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
