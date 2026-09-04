package com.lineup.app.ui

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lineup.app.R
import com.lineup.app.databinding.ItemPersonBinding

data class PersonRow(val personId: Int, val appearanceCount: Int)

/** One card per identified person: colored avatar, label, appearance-count pill. */
class PersonAdapter(private val rows: List<PersonRow>) :
    RecyclerView.Adapter<PersonAdapter.ViewHolder>() {

    private val avatarColors = intArrayOf(
        R.color.avatar_1, R.color.avatar_2, R.color.avatar_3,
        R.color.avatar_4, R.color.avatar_5, R.color.avatar_6,
    )

    inner class ViewHolder(val binding: ItemPersonBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPersonBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = rows[position]
        val context = holder.binding.root.context
        holder.binding.personLabel.text =
            context.getString(R.string.person_label_format, row.personId + 1)
        holder.binding.appearanceCount.text =
            context.getString(R.string.appearance_count_format, row.appearanceCount)
        holder.binding.avatarInitial.text = (row.personId + 1).toString()

        val colorRes = avatarColors[row.personId % avatarColors.size]
        val bg = holder.binding.avatarFrame.background.mutate() as GradientDrawable
        bg.setColor(context.getColor(colorRes))
    }

    override fun getItemCount() = rows.size
}
