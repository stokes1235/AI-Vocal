package com.example.myapplication1.ocr

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.ocrLanguageDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ocr_language",
)

class OcrLanguagePreferences(private val context: Context) {

    private val keyEngine = stringPreferencesKey("engine")

    val engineFlow: Flow<OcrEngine> = context.ocrLanguageDataStore.data.map { prefs ->
        OcrEngine.fromStored(prefs[keyEngine])
    }

    suspend fun getEngine(): OcrEngine = engineFlow.first()

    suspend fun setEngine(engine: OcrEngine) {
        context.ocrLanguageDataStore.edit { prefs ->
            prefs[keyEngine] = engine.toStored()
        }
    }
}
