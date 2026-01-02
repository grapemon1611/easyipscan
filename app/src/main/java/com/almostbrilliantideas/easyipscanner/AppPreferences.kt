package com.almostbrilliantideas.easyipscanner

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_preferences")

class AppPreferences(private val context: Context) {

    companion object {
        private val FIRST_RUN_COMPLETED = booleanPreferencesKey("first_run_completed")
    }

    val hasCompletedFirstRun: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[FIRST_RUN_COMPLETED] ?: false
    }

    suspend fun setFirstRunCompleted() {
        context.dataStore.edit { preferences ->
            preferences[FIRST_RUN_COMPLETED] = true
        }
    }
}
