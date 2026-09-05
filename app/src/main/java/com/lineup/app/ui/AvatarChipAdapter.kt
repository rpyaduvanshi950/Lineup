package com.lineup.app.ui

import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lineup.app.R
import com.lineup.app.databinding.ItemAvatarChipBinding

data class AvatarChipRow(val personId: Int, val appearanceCount: Int, val avatarPath: String?)

/** One circular avatar chip per identified person: their actual representative-shot crop
 * (falling back to a solid color if the crop is missing), with an amber appearance-count badge. */
class AvatarChipAdapter(private val rows: List<AvatarChipRow>) :
    RecyclerView.Adapter<AvatarChipAdapter.ViewHolder>() {

    private val fallbackColors = intArrayOf(
        R.color.avatar_1, R.color.avatar_2, R.color.avatar_3,
        R.color.avatar_4, R.color.avatar_5, R.color.avatar_6,
    )

    inner class ViewHolder(val binding: ItemAvatarChipBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAvatarChipBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = rows[position]
        val context = holder.binding.root.context
        holder.binding.appearanceBadge.text = row.appearanceCount.toString()
        holder.binding.root.contentDescription =
            context.getString(R.string.cd_avatar_format, row.personId + 1, row.appearanceCount)

        val bitmap = row.avatarPath?.let { BitmapFactory.decodeFile(it) }
        if (bitmap != null) {
            holder.binding.avatarImage.setImageBitmap(bitmap)
        } else {
            holder.binding.avatarImage.setImageDrawable(null)
            val bg = holder.binding.avatarImage.background.mutate() as GradientDrawable
            bg.setColor(context.getColor(fallbackColors[row.personId % fallbackColors.size]))
        }

        holder.binding.root.animateStaggeredEntrance(position)
    }

    override fun getItemCount() = rows.size
}
