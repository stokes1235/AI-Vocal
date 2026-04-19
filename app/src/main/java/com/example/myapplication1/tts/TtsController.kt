package com.example.myapplication1.tts

import android.app.Application

/**
 * 应用内 TTS 统一入口：多说话人 VITS 的 [voiceId] 与 sherpa [com.k2fsa.sherpa.onnx.GenerationConfig.sid] 一致。
 * 中英各一套 [com.k2fsa.sherpa.onnx.OfflineTts]，在首次需要时由 [SherpaTtsPlayer] 懒加载。
 */
class TtsController(
    app: Application,
) {
    private val player = SherpaTtsPlayer(app)

    fun isModelInstalled(kind: TtsEngineKind): Boolean = player.isModelInstalled(kind)

    /** @param voiceId 声线 id（VITS speaker / sid） */
    fun speak(text: String, voiceId: Int) {
        speak(text, voiceId, TtsEngineKind.Chinese)
    }

    /**
     * @param voiceId 声线 id（VITS speaker / sid）
     * @param engine 中文 aishell3 / 英文 vctk
     */
    fun speak(text: String, voiceId: Int, engine: TtsEngineKind) {
        if (!player.isModelInstalled(engine)) return
        player.speak(text, voiceId, engine = engine)
    }

    fun stop() = player.stop()

    fun release() = player.release()
}
