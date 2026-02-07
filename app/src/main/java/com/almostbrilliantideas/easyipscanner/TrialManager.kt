package com.almostbrilliantideas.easyipscanner

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

data class TrialData(
    val firstInstallTimestamp: Long = 0L,
    val trialExpirationDate: Long = 0L,
    val isPurchased: Boolean = false
)

class TrialManager(private val context: Context) {

    private val database = FirebaseDatabase.getInstance()
    private val usersRef = database.getReference("users")

    private val deviceId: String
        get() = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

    private val userRef get() = usersRef.child(deviceId)

    init {
        Log.d(TAG, "TrialManager initialized")
        Log.d(TAG, "Firebase Database reference: ${database.reference}")
        Log.d(TAG, "Users reference path: ${usersRef.path}")
    }

    companion object {
        private const val TAG = "TrialManager"
        private const val TRIAL_DURATION_DAYS = 7
        private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L

        private const val KEY_FIRST_INSTALL = "firstInstallTimestamp"
        private const val KEY_EXPIRATION = "trialExpirationDate"
        private const val KEY_PURCHASED = "isPurchased"
    }

    suspend fun initializeTrial(): TrialData {
        Log.d(TAG, "TrialManager: Starting initializeTrial")
        Log.d(TAG, "initializeTrial: deviceId=$deviceId")
        Log.d(TAG, "initializeTrial: Fetching existing trial data from Firebase...")

        val snapshot = userRef.get().await()
        Log.d(TAG, "initializeTrial: Snapshot received, exists=${snapshot.exists()}")

        if (snapshot.exists()) {
            val existingData = snapshot.toTrialData()
            Log.d(TAG, "initializeTrial: Found existing trial data - firstInstall=${existingData.firstInstallTimestamp}, expiration=${existingData.trialExpirationDate}, isPurchased=${existingData.isPurchased}")
            return existingData
        }

        Log.d(TAG, "initializeTrial: No existing data, creating new trial...")
        val now = System.currentTimeMillis()
        val expirationDate = now + (TRIAL_DURATION_DAYS * MILLIS_PER_DAY)
        Log.d(TAG, "initializeTrial: now=$now, expirationDate=$expirationDate (${TRIAL_DURATION_DAYS} days from now)")

        val trialData = TrialData(
            firstInstallTimestamp = now,
            trialExpirationDate = expirationDate,
            isPurchased = false
        )

        Log.d(TAG, "initializeTrial: Saving new trial data to Firebase...")
        userRef.setValue(trialData.toMap()).await()
        Log.d(TAG, "initializeTrial: Trial data saved successfully")
        return trialData
    }

    suspend fun isTrialExpired(): Boolean {
        Log.d(TAG, "isTrialExpired: Checking trial expiration status...")
        val snapshot = userRef.get().await()
        Log.d(TAG, "isTrialExpired: Snapshot received, exists=${snapshot.exists()}")

        if (!snapshot.exists()) {
            Log.d(TAG, "isTrialExpired: No trial data found, returning false")
            return false
        }

        val trialData = snapshot.toTrialData()
        Log.d(TAG, "isTrialExpired: trialData - expiration=${trialData.trialExpirationDate}, isPurchased=${trialData.isPurchased}")

        if (trialData.isPurchased) {
            Log.d(TAG, "isTrialExpired: User has purchased, returning false")
            return false
        }

        val now = System.currentTimeMillis()
        val isExpired = now > trialData.trialExpirationDate
        Log.d(TAG, "isTrialExpired: now=$now, expiration=${trialData.trialExpirationDate}, isExpired=$isExpired")
        return isExpired
    }

    suspend fun markAsPurchased() {
        Log.d(TAG, "markAsPurchased: Marking user as purchased...")
        userRef.child(KEY_PURCHASED).setValue(true).await()
        Log.d(TAG, "markAsPurchased: Purchase status saved successfully")
    }

    suspend fun isPurchased(): Boolean {
        Log.d(TAG, "isPurchased: Checking purchase status...")
        val snapshot = userRef.child(KEY_PURCHASED).get().await()
        val purchased = snapshot.getValue(Boolean::class.java) ?: false
        Log.d(TAG, "isPurchased: result=$purchased")
        return purchased
    }

    suspend fun getTrialData(): TrialData? {
        Log.d(TAG, "getTrialData: Fetching trial data...")
        val snapshot = userRef.get().await()
        val data = if (snapshot.exists()) snapshot.toTrialData() else null
        Log.d(TAG, "getTrialData: result=$data")
        return data
    }

    fun observeTrialData(): Flow<TrialData?> = callbackFlow {
        Log.d(TAG, "observeTrialData: Setting up real-time listener...")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val data = if (snapshot.exists()) snapshot.toTrialData() else null
                Log.d(TAG, "observeTrialData: onDataChange received, data=$data")
                trySend(data)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.d(TAG, "observeTrialData: onCancelled, error=${error.message}")
                close(error.toException())
            }
        }

        userRef.addValueEventListener(listener)
        Log.d(TAG, "observeTrialData: Listener added")

        awaitClose {
            Log.d(TAG, "observeTrialData: Removing listener")
            userRef.removeEventListener(listener)
        }
    }

    fun getRemainingTrialDays(trialData: TrialData): Int {
        Log.d(TAG, "getRemainingTrialDays: Calculating remaining days...")
        if (trialData.isPurchased) {
            Log.d(TAG, "getRemainingTrialDays: User purchased, returning Int.MAX_VALUE")
            return Int.MAX_VALUE
        }

        val remainingMillis = trialData.trialExpirationDate - System.currentTimeMillis()
        val days = if (remainingMillis > 0) {
            (remainingMillis / MILLIS_PER_DAY).toInt()
        } else {
            0
        }
        Log.d(TAG, "getRemainingTrialDays: remainingMillis=$remainingMillis, days=$days")
        return days
    }

    private fun DataSnapshot.toTrialData(): TrialData {
        Log.d(TAG, "toTrialData: Parsing snapshot to TrialData...")
        val data = TrialData(
            firstInstallTimestamp = child(KEY_FIRST_INSTALL).getValue(Long::class.java) ?: 0L,
            trialExpirationDate = child(KEY_EXPIRATION).getValue(Long::class.java) ?: 0L,
            isPurchased = child(KEY_PURCHASED).getValue(Boolean::class.java) ?: false
        )
        Log.d(TAG, "toTrialData: Parsed data=$data")
        return data
    }

    private fun TrialData.toMap(): Map<String, Any> {
        Log.d(TAG, "toMap: Converting TrialData to Map...")
        val map = mapOf(
            KEY_FIRST_INSTALL to firstInstallTimestamp,
            KEY_EXPIRATION to trialExpirationDate,
            KEY_PURCHASED to isPurchased
        )
        Log.d(TAG, "toMap: result=$map")
        return map
    }
}
