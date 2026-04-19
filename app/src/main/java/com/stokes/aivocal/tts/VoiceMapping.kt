package com.stokes.aivocal.tts

/**
 * @param displayName 展示用标准人名（命中后浮窗等使用该字段）
 * @param aliases 可选：OCR 常见错形、异体字等，逗号/顿号等在 UI 中解析；参与包含与模糊匹配
 */
data class VoiceMapping(
    val displayName: String,
    val speakerId: Int,
    val aliases: List<String> = emptyList(),
)
