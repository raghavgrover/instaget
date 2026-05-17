package com.example.fetchin_instagrammediadownloader2026.ui.library

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.fetchin_instagrammediadownloader2026.data.db.MediaItem
import com.example.fetchin_instagrammediadownloader2026.databinding.ItemCarouselPageBinding

class CarouselPagerAdapter(
    private val items: List<MediaItem>
) : RecyclerView.Adapter<CarouselPagerAdapter.PageViewHolder>() {

    inner class PageViewHolder(val binding: ItemCarouselPageBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val binding = ItemCarouselPageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val item = items[position]
        val uri = Uri.parse(item.localPath)

        Glide.with(holder.binding.ivPage.context)
            .load(uri)
            .into(holder.binding.ivPage)

        if (item.mediaType == "VIDEO") {
            holder.binding.ivPlayOverlay.visibility = View.VISIBLE
            holder.binding.root.setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "video/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                it.context.startActivity(intent)
            }
        } else {
            holder.binding.ivPlayOverlay.visibility = View.GONE
            holder.binding.root.setOnClickListener(null)
        }
    }

    override fun getItemCount() = items.size
}
