package com.example.myapplication1.tts

import com.example.myapplication1.ocr.OcrEngine

fun OcrEngine.toTtsEngineKind(): TtsEngineKind =
    when (this) {
        OcrEngine.CHINESE -> TtsEngineKind.Chinese
        OcrEngine.ENGLISH -> TtsEngineKind.English
    }
