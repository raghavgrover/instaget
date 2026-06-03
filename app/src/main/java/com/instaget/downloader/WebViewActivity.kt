package com.instaget.downloader

import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.graphics.Color
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.instaget.downloader.databinding.ActivityWebviewBinding

/**
 * In-app browser for Terms of Use and Privacy Policy documents.
 * Pass EXTRA_URL and EXTRA_TITLE as intent extras.
 */
class WebViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL   = "extra_url"
        const val EXTRA_TITLE = "extra_title"
    }

    private lateinit var binding: ActivityWebviewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Edge-to-edge: only purple at top, matching all other pages
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val statusBarTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = statusBarTop)
            insets
        }
        WindowInsetsControllerCompat(window, binding.root).isAppearanceLightStatusBars = false

        val url   = intent.getStringExtra(EXTRA_URL)   ?: ""
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "InstaGet"

        // Toolbar setup
        binding.tvTitle.text = title
        binding.toolbar.navigationIcon?.let {
            val tinted = DrawableCompat.wrap(it).mutate()
            DrawableCompat.setTint(tinted, Color.WHITE)
            binding.toolbar.navigationIcon = tinted
        }
        binding.toolbar.setNavigationOnClickListener { finish() }

        // WebView setup
        binding.webView.settings.apply {
            javaScriptEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
            override fun onPageFinished(view: WebView, url: String) {
                binding.progressBar.visibility = android.view.View.GONE
            }
        }
        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.progressBar.progress = newProgress
                binding.progressBar.visibility =
                    if (newProgress < 100) android.view.View.VISIBLE else android.view.View.GONE
            }
        }

        if (url.isNotBlank()) binding.webView.loadUrl(url)
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) binding.webView.goBack()
        else super.onBackPressed()
    }
}
