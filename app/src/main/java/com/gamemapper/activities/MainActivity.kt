package com.gamemapper.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.gamemapper.R
import com.gamemapper.adapters.ProfileAdapter
import com.gamemapper.databinding.ActivityMainBinding
import com.gamemapper.utils.Constants
import com.gamemapper.utils.ProfileStorage

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var profileAdapter: ProfileAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupInputs()
        setupButtons()
    }

    override fun onResume() {
        super.onResume()
        refreshProfiles()
    }

    private fun setupRecyclerView() {
        profileAdapter = ProfileAdapter(
            onOpen = { profile ->
                val intent = Intent(this, ControlMapActivity::class.java)
                intent.putExtra(Constants.EXTRA_PROFILE_ID, profile.id)
                startActivity(intent)
            },
            onDelete = { profile ->
                ProfileStorage.deleteProfile(this, profile.id)
                refreshProfiles()
                Toast.makeText(this, "Perfil removido", Toast.LENGTH_SHORT).show()
            }
        )
        binding.rvProfiles.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = profileAdapter
        }
    }

    private fun setupInputs() {
        binding.etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                startAnalysis()
                true
            } else false
        }

        // Quick fill buttons for popular CPPS games
        binding.btnCpJourney.setOnClickListener {
            binding.etUrl.setText("https://play.cpjourney.net")
        }
        binding.btnClubPenguin.setOnClickListener {
            binding.etUrl.setText("https://cpps.app/auth/login")
        }
        binding.btnIcer.setOnClickListener {
            binding.etUrl.setText("https://icer.ink")
        }
        binding.btnCpLegacy.setOnClickListener {
            binding.etUrl.setText("https://cplegacy.com")
        }
    }

    private fun setupButtons() {
        binding.btnAnalyze.setOnClickListener { startAnalysis() }

        binding.btnProfiles.setOnClickListener {
            startActivity(Intent(this, ProfilesActivity::class.java))
        }
    }

    private fun startAnalysis() {
        val rawUrl = binding.etUrl.text.toString().trim()
        if (rawUrl.isEmpty()) {
            binding.etUrl.error = "Digite a URL do jogo"
            return
        }
        val url = if (!rawUrl.startsWith("http")) "https://$rawUrl" else rawUrl

        getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
            .edit().putString(Constants.KEY_LAST_URL, url).apply()

        val intent = Intent(this, AnalyzerActivity::class.java)
        intent.putExtra(Constants.EXTRA_GAME_URL, url)
        intent.putExtra(Constants.EXTRA_ANALYSIS_MODE, Constants.ANALYSIS_MODE_DEEP)
        startActivity(intent)
    }

    private fun refreshProfiles() {
        val profiles = ProfileStorage.loadProfiles(this)
        profileAdapter.submitList(profiles)
        binding.tvEmptyState.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
        binding.rvProfiles.visibility = if (profiles.isEmpty()) View.GONE else View.VISIBLE
    }
}
