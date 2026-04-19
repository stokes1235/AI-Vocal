package com.stokes.aivocal.ocr

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.ocrTimingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ocr_timing",
)

class OcrTimingPreferences(context: Context) {

    private val appContext = context.applicationContext

    private val keyIntervalMs = intPreferencesKey("ocr_interval_ms")
    private val keyStableMs = intPreferencesKey("ocr_stable_ms")

    val timingFlow: Flow<Pair<Int, Int>> = appContext.ocrTimingDataStore.data.map { prefs ->
        val interval = prefs[keyIntervalMs] ?: DEFAULT_INTERVAL_MS
        val stable = prefs[keyStableMs] ?: DEFAULT_STABLE_MS
        interval to stable
    }

    suspend fun getIntervalMs(): Int = timingFlow.first().first

    suspend fun getStableMs(): Int = timingFlow.first().second

    suspend fun setTiming(intervalMs: Int, stableMs: Int) {
        appContext.ocrTimingDataStore.edit { prefs ->
            prefs[keyIntervalMs] = intervalMs
            prefs[keyStableMs] = stableMs
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 300
        const val DEFAULT_STABLE_MS = 400
        const val INTERVAL_MIN = 150
        const val INTERVAL_MAX = 800
        const val STABLE_MIN = 200
        const val STABLE_MAX = 1200
    }
}
