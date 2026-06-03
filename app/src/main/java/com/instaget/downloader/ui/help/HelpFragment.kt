package com.instaget.downloader.ui.help

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.graphics.drawable.DrawableCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.instaget.downloader.databinding.FragmentHelpBinding

class HelpFragment : Fragment() {

    private var _binding: FragmentHelpBinding? = null
    private val binding get() = _binding!!

    // Track expanded state for each of the 4 FAQ tiles
    private val expanded = BooleanArray(4) { false }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHelpBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Back arrow — tint white programmatically (reliable on all OEMs)
        binding.toolbar.navigationIcon?.let {
            val tinted = DrawableCompat.wrap(it).mutate()
            DrawableCompat.setTint(tinted, Color.WHITE)
            binding.toolbar.navigationIcon = tinted
        }
        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        // Wire up all 4 FAQ tiles: (header, content, chevron)
        val tiles = listOf(
            Triple(binding.header1, binding.content1, binding.chevron1),
            Triple(binding.header2, binding.content2, binding.chevron2),
            Triple(binding.header3, binding.content3, binding.chevron3),
            Triple(binding.header4, binding.content4, binding.chevron4)
        )

        tiles.forEachIndexed { index, (header, content, chevron) ->
            header.setOnClickListener {
                expanded[index] = !expanded[index]
                val isOpen = expanded[index]
                content.visibility = if (isOpen) View.VISIBLE else View.GONE
                chevron.animate()
                    .rotation(if (isOpen) 180f else 0f)
                    .setDuration(200)
                    .start()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
