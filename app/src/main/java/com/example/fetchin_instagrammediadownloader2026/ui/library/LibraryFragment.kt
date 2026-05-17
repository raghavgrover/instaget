package com.example.fetchin_instagrammediadownloader2026.ui.library

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.example.fetchin_instagrammediadownloader2026.FullScreenViewerActivity
import com.example.fetchin_instagrammediadownloader2026.R
import com.example.fetchin_instagrammediadownloader2026.databinding.FragmentLibraryBinding
import kotlinx.coroutines.launch

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MediaViewModel by viewModels()
    private lateinit var adapter: MediaAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = MediaAdapter(
            onItemClick = { libraryItem -> openFullScreen(libraryItem) },
            onItemLongClick = { libraryItem -> showDeleteDialog(libraryItem) }
        )

        binding.recyclerView.layoutManager =
            StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        binding.recyclerView.adapter = adapter

        binding.chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            when {
                checkedIds.contains(R.id.chipAll) -> viewModel.loadAll()
                checkedIds.contains(R.id.chipPhotos) -> viewModel.loadByType("IMAGE")
                checkedIds.contains(R.id.chipVideos) -> viewModel.loadByType("VIDEO")
            }
        }

        binding.btnStartDownloading.setOnClickListener {
            findNavController().navigate(R.id.homeFragment)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.libraryItems.collect { items ->
                adapter.submitList(items)
                if (items.isEmpty()) {
                    binding.emptyState.visibility = View.VISIBLE
                    binding.recyclerView.visibility = View.GONE
                } else {
                    binding.emptyState.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun openFullScreen(libraryItem: LibraryItem) {
        val item = libraryItem.mediaItem
        val intent = Intent(requireContext(), FullScreenViewerActivity::class.java).apply {
            putExtra(FullScreenViewerActivity.EXTRA_MEDIA_ID, item.id)
            putExtra(FullScreenViewerActivity.EXTRA_LOCAL_PATH, item.localPath)
            putExtra(FullScreenViewerActivity.EXTRA_MEDIA_TYPE, item.mediaType)
            if (libraryItem.count > 1 && item.shortcode.isNotBlank()) {
                putExtra(FullScreenViewerActivity.EXTRA_SHORTCODE, item.shortcode)
            }
        }
        startActivity(intent)
    }

    private fun showDeleteDialog(libraryItem: LibraryItem) {
        val item = libraryItem.mediaItem
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.label_delete_confirm)
            .setPositiveButton(R.string.label_delete) { _, _ ->
                viewModel.delete(item)
            }
            .setNegativeButton(R.string.label_cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
