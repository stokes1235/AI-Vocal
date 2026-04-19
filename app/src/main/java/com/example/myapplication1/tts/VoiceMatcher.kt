package com.example.myapplication1.tts

/**
 * 人名区 OCR 与配置人名匹配：归一化后双向 contains。
 */
object VoiceMatcher {

    data class ResolveResult(
        val speakerId: Int,
        /** 与某条映射的人名字段匹配成功时的展示名；未匹配或人名为空时为 null。 */
        val matchedDisplayName: String?,
    )

    fun resolveWithDisplayName(
        mappings: List<VoiceMapping>,
        defaultSpeakerId: Int,
        nameOcrText: String,
    ): ResolveResult {
        val norm = nameOcrText.trim().replace(Regex("\\s+"), " ")
        if (norm.isEmpty()) return ResolveResult(defaultSpeakerId, null)
        val compact = norm.replace(" ", "")
        for (m in mappings) {
            val key = m.displayName.trim().replace(" ", "")
            if (key.isEmpty()) continue
            if (compact.contains(key) || key.contains(compact)) {
                return ResolveResult(m.speakerId, m.displayName.trim())
            }
        }
        return ResolveResult(defaultSpeakerId, null)
    }

    fun resolve(
        mappings: List<VoiceMapping>,
        defaultSpeakerId: Int,
        nameOcrText: String,
    ): Int = resolveWithDisplayName(mappings, defaultSpeakerId, nameOcrText).speakerId
}
