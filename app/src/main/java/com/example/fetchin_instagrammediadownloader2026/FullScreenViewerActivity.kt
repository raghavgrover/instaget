package com.example.fetchin_instagrammediadownloader2026

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.example.fetchin_instagrammediadownloader2026.data.db.AppDatabase
import com.example.fetchin_instagrammediadownloader2026.data.db.MediaItem
import com.example.fetchin_instagrammediadownloader2026.databinding.ActivityFullscreenBinding
import com.example.fetchin_instagrammediadownloader2026.ui.library.CarouselPagerAdapter
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

    private var scaleFactor = 1f
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    private var carouselItems: List<MediaItem> = emptyList()
    private val dots = mutableListOf<ImageView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFullscreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""

        mediaId = intent.getLongExtra(EXTRA_MEDIA_ID, -1)
        localPath = intent.getStringExtra(EXTRA_LOCAL_PATH) ?: ""
        mediaType = intent.getStringExtra(EXTRA_MEDIA_TYPE) ?: "IMAGE"
        shortcode = intent.getStringExtra(EXTRA_SHORTCODE) ?: ""

        if (shortcode.isNotBlank()) {
            loadCarousel()
        } else {
            showSingleItem()
        }
    }

    private fun loadCarousel() {
        lifecycleScope.launch {
            carouselItems = withContext(Dispatchers.IO) {
                AppDatabase.getInstance(this@FullScreenViewerActivity)
                    .mediaDao()
                    .getAllByShortcode(shortcode)
                    .sortedBy { it.downloadedAt }
            }
            if (carouselItems.isEmpty()) {
                showSingleItem()
                return@launch
            }
            setupCarousel(carouselItems)
        }
    }

    private fun setupCarousel(items: List<MediaItem>) {
        binding.ivFullscreen.visibility = View.GONE
        binding.videoView.visibility = View.GONE
        binding.viewPager.visibility = View.VISIBLE
        binding.dotsContainer.visibility = View.VISIBLE

        binding.viewPager.adapter = CarouselPagerAdapter(items)
        buildDots(items.size)

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateDots(position)
            }
        })
    }

    private fun buildDots(count: Int) {
        binding.dotsContainer.removeAllViews()
        dots.clear()
        val size = (8 * resources.displayMetrics.density).toInt()
        val margin = (5 * resources.displayMetrics.density).toInt()
        repeat(count) { i ->
            val dot = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).also {
                    it.setMargins(margin, 0, margin, 0)
                }
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
        val uri = Uri.parse(localPath)
        if (mediaType == "VIDEO") setupVideo(uri) else setupImage(uri)
    }

    private fun setupImage(uri: Uri) {
        binding.ivFullscreen.visibility = View.VISIBLE
        binding.videoView.visibility = View.GONE

        Glide.with(this).load(uri).into(binding.ivFullscreen)

        scaleGestureDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    scaleFactor *= detector.scaleFactor
                    scaleFactor = scaleFactor.coerceIn(0.5f, 5f)
                    binding.ivFullscreen.scaleX = scaleFactor
                    binding.ivFullscreen.scaleY = scaleFactor
                    return true
                }
            })
        binding.ivFullscreen.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun setupVideo(uri: Uri) {
        binding.videoView.visibility = View.VISIBLE
        binding.ivFullscreen.visibility = View.GONE
        val mediaController = MediaController(this)
        mediaController.setAnchorView(binding.videoView)
        binding.videoView.setMediaController(mediaController)
        binding.videoView.setVideoURI(uri)
        binding.videoView.requestFocus()
        binding.videoView.start()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.fullscreen_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            R.id.action_share -> { shareMedia(); true }
            R.id.action_delete -> { confirmDelete(); true }
            else -> super.onOptionsItemSelected(item)
        }
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
        AlertDialog.Builder(this)
            .setTitle(R.string.label_delete_confirm)
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
}
