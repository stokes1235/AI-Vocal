package com.stokes.aivocal.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 左侧设置面板：主内容层可向右平移露出下方面板。
 * 自完全闭合状态松手时，自确认横向拖动起的累计右移距离至少为屏幕宽度 [minOpenFraction]（默认 1/5）则吸附打开。
 */
class SlidingSettingsHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private var panel: View? = null
    private var slideSurface: View? = null
    private var panelWidthPx = 0

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragMode: DragMode = DragMode.Unknown
    private var settledTranslationAtDown = 0f

    private var interceptingHorizontal = false
    private var gestureStartX = 0f
    private var gestureStartTranslation = 0f

    private enum class DragMode { Unknown, Horizontal, Vertical }

    /** 打开阈值：相对屏幕宽度的比例（默认 1/5）。 */
    var minOpenFraction: Float = 1f / 5f
        set(value) {
            field = value.coerceIn(0.05f, 0.95f)
        }

    override fun onFinishInflate() {
        super.onFinishInflate()
        require(childCount == 2) {
            "SlidingSettingsHost 需要两个子 View：先面板、后主内容（可滑动层）"
        }
        panel = getChildAt(0)
        slideSurface = getChildAt(1)
        slideSurface?.elevation = 4f * resources.displayMetrics.density
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val density = resources.displayMetrics.density
        val maxPanel = (w * 0.85f).roundToInt().coerceAtMost((320 * density).roundToInt())
        panelWidthPx = maxPanel.coerceAtLeast((48 * density).roundToInt())
        panel?.measure(
            MeasureSpec.makeMeasureSpec(panelWidthPx, MeasureSpec.EXACTLY),
            heightMeasureSpec,
        )
        slideSurface?.measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            heightMeasureSpec,
        )
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val h = bottom - top
        val tw = right - left
        panel?.layout(0, 0, panelWidthPx, h)
        slideSurface?.layout(0, 0, tw, h)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val surf = slideSurface ?: return super.onInterceptTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragMode = DragMode.Unknown
                interceptingHorizontal = false
                settledTranslationAtDown = surf.translationX
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (dragMode == DragMode.Unknown && hypot(dx, dy) > touchSlop) {
                    dragMode = if (abs(dx) > abs(dy) * 1.25f) {
                        DragMode.Horizontal
                    } else {
                        DragMode.Vertical
                    }
                }
                if (dragMode == DragMode.Horizontal) {
                    interceptingHorizontal = true
                    gestureStartX = ev.x
                    gestureStartTranslation = surf.translationX
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragMode = DragMode.Unknown
                interceptingHorizontal = false
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val surf = slideSurface ?: return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (interceptingHorizontal) {
                    val dx = ev.x - gestureStartX
                    surf.translationX = (gestureStartTranslation + dx)
                        .coerceIn(0f, panelWidthPx.toFloat())
                    requestLayout()
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (interceptingHorizontal) {
                    val totalDx = ev.x - gestureStartX
                    settleFromGesture(surf, totalDx)
                    interceptingHorizontal = false
                    dragMode = DragMode.Unknown
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (interceptingHorizontal) {
                    settleFromGesture(surf, 0f)
                    interceptingHorizontal = false
                }
                dragMode = DragMode.Unknown
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun settleFromGesture(surf: View, totalDx: Float) {
        val screenW = width.coerceAtLeast(1)
        val thresholdPx = (screenW * minOpenFraction).coerceAtLeast(1f)
        val fromClosed = settledTranslationAtDown <= 0.5f
        val open = if (fromClosed) {
            totalDx >= thresholdPx || surf.translationX >= thresholdPx
        } else {
            surf.translationX >= panelWidthPx / 2f
        }
        animateSurfaceTo(if (open) panelWidthPx.toFloat() else 0f)
    }

    private fun animateSurfaceTo(target: Float) {
        val surf = slideSurface ?: return
        surf.animate().translationX(target).setDuration(220L)
            .setUpdateListener { requestLayout() }
            .withEndAction {
                surf.animate().setUpdateListener(null)
                requestLayout()
            }
            .start()
    }

    fun openSettings(animated: Boolean = true) {
        val surf = slideSurface ?: return
        if (!animated) {
            surf.translationX = panelWidthPx.toFloat()
            requestLayout()
            return
        }
        animateSurfaceTo(panelWidthPx.toFloat())
    }

    fun closeSettings(animated: Boolean = true) {
        val surf = slideSurface ?: return
        if (!animated) {
            surf.translationX = 0f
            requestLayout()
            return
        }
        animateSurfaceTo(0f)
    }

    fun isSettingsOpen(): Boolean =
        (slideSurface?.translationX ?: 0f) >= panelWidthPx / 2f
}
