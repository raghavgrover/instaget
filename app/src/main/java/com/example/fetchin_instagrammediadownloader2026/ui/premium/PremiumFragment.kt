package com.example.fetchin_instagrammediadownloader2026.ui.premium

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.fetchin_instagrammediadownloader2026.R
import com.example.fetchin_instagrammediadownloader2026.billing.BillingManager
import com.example.fetchin_instagrammediadownloader2026.databinding.FragmentPremiumBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class PremiumFragment : Fragment() {

    private var _binding: FragmentPremiumBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PremiumViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPremiumBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupFeatureTexts()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.subscriptionState.collect { state ->
                renderSubscriptionState(state)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.billingLoading.collect { loading ->
                binding.billingLoadingIndicator.visibility = if (loading) View.VISIBLE else View.GONE
            }
        }

        binding.btnMonthly.setOnClickListener {
            lifecycleScope.launch {
                viewModel.fetchProductDetails()
                val productDetails = viewModel.getMonthlyProductDetails()
                if (productDetails != null) {
                    BillingManager.getInstance(requireContext())
                        .launchPurchaseFlow(requireActivity(), productDetails, null)
                } else {
                    Snackbar.make(binding.root, "Unable to connect to Play Store. Try again.", Snackbar.LENGTH_LONG).show()
                }
            }
        }

        binding.btnAnnual.setOnClickListener {
            lifecycleScope.launch {
                viewModel.fetchProductDetails()
                val productDetails = viewModel.getAnnualProductDetails()
                if (productDetails != null) {
                    BillingManager.getInstance(requireContext())
                        .launchPurchaseFlow(requireActivity(), productDetails, null)
                } else {
                    Snackbar.make(binding.root, "Unable to connect to Play Store. Try again.", Snackbar.LENGTH_LONG).show()
                }
            }
        }

        binding.btnRestore.setOnClickListener {
            viewModel.restorePurchases { active ->
                val msg = if (active) {
                    getString(R.string.label_subscription_restored)
                } else {
                    getString(R.string.label_no_active_subscription)
                }
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupFeatureTexts() {
        binding.featureFree1.tvFeatureText.text = "5 lifetime downloads"
        binding.featureFree2.tvFeatureText.text = "Photos, Videos, Reels, Stories"
        binding.featureFree3.tvFeatureText.text = "Carousel / batch download"

        binding.featureMonthly1.tvFeatureText.text = "Unlimited downloads"
        binding.featureMonthly2.tvFeatureText.text = "Photos, Videos, Reels, Stories"
        binding.featureMonthly3.tvFeatureText.text = "Carousel / batch download"

        binding.featureAnnual1.tvFeatureText.text = "Unlimited downloads"
        binding.featureAnnual2.tvFeatureText.text = "Photos, Videos, Reels, Stories"
        binding.featureAnnual3.tvFeatureText.text = "Carousel / batch download"
    }

    private fun renderSubscriptionState(state: SubscriptionState) {
        val primaryColor = requireContext().getColor(R.color.colorPrimary)
        val defaultColor = requireContext().getColor(R.color.surface)
        val selectedBorder = 2f

        binding.cardFree.strokeWidth = 0
        binding.cardMonthly.strokeWidth = 0
        binding.cardAnnual.strokeWidth = 0

        when (state) {
            SubscriptionState.LOADING -> {
                binding.billingLoadingIndicator.visibility = View.VISIBLE
            }
            SubscriptionState.FREE -> {
                binding.billingLoadingIndicator.visibility = View.GONE
                binding.cardFree.strokeColor = primaryColor
                binding.cardFree.strokeWidth = 6
                binding.cardFree.cardElevation = 8f
                binding.btnFreePlan.isEnabled = false
                binding.btnMonthly.isEnabled = true
                binding.btnAnnual.isEnabled = true
            }
            SubscriptionState.SUBSCRIBED_MONTHLY -> {
                binding.billingLoadingIndicator.visibility = View.GONE
                binding.cardMonthly.strokeColor = primaryColor
                binding.cardMonthly.strokeWidth = 6
                binding.cardMonthly.cardElevation = 8f
                binding.btnMonthly.text = "Active Plan"
                binding.btnMonthly.isEnabled = false
                binding.btnFreePlan.isEnabled = false
                binding.btnAnnual.isEnabled = true
            }
            SubscriptionState.SUBSCRIBED_ANNUAL -> {
                binding.billingLoadingIndicator.visibility = View.GONE
                binding.cardAnnual.strokeColor = primaryColor
                binding.cardAnnual.strokeWidth = 6
                binding.cardAnnual.cardElevation = 8f
                binding.btnAnnual.text = "Active Plan"
                binding.btnAnnual.isEnabled = false
                binding.btnFreePlan.isEnabled = false
                binding.btnMonthly.isEnabled = true
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
