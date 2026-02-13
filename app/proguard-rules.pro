# ============================================================================
# ProGuard/R8 Rules for EasyIP Scan
# ============================================================================
# CRITICAL: These rules protect essential classes from being stripped or
# obfuscated in ways that break functionality. Be AGGRESSIVE with keep rules -
# it's better to keep too much than break billing/Firebase in production.
# ============================================================================

# ----------------------------------------------------------------------------
# GENERAL ANDROID RULES
# ----------------------------------------------------------------------------

# Keep line numbers and source file info for crash reporting
-keepattributes SourceFile,LineNumberTable

# Hide original source file name in stack traces (optional security)
-renamesourcefileattribute SourceFile

# Keep annotations (required by many libraries)
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ----------------------------------------------------------------------------
# BUILDCONFIG - Required for About page version display
# ----------------------------------------------------------------------------

-keep class com.almostbrilliantideas.easyipscanner.BuildConfig { *; }

# ----------------------------------------------------------------------------
# GOOGLE PLAY BILLING LIBRARY - CRITICAL for in-app purchases
# ----------------------------------------------------------------------------
# DO NOT REMOVE: Breaking these classes will break purchase flow and cause
# revenue loss. Google Play Billing uses reflection extensively.

# Keep all billing classes
-keep class com.android.billingclient.** { *; }
-keep interface com.android.billingclient.** { *; }

# Keep AIDL interfaces (required for IPC with Play Store)
-keep class com.android.vending.billing.** { *; }

# Keep billing response codes and enums
-keepclassmembers enum com.android.billingclient.api.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep purchase token and other billing data classes
-keep class com.android.billingclient.api.Purchase { *; }
-keep class com.android.billingclient.api.Purchase$* { *; }
-keep class com.android.billingclient.api.ProductDetails { *; }
-keep class com.android.billingclient.api.ProductDetails$* { *; }
-keep class com.android.billingclient.api.BillingResult { *; }
-keep class com.android.billingclient.api.BillingClient { *; }
-keep class com.android.billingclient.api.BillingClient$* { *; }
-keep class com.android.billingclient.api.BillingFlowParams { *; }
-keep class com.android.billingclient.api.BillingFlowParams$* { *; }
-keep class com.android.billingclient.api.AcknowledgePurchaseParams { *; }
-keep class com.android.billingclient.api.QueryProductDetailsParams { *; }
-keep class com.android.billingclient.api.QueryProductDetailsParams$* { *; }
-keep class com.android.billingclient.api.QueryPurchasesParams { *; }
-keep class com.android.billingclient.api.PendingPurchasesParams { *; }

# Keep billing listeners and callbacks
-keep interface com.android.billingclient.api.PurchasesUpdatedListener { *; }
-keep interface com.android.billingclient.api.BillingClientStateListener { *; }
-keep interface com.android.billingclient.api.ProductDetailsResponseListener { *; }
-keep interface com.android.billingclient.api.PurchasesResponseListener { *; }
-keep interface com.android.billingclient.api.AcknowledgePurchaseResponseListener { *; }

# ----------------------------------------------------------------------------
# FIREBASE REALTIME DATABASE - CRITICAL for trial tracking
# ----------------------------------------------------------------------------
# Firebase uses reflection to serialize/deserialize data. Breaking these
# rules will cause trial tracking to fail silently.

# Keep all Firebase classes
-keep class com.google.firebase.** { *; }
-keep interface com.google.firebase.** { *; }

# Keep Firebase Database specifically
-keep class com.google.firebase.database.** { *; }
-keep interface com.google.firebase.database.** { *; }

# Keep Firebase data snapshot methods
-keepclassmembers class com.google.firebase.database.DataSnapshot {
    public <methods>;
}

# Keep value event listeners
-keep interface com.google.firebase.database.ValueEventListener { *; }
-keep interface com.google.firebase.database.ChildEventListener { *; }

# Keep Firebase GenericTypeIndicator for generic types
-keep class com.google.firebase.database.GenericTypeIndicator { *; }

# Keep Google Play Services (Firebase dependency)
-keep class com.google.android.gms.** { *; }
-keep interface com.google.android.gms.** { *; }

# ----------------------------------------------------------------------------
# APP DATA CLASSES - Required for Firebase serialization
# ----------------------------------------------------------------------------
# These data classes are serialized to/from Firebase. Their field names
# must be preserved exactly.

# Trial data - synced with Firebase
-keep class com.almostbrilliantideas.easyipscanner.TrialData { *; }
-keepclassmembers class com.almostbrilliantideas.easyipscanner.TrialData {
    <init>();
    <init>(...);
    <fields>;
}

# Keep TrialManager and all its methods
-keep class com.almostbrilliantideas.easyipscanner.TrialManager { *; }

# ----------------------------------------------------------------------------
# APP BILLING CLASSES - Critical for purchase flow
# ----------------------------------------------------------------------------

