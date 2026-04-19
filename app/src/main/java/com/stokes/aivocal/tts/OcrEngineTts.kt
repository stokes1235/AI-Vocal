package com.stokes.aivocal.tts

import com.stokes.aivocal.ocr.OcrEngine

fun OcrEngine.toTtsEngineKind(): TtsEngineKind =
    when (this) {
        OcrEngine.CHINESE -> TtsEngineKind.Chinese
        OcrEngine.ENGLISH -> TtsEngineKind.English
    }
