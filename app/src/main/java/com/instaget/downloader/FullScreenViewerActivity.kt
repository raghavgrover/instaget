package com.instaget.downloader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.instaget.downloader.data.db.AppDatabase
import com.instaget.downloader.data.db.MediaItem
import com.instaget.downloader.databinding.ActivityFullscreenBinding
import com.instaget.downloader.ui.library.CarouselPagerAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FullScreenViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MEDIA_ID = "extra_media_id"
        const val EXTRA_LOCAL_PATH = "extra_local_path"
        const val EXTRA_MEDIA_TYPE = "extra_media_type"
        const val EXTRA_SHORTCODE = "extra_shortcode"
    }

    private lateinit var binding: ActivityFullscreenBinding
    private var mediaId: Long = -1
    private var localPath: String = ""
    private var mediaType: String = "IMAGE"
    private var shortcode: String = ""
    private var originalUrl: String = ""

    private var carouselItems: List<MediaItem> = emptyList()
    private val dots = mutableListOf<ImageView>()
    private var savedVideoPosition = 0

    private val playPauseHandler = Handler(Looper.getMainLooper())
    private val updatePlayIcon = object : Runnable {
        override fun run() {
            binding.btnPlayPause.setImageResource(
                if (binding.videoView.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_white
            )
            playPauseHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFullscreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaId = intent.getLongExtra(EXTRA_MEDIA_ID, -1)
        localPath = intent.getStringExtra(EXTRA_LOCAL_PATH) ?: ""
        mediaType = intent.getStringExtra(EXTRA_MEDIA_TYPE) ?: "IMAGE"
        shortcode = intent.getStringExtra(EXTRA_SHORTCODE) ?: ""

        // Black background needs white status bar icons
        window.statusBarColor = Color.BLACK
        WindowInsetsControllerCompat(window, binding.root).isAppearanceLightStatusBars = false

        binding.root.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                binding.root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val controlsH = binding.controlsBar.height
                // Text-only posts have no media frame — don't size it
                if (mediaType != "TEXT") {
                    binding.mediaFrame.updateLayoutParams { height = binding.scrollView.height - controlsH }
                }
                binding.scrollView.updatePadding(bottom = controlsH)
            }
        })

        if (shortcode.isNotBlank()) {
            loadCarousel()
        } else {
            showSingleItem()
        }

        setupControls()
        loadMetadata()
    }

    private fun loadCarousel() {
        lifecycleScope.launch {
            carouselItems = withContext(Dispatchers.IO) {
                AppDatabase.getInstance(this@FullScreenViewerActivity)
                    .mediaDao()
                    .getAllByShortcode(shortcode)
                    .sortedBy { it.downloadedAt }
            }
            if (carouselItems.isEmpty()) { showSingleItem(); return@launch }
            setupCarousel(carouselItems)
            loadMetadataFromItem(carouselItems.first())
        }
    }

    private fun setupCarousel(items: List<MediaItem>) {
        binding.ivFullscreen.visibility = View.GONE
        binding.videoView.visibility = View.GONE
        binding.viewPager.visibility = View.VISIBLE
        binding.dotsContainer.visibility = View.VISIBLE
        binding.btnRewind.visibility = View.GONE
        binding.btnPlayPause.visibility = View.GONE
        binding.btnForward.visibility = View.GONE

        binding.viewPager.adapter = CarouselPagerAdapter(items)
        buildDots(items.size)
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) { updateDots(position) }
        })
    }

    private fun buildDots(count: Int) {
        binding.dotsContainer.removeAllViews()
        dots.clear()
        val size = (8 * resources.displayMetrics.density).toInt()
        val margin = (5 * resources.displayMetrics.density).toInt()
        repeat(count) { i ->
            val dot = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).also { it.setMargins(margin, 0, margin, 0) }
                setImageDrawable(circleDot(i == 0))
            }
            dots.add(dot)
            binding.dotsContainer.addView(dot)
        }
    }

    private fun updateDots(selected: Int) {
        dots.forEachIndexed { i, dot -> dot.setImageDrawable(circleDot(i == selected)) }
    }

    private fun circleDot(selected: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (selected) Color.WHITE else Color.argb(100, 255, 255, 255))
    }

    private fun showSingleItem() {
        if (localPath.isEmpty()) { finish(); return }
        when (mediaType) {
            "VIDEO" -> setupVideo(Uri.parse(localPath))
            "TEXT"  -> setupTextOnly()
            else    -> setupImage(Uri.parse(localPath))
        }
    }

    private fun setupTextOnly() {
        // Hide the media frame entirely — caption/username from infoSection is all there is
        binding.mediaFrame.visibility = View.GONE
        binding.ivFullscreen.visibility = View.GONE
        binding.videoView.visibility = View.GONE
        binding.btnRewind.visibility = View.GONE
        binding.btnPlayPause.visibility = View.GONE
        binding.btnForward.visibility = View.GONE
        // White background throughout — no black gap below the caption box
        binding.root.setBackgroundColor(Color.WHITE)
        binding.scrollView.setBackgroundColor(Color.WHITE)
        binding.controlsBar.setBackgroundColor(Color.parseColor("#F0F0F0"))
        // Re-tint control bar icons to dark so they're visible on the light background
        val darkTint = android.content.res.ColorStateList.valueOf(Color.parseColor("#333333"))
        listOf(binding.btnCopyLink, binding.btnShare, binding.btnDelete, binding.btnInstagram)
            .forEach { it.imageTintList = darkTint }
    }

    private fun setupImage(uri: Uri) {
        binding.ivFullscreen.visibility = View.VISIBLE
        binding.videoView.visibility = View.GONE
        binding.btnRewind.visibility = View.GONE
        binding.btnPlayPause.visibility = View.GONE
        binding.btnForward.visibility = View.GONE
        Glide.with(this).load(uri).into(binding.ivFullscreen)
    }

    private fun setupVideo(uri: Uri, seekTo: Int = 0) {
        binding.videoView.visibility = View.VISIBLE
        binding.ivFullscreen.visibility = View.GONE
        binding.btnRewind.visibility = View.VISIBLE
        binding.btnPlayPause.visibility = View.VISIBLE
        binding.btnForward.visibility = View.VISIBLE
        binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
        binding.videoView.setVideoURI(uri)
        binding.videoView.setOnPreparedListener { mp ->
            if (seekTo > 0) mp.seekTo(seekTo)
            mp.start()
        }
        binding.videoView.requestFocus()
        playPauseHandler.post(updatePlayIcon)
    }

    private fun setupControls() {
        binding.btnCopyLink.setOnClickListener {
            val link = originalUrl.ifBlank {
                when {
                    shortcode.isNotBlank() && mediaType == "VIDEO" -> "https://www.instagram.com/reel/$shortcode/"
                    shortcode.isNotBlank() -> "https://www.instagram.com/p/$shortcode/"
                    else -> ""
                }
            }
            if (link.isNotBlank()) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("link", link))
                Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnCopyUsername.setOnClickListener {
            val text = binding.tvUsername.text.toString()
            if (text.isNotBlank()) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("username", text))
                Toast.makeText(this, "Username copied", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnCopyCaption.setOnClickListener {
            val text = binding.tvCaption.text.toString()
            if (text.isNotBlank()) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("caption", text))
                Toast.makeText(this, "Caption copied", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnPlayPause.setOnClickListener {
            if (binding.videoView.isPlaying) binding.videoView.pause()
            else binding.videoView.start()
        }
        binding.btnRewind.setOnClickListener {
            val pos = (binding.videoView.currentPosition - 10_000).coerceAtLeast(0)
            binding.videoView.seekTo(pos)
        }
        binding.btnForward.setOnClickListener {
            val dur = binding.videoView.duration.takeIf { it > 0 } ?: Int.MAX_VALUE
            val pos = (binding.videoView.currentPosition + 10_000).coerceAtMost(dur)
            binding.videoView.seekTo(pos)
        }
        binding.btnShare.setOnClickListener { shareMedia() }
        binding.btnDelete.setOnClickListener { confirmDelete() }
        binding.btnInstagram.setOnClickListener { openInInstagram() }
    }

    private fun loadMetadata() {
        if (mediaId == -1L && shortcode.isBlank()) return
        lifecycleScope.launch {
            val item = withContext(Dispatchers.IO) {
                val dao = AppDatabase.getInstance(this@FullScreenViewerActivity).mediaDao()
                if (mediaId != -1L) dao.getById(mediaId)
                else if (shortcode.isNotBlank()) dao.getByShortcode(shortcode)
                else null
            } ?: return@launch
            loadMetadataFromItem(item)
            originalUrl = item.originalUrl
        }
    }

    private fun loadMetadataFromItem(item: MediaItem) {
        originalUrl = item.originalUrl
        val hasInfo = item.username.isNotBlank() || item.caption.isNotBlank()
        if (hasInfo) {
            binding.infoSection.visibility = View.VISIBLE
            binding.tvUsername.text = if (item.username.isNotBlank()) "@${item.username}" else ""
            binding.tvCaption.text = item.caption
            binding.tvUsername.visibility = if (item.username.isNotBlank()) View.VISIBLE else View.GONE
            binding.tvCaption.visibility = if (item.caption.isNotBlank()) View.VISIBLE else View.GONE
            binding.btnCopyUsername.visibility = if (item.username.isNotBlank()) View.VISIBLE else View.GONE
            binding.btnCopyCaption.visibility = if (item.caption.isNotBlank()) View.VISIBLE else View.GONE
        }
    }

    private fun openInInstagram() {
        val url = when {
            shortcode.isNotBlank() && mediaType == "VIDEO" -> "https://www.instagram.com/reel/$shortcode/"
            shortcode.isNotBlank() -> "https://www.instagram.com/p/$shortcode/"
            originalUrl.isNotBlank() -> originalUrl
            else -> return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: android.content.ActivityNotFoundException) {}
    }

    private fun shareMedia() {
        val uri = Uri.parse(localPath)
        val mimeType = if (mediaType == "VIDEO") "video/*" else "image/*"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.label_share)))
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this, R.style.RoundedAlertDialog)
            .setTitle(R.string.label_delete_confirm)
            .setMessage(R.string.label_delete_gallery_note)
            .setPositiveButton(R.string.label_delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val dao = AppDatabase.getInstance(this@FullScreenViewerActivity).mediaDao()
                        if (shortcode.isNotBlank()) {
                            dao.getAllByShortcode(shortcode).forEach {
                                dao.delete(it)
                                try { contentResolver.delete(Uri.parse(it.localPath), null, null) } catch (_: Exception) {}
                            }
                        } else if (mediaId != -1L) {
                            val it = dao.getById(mediaId)
                            if (it != null) {
                                dao.delete(it)
                                try { contentResolver.delete(Uri.parse(localPath), null, null) } catch (_: Exception) {}
                            }
                        }
                    }
                    finish()
                }
            }
            .setNegativeButton(R.string.label_cancel, null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        if (mediaType == "VIDEO") {
            savedVideoPosition = binding.videoView.currentPosition
            binding.videoView.pause()
        }
    }

    override fun onResume() {
        super.onResume()
        if (mediaType == "VIDEO" && localPath.isNotBlank()) {
            setupVideo(Uri.parse(localPath), savedVideoPosition)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        playPauseHandler.removeCallbacks(updatePlayIcon)
        binding.videoView.stopPlayback()
    }
}
