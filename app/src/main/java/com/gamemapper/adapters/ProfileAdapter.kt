package com.gamemapper.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gamemapper.databinding.ItemProfileBinding
import com.gamemapper.models.ControlProfile
import java.text.SimpleDateFormat
import java.util.*

class ProfileAdapter(
    private val onOpen: (ControlProfile) -> Unit,
    private val onDelete: (ControlProfile) -> Unit
) : ListAdapter<ControlProfile, ProfileAdapter.ProfileViewHolder>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ControlProfile>() {
            override fun areItemsTheSame(a: ControlProfile, b: ControlProfile) = a.id == b.id
            override fun areContentsTheSame(a: ControlProfile, b: ControlProfile) = a == b
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
        val binding = ItemProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ProfileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ProfileViewHolder(private val binding: ItemProfileBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(profile: ControlProfile) {
            binding.tvProfileName.text = profile.name
            binding.tvProfileUrl.text = profile.gameUrl
            binding.tvControlCount.text = "${profile.controls.size} controles"
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            binding.tvDate.text = sdf.format(Date(profile.createdAt))

            binding.root.setOnClickListener { onOpen(profile) }
            binding.btnDelete.setOnClickListener { onDelete(profile) }
        }
    }
}
