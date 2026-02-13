package com.almostbrilliantideas.easyipscanner

/**
 * BillingManager - Google Play Billing Library v7 Integration
 * ============================================================
 *
 * Complete implementation for one-time purchase "easyipscan_unlock" ($9.99 USD)
 *
 * FEATURES:
 * - Initialize billing connection on app start
 * - Query product details for easyipscan_unlock
 * - Launch purchase flow when triggered
 * - Process purchase results and acknowledge within 3 days
 * - Handle purchase restoration for reinstalls/device changes
 * - Sync successful purchases to Firebase (isPurchased = true)
 * - Cache purchase state locally in SharedPreferences for offline fallback
 * - Handle refunds by checking Google Play purchase state
 *
 * EDGE CASES HANDLED:
 * - Purchase pending (payment processing)
 * - Purchase cancelled (user backed out)
 * - Network errors (uses cached state from SharedPreferences)
 * - Refunds (purchase no longer returned by queryPurchases)
 *
 * LIFECYCLE:
 * - Call connect() in Activity.onCreate()
 * - Call disconnect() in Activity.onDestroy()
 * - Auto-reconnects on disconnect
 *
 * @see <a href="https://developer.android.com/google/play/billing">Google Play Billing Documentation</a>
 */

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Represents the connection state of the BillingClient.
 */
sealed class BillingState {
    object Disconnected : BillingState()
    object Connecting : BillingState()
    object Connected : BillingState()
    data class Error(val message: String) : BillingState()
}

sealed class PurchaseResult {
    object Success : PurchaseResult()
    object Pending : PurchaseResult()
    object Cancelled : PurchaseResult()
    object AlreadyOwned : PurchaseResult()
    data class Error(val message: String) : PurchaseResult()
}

/**
 * Manages all Google Play Billing operations for the app.
 *
 * Usage:
 * ```
 * val billingManager = BillingManager(context, trialManager, appPreferences)
 * billingManager.connect()
 *
 * // Observe purchase state
 * billingManager.isPurchased.collect { purchased ->
 *     if (purchased) unlockPremiumFeatures()
 * }
 *
 * // Launch purchase (call from Activity)
 * billingManager.launchPurchaseFlow(activity)
 *
 * // Cleanup
 * billingManager.disconnect()
 * ```
 */
