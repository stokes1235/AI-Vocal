package com.stokes.aivocal.tts

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.ttsReadAloudDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tts_read_aloud",
)

/**
 * 是否在 OCR 出稳定主文本后同步进行离线 TTS；关闭时仅更新界面与浮窗，不合成语音。
 */
class TtsReadAloudPreferences(private val context: Context) {

    private val keyEnabled = booleanPreferencesKey("read_aloud_enabled")

    val readAloudEnabledFlow: Flow<Boolean> = context.ttsReadAloudDataStore.data.map { prefs ->
        prefs[keyEnabled] ?: true
    }

    suspend fun isReadAloudEnabled(): Boolean = readAloudEnabledFlow.first()

    suspend fun setReadAloudEnabled(enabled: Boolean) {
        context.ttsReadAloudDataStore.edit { it[keyEnabled] = enabled }
    }
}
