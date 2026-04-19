package com.stokes.aivocal.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * ML Kit 设备端文字识别：中文模型或拉丁文（英语等）模型，首次使用可能下载模型。
 */
class LocalOcrRecognizer(
    engine: OcrEngine,
) {

    private val recognizer = when (engine) {
        OcrEngine.CHINESE -> TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build(),
        )
        OcrEngine.ENGLISH -> TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS,
        )
    }

    suspend fun recognize(bitmap: Bitmap): String = withContext(Dispatchers.Default) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image).await().text.trim()
    }

    fun close() {
        recognizer.close()
    }
}
