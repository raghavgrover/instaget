package com.instaget.downloader.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.instaget.downloader.data.db.AppDatabase
import com.instaget.downloader.data.db.MediaItem
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
            val resolver = getApplication<Application>().contentResolver
            if (item.shortcode.isNotBlank()) {
                // Delete all items belonging to this carousel/post
                dao.getAllByShortcode(item.shortcode).forEach {
                    dao.delete(it)
                    try { resolver.delete(Uri.parse(it.localPath), null, null) } catch (_: Exception) {}
                }
            } else {
                dao.delete(item)
                try { resolver.delete(Uri.parse(item.localPath), null, null) } catch (_: Exception) {}
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
            LibraryItem(mediaItem = group.minByOrNull { it.downloadedAt } ?: group.first(), count = group.size)
        }
    }
}
