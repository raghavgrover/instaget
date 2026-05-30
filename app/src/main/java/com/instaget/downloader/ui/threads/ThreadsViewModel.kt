package com.instaget.downloader.ui.threads

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.instaget.downloader.data.ThreadsPostInfo
import com.instaget.downloader.data.ThreadsScraper
import com.instaget.downloader.data.db.AppDatabase
import com.instaget.downloader.data.db.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class ThreadsScrapeState {
    object Idle : ThreadsScrapeState()
    object Loading : ThreadsScrapeState()
    data class Success(val info: ThreadsPostInfo) : ThreadsScrapeState()
    object AlreadyDownloaded : ThreadsScrapeState()
    data class Error(val message: String) : ThreadsScrapeState()
}

class ThreadsViewModel(application: Application) : AndroidViewModel(application) {

    private val scraper = ThreadsScraper()
    private val dao = AppDatabase.getInstance(application).mediaDao()

    private val _scrapeState = MutableStateFlow<ThreadsScrapeState>(ThreadsScrapeState.Idle)
    val scrapeState: StateFlow<ThreadsScrapeState> = _scrapeState.asStateFlow()

    fun fetchPost(url: String) {
        if (url.isBlank()) {
            _scrapeState.value = ThreadsScrapeState.Error("Please paste a Threads link")
            return
        }
        if (!scraper.isThreadsUrl(url)) {
            _scrapeState.value = ThreadsScrapeState.Error("Invalid Threads URL — must contain threads.net/@user/post/…")
            return
        }

        viewModelScope.launch {
            _scrapeState.value = ThreadsScrapeState.Loading

            // Check if already downloaded
            val postId = scraper.extractPostId(url)
            if (postId != null) {
                val existing = dao.getByShortcode("threads_$postId")
                if (existing != null) {
                    _scrapeState.value = ThreadsScrapeState.AlreadyDownloaded
                    return@launch
                }
            }

            val result = scraper.fetchPostInfo(url)
            _scrapeState.value = if (result.isSuccess) {
                ThreadsScrapeState.Success(result.getOrThrow())
            } else {
                ThreadsScrapeState.Error(result.exceptionOrNull()?.message ?: "Failed to fetch post")
            }
        }
    }

    fun reset() {
        _scrapeState.value = ThreadsScrapeState.Idle
    }

    /** Save a text-only post to the library after the user taps "Save as .txt" */
    fun saveTextPostToLibrary(info: ThreadsPostInfo, localPath: String) {
        viewModelScope.launch {
            dao.insert(
                MediaItem(
                    shortcode    = "threads_${info.postId}",
                    originalUrl  = "",
                    localPath    = localPath,
                    mediaType    = "TEXT",
                    thumbnailPath = "",
                    fileName     = "Threads_${info.postId}.txt",
                    downloadedAt = System.currentTimeMillis(),
                    username     = info.username,
                    caption      = info.text
                )
            )
        }
    }
}
