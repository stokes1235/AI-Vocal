package com.stokes.aivocal.region

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.core.content.ContextCompat
import com.stokes.aivocal.NormalizedRect
import com.stokes.aivocal.R

/**
 * 全屏仅绘制：四周压暗 + 选区描边与手柄。窗口需带 [android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE]。
 */
class RegionVisualOverlayView(
    context: Context,
    private val regionSupplier: () -> NormalizedRect,
) : View(context) {

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99000000.toInt()
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * resources.displayMetrics.density
        color = ContextCompat.getColor(context, R.color.teal_200)
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.teal_200)
    }
    private val handleRadius = 24f * resources.displayMetrics.density
    private val rectPx = RectF()

    init {
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = regionSupplier()
        val l = r.left * w
        val t = r.top * h
        val right = r.right * w
        val btm = r.bottom * h
        canvas.drawRect(0f, 0f, w, t, dimPaint)
        canvas.drawRect(0f, btm, w, h, dimPaint)
        canvas.drawRect(0f, t, l, btm, dimPaint)
        canvas.drawRect(right, t, w, btm, dimPaint)

        rectPx.set(l, t, right, btm)
        canvas.drawRect(rectPx, borderPaint)
        canvas.drawCircle(rectPx.right, rectPx.bottom, handleRadius * 0.45f, handleFillPaint)
    }
}
