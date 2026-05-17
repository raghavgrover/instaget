package com.example.fetchin_instagrammediadownloader2026.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.fetchin_instagrammediadownloader2026.data.InstagramScraper
import com.example.fetchin_instagrammediadownloader2026.data.MediaInfo
import com.example.fetchin_instagrammediadownloader2026.data.db.AppDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class ScrapeState {
    object Idle : ScrapeState()
    object Loading : ScrapeState()
    data class Success(val info: MediaInfo) : ScrapeState()
    data class AlreadyDownloaded(val shortcode: String) : ScrapeState()
    data class Error(val message: String) : ScrapeState()
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val scraper = InstagramScraper()
    private val dao = AppDatabase.getInstance(application).mediaDao()

    private val _scrapeState = MutableStateFlow<ScrapeState>(ScrapeState.Idle)
    val scrapeState: StateFlow<ScrapeState> = _scrapeState.asStateFlow()

    fun fetchMedia(url: String) {
        if (url.isBlank()) {
            _scrapeState.value = ScrapeState.Error("Please paste an Instagram link")
            return
        }
        if (!url.contains("instagram.com")) {
            _scrapeState.value = ScrapeState.Error("Invalid Instagram URL")
            return
        }

        viewModelScope.launch {
            _scrapeState.value = ScrapeState.Loading

            val shortcode = scraper.extractShortcode(url)
            if (shortcode != null) {
                val existing = dao.getByShortcode(shortcode)
                if (existing != null) {
                    _scrapeState.value = ScrapeState.AlreadyDownloaded(shortcode)
                    return@launch
                }
            }

            val result = scraper.fetchMediaInfo(url)
            _scrapeState.value = if (result.isSuccess) {
                ScrapeState.Success(result.getOrThrow())
            } else {
                ScrapeState.Error(result.exceptionOrNull()?.message ?: "Download failed")
            }
        }
    }

    fun reset() {
        _scrapeState.value = ScrapeState.Idle
    }
}
