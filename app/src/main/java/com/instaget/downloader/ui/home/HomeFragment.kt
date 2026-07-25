package com.instaget.downloader.ui.home

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import androidx.navigation.fragment.findNavController
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.LoadAdError
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.instaget.downloader.R
import com.google.android.material.snackbar.Snackbar
import com.instaget.downloader.ads.AdConfig
import com.instaget.downloader.ads.CreditsManager
import com.instaget.downloader.ads.RewardedInterstitialManager
import com.instaget.downloader.billing.BillingManager
import com.instaget.downloader.data.MediaInfo
import com.instaget.downloader.databinding.FragmentHomeBinding
import com.instaget.downloader.worker.DownloadWorker
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private lateinit var rewardedAdManager: RewardedInterstitialManager
    private var bannerAdView: com.google.android.gms.ads.AdView? = null
    private val viewModel: HomeViewModel by viewModels()
    private var pendingMediaInfo: MediaInfo? = null

    private val bannerRetryHandler = Handler(Looper.getMainLooper())
    private var bannerRetryRunnable: Runnable? = null
    private var bannerRetryAttempt = 0

    companion object {
        private const val TAG = "BannerAd"
        private val BANNER_RETRY_DELAYS_MS = longArrayOf(15_000, 30_000, 60_000)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            pendingMediaInfo?.let { enqueueDownload(it) }
        } else {
            Snackbar.make(binding.root, "Storage permission required to save files", Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tilUrl.setEndIconOnClickListener {
            val cb = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cb.primaryClip?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrBlank()) binding.etUrl.setText(text)
            else Snackbar.make(binding.root, "Clipboard is empty", Snackbar.LENGTH_SHORT).show()
        }

        // Set up ads infrastructure (credits replace the old 10-download hard limit)
        rewardedAdManager = RewardedInterstitialManager.getInstance(requireContext())
        rewardedAdManager.preload()
        updateCreditsDisplay()

        val isPremium = BillingManager.getInstance(requireContext()).isUserSubscribed()
        if (!isPremium) {
            loadBannerAd()
        }

        binding.tvCredits.setOnClickListener { showCreditsInfoDialog() }
        binding.ivHelp.setOnClickListener { findNavController().navigate(R.id.helpFragment) }

        binding.btnDownload.setOnClickListener {
            val url = binding.etUrl.text?.toString()?.trim() ?: ""
            viewModel.fetchMedia(url)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.scrapeState.collect { state ->
                when (state) {
                    is ScrapeState.Idle -> {
                        binding.progressIndicator.visibility = View.GONE
                        binding.btnDownload.isEnabled = true
                    }
                    is ScrapeState.Loading -> {
                        binding.progressIndicator.visibility = View.VISIBLE
                        binding.progressIndicator.isIndeterminate = true
                        binding.tvStatus.visibility = View.VISIBLE
                        binding.tvStatus.text = "Fetching media info…"
                        binding.btnDownload.isEnabled = false
                    }
                    is ScrapeState.Success -> {
                        binding.btnDownload.isEnabled = true
                        binding.progressIndicator.visibility = View.GONE
                        val info = state.info
                        binding.tvStatus.visibility = View.VISIBLE
                        binding.tvStatus.text = "@${info.username} · ${info.mediaType.replaceFirstChar { it.uppercase() }}"
                        pendingMediaInfo = info
                        // Gate: show rewarded ad first if 0 credits, then start download
                        val isPremiumNow = BillingManager.getInstance(requireContext()).isUserSubscribed()
                        rewardedAdManager.gateDownload(requireActivity(), isPremiumNow,
                            onCreditsUpdated = { updateCreditsDisplay() },
                            onProceed = { proceedWithPermissionCheck(info) }
                        )
                        viewModel.reset()
                    }
                    is ScrapeState.AlreadyDownloaded -> {
                        binding.btnDownload.isEnabled = true
                        binding.progressIndicator.visibility = View.GONE
                        binding.tvStatus.visibility = View.VISIBLE
                        binding.tvStatus.text = "Already downloaded ✓"
                        Snackbar.make(binding.root, "This post is already in your library", Snackbar.LENGTH_LONG).show()
                        viewModel.reset()
                    }
                    is ScrapeState.Error -> {
                        binding.btnDownload.isEnabled = true
                        binding.progressIndicator.visibility = View.GONE
                        binding.tvStatus.visibility = View.VISIBLE
                        binding.tvStatus.text = state.message
                        Snackbar.make(binding.root, state.message, Snackbar.LENGTH_LONG).show()
                        viewModel.reset()
                    }
                }
            }
        }
    }

    private fun proceedWithPermissionCheck(info: MediaInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            enqueueDownload(info)
            return
        }
        val perm = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(requireContext(), perm) == PackageManager.PERMISSION_GRANTED) {
            enqueueDownload(info)
        } else {
            pendingMediaInfo = info
            permissionLauncher.launch(arrayOf(perm))
        }
    }

    private fun enqueueDownload(info: MediaInfo) {
        val timestamp = System.currentTimeMillis()
        val originalUrl = binding.etUrl.text?.toString()?.trim() ?: ""

        val itemsToDownload: List<Triple<String, String, String>> = when {
            info.mediaType == "carousel" && info.carouselItems.isNotEmpty() -> {
                info.carouselItems.mapIndexedNotNull { i, item ->
                    val url = item.videoUrl ?: item.imageUrl ?: return@mapIndexedNotNull null
                    val ext = if (item.mediaType == "video") "mp4" else "jpg"
                    val type = if (item.mediaType == "video") "VIDEO" else "IMAGE"
                    Triple(url, "InstaGet_${timestamp}_${i + 1}.$ext", type)
                }
            }
            info.videoUrl != null -> listOf(Triple(info.videoUrl, "InstaGet_${timestamp}.mp4", "VIDEO"))
            info.mediaType == "video" -> {
                // Video identified but URL missing — don't silently download the thumbnail
                Snackbar.make(binding.root, "Could not get video URL — Instagram may require login for this content", Snackbar.LENGTH_LONG).show()
                return
            }
            info.imageUrl != null -> listOf(Triple(info.imageUrl, "InstaGet_${timestamp}.jpg", "IMAGE"))
            else -> {
                Snackbar.make(binding.root, "No downloadable URL found", Snackbar.LENGTH_LONG).show()
                return
            }
        }

        binding.progressIndicator.isIndeterminate = false
        binding.progressIndicator.progress = 0
        binding.progressIndicator.visibility = View.VISIBLE
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = "Downloading…"

        val workManager = WorkManager.getInstance(requireContext())
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val isPremium = BillingManager.getInstance(requireContext()).isUserSubscribed()

        itemsToDownload.forEachIndexed { index, (url, filename, mediaType) ->
            val isLastItem = index == itemsToDownload.lastIndex
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .setInputData(
                    workDataOf(
                        DownloadWorker.KEY_MEDIA_URL to url,
                        DownloadWorker.KEY_FILENAME to filename,
                        DownloadWorker.KEY_SHORTCODE to info.shortcode,
                        DownloadWorker.KEY_ORIGINAL_URL to originalUrl,
                        DownloadWorker.KEY_MEDIA_TYPE to mediaType,
                        DownloadWorker.KEY_THUMBNAIL_URL to (info.thumbnailUrl ?: ""),
                        DownloadWorker.KEY_USERNAME to info.username,
                        DownloadWorker.KEY_CAPTION to info.caption,
                        // 1 credit per download action, not per carousel item — charged by the
                        // worker itself (atomic with the save) on whichever item finishes last.
                        DownloadWorker.KEY_CONSUME_CREDIT to isLastItem,
                        DownloadWorker.KEY_IS_PREMIUM to isPremium
                    )
                )
                .build()

            workManager.enqueue(request)

            if (isLastItem) {
                workManager.getWorkInfoByIdLiveData(request.id)
                    .observe(viewLifecycleOwner) { workInfo ->
                        if (workInfo == null) return@observe
                        when (workInfo.state) {
                            WorkInfo.State.RUNNING -> {
                                val pct = workInfo.progress.getInt(DownloadWorker.KEY_PROGRESS, 0)
                                binding.progressIndicator.progress = pct
                                binding.tvStatus.text = "Downloading… $pct%"
                            }
                            WorkInfo.State.SUCCEEDED -> {
                                binding.progressIndicator.visibility = View.GONE
                                val totalCount = itemsToDownload.size
                                val msg = if (totalCount > 1)
                                    "Saved $totalCount files to DCIM/InstaGet ✓"
                                else
                                    "Saved to DCIM/InstaGet ✓"
                                binding.tvStatus.visibility = View.VISIBLE
                                binding.tvStatus.text = msg
                                binding.etUrl.text?.clear()
                                Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                                updateCreditsDisplay()
                            }
                            WorkInfo.State.FAILED -> {
                                binding.progressIndicator.visibility = View.GONE
                                val err = workInfo.outputData.getString("error") ?: "Download failed"
                                binding.tvStatus.visibility = View.VISIBLE
                                binding.tvStatus.text = err
                                Snackbar.make(binding.root, err, Snackbar.LENGTH_LONG).show()
                            }
                            else -> Unit
                        }
                    }
            }
        }
    }

    private fun showCreditsInfoDialog() {
        val isPremium = BillingManager.getInstance(requireContext()).isUserSubscribed()
        val balance = CreditsManager.getCredits(requireContext())
        val message = if (isPremium) {
            "You're a Premium Member — enjoy unlimited downloads with no ads! 🎉"
        } else {
            val statusLine = when {
                balance > 3 -> "You have $balance credits. You're good to go! 👍"
                balance > 0 -> "You have $balance credit${if (balance == 1) "" else "s"} left — running low!"
                else        -> "You're out of credits. Watch a short ad after your next download to earn more."
            }
            """$statusLine

Each download uses 1 credit. When you run out, a short ad plays after the download — watch it fully to earn 2 more credits.

You started with 10 free credits. Credits are yours to keep — they never expire.

Go Premium to skip ads entirely and download without limits.
"""
        }
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.RoundedAlertDialog)
            .setTitle("⚡ Download Credits")
            .setMessage(message)
            .setPositiveButton("Got it", null)
        if (!isPremium) {
            builder.setNegativeButton("Go Premium") { _, _ ->
                requireActivity()
                    .findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
                        R.id.bottomNavigationView
                    )?.selectedItemId = R.id.premiumFragment
            }
        }
        val dialog = builder.show()
        // Style Go Premium button: purple background, white text
        if (!isPremium) {
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.apply {
                setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.colorPrimary))
                setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
            }
        }
    }

    private fun updateCreditsDisplay() {
        val isPremium = BillingManager.getInstance(requireContext()).isUserSubscribed()
        binding.tvCredits.text = "⚡ ${CreditsManager.displayString(requireContext(), isPremium)}"
    }

    override fun onResume() {
        super.onResume()
        updateCreditsDisplay()
        // Pick up any Instagram URL shared to the app via the share sheet
        val prefs = requireContext().getSharedPreferences("share_prefs", Context.MODE_PRIVATE)
        val pendingUrl = prefs.getString("pending_ig_url", null)
        if (!pendingUrl.isNullOrBlank()) {
            prefs.edit().remove("pending_ig_url").apply()
            binding.etUrl.setText(pendingUrl)
            binding.root.post { binding.btnDownload.performClick() }
        }
    }

    private fun loadBannerAd() {
        // Official Google approach: create AdView in code, set adUnitId FIRST,
        // then setAdSize, then addView to container, then loadAd.
        val adWidth = (resources.displayMetrics.widthPixels /
                resources.displayMetrics.density).toInt()
        val adSize = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(requireContext(), adWidth)
        val adView = com.google.android.gms.ads.AdView(requireContext()).apply {
            adUnitId = AdConfig.BANNER_IG
            setAdSize(adSize)
            adListener = object : AdListener() {
                override fun onAdLoaded() {
                    bannerRetryAttempt = 0
                    Log.d(TAG, "Banner loaded")
                }
                override fun onAdFailedToLoad(err: LoadAdError) {
                    Log.w(TAG, "Banner failed to load: code=${err.code} message=${err.message}")
                    scheduleBannerRetry()
                }
            }
        }
        bannerAdView = adView
        binding.adContainer.removeAllViews()
        binding.adContainer.addView(adView)
        binding.adContainer.visibility = View.VISIBLE
        adView.loadAd(AdRequest.Builder().build())
    }

    private fun scheduleBannerRetry() {
        val delay = BANNER_RETRY_DELAYS_MS[bannerRetryAttempt.coerceAtMost(BANNER_RETRY_DELAYS_MS.lastIndex)]
        bannerRetryAttempt++
        val runnable = Runnable {
            if (_binding == null) return@Runnable
            bannerAdView?.loadAd(AdRequest.Builder().build())
        }
        bannerRetryRunnable = runnable
        bannerRetryHandler.postDelayed(runnable, delay)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        bannerRetryRunnable?.let { bannerRetryHandler.removeCallbacks(it) }
        bannerRetryRunnable = null
        bannerAdView?.destroy()
        bannerAdView = null
        _binding = null
    }
}
