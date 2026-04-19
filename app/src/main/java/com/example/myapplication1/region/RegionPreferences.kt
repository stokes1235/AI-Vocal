package com.example.myapplication1.region

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.myapplication1.NormalizedRect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.regionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "capture_region",
)

class RegionPreferences(private val context: Context) {

    private val keyLeft = floatPreferencesKey("left")
    private val keyTop = floatPreferencesKey("top")
    private val keyRight = floatPreferencesKey("right")
    private val keyBottom = floatPreferencesKey("bottom")

    val regionFlow: Flow<NormalizedRect?> = context.regionDataStore.data.map { prefs ->
        val l = prefs[keyLeft]
        val t = prefs[keyTop]
        val r = prefs[keyRight]
        val b = prefs[keyBottom]
        if (l != null && t != null && r != null && b != null) {
            runCatching { NormalizedRect(l, t, r, b) }.getOrNull()
        } else {
            null
        }
    }

    suspend fun getRegionOrNull(): NormalizedRect? = regionFlow.first()

    suspend fun save(region: NormalizedRect) {
        context.regionDataStore.edit { prefs ->
            prefs[keyLeft] = region.left
            prefs[keyTop] = region.top
            prefs[keyRight] = region.right
            prefs[keyBottom] = region.bottom
        }
    }

    suspend fun clear() {
        context.regionDataStore.edit { it.clear() }
    }
}
