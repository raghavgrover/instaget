package com.instaget.downloader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_items")
data class MediaItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val shortcode: String,
    val originalUrl: String,
    val localPath: String,
    val mediaType: String,
    val thumbnailPath: String,
    val fileName: String,
    val downloadedAt: Long,
    val username: String = "",
    val caption: String = "",
    val isPremium: Boolean = false
)
