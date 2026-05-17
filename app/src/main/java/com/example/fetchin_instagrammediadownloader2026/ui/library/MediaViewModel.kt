package com.example.fetchin_instagrammediadownloader2026.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.fetchin_instagrammediadownloader2026.data.db.AppDatabase
import com.example.fetchin_instagrammediadownloader2026.data.db.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MediaViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getInstance(application).mediaDao()

    private val _libraryItems = MutableStateFlow<List<LibraryItem>>(emptyList())
    val libraryItems: StateFlow<List<LibraryItem>> = _libraryItems.asStateFlow()

    init {
        loadAll()
    }

    fun loadAll() {
        viewModelScope.launch {
            dao.getAllItems().collect { items ->
                _libraryItems.value = groupIntoLibraryItems(items)
            }
        }
    }

    fun loadByType(type: String) {
        viewModelScope.launch {
            dao.getAllByType(type).collect { items ->
                _libraryItems.value = groupIntoLibraryItems(items)
            }
        }
    }

    fun delete(item: MediaItem) {
        viewModelScope.launch {
            if (item.shortcode.isNotBlank()) {
                // Delete all items belonging to this carousel/post
                dao.getAllByShortcode(item.shortcode).forEach { dao.delete(it) }
            } else {
                dao.delete(item)
            }
        }
    }

    private fun groupIntoLibraryItems(items: List<MediaItem>): List<LibraryItem> {
        // Group by shortcode; items without a shortcode get their own group
        val groups = linkedMapOf<String, MutableList<MediaItem>>()
        for (item in items) {
            val key = if (item.shortcode.isNotBlank()) item.shortcode else "solo_${item.id}"
            groups.getOrPut(key) { mutableListOf() }.add(item)
        }
        return groups.values.map { group ->
            LibraryItem(mediaItem = group.first(), count = group.size)
        }
    }
}
