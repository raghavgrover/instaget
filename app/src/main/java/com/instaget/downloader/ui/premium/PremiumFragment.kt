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

    // Track previous subscription state to detect fresh purchases
    private var previousSubscriptionState: SubscriptionState? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPremiumBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Set subscribed header height to 25% of screen
        val screenHeight = resources.displayMetrics.heightPixels
        binding.subscribedHeader.layoutParams.height = (screenHeight * 0.25).toInt()

        // Default: Annual selected
        updatePlanSelection()

        // Observe subscription state — detect transitions for purchase success toast
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.subscriptionState.collect { state ->
                val prev = previousSubscriptionState
                if (prev == SubscriptionState.FREE && state.isSubscribed()) {
                    Snackbar.make(binding.root, "Subscription activated! Thank you.", Snackbar.LENGTH_LONG).show()
                }
                previousSubscriptionState = state
                renderSubscriptionState(state)
            }
        }

        // Observe billing loading indicator
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.billingLoading.collect { loading ->
                binding.billingLoadingIndicator.visibility = if (loading) View.VISIBLE else View.GONE
            }
        }

        // Fetch prices from Play Store and update all 3 tiles dynamically
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.fetchProductDetails()

            // Monthly
            viewModel.getMonthlyProductDetails()
                ?.subscriptionOfferDetails?.firstOrNull()
                ?.pricingPhases?.pricingPhaseList?.firstOrNull()
                ?.formattedPrice
                ?.let { binding.tvMonthlyPrice.text = it }

            // Semi-annual
            viewModel.getSemiAnnualProductDetails()
                ?.subscriptionOfferDetails?.firstOrNull()
                ?.pricingPhases?.pricingPhaseList?.firstOrNull()
                ?.formattedPrice
                ?.let { binding.tvSemiAnnualPrice.text = it }

            // Annual
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

        // Single subscribe button — launches Play Store billing flow
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
                    val msg = if (BillingManager.getInstance(requireContext()).let {
                            it.getMonthlyProductDetails() == null &&
                            it.getAnnualProductDetails() == null
                        }) {
                        "Subscriptions not set up yet in Play Console. Check back soon."
                    } else {
                        "Unable to connect to Play Store. Please try again."
                    }
                    Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                }
            }
        }

        binding.btnRestore.setOnClickListener {
            viewModel.restorePurchases { active ->
                val msg = if (active) getString(R.string.label_subscription_restored)
                          else getString(R.string.label_no_active_subscription)
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    /** Highlights the selected card and syncs the subscribe button label. */
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
                showFreeLayout()
                updatePlanSelection()
            }
            SubscriptionState.SUBSCRIBED_MONTHLY,
            SubscriptionState.SUBSCRIBED_SEMI_ANNUAL,
            SubscriptionState.SUBSCRIBED_ANNUAL -> {
                binding.billingLoadingIndicator.visibility = View.GONE
                showSubscribedLayout()
            }
        }
    }

    private fun showFreeLayout() {
        binding.freeHeader.visibility = View.VISIBLE
        binding.subscribedHeader.visibility = View.GONE
        binding.subscriptionContent.visibility = View.VISIBLE
    }

    private fun showSubscribedLayout() {
        binding.freeHeader.visibility = View.GONE
        binding.subscribedHeader.visibility = View.VISIBLE
        binding.subscriptionContent.visibility = View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/** Returns true for any active subscription state. */
private fun SubscriptionState.isSubscribed() =
    this == SubscriptionState.SUBSCRIBED_MONTHLY ||
    this == SubscriptionState.SUBSCRIBED_SEMI_ANNUAL ||
    this == SubscriptionState.SUBSCRIBED_ANNUAL
