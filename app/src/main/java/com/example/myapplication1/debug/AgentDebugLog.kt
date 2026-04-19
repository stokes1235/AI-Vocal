package com.example.myapplication1.debug

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Debug session NDJSON：发往本机 ingest（需 USB 调试 `adb reverse tcp:7936 tcp:7936`；模拟器可用 10.0.2.2）。
 */
object AgentDebugLog {

    private const val SESSION = "63b36b"
    private const val TAG = "AgentDebug"
    private const val INGEST = "http://127.0.0.1:7936/ingest/846fa02e-bb70-487d-9335-7918ea0314df"
    private const val INGEST_EMU = "http://10.0.2.2:7936/ingest/846fa02e-bb70-487d-9335-7918ea0314df"

    fun emit(hypothesisId: String, location: String, message: String, data: Map<String, Any?> = emptyMap()) {
        // #region agent log
        val dataJson = JSONObject()
        for ((k, v) in data) {
            when (v) {
                null -> dataJson.put(k, JSONObject.NULL)
                is Number -> dataJson.put(k, v)
                is Boolean -> dataJson.put(k, v)
                else -> dataJson.put(k, v.toString())
            }
        }
        val json = JSONObject().apply {
            put("sessionId", SESSION)
            put("timestamp", System.currentTimeMillis())
            put("hypothesisId", hypothesisId)
            put("location", location)
            put("message", message)
            put("data", dataJson)
        }
        val line = json.toString()
        Log.i(TAG, line)
        Thread {
            post(INGEST, line)
            post(INGEST_EMU, line)
        }.start()
        // #endregion
    }

    private fun post(url: String, body: String) {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("X-Debug-Session-Id", SESSION)
            c.doOutput = true
            c.connectTimeout = 800
            c.readTimeout = 800
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            c.inputStream?.close()
            c.disconnect()
        } catch (_: Exception) {
        }
    }
}
