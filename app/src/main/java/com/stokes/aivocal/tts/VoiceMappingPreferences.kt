package com.stokes.aivocal.tts

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.voiceMappingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "voice_mapping",
)

/**
 * 中英 TTS 引擎各自独立的默认 speaker 与人名映射；旧版单份数据在 [migrateFromLegacyIfNeeded] 中复制到两套。
 */
class VoiceMappingPreferences(private val context: Context) {

    private val keyMigrated = booleanPreferencesKey("voice_prefs_engine_split_migrated_v1")
    private val keyLegacyDefault = intPreferencesKey("default_speaker_id")
    private val keyLegacyMappings = stringPreferencesKey("mappings_json")

    private val keyDefaultZh = intPreferencesKey("default_speaker_id_zh")
    private val keyMappingsZh = stringPreferencesKey("mappings_json_zh")
    private val keyDefaultEn = intPreferencesKey("default_speaker_id_en")
    private val keyMappingsEn = stringPreferencesKey("mappings_json_en")

    suspend fun migrateFromLegacyIfNeeded() {
        context.voiceMappingDataStore.edit { prefs ->
            if (prefs[keyMigrated] == true) return@edit
            val legacyDef = prefs[keyLegacyDefault]
            val legacyMap = prefs[keyLegacyMappings]
            prefs[keyDefaultZh] = legacyDef ?: 0
            prefs[keyMappingsZh] = legacyMap ?: "[]"
            prefs[keyDefaultEn] = legacyDef ?: 0
            prefs[keyMappingsEn] = legacyMap ?: "[]"
            prefs[keyMigrated] = true
        }
    }

    private fun keysFor(kind: TtsEngineKind) =
        when (kind) {
            TtsEngineKind.Chinese -> keyDefaultZh to keyMappingsZh
            TtsEngineKind.English -> keyDefaultEn to keyMappingsEn
        }

    fun defaultSpeakerIdFlow(kind: TtsEngineKind): Flow<Int> =
        context.voiceMappingDataStore.data.map { prefs ->
            val (defKey, _) = keysFor(kind)
            prefs[defKey] ?: 0
        }

    fun mappingsFlow(kind: TtsEngineKind): Flow<List<VoiceMapping>> =
        context.voiceMappingDataStore.data.map { prefs ->
            val (_, mapKey) = keysFor(kind)
            parseMappings(prefs[mapKey] ?: "[]")
        }

    suspend fun getDefaultSpeakerId(kind: TtsEngineKind): Int {
        migrateFromLegacyIfNeeded()
        return defaultSpeakerIdFlow(kind).first()
    }

    suspend fun getMappings(kind: TtsEngineKind): List<VoiceMapping> {
        migrateFromLegacyIfNeeded()
        return mappingsFlow(kind).first()
    }

    suspend fun setDefaultSpeakerId(kind: TtsEngineKind, id: Int) {
        migrateFromLegacyIfNeeded()
        val (defKey, _) = keysFor(kind)
        context.voiceMappingDataStore.edit { it[defKey] = id }
    }

    suspend fun setMappings(kind: TtsEngineKind, list: List<VoiceMapping>) {
        migrateFromLegacyIfNeeded()
        val (_, mapKey) = keysFor(kind)
        val arr = JSONArray()
        for (m in list) {
            val o = JSONObject().put("n", m.displayName).put("s", m.speakerId)
            if (m.aliases.isNotEmpty()) {
                val a = JSONArray()
                for (x in m.aliases) a.put(x)
                o.put("a", a)
            }
            arr.put(o)
        }
        context.voiceMappingDataStore.edit { it[mapKey] = arr.toString() }
    }

    private fun parseMappings(json: String): List<VoiceMapping> {
        val out = mutableListOf<VoiceMapping>()
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val aliases = mutableListOf<String>()
                o.optJSONArray("a")?.let { ja ->
                    for (j in 0 until ja.length()) {
                        val s = ja.optString(j, "").trim()
                        if (s.isNotEmpty()) aliases.add(s)
                    }
                }
                out.add(VoiceMapping(o.getString("n"), o.getInt("s"), aliases))
            }
        }
        return out
    }
}
