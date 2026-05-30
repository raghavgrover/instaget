package com.instaget.downloader.ui.threads

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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
import com.google.android.material.snackbar.Snackbar
import com.instaget.downloader.R
import com.instaget.downloader.data.ThreadsPostInfo
import com.instaget.downloader.databinding.FragmentThreadsBinding
import com.instaget.downloader.worker.DownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ThreadsFragment : Fragment() {

    private var _binding: FragmentThreadsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ThreadsViewModel by viewModels()
    private var pendingInfo: ThreadsPostInfo? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            pendingInfo?.let { enqueueMediaDownload(it) }
        } else {
            Snackbar.make(binding.root, "Storage permission required to save files", Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentThreadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Paste button
        binding.tilUrl.setEndIconOnClickListener {
            val cb = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cb.primaryClip?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrBlank()) binding.etUrl.setText(text)
            else Snackbar.make(binding.root, "Clipboard is empty", Snackbar.LENGTH_SHORT).show()
        }

        // Fetch & Download
        binding.btnFetch.setOnClickListener {
            val url = binding.etUrl.text?.toString()?.trim() ?: ""
            viewModel.fetchPost(url)
        }

        // Observe state
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.scrapeState.collect { state ->
                when (state) {
                    is ThreadsScrapeState.Idle -> {
                        binding.progressIndicator.visibility = View.GONE
                        binding.btnFetch.isEnabled = true
                    }
                    is ThreadsScrapeState.Loading -> {
                        binding.progressIndicator.visibility = View.VISIBLE
                        binding.progressIndicator.isIndeterminate = true
                        binding.tvStatus.visibility = View.VISIBLE
                        binding.tvStatus.text = "Fetching post info…"
                        binding.btnFetch.isEnabled = false
                        binding.cardResult.visibility = View.GONE
                    }
                    is ThreadsScrapeState.Success -> {
                        binding.btnFetch.isEnabled = true
                        binding.progressIndicator.visibility = View.GONE
                        showResult(state.info)
                        viewModel.reset()
                    }
                    is ThreadsScrapeState.AlreadyDownloaded -> {
                        binding.btnFetch.isEnabled = true
                        binding.progressIndicator.visibility = View.GONE
                        binding.tvStatus.visibility = View.VISIBLE
                        binding.tvStatus.text = "Already downloaded ✓"
                        Snackbar.make(binding.root, "This post is already in your library", Snackbar.LENGTH_LONG).show()
                        viewModel.reset()
                    }
                    is ThreadsScrapeState.Error -> {
                        binding.btnFetch.isEnabled = true
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

    private fun showResult(info: ThreadsPostInfo) {
        // Update card content
        binding.tvUsername.text = if (info.username.isNotBlank()) "@${info.username}" else "Unknown user"

        val badge = when (info.mediaType) {
            "video" -> "VIDEO"
            "image" -> "PHOTO"
            "carousel" -> "CAROUSEL"
            else -> "TEXT"
        }
        binding.tvMediaType.text = badge

        if (info.text.isNotBlank()) {
            binding.tvPostText.text = info.text
            binding.tvPostText.visibility = View.VISIBLE
        } else {
            binding.tvPostText.visibility = View.GONE
        }

        binding.tvStatus.visibility = View.GONE
        binding.cardResult.visibility = View.VISIBLE

        when (info.mediaType) {
            "text" -> {
                // Text-only: show copy / save buttons
                binding.rowTextActions.visibility = View.VISIBLE
                binding.btnCopyText.setOnClickListener { copyTextToClipboard(info.text) }
                binding.btnSaveText.setOnClickListener { saveTextToFile(info) }
            }
            else -> {
                // Media post: trigger download immediately
                binding.rowTextActions.visibility = View.GONE
                pendingInfo = info
                checkPermissionsAndDownload(info)
            }
        }
    }

    private fun checkPermissionsAndDownload(info: ThreadsPostInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            enqueueMediaDownload(info)
        } else {
            val perm = Manifest.permission.WRITE_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(requireContext(), perm) == PackageManager.PERMISSION_GRANTED) {
                enqueueMediaDownload(info)
            } else {
                pendingInfo = info
                permissionLauncher.launch(arrayOf(perm))
            }
        }
    }

    private fun enqueueMediaDownload(info: ThreadsPostInfo) {
        val timestamp = System.currentTimeMillis()
        val originalUrl = binding.etUrl.text?.toString()?.trim() ?: ""

        val itemsToDownload: List<Triple<String, String, String>> = when {
            info.mediaType == "carousel" && info.imageUrls.size > 1 -> {
                info.imageUrls.mapIndexed { i, url ->
                    Triple(url, "Threads_${timestamp}_${i + 1}.jpg", "IMAGE")
                }
            }
            info.mediaType == "video" && info.videoUrl != null -> {
                listOf(Triple(info.videoUrl, "Threads_${timestamp}.mp4", "VIDEO"))
            }
            info.mediaType == "image" && info.imageUrls.isNotEmpty() -> {
                listOf(Triple(info.imageUrls.first(), "Threads_${timestamp}.jpg", "IMAGE"))
            }
            else -> {
                Snackbar.make(binding.root, "No downloadable media URL found", Snackbar.LENGTH_LONG).show()
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

        itemsToDownload.forEachIndexed { index, (url, filename, mediaType) ->
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .setInputData(
                    workDataOf(
                        DownloadWorker.KEY_MEDIA_URL to url,
                        DownloadWorker.KEY_FILENAME to filename,
                        DownloadWorker.KEY_SHORTCODE to "threads_${info.postId}",
                        DownloadWorker.KEY_ORIGINAL_URL to originalUrl,
                        DownloadWorker.KEY_MEDIA_TYPE to mediaType,
                        DownloadWorker.KEY_THUMBNAIL_URL to (info.videoThumbnailUrl ?: ""),
                        DownloadWorker.KEY_USERNAME to info.username,
                        DownloadWorker.KEY_CAPTION to info.text,
                        DownloadWorker.KEY_REFERER to "https://www.threads.net/",
                        DownloadWorker.KEY_COOKIE to info.sessionCookies
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
                                val total = itemsToDownload.size
                                val msg = if (total > 1) "Saved $total files to DCIM/InstaGet ✓"
                                          else "Saved to DCIM/InstaGet ✓"
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

    private fun copyTextToClipboard(text: String) {
        val cb = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("Threads post", text))
        Snackbar.make(binding.root, "Text copied to clipboard", Snackbar.LENGTH_SHORT).show()
    }

    private fun saveTextToFile(info: ThreadsPostInfo) {
        viewLifecycleOwner.lifecycleScope.launch {
            val filename = "Threads_${info.postId}.txt"
            val content = buildString {
                if (info.username.isNotBlank()) appendLine("@${info.username}")
                appendLine()
                append(info.text)
            }

            // Returns the saved URI/path string on success, null on failure
            val savedPath: String? = withContext(Dispatchers.IO) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // MediaStore.Downloads maps to the Download/ folder — RELATIVE_PATH must be under Download/
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, filename)
                            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                            put(MediaStore.Downloads.RELATIVE_PATH, "Download/InstaGet")
                        }
                        val uri = requireContext().contentResolver
                            .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        if (uri != null) {
                            requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                                out.write(content.toByteArray())
                            }
                            uri.toString()
                        } else {
                            android.util.Log.e("ThreadsFragment", "MediaStore insert returned null for $filename")
                            null
                        }
                    } else {
                        val dir = File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                            "InstaGet"
                        ).also { it.mkdirs() }
                        val file = File(dir, filename)
                        FileOutputStream(file).use { it.write(content.toByteArray()) }
                        file.absolutePath
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ThreadsFragment", "saveTextToFile error", e)
                    null
                }
            }

            if (savedPath != null) {
                // Save to Library so it appears in the Downloads tab
                viewModel.saveTextPostToLibrary(info, savedPath)
                Snackbar.make(binding.root, "Saved to Downloads/InstaGet ✓", Snackbar.LENGTH_LONG).show()
                binding.etUrl.text?.clear()
            } else {
                Snackbar.make(binding.root, "Failed to save file", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
