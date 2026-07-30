package com.gamemapper.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.gamemapper.adapters.ProfileAdapter
import com.gamemapper.databinding.ActivityProfilesBinding
import com.gamemapper.utils.Constants
import com.gamemapper.utils.ProfileStorage

class ProfilesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfilesBinding
    private lateinit var profileAdapter: ProfileAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfilesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupRecyclerView()
        refreshProfiles()
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
            layoutManager = LinearLayoutManager(this@ProfilesActivity)
            adapter = profileAdapter
        }
    }

    private fun refreshProfiles() {
        val profiles = ProfileStorage.loadProfiles(this)
        profileAdapter.submitList(profiles)
        binding.tvEmpty.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
        binding.rvProfiles.visibility = if (profiles.isEmpty()) View.GONE else View.VISIBLE
    }
}
