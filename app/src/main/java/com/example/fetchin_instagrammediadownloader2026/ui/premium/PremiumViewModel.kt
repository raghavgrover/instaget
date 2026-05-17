package com.example.fetchin_instagrammediadownloader2026.ui.premium

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.fetchin_instagrammediadownloader2026.billing.BillingManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SubscriptionState {
    LOADING, FREE, SUBSCRIBED_MONTHLY, SUBSCRIBED_ANNUAL
}

class PremiumViewModel(application: Application) : AndroidViewModel(application) {

    private val billingManager = BillingManager.getInstance(application)

    private val _subscriptionState = MutableStateFlow(SubscriptionState.LOADING)
    val subscriptionState: StateFlow<SubscriptionState> = _subscriptionState.asStateFlow()

    private val _billingLoading = MutableStateFlow(false)
    val billingLoading: StateFlow<Boolean> = _billingLoading.asStateFlow()

    init {
        viewModelScope.launch {
            billingManager.isSubscribedFlow.collect { subscribed ->
                if (_subscriptionState.value == SubscriptionState.LOADING && !subscribed) {
                    _subscriptionState.value = SubscriptionState.FREE
                } else if (subscribed) {
                    _subscriptionState.value = SubscriptionState.SUBSCRIBED_MONTHLY
                }
            }
        }
        refreshSubscriptionState()
    }

    private fun refreshSubscriptionState() {
        viewModelScope.launch {
            val active = billingManager.queryActivePurchases()
            _subscriptionState.value = if (active) SubscriptionState.SUBSCRIBED_MONTHLY else SubscriptionState.FREE
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
    fun getAnnualProductDetails() = billingManager.getAnnualProductDetails()

    fun restorePurchases(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val active = billingManager.queryActivePurchases()
            _subscriptionState.value = if (active) SubscriptionState.SUBSCRIBED_MONTHLY else SubscriptionState.FREE
            onResult(active)
        }
    }
}
