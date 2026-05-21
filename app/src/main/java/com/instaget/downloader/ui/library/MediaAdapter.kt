package com.instaget.downloader.ui.library

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.instaget.downloader.data.db.MediaItem
import com.instaget.downloader.databinding.ItemMediaBinding

class MediaAdapter(
    private val onItemClick: (LibraryItem) -> Unit,
    private val onItemLongClick: (LibraryItem) -> Unit
) : ListAdapter<LibraryItem, MediaAdapter.MediaViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<LibraryItem>() {
            override fun areItemsTheSame(old: LibraryItem, new: LibraryItem) =
                old.mediaItem.id == new.mediaItem.id
            override fun areContentsTheSame(old: LibraryItem, new: LibraryItem) = old == new
        }
    }

    inner class MediaViewHolder(private val binding: ItemMediaBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(libraryItem: LibraryItem) {
            val item = libraryItem.mediaItem
            val uri = Uri.parse(item.localPath)

            Glide.with(binding.ivThumbnail.context)
                .load(uri)
                .centerCrop()
                .into(binding.ivThumbnail)

            binding.ivPlayOverlay.visibility =
                if (item.mediaType == "VIDEO" && libraryItem.count <= 1) View.VISIBLE else View.GONE

            if (libraryItem.count > 1) {
                binding.carouselBadge.visibility = View.VISIBLE
                binding.tvCarouselCount.text = libraryItem.count.toString()
            } else {
                binding.carouselBadge.visibility = View.GONE
            }

            binding.root.setOnClickListener { onItemClick(libraryItem) }
            binding.root.setOnLongClickListener {
                onItemLongClick(libraryItem)
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val binding = ItemMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MediaViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
