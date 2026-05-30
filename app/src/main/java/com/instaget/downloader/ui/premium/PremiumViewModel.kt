package com.instaget.downloader.ui.premium

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.instaget.downloader.billing.BillingManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SubscriptionState {
    LOADING, FREE, SUBSCRIBED_MONTHLY, SUBSCRIBED_SEMI_ANNUAL, SUBSCRIBED_ANNUAL
}

class PremiumViewModel(application: Application) : AndroidViewModel(application) {

    private val billingManager = BillingManager.getInstance(application)

    private val _subscriptionState = MutableStateFlow(SubscriptionState.LOADING)
    val subscriptionState: StateFlow<SubscriptionState> = _subscriptionState.asStateFlow()

    private val _billingLoading = MutableStateFlow(false)
    val billingLoading: StateFlow<Boolean> = _billingLoading.asStateFlow()

    init {
        viewModelScope.launch {
            billingManager.activeSkuFlow.collect { sku ->
                _subscriptionState.value = skuToState(sku)
            }
        }
        refreshSubscriptionState()
    }

    private fun skuToState(sku: String) = when (sku) {
        BillingManager.ANNUAL_SKU -> SubscriptionState.SUBSCRIBED_ANNUAL
        BillingManager.SEMI_ANNUAL_SKU -> SubscriptionState.SUBSCRIBED_SEMI_ANNUAL
        BillingManager.MONTHLY_SKU -> SubscriptionState.SUBSCRIBED_MONTHLY
        else -> SubscriptionState.FREE
    }

    private fun refreshSubscriptionState() {
        viewModelScope.launch {
            billingManager.queryActivePurchases()
            // activeSkuFlow collector above will update state automatically
        }
    }

    suspend fun fetchProductDetails() {
        _billingLoading.value = true
        try {
            billingManager.querySubscriptionDetails()
        } finally {
            _billingLoading.value = false
        }
    }

    fun getMonthlyProductDetails() = billingManager.getMonthlyProductDetails()
    fun getSemiAnnualProductDetails() = billingManager.getSemiAnnualProductDetails()
    fun getAnnualProductDetails() = billingManager.getAnnualProductDetails()

    fun restorePurchases(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val active = billingManager.queryActivePurchases()
            // activeSkuFlow collector updates state automatically
            onResult(active)
        }
    }
}
