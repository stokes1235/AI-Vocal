package com.stokes.aivocal.capture

import android.graphics.Bitmap
import android.media.Image
import java.nio.ByteOrder

/**
 * VirtualDisplay + RGBA_8888 单 plane，按行拷贝为 Bitmap（处理 rowStride 填充）。
 */
internal fun Image.toBitmapArgb8888(): Bitmap {
    val width = width
    val height = height
    val plane = planes[0]
    val buffer = plane.buffer.duplicate().order(ByteOrder.nativeOrder())
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride
    val row = ByteArray(rowStride)
    val pixels = IntArray(width * height)
    var out = 0
    for (y in 0 until height) {
        buffer.position(y * rowStride)
        val n = minOf(rowStride, buffer.remaining())
        buffer.get(row, 0, n)
        var xByte = 0
        for (x in 0 until width) {
            val r = row[xByte].toInt() and 0xFF
            val g = row[xByte + 1].toInt() and 0xFF
            val b = row[xByte + 2].toInt() and 0xFF
            val a = row[xByte + 3].toInt() and 0xFF
            pixels[out++] = (a shl 24) or (r shl 16) or (g shl 8) or b
            xByte += pixelStride
        }
    }
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, width, 0, 0, width, height)
    }
}
