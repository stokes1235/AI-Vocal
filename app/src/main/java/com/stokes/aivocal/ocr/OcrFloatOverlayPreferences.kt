package com.stokes.aivocal.ocr

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.ocrFloatOverlayDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ocr_float_overlay",
)

/**
 * 是否在实时 OCR 时显示「实时识别」悬浮窗；关闭后仍向主界面 [OcrResultBus] 推送，仅不显示浮窗。
 */
class OcrFloatOverlayPreferences(private val context: Context) {

    private val keyEnabled = booleanPreferencesKey("float_overlay_enabled")

    val floatOverlayEnabledFlow: Flow<Boolean> = context.ocrFloatOverlayDataStore.data.map { prefs ->
        prefs[keyEnabled] ?: true
    }

    suspend fun isFloatOverlayEnabled(): Boolean = floatOverlayEnabledFlow.first()

    suspend fun setFloatOverlayEnabled(enabled: Boolean) {
        context.ocrFloatOverlayDataStore.edit { it[keyEnabled] = enabled }
    }
}
