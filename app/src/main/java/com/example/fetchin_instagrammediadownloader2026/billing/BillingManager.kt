package com.example.fetchin_instagrammediadownloader2026.billing

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import com.android.billingclient.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class BillingManager private constructor(private val context: Context) {

    companion object {
        const val MONTHLY_SKU = "instaget_pro_monthly"
        const val ANNUAL_SKU = "instaget_pro_annual"
        private const val PREF_NAME = "billing_prefs"
        private const val KEY_IS_SUBSCRIBED = "is_subscribed"

        @Volatile
        private var INSTANCE: BillingManager? = null

        fun getInstance(context: Context): BillingManager {
            return INSTANCE ?: synchronized(this) {
                BillingManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private val _isSubscribedFlow = MutableStateFlow(prefs.getBoolean(KEY_IS_SUBSCRIBED, false))
    val isSubscribedFlow: StateFlow<Boolean> = _isSubscribedFlow.asStateFlow()

    private var cachedSubscribed: Boolean? = null
    private var monthlyProductDetails: ProductDetails? = null
    private var annualProductDetails: ProductDetails? = null

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            CoroutineScope(Dispatchers.IO).launch {
                purchases.forEach { handlePurchase(it) }
            }
        }
    }

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases()
        .build()

    init {
        connectBillingClient()
    }

    private fun connectBillingClient() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    CoroutineScope(Dispatchers.IO).launch {
                        queryActivePurchases()
                    }
                }
            }

            override fun onBillingServiceDisconnected() {
                connectBillingClient()
            }
        })
    }

    suspend fun querySubscriptionDetails(): List<ProductDetails> {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(MONTHLY_SKU)
                .setProductType(BillingClient.ProductType.SUBS)
                .build(),
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(ANNUAL_SKU)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        return suspendCoroutine { cont ->
            billingClient.queryProductDetailsAsync(params) { _, productDetailsList ->
                productDetailsList.forEach { pd ->
                    when (pd.productId) {
                        MONTHLY_SKU -> monthlyProductDetails = pd
                        ANNUAL_SKU -> annualProductDetails = pd
                    }
                }
                cont.resume(productDetailsList)
            }
        }
    }

    fun launchPurchaseFlow(activity: Activity, productDetails: ProductDetails, offerToken: String?) {
        val offerDetails = productDetails.subscriptionOfferDetails
        val selectedOffer = if (offerToken != null) {
            offerDetails?.firstOrNull { it.offerToken == offerToken }
        } else {
            offerDetails?.firstOrNull()
        }

        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails)
                .apply { selectedOffer?.let { setOfferToken(it.offerToken) } }
                .build()
        )
        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        billingClient.launchBillingFlow(activity, billingFlowParams)
    }

    fun getMonthlyProductDetails(): ProductDetails? = monthlyProductDetails
    fun getAnnualProductDetails(): ProductDetails? = annualProductDetails

    suspend fun queryActivePurchases(): Boolean {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        return suspendCoroutine { cont ->
            billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    val active = purchases.any { purchase ->
                        purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                                (purchase.products.contains(MONTHLY_SKU) ||
                                        purchase.products.contains(ANNUAL_SKU))
                    }
                    cachedSubscribed = active
                    prefs.edit().putBoolean(KEY_IS_SUBSCRIBED, active).apply()
                    _isSubscribedFlow.value = active
                    cont.resume(active)
                } else {
                    val cached = prefs.getBoolean(KEY_IS_SUBSCRIBED, false)
                    cont.resume(cached)
                }
            }
        }
    }

    suspend fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            if (!purchase.isAcknowledged) {
                val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                suspendCoroutine<Unit> { cont ->
                    billingClient.acknowledgePurchase(acknowledgePurchaseParams) {
                        cont.resume(Unit)
                    }
                }
            }
            cachedSubscribed = true
            prefs.edit().putBoolean(KEY_IS_SUBSCRIBED, true).apply()
            _isSubscribedFlow.value = true
        }
    }

    fun isUserSubscribed(): Boolean {
        return cachedSubscribed ?: prefs.getBoolean(KEY_IS_SUBSCRIBED, false)
    }
}
