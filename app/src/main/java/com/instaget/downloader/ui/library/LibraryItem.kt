package com.instaget.downloader.ui.library

import com.instaget.downloader.data.db.MediaItem

data class LibraryItem(
    val mediaItem: MediaItem,
    val count: Int  // 1 for single, >1 for carousel
)
