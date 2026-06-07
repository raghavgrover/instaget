package com.instaget.downloader.ui.premium

import android.content.Intent
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
                ?.let { binding.tvMonthlyPrice.text = cleanPrice(it) }

            // Semi-annual
            viewModel.getSemiAnnualProductDetails()
                ?.subscriptionOfferDetails?.firstOrNull()
                ?.pricingPhases?.pricingPhaseList?.firstOrNull()
                ?.formattedPrice
                ?.let { binding.tvSemiAnnualPrice.text = cleanPrice(it) }

            // Annual
            viewModel.getAnnualProductDetails()
                ?.subscriptionOfferDetails?.firstOrNull()
                ?.pricingPhases?.pricingPhaseList?.firstOrNull()
                ?.formattedPrice
                ?.let { binding.tvAnnualPrice.text = cleanPrice(it) }
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

        binding.tvTerms.setOnClickListener { openWebView("Terms of Use", R.string.url_terms_of_use) }
        binding.tvPrivacy.setOnClickListener { openWebView("Privacy Policy", R.string.url_privacy_policy) }

        binding.btnRestore.setOnClickListener {
            viewModel.restorePurchases { active ->
                val msg = if (active) getString(R.string.label_subscription_restored)
                          else getString(R.string.label_no_active_subscription)
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
            }
        }

        binding.btnCancelSubscription.setOnClickListener {
            // Opens Google Play subscription management — user cancels there
            val uri = android.net.Uri.parse("https://play.google.com/store/account/subscriptions")
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
        }
    }

    override fun onResume() {
        super.onResume()
        // Always refresh from Play Store on every visit so cancellations are reflected immediately
        viewModel.refresh()
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
                showSubscribedLayout(state)
            }
        }
    }

    private fun showFreeLayout() {
        binding.freeHeader.visibility = View.VISIBLE
        binding.subscribedHeader.visibility = View.GONE
        binding.subscriptionContent.visibility = View.VISIBLE
        binding.btnCancelSubscription.visibility = View.GONE
    }

    private fun showSubscribedLayout(state: SubscriptionState) {
        binding.freeHeader.visibility = View.GONE
        binding.subscribedHeader.visibility = View.VISIBLE
        binding.subscriptionContent.visibility = View.GONE
        binding.btnCancelSubscription.visibility = View.VISIBLE

        // Show cancellation expiry notice only if subscription was cancelled by user
        if (viewModel.isCancelled()) {
            val purchaseTimeMs = viewModel.getLastPurchaseTimeMs()
            val periodMs = when (state) {
                SubscriptionState.SUBSCRIBED_SEMI_ANNUAL -> 180L * 24 * 60 * 60 * 1000
                SubscriptionState.SUBSCRIBED_ANNUAL      -> 365L * 24 * 60 * 60 * 1000
                else                                     -> 30L  * 24 * 60 * 60 * 1000
            }
            val expiryMs = purchaseTimeMs + periodMs
            val sdf = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
            val expiryDate = sdf.format(java.util.Date(expiryMs))
            binding.tvCancellationNotice.text =
                "Your subscription has been cancelled\nand will expire on ~$expiryDate"
            binding.tvCancellationNotice.visibility = View.VISIBLE
        } else {
            binding.tvCancellationNotice.visibility = View.GONE
        }
    }

    /**
     * Strip trailing zero-decimals from a Play Store formatted price.
     * Examples:  "₹149.00" → "₹149"   "$1.49" → "$1.49"   "€2,00" → "€2"
     * Locale-safe: handles both "." and "," as the decimal separator.
     */
    private fun cleanPrice(price: String): String {
        // Match a decimal separator followed by ONLY zeros, at the end of the price
        // (allow optional trailing currency code/symbol after the digits).
        return Regex("""([.,])0+(?=\D|$)""").replace(price, "")
    }

    private fun openWebView(title: String, urlResId: Int) {
        val url = getString(urlResId)
        startActivity(
            Intent(requireContext(), com.instaget.downloader.WebViewActivity::class.java).apply {
                putExtra(com.instaget.downloader.WebViewActivity.EXTRA_URL, url)
                putExtra(com.instaget.downloader.WebViewActivity.EXTRA_TITLE, title)
            }
        )
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
