package com.instaget.downloader.ui.premium

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.instaget.downloader.R
import com.instaget.downloader.billing.BillingManager
import com.instaget.downloader.databinding.FragmentPremiumBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class PremiumFragment : Fragment() {

    private var _binding: FragmentPremiumBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PremiumViewModel by viewModels()

    private enum class SelectedPlan { MONTHLY, SEMI_ANNUAL, ANNUAL }
    private var selectedPlan = SelectedPlan.ANNUAL

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPremiumBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Default: Annual selected
        updatePlanSelection()

        // Observe subscription state
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.subscriptionState.collect { state ->
                renderSubscriptionState(state)
            }
        }

        // Observe billing loading
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.billingLoading.collect { loading ->
                binding.billingLoadingIndicator.visibility = if (loading) View.VISIBLE else View.GONE
            }
        }

        // Fetch prices from Play Store and update UI dynamically
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.fetchProductDetails()
            viewModel.getMonthlyProductDetails()
                ?.subscriptionOfferDetails?.firstOrNull()
                ?.pricingPhases?.pricingPhaseList?.firstOrNull()
                ?.formattedPrice
                ?.let { binding.tvMonthlyPrice.text = it }
            viewModel.getAnnualProductDetails()
                ?.subscriptionOfferDetails?.firstOrNull()
                ?.pricingPhases?.pricingPhaseList?.firstOrNull()
                ?.formattedPrice
                ?.let { binding.tvAnnualPrice.text = it }
        }

        // Card tap — select plan
        binding.cardMonthly.setOnClickListener {
            selectedPlan = SelectedPlan.MONTHLY
            updatePlanSelection()
        }
        binding.cardSemiAnnual.setOnClickListener {
            selectedPlan = SelectedPlan.SEMI_ANNUAL
            updatePlanSelection()
        }
        binding.cardAnnual.setOnClickListener {
            selectedPlan = SelectedPlan.ANNUAL
            updatePlanSelection()
        }

        // Single subscribe button
        binding.btnSubscribe.setOnClickListener {
            lifecycleScope.launch {
                viewModel.fetchProductDetails()
                val productDetails = when (selectedPlan) {
                    SelectedPlan.MONTHLY -> viewModel.getMonthlyProductDetails()
                    SelectedPlan.SEMI_ANNUAL -> viewModel.getSemiAnnualProductDetails()
                    SelectedPlan.ANNUAL -> viewModel.getAnnualProductDetails()
                }
                if (productDetails != null) {
                    BillingManager.getInstance(requireContext())
                        .launchPurchaseFlow(requireActivity(), productDetails, null)
                } else {
                    Snackbar.make(
                        binding.root,
                        "Unable to connect to Play Store. Try again.",
                        Snackbar.LENGTH_LONG
                    ).show()
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

    /** Highlights the selected card and updates the subscribe button text. */
    private fun updatePlanSelection() {
        val primary = requireContext().getColor(R.color.colorPrimary)
        binding.cardMonthly.apply {
            strokeColor = if (selectedPlan == SelectedPlan.MONTHLY) primary else 0
            strokeWidth = if (selectedPlan == SelectedPlan.MONTHLY) 6 else 0
        }
        binding.cardSemiAnnual.apply {
            strokeColor = if (selectedPlan == SelectedPlan.SEMI_ANNUAL) primary else 0
            strokeWidth = if (selectedPlan == SelectedPlan.SEMI_ANNUAL) 6 else 0
        }
        binding.cardAnnual.apply {
            strokeColor = if (selectedPlan == SelectedPlan.ANNUAL) primary else 0
            strokeWidth = if (selectedPlan == SelectedPlan.ANNUAL) 6 else 0
        }
        binding.btnSubscribe.isEnabled = true
        binding.btnSubscribe.text = when (selectedPlan) {
            SelectedPlan.MONTHLY -> "Subscribe Monthly"
            SelectedPlan.SEMI_ANNUAL -> "Subscribe Semi-annually"
            SelectedPlan.ANNUAL -> "Subscribe Annually"
        }
    }

    private fun renderSubscriptionState(state: SubscriptionState) {
        val primary = requireContext().getColor(R.color.colorPrimary)
        when (state) {
            SubscriptionState.LOADING -> {
                binding.billingLoadingIndicator.visibility = View.VISIBLE
            }
            SubscriptionState.FREE -> {
                binding.billingLoadingIndicator.visibility = View.GONE
                updatePlanSelection()
            }
            SubscriptionState.SUBSCRIBED_MONTHLY -> {
                binding.billingLoadingIndicator.visibility = View.GONE
                binding.cardMonthly.strokeColor = primary
                binding.cardMonthly.strokeWidth = 6
                binding.cardSemiAnnual.strokeWidth = 0
                binding.cardAnnual.strokeWidth = 0
                binding.btnSubscribe.text = "Monthly Plan Active"
                binding.btnSubscribe.isEnabled = false
            }
            SubscriptionState.SUBSCRIBED_SEMI_ANNUAL -> {
                binding.billingLoadingIndicator.visibility = View.GONE
                binding.cardMonthly.strokeWidth = 0
                binding.cardSemiAnnual.strokeColor = primary
                binding.cardSemiAnnual.strokeWidth = 6
                binding.cardAnnual.strokeWidth = 0
                binding.btnSubscribe.text = "Semi-annual Plan Active"
                binding.btnSubscribe.isEnabled = false
            }
            SubscriptionState.SUBSCRIBED_ANNUAL -> {
                binding.billingLoadingIndicator.visibility = View.GONE
                binding.cardMonthly.strokeWidth = 0
                binding.cardSemiAnnual.strokeWidth = 0
                binding.cardAnnual.strokeColor = primary
                binding.cardAnnual.strokeWidth = 6
                binding.btnSubscribe.text = "Annual Plan Active"
                binding.btnSubscribe.isEnabled = false
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
