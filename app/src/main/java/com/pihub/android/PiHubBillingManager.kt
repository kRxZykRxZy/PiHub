package com.pihub.android

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Google Play Billing for PiHub: Pro subscription + Lifetime one-time purchase. */
class PiHubBillingManager(context: Context) : BillingClient.PurchasesUpdatedListener {
    companion object {
        const val PRO = "pihub_pro"
        const val LIFETIME = "pihub_lifetime"
        const val PRO_PRICE = "£2.99/month"
        const val LIFETIME_PRICE = "£8.99"
    }

    data class Plan(val productId: String, val title: String, val description: String, val price: String, val offerToken: String?, val type: String)

    private val _plans = MutableStateFlow<List<Plan>>(emptyList())
    val plans: StateFlow<List<Plan>> = _plans.asStateFlow()
    private val _activeProductId = MutableStateFlow<String?>(null)
    val activeProductId: StateFlow<String?> = _activeProductId.asStateFlow()
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    private val billingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    init {
        billingClient.startConnection(object : BillingClient.BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) { queryPlans(); refreshPurchases() }
                else _message.value = "Google Play Billing unavailable: ${result.debugMessage}"
            }
            override fun onBillingServiceDisconnected() = Unit
        })
    }

    fun queryPlans() {
        if (!billingClient.isReady) return
        val products = listOf(
            QueryProductDetailsParams.Product.newBuilder().setProductId(PRO).setProductType(ProductType.SUBS).build(),
            QueryProductDetailsParams.Product.newBuilder().setProductId(LIFETIME).setProductType(ProductType.INAPP).build()
        )
        billingClient.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(products).build()) { result, response ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) { _message.value = result.debugMessage; return@queryProductDetailsAsync }
            _plans.value = response.productDetailsList.mapNotNull { product ->
                if (product.productType == ProductType.SUBS) {
                    val offer = product.subscriptionOfferDetails?.firstOrNull() ?: return@mapNotNull null
                    val price = offer.pricingPhases.pricingPhaseList.lastOrNull()?.formattedPrice ?: return@mapNotNull null
                    Plan(product.productId, product.title, product.description, price, offer.offerToken, "subscription")
                } else {
                    val price = product.oneTimePurchaseOfferDetails?.formattedPrice ?: return@mapNotNull null
                    Plan(product.productId, product.title, product.description, price, null, "lifetime")
                }
            }
        }
    }

    fun buy(activity: Activity, productId: String) {
        val type = if (productId == LIFETIME) ProductType.INAPP else ProductType.SUBS
        billingClient.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(listOf(
            QueryProductDetailsParams.Product.newBuilder().setProductId(productId).setProductType(type).build()
        )).build()) { result, response ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) { _message.value = result.debugMessage; return@queryProductDetailsAsync }
            val details = response.productDetailsList.firstOrNull() ?: run { _message.value = "Plan unavailable on Google Play."; return@queryProductDetailsAsync }
            val params = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details)
            if (type == ProductType.SUBS) {
                val offer = details.subscriptionOfferDetails?.firstOrNull() ?: run { _message.value = "Pro subscription unavailable on Google Play."; return@queryProductDetailsAsync }
                params.setOfferToken(offer.offerToken)
            }
            billingClient.launchBillingFlow(activity, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(params.build())).build())
        }
    }

    fun refreshPurchases() {
        if (!billingClient.isReady) return
        billingClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(ProductType.SUBS).build()) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) applyPurchases(purchases)
        }
        billingClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build()) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                purchases.forEach(::processPurchase)
                if (purchases.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }) _activeProductId.value = LIFETIME
            }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> { purchases.orEmpty().forEach(::processPurchase); _message.value = "PiHub purchase updated." }
            BillingClient.BillingResponseCode.USER_CANCELED -> _message.value = "Purchase cancelled."
            else -> _message.value = result.debugMessage.ifBlank { "Google Play purchase failed." }
        }
    }

    private fun applyPurchases(purchases: List<Purchase>) {
        purchases.forEach(::processPurchase)
        purchases.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }?.products?.firstOrNull()?.let { _activeProductId.value = it }
    }

    private fun processPurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED || purchase.isAcknowledged) return
        billingClient.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()) { result ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) _message.value = "Purchase acknowledgement failed: ${result.debugMessage}"
        }
    }

    fun close() = billingClient.endConnection()
}
