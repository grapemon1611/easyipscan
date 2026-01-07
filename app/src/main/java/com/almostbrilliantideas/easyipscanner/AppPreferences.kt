package com.almostbrilliantideas.easyipscanner

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_preferences")

data class LastScannedNetwork(
    val ssid: String?,
    val gatewayIp: String?,
    val scanTimestamp: Long
)

class AppPreferences(private val context: Context) {

    companion object {
        private val FIRST_RUN_COMPLETED = booleanPreferencesKey("first_run_completed")
        private val LAST_SCANNED_SSID = stringPreferencesKey("last_scanned_ssid")
        private val LAST_SCANNED_GATEWAY = stringPreferencesKey("last_scanned_gateway")
        private val LAST_SCAN_TIMESTAMP = longPreferencesKey("last_scan_timestamp")
    }

    val hasCompletedFirstRun: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[FIRST_RUN_COMPLETED] ?: false
    }

    suspend fun setFirstRunCompleted() {
        context.dataStore.edit { preferences ->
            preferences[FIRST_RUN_COMPLETED] = true
        }
    }

    val lastScannedNetwork: Flow<LastScannedNetwork?> = context.dataStore.data.map { preferences ->
        val ssid = preferences[LAST_SCANNED_SSID]
        val gateway = preferences[LAST_SCANNED_GATEWAY]
        val timestamp = preferences[LAST_SCAN_TIMESTAMP] ?: 0L

        if (ssid != null || gateway != null) {
            LastScannedNetwork(ssid, gateway, timestamp)
        } else {
            null
        }
    }

    suspend fun getLastScannedNetworkSync(): LastScannedNetwork? {
        return lastScannedNetwork.first()
    }

    suspend fun saveScannedNetwork(ssid: String?, gatewayIp: String?) {
        context.dataStore.edit { preferences ->
            if (ssid != null) {
                preferences[LAST_SCANNED_SSID] = ssid
            } else {
                preferences.remove(LAST_SCANNED_SSID)
            }
            if (gatewayIp != null) {
                preferences[LAST_SCANNED_GATEWAY] = gatewayIp
            } else {
                preferences.remove(LAST_SCANNED_GATEWAY)
            }
            preferences[LAST_SCAN_TIMESTAMP] = System.currentTimeMillis()
        }
    }

    suspend fun clearLastScannedNetwork() {
        context.dataStore.edit { preferences ->
            preferences.remove(LAST_SCANNED_SSID)
            preferences.remove(LAST_SCANNED_GATEWAY)
            preferences.remove(LAST_SCAN_TIMESTAMP)
        }
    }
}
