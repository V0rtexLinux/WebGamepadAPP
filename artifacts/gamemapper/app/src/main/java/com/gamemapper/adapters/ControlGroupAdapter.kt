package com.gamemapper.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.recyclerview.widget.RecyclerView
import com.gamemapper.R
import com.gamemapper.databinding.ItemControlBinding
import com.gamemapper.databinding.ItemControlGroupHeaderBinding
import com.gamemapper.models.ControlCategory
import com.gamemapper.models.ControlModel
import com.gamemapper.utils.ColorUtils

class ControlGroupAdapter(
    private val groups: List<Map.Entry<ControlCategory, List<ControlModel>>>,
    private val gridMode: Boolean = false,
    private val gamepadMode: Boolean = false
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ITEM = 1
    }

    data class ListItem(val isHeader: Boolean, val category: ControlCategory? = null, val control: ControlModel? = null)

    private val flatList: List<ListItem> = buildList {
        groups.forEach { (cat, controls) ->
            if (!gridMode) add(ListItem(true, cat))
            controls.forEach { ctrl -> add(ListItem(false, control = ctrl)) }
        }
    }

    override fun getItemViewType(position: Int) =
        if (flatList[position].isHeader) TYPE_HEADER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            val binding = ItemControlGroupHeaderBinding.inflate(inflater, parent, false)
            HeaderViewHolder(binding)
        } else {
            val binding = ItemControlBinding.inflate(inflater, parent, false)
            ControlViewHolder(binding, gridMode, gamepadMode)
        }
    }

    override fun getItemCount() = flatList.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = flatList[position]
        if (holder is HeaderViewHolder && item.isHeader) holder.bind(item.category!!)
        else if (holder is ControlViewHolder && item.control != null) holder.bind(item.control)
        // entrance animation
        val anim = AnimationUtils.loadAnimation(holder.itemView.context, R.anim.item_enter)
        holder.itemView.startAnimation(anim)
    }

    class HeaderViewHolder(private val binding: ItemControlGroupHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(category: ControlCategory) {
            binding.tvCategoryName.text = ColorUtils.getLabelForCategory(category)
            val color = ColorUtils.getColorForCategory(category)
            binding.viewIndicator.setBackgroundColor(color)
            binding.tvCategoryName.setTextColor(color)
        }
    }

    class ControlViewHolder(
        private val binding: ItemControlBinding,
        private val gridMode: Boolean,
        private val gamepadMode: Boolean
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(control: ControlModel) {
            binding.tvLabel.text = control.label
            binding.tvDescription.text = control.description
            binding.tvType.text = ColorUtils.getLabelForType(control.type)
            binding.tvType.setBackgroundColor(control.color)

            // Key badge
            if (control.keyLabel != null) {
                binding.tvKeyBadge.visibility = View.VISIBLE
                binding.tvKeyBadge.text = control.keyLabel
                binding.tvKeyBadge.setBackgroundColor(control.color)
            } else {
                binding.tvKeyBadge.visibility = View.GONE
            }

            // Category color strip
            binding.viewColorStrip.setBackgroundColor(control.color)

            // Card elevation & style
            val alpha = if (gamepadMode) 0.95f else 1.0f
            binding.root.alpha = alpha
        }
    }
}
