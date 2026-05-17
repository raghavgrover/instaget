package com.example.fetchin_instagrammediadownloader2026.ui.home

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.fetchin_instagrammediadownloader2026.data.MediaInfo
import com.example.fetchin_instagrammediadownloader2026.databinding.FragmentHomeBinding
import com.example.fetchin_instagrammediadownloader2026.worker.DownloadWorker
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by viewModels()
    private var pendingMediaInfo: MediaInfo? = null

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
                        checkPermissionsAndDownload(info)
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

    private fun checkPermissionsAndDownload(info: MediaInfo) {
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

        itemsToDownload.forEachIndexed { index, (url, filename, mediaType) ->
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(
                    workDataOf(
                        DownloadWorker.KEY_MEDIA_URL to url,
                        DownloadWorker.KEY_FILENAME to filename,
                        DownloadWorker.KEY_SHORTCODE to info.shortcode,
                        DownloadWorker.KEY_ORIGINAL_URL to originalUrl,
                        DownloadWorker.KEY_MEDIA_TYPE to mediaType,
                        DownloadWorker.KEY_THUMBNAIL_URL to (info.thumbnailUrl ?: "")
                    )
                )
                .build()

            workManager.enqueue(request)

            if (index == itemsToDownload.lastIndex) {
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
