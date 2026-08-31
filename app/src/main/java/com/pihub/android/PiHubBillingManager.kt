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

/** Google Play subscriptions used by PiHub. */
class PiHubBillingManager(context: Context) : BillingClient.PurchasesUpdatedListener {
    companion object {
        const val PRO = "pihub_pro"
        const val PRO_PLUS = "pihub_pro_plus"
    }

    data class Plan(
        val productId: String,
        val title: String,
        val description: String,
        val price: String,
        val offerToken: String?
    )

    private val _plans = MutableStateFlow<List<Plan>>(emptyList())
    val plans: StateFlow<List<Plan>> = _plans.asStateFlow()

    private val _activeProductId = MutableStateFlow<String?>(null)
    val activeProductId: StateFlow<String?> = _activeProductId.asStateFlow()

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    private val billingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .enableAutoServiceReconnection()
        .build()

    init {
        billingClient.startConnection(object : BillingClient.BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryPlans()
                    refreshPurchases()
                } else {
                    _message.value = "Google Play Billing unavailable: ${result.debugMessage}"
                }
            }
            override fun onBillingServiceDisconnected() = Unit
        })
    }

    fun queryPlans() {
        if (!billingClient.isReady) return
        val products = listOf(PRO, PRO_PLUS).map { id ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id)
                .setProductType(ProductType.SUBS)
                .build()
        }
        billingClient.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(products).build()
        ) { result, response ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                _message.value = result.debugMessage
                return@queryProductDetailsAsync
            }
            _plans.value = response.productDetailsList.mapNotNull { product ->
                val offer = product.subscriptionOfferDetails?.firstOrNull()
                val price = offer?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice
                    ?: return@mapNotNull null
                Plan(product.productId, product.title, product.description, price, offer.offerToken)
            }
        }
    }

    fun buy(activity: Activity, productId: String) {
        if (_activeProductId.value == productId) {
            _message.value = "You already have this PiHub plan."
            return
        }
        billingClient.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(productId)
                            .setProductType(ProductType.SUBS)
                            .build()
                    )
                ).build()
        ) { result, response ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                _message.value = result.debugMessage
                return@queryProductDetailsAsync
            }
            val details = response.productDetailsList.firstOrNull()
            val offer = details?.subscriptionOfferDetails?.firstOrNull()
            if (details == null || offer == null) {
                _message.value = "Plan unavailable on Google Play."
                return@queryProductDetailsAsync
            }
            val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .setOfferToken(offer.offerToken)
                .build()
            billingClient.launchBillingFlow(
                activity,
                BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(listOf(productParams))
                    .build()
            )
        }
    }

    fun refreshPurchases() {
        if (!billingClient.isReady) return
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(ProductType.SUBS).build()
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                applyPurchases(purchases)
            }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases.orEmpty().forEach(::processPurchase)
                _message.value = "PiHub subscription updated."
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> _message.value = "Purchase cancelled."
            else -> _message.value = result.debugMessage.ifBlank { "Google Play purchase failed." }
        }
    }

    private fun applyPurchases(purchases: List<Purchase>) {
        purchases.forEach(::processPurchase)
        _activeProductId.value = purchases.firstOrNull {
            it.purchaseState == Purchase.PurchaseState.PURCHASED
        }?.products?.firstOrNull()
    }

    private fun processPurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (!purchase.isAcknowledged) {
            billingClient.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
            ) { result ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    _message.value = "Purchase acknowledgement failed: ${result.debugMessage}"
                }
            }
        }
    }

    fun close() = billingClient.endConnection()
}
