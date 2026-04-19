package com.stokes.aivocal.region

import android.annotation.SuppressLint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import com.stokes.aivocal.NormalizedRect

/**
 * 选区拖动 / 右下角缩放。触摸坐标与布局统一为 [bounds] 表示的叠加层区域：
 * `local = raw - bounds.left/top`，与 [WindowManager.LayoutParams] 的 x/y 一致。
 */
class RegionGestureHandler(
    density: Float,
    private val onRegionChanged: () -> Unit,
) {
    private val handleTouchHalf = RegionOverlayTouchSpec.HANDLE_TOUCH_HALF_DP * density
    private val slopLeftTopPx = RegionOverlayTouchSpec.SLOP_LEFT_TOP_DP * density
    private val slopRightPx = RegionOverlayTouchSpec.SLOP_RIGHT_DP * density
    private val slopBottomPx = RegionOverlayTouchSpec.SLOP_BOTTOM_DP * density

    private var normLeft = 0.08f
    private var normTop = 0.62f
    private var normRight = 0.92f
    private var normBottom = 0.94f

    private val rectPx = RectF()
    private val brHandle = RectF()
    private val innerPassThrough = RectF()

    private var mode = Mode.NONE
    private var startNormLeft = 0f
    private var startNormTop = 0f
    private var startNormRight = 0f
    private var startNormBottom = 0f
    private var startLocalX = 0f
    private var startLocalY = 0f

    private enum class Mode { NONE, DRAG, RESIZE_BR }

    fun setInitialRegion(region: NormalizedRect) {
        normLeft = region.left
        normTop = region.top
        normRight = region.right
        normBottom = region.bottom
        onRegionChanged()
    }

    fun getNormalizedRect(): NormalizedRect =
        NormalizedRect(normLeft, normTop, normRight, normBottom)

    private fun updateRectPx(sw: Float, sh: Float) {
        rectPx.set(
            normLeft * sw,
            normTop * sh,
            normRight * sw,
            normBottom * sh,
        )
        brHandle.set(
            rectPx.right - handleTouchHalf,
            rectPx.bottom - handleTouchHalf,
            rectPx.right + handleTouchHalf,
            rectPx.bottom + handleTouchHalf,
        )
        val il = rectPx.left + slopLeftTopPx
        val it = rectPx.top + slopLeftTopPx
        val ir = rectPx.right - slopRightPx
        val ib = rectPx.bottom - slopBottomPx
        if (ir > il && ib > it) {
            innerPassThrough.set(il, it, ir, ib)
        } else {
            innerPassThrough.setEmpty()
        }
    }

    private fun isOnDragBorder(x: Float, y: Float): Boolean {
        if (innerPassThrough.isEmpty) {
            return rectPx.contains(x, y)
        }
        return rectPx.contains(x, y) && !innerPassThrough.contains(x, y)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun onTouchEvent(event: MotionEvent, bounds: Rect): Boolean {
        val sw = bounds.width().toFloat()
        val sh = bounds.height().toFloat()
        if (sw <= 0f || sh <= 0f) return false
        val x = event.rawX - bounds.left
        val y = event.rawY - bounds.top
        updateRectPx(sw, sh)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startLocalX = x
                startLocalY = y
                startNormLeft = normLeft
                startNormTop = normTop
                startNormRight = normRight
                startNormBottom = normBottom
                mode = when {
                    brHandle.contains(x, y) && !innerPassThrough.contains(x, y) -> Mode.RESIZE_BR
                    isOnDragBorder(x, y) -> Mode.DRAG
                    else -> Mode.NONE
                }
                return mode != Mode.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.DRAG) {
                    val dx = (x - startLocalX) / sw
                    val dy = (y - startLocalY) / sh
                    val rw = startNormRight - startNormLeft
                    val rh = startNormBottom - startNormTop
                    var nl = startNormLeft + dx
                    var nt = startNormTop + dy
                    nl = nl.coerceIn(0f, 1f - rw)
                    nt = nt.coerceIn(0f, 1f - rh)
                    normLeft = nl
                    normTop = nt
                    normRight = nl + rw
                    normBottom = nt + rh
                    onRegionChanged()
                    return true
                }
                if (mode == Mode.RESIZE_BR) {
                    val nx = (x / sw).coerceIn(normLeft + 0.05f, 1f)
                    val ny = (y / sh).coerceIn(normTop + 0.05f, 1f)
                    normRight = nx
                    normBottom = ny
                    onRegionChanged()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = Mode.NONE
            }
        }
        return false
    }
}
