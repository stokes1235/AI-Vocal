package com.example.myapplication1.ocr

/**
 * 与 [OcrLanguagePreferences] 持久化字符串对应。
 */
enum class OcrEngine {
    CHINESE,
    ENGLISH,
    ;

    fun toStored(): String =
        when (this) {
            CHINESE -> STORED_CHINESE
            ENGLISH -> STORED_ENGLISH
        }

    companion object {
        private const val STORED_CHINESE = "chinese"
        private const val STORED_ENGLISH = "english"

        fun fromStored(value: String?): OcrEngine =
            when (value) {
                STORED_ENGLISH -> ENGLISH
                else -> CHINESE
            }
    }
}