# Keep BillingManager and its sealed classes
-keep class com.almostbrilliantideas.easyipscanner.BillingManager { *; }
-keep class com.almostbrilliantideas.easyipscanner.BillingState { *; }
-keep class com.almostbrilliantideas.easyipscanner.BillingState$* { *; }
-keep class com.almostbrilliantideas.easyipscanner.PurchaseResult { *; }
-keep class com.almostbrilliantideas.easyipscanner.PurchaseResult$* { *; }

# ----------------------------------------------------------------------------
# APP PREFERENCES - DataStore serialization
# ----------------------------------------------------------------------------

-keep class com.almostbrilliantideas.easyipscanner.AppPreferences { *; }
-keep class com.almostbrilliantideas.easyipscanner.LastScannedNetwork { *; }

# ----------------------------------------------------------------------------
# OTHER APP DATA CLASSES
# ----------------------------------------------------------------------------

# Network scanning data classes
-keep class com.almostbrilliantideas.easyipscanner.ScanResult { *; }
-keep class com.almostbrilliantideas.easyipscanner.StoredDevice { *; }
-keep class com.almostbrilliantideas.easyipscanner.DeviceNames { *; }
-keep class com.almostbrilliantideas.easyipscanner.PortBannerResult { *; }
-keep class com.almostbrilliantideas.easyipscanner.PortInfo { *; }
-keep class com.almostbrilliantideas.easyipscanner.PortScanResult { *; }
-keep class com.almostbrilliantideas.easyipscanner.SpeedTestResult { *; }

# Network state classes
-keep class com.almostbrilliantideas.easyipscanner.NetworkState { *; }
-keep class com.almostbrilliantideas.easyipscanner.NetworkState$* { *; }
-keep class com.almostbrilliantideas.easyipscanner.CurrentNetwork { *; }
-keep class com.almostbrilliantideas.easyipscanner.NetworkChangeResult { *; }
-keep class com.almostbrilliantideas.easyipscanner.WiFiInfo { *; }

# Device state enum
-keep class com.almostbrilliantideas.easyipscanner.DeviceState { *; }
-keepclassmembers enum com.almostbrilliantideas.easyipscanner.DeviceState {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ----------------------------------------------------------------------------
# JETPACK COMPOSE - UI Framework
# ----------------------------------------------------------------------------
# Compose uses reflection for recomposition. These rules prevent crashes.

# Keep Compose runtime
-keep class androidx.compose.** { *; }
-keep interface androidx.compose.** { *; }

# Keep @Composable functions from being stripped
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# Keep Compose compiler metadata
-keepattributes RuntimeVisibleAnnotations

# Keep stable/immutable annotations
-keep @androidx.compose.runtime.Stable class *
-keep @androidx.compose.runtime.Immutable class *

# ----------------------------------------------------------------------------
# KOTLIN COROUTINES & FLOW
# ----------------------------------------------------------------------------

-keep class kotlinx.coroutines.** { *; }
-keep interface kotlinx.coroutines.** { *; }

# Keep Flow and StateFlow
-keep class kotlinx.coroutines.flow.** { *; }
-keep interface kotlinx.coroutines.flow.** { *; }

# ----------------------------------------------------------------------------
# KOTLIN SERIALIZATION & REFLECTION
# ----------------------------------------------------------------------------

# Keep Kotlin metadata for reflection
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Keep data class component functions
-keepclassmembers class * {
    public <init>(...);
}

# Keep Kotlin standard library classes used by reflection
-keep class kotlin.reflect.** { *; }
-keep class kotlin.jvm.internal.** { *; }

# ----------------------------------------------------------------------------
# OKHTTP - Network Library
# ----------------------------------------------------------------------------

-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep OkHttp platform classes
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn org.codehaus.mojo.animal_sniffer.*
-dontwarn javax.annotation.**

# ----------------------------------------------------------------------------
# JMDNS - mDNS Discovery Library
# ----------------------------------------------------------------------------

-keep class javax.jmdns.** { *; }
-keep interface javax.jmdns.** { *; }
-dontwarn javax.jmdns.**

# ----------------------------------------------------------------------------
# ANDROIDX DATASTORE
# ----------------------------------------------------------------------------

-keep class androidx.datastore.** { *; }
-keep interface androidx.datastore.** { *; }

# Keep preference keys
-keepclassmembers class * {
    @androidx.datastore.preferences.core.Preferences$Key *;
}

# ----------------------------------------------------------------------------
# ANDROIDX LIFECYCLE
# ----------------------------------------------------------------------------

-keep class androidx.lifecycle.** { *; }
-keep interface androidx.lifecycle.** { *; }

# Keep ViewModel classes
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# ----------------------------------------------------------------------------
# SUPPRESS WARNINGS
# ----------------------------------------------------------------------------
# These warnings are safe to ignore

-dontwarn java.lang.invoke.StringConcatFactory
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn sun.misc.Unsafe
-dontwarn javax.naming.**

# Ignore missing classes from optional dependencies
-dontwarn org.slf4j.**
-dontwarn ch.qos.logback.**

# ----------------------------------------------------------------------------
# DEBUG: Uncomment to see what's being removed/kept
# ----------------------------------------------------------------------------
# -printusage usage.txt
# -printseeds seeds.txt
# -printmapping mapping.txt
