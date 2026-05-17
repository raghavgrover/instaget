package com.example.fetchin_instagrammediadownloader2026.ui.library

import com.example.fetchin_instagrammediadownloader2026.data.db.MediaItem

data class LibraryItem(
    val mediaItem: MediaItem,
    val count: Int  // 1 for single, >1 for carousel
)