class BillingManager(
    private val context: Context,
    private val trialManager: TrialManager? = null,
    private val appPreferences: AppPreferences? = null
) : PurchasesUpdatedListener {

    companion object {
        private const val TAG = "BillingManager"

        /**
         * Product ID for the one-time unlock purchase.
         * Must match EXACTLY with the product created in Play Console.
         */
        const val PRODUCT_ID = "easyipscan_unlock"

        // Retry delay for reconnection attempts
        private const val RECONNECT_DELAY_MS = 3000L
        private const val MAX_RECONNECT_ATTEMPTS = 3
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var reconnectAttempts = 0

    private val _billingState = MutableStateFlow<BillingState>(BillingState.Disconnected)
    val billingState: StateFlow<BillingState> = _billingState.asStateFlow()

    private val _purchaseResult = MutableStateFlow<PurchaseResult?>(null)
    val purchaseResult: StateFlow<PurchaseResult?> = _purchaseResult.asStateFlow()

    private val _isPurchased = MutableStateFlow(false)
    val isPurchased: StateFlow<Boolean> = _isPurchased.asStateFlow()

    private val _productPrice = MutableStateFlow<String?>(null)
    val productPrice: StateFlow<String?> = _productPrice.asStateFlow()

    private var productDetails: ProductDetails? = null

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    init {
        // Load cached purchase state immediately for fast UI
        scope.launch {
            val cachedPurchase = appPreferences?.getIsPurchasedSync() ?: false
            if (cachedPurchase) {
                _isPurchased.value = true
                Log.d(TAG, "Loaded cached purchase state: purchased=true")
            }
        }
    }

    /**
     * Establishes connection to Google Play Billing service.
     *
     * LIFECYCLE: Call this in Activity.onCreate() or when billing features are first needed.
     * The BillingClient will automatically:
     * - Query available products from Play Console
     * - Check for existing purchases (restore functionality)
     * - Handle reconnection if the service disconnects
     */
    fun connect() {
        if (_billingState.value == BillingState.Connected ||
            _billingState.value == BillingState.Connecting) {
            return
        }

        _billingState.value = BillingState.Connecting
        Log.d(TAG, "Connecting to Google Play Billing...")

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Billing client connected successfully")
                    _billingState.value = BillingState.Connected
                    reconnectAttempts = 0
                    queryProductDetails()
                    queryExistingPurchases()
                } else {
                    Log.e(TAG, "Billing setup failed: ${billingResult.debugMessage}")
                    _billingState.value = BillingState.Error(billingResult.debugMessage)
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.d(TAG, "Billing service disconnected")
                _billingState.value = BillingState.Disconnected

                // Attempt to reconnect with exponential backoff
                if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                    reconnectAttempts++
                    val delay = RECONNECT_DELAY_MS * reconnectAttempts
                    Log.d(TAG, "Scheduling reconnect attempt $reconnectAttempts in ${delay}ms")
                    scope.launch {
                        kotlinx.coroutines.delay(delay)
                        if (_billingState.value == BillingState.Disconnected) {
                            connect()
                        }
                    }
                }
            }
        })
    }

    private fun queryProductDetails() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                productDetails = productDetailsList.firstOrNull()
                if (productDetails != null) {
                    Log.d(TAG, "Product details loaded: ${productDetails?.name}")
                    // Extract and expose the formatted price
                    val price = productDetails?.oneTimePurchaseOfferDetails?.formattedPrice
                    _productPrice.value = price
                    Log.d(TAG, "Product price: $price")
                } else {
                    Log.w(TAG, "Product not found: $PRODUCT_ID. Make sure product is active in Play Console.")
                }
            } else {
                Log.e(TAG, "Failed to query product details: ${billingResult.debugMessage}")
            }
        }
    }

    /**
     * Checks for existing purchases and handles refund detection.
     *
     * This method:
     * - Queries all in-app purchases associated with the user's Google account
     * - Updates isPurchased state if the full version was previously bought
     * - Acknowledges any pending purchases (required within 3 days of purchase)
     * - Detects refunds: if Google Play returns no purchase but we had one cached,
     *   the purchase was likely refunded
     *
     * Called automatically on connect(), but can also be triggered via restorePurchases().
     */
    private fun queryExistingPurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchasesList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val validPurchase = purchasesList.find { purchase ->
                    purchase.products.contains(PRODUCT_ID) &&
                    purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                }

                val hasPurchase = validPurchase != null
                _isPurchased.value = hasPurchase
                Log.d(TAG, "Existing purchases checked: isPurchased=$hasPurchase")

                // Handle the result
                if (hasPurchase) {
                    // Acknowledge if needed and sync to Firebase + local cache
                    if (!validPurchase!!.isAcknowledged) {
                        acknowledgePurchase(validPurchase)
                    } else {
                        // Already acknowledged, just ensure sync
                        syncPurchaseState(true)
                    }
                } else {
                    // No valid purchase found from Google Play
                    // This could mean: never purchased, or refunded
                    // Check local cache to detect refund scenario
                    scope.launch {
                        val cachedPurchase = appPreferences?.getIsPurchasedSync() ?: false
                        if (cachedPurchase) {
                            // Had a purchase cached but Google Play says no purchase
                            // This indicates a refund - clear local state
                            Log.w(TAG, "Refund detected: cached purchase exists but Google Play returned none")
                            handleRefund()
                        }
                    }
                }
            } else {
                Log.e(TAG, "Failed to query purchases: ${billingResult.debugMessage}")
                // On network error, rely on cached state (already loaded in init)
            }
        }
    }

    /**
     * Handles refund by clearing local purchase state.
     * Note: Firebase isPurchased is one-way (can't be set to false from client),
     * so we clear local cache only. For server-side refund handling,
     * use Google Play Developer API webhooks.
     */
    private fun handleRefund() {
        Log.d(TAG, "Processing refund - clearing local purchase cache")
        _isPurchased.value = false
        scope.launch {
            appPreferences?.setIsPurchased(false)
        }
        // Note: Firebase flag cannot be reverted from client side due to security rules
        // Server-side webhook should handle Firebase cleanup for refunds
    }

    /**
     * Syncs purchase state to Firebase and local cache.
     */
    private fun syncPurchaseState(purchased: Boolean) {
        scope.launch {
            if (purchased) {
                // Sync to local cache
                appPreferences?.setIsPurchased(true)
                Log.d(TAG, "Purchase synced to local cache")

                // Sync to Firebase
                try {
                    trialManager?.markAsPurchased()
                    Log.d(TAG, "Purchase synced to Firebase")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to sync purchase to Firebase: ${e.message}")
                    // Local cache is still set, so app will work offline
                }
            }
        }
    }

    /**
     * Launches the Google Play purchase flow for the full version product.
     *
     * EQUIVALENT TO: startPurchase() - This is the entry point for initiating a purchase.
     *
     * Prerequisites:
     * - BillingClient must be connected (call connect() first)
     * - Product must exist and be active in Play Console
     * - Must be called from an Activity context
     *
     * @param activity The Activity to launch the purchase dialog from
     */
    fun launchPurchaseFlow(activity: Activity) {
        val details = productDetails
        if (details == null) {
            Log.e(TAG, "Product details not loaded yet")
            _purchaseResult.value = PurchaseResult.Error("Product not available. Please try again.")
            return
        }

        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .build()

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .build()

        val billingResult = billingClient.launchBillingFlow(activity, billingFlowParams)
        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.e(TAG, "Failed to launch billing flow: ${billingResult.debugMessage}")
            _purchaseResult.value = PurchaseResult.Error(billingResult.debugMessage)
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase ->
                    handlePurchase(purchase)
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.d(TAG, "User cancelled purchase")
                _purchaseResult.value = PurchaseResult.Cancelled
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                Log.d(TAG, "Item already owned")
                _isPurchased.value = true
                _purchaseResult.value = PurchaseResult.AlreadyOwned
            }
            else -> {
                Log.e(TAG, "Purchase failed: ${billingResult.debugMessage}")
                _purchaseResult.value = PurchaseResult.Error(billingResult.debugMessage)
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> {
                Log.d(TAG, "Purchase completed, acknowledging...")
                if (!purchase.isAcknowledged) {
                    acknowledgePurchase(purchase)
                } else {
                    _isPurchased.value = true
                    _purchaseResult.value = PurchaseResult.Success
                    syncPurchaseState(true)
                }
            }
            Purchase.PurchaseState.PENDING -> {
                Log.d(TAG, "Purchase pending - awaiting payment completion")
                _purchaseResult.value = PurchaseResult.Pending
                // Don't unlock yet - will be handled when purchase completes
            }
            Purchase.PurchaseState.UNSPECIFIED_STATE -> {
                Log.w(TAG, "Purchase in unspecified state")
            }
        }
    }

    /**
     * Acknowledges a purchase with Google Play.
     *
     * IMPORTANT: Purchases must be acknowledged within 3 days or they are auto-refunded.
     * After acknowledgment, syncs purchase state to Firebase and local cache.
     */
    private fun acknowledgePurchase(purchase: Purchase) {
        val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()

        billingClient.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.d(TAG, "Purchase acknowledged successfully")
                _isPurchased.value = true
                _purchaseResult.value = PurchaseResult.Success

                // Sync to Firebase and local cache
                syncPurchaseState(true)
            } else {
                Log.e(TAG, "Failed to acknowledge purchase: ${billingResult.debugMessage}")
                _purchaseResult.value = PurchaseResult.Error("Failed to complete purchase. Please try again.")
            }
        }
    }

    /**
     * Restores purchases for reinstalls or device changes.
     *
     * Queries Google Play for existing purchases and syncs state.
     * Note: With device fingerprinting approach, purchases are restored per-device
     * (not cross-device sync - that requires Google account linking).
     *
     * @param onComplete Callback with true if purchase was found and restored
     */
    fun restorePurchases(onComplete: (Boolean) -> Unit) {
        if (!billingClient.isReady) {
            Log.w(TAG, "Billing client not ready, attempting to connect first")
            connect()
            // Return cached state while connection establishes
            scope.launch {
                val cached = appPreferences?.getIsPurchasedSync() ?: false
                onComplete(cached)
            }
            return
        }

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchasesList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val validPurchase = purchasesList.find { purchase ->
                    purchase.products.contains(PRODUCT_ID) &&
                    purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                }

                val hasPurchase = validPurchase != null
                _isPurchased.value = hasPurchase
                Log.d(TAG, "Restore purchases: found=$hasPurchase")

                if (hasPurchase) {
                    // Ensure it's acknowledged and synced
                    if (!validPurchase!!.isAcknowledged) {
                        acknowledgePurchase(validPurchase)
                    } else {
                        syncPurchaseState(true)
                    }
                }

                onComplete(hasPurchase)
            } else {
                Log.e(TAG, "Failed to restore purchases: ${billingResult.debugMessage}")
                // Fall back to cached state on network error
                scope.launch {
                    val cached = appPreferences?.getIsPurchasedSync() ?: false
                    onComplete(cached)
                }
            }
        }
    }

    fun clearPurchaseResult() {
        _purchaseResult.value = null
    }

    /**
     * Returns the product title from Play Console configuration.
     */
    fun getProductName(): String? = productDetails?.name

    /**
     * Returns the product description from Play Console configuration.
     */
    fun getProductDescription(): String? = productDetails?.description

    /**
     * Returns true if product details have been loaded.
     */
    fun isProductAvailable(): Boolean = productDetails != null

    /**
     * Manually refreshes purchase state from Google Play.
     * Useful for checking purchase status after app returns from background.
     */
    fun refreshPurchaseState() {
        if (billingClient.isReady) {
            queryExistingPurchases()
        } else {
            connect()
        }
    }

    /**
     * Releases BillingClient resources.
     *
     * LIFECYCLE: Call this in Activity.onDestroy() to properly clean up resources.
     * Failure to call this may result in memory leaks.
     */
    fun disconnect() {
        reconnectAttempts = MAX_RECONNECT_ATTEMPTS // Prevent auto-reconnect on intentional disconnect
        if (billingClient.isReady) {
            billingClient.endConnection()
            _billingState.value = BillingState.Disconnected
            Log.d(TAG, "Billing client disconnected")
        }
    }
}
