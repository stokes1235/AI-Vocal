package com.example.myapplication1.ocr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 将 Service 中的 OCR 主文本、匹配到的人名（若有）与当前朗读所用 speaker id 传到 UI。
 */
object OcrResultBus {

    data class Publish(
        val mainText: String,
        val speakerId: Int,
        /** 与声线映射匹配成功的人名；无人名区域或未匹配时为 null，界面展示为「（null）」。 */
        val personLabel: String? = null,
    )

    private val _publish = MutableStateFlow(Publish("", 0, null))
    val publish: StateFlow<Publish> = _publish.asStateFlow()

    fun post(mainText: String, speakerId: Int, personLabel: String? = null) {
        _publish.value = Publish(mainText, speakerId, personLabel)
    }

    fun clear() {
        _publish.value = Publish("", 0, null)
    }

    /** 主界面 / 浮窗展示：「人名：识别句」或「（null）：识别句」。 */
    fun formatDisplayLine(pub: Publish, emptyMainFallback: String): String {
        val head = pub.personLabel?.takeIf { it.isNotBlank() } ?: "（null）"
        val body = pub.mainText.ifEmpty { emptyMainFallback }
        return "$head：$body"
    }
}
