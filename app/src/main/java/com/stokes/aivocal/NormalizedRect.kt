package com.stokes.aivocal

import android.graphics.Rect

/**
 * 相对整屏的归一化矩形，坐标为 0..1，便于不同分辨率下复用。
 */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f)
        require(right > left && bottom > top)
    }

    fun toPixelRect(width: Int, height: Int): Rect {
        val l = (left * width).toInt().coerceIn(0, width - 1)
        val t = (top * height).toInt().coerceIn(0, height - 1)
        val r = (right * width).toInt().coerceIn(l + 1, width)
        val b = (bottom * height).toInt().coerceIn(t + 1, height)
        return Rect(l, t, r, b)
    }

    companion object {
        fun defaultTextArea(): NormalizedRect = NormalizedRect(
            left = 0.08f,
            top = 0.62f,
            right = 0.92f,
            bottom = 0.94f,
        )

        /** 整屏识别区域（相对采集位图全宽高）。 */
        fun fullScreen(): NormalizedRect = NormalizedRect(
            left = 0f,
            top = 0f,
            right = 1f,
            bottom = 1f,
        )
    }
}
