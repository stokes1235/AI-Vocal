package com.example.myapplication1.region

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.example.myapplication1.NormalizedRect
import com.example.myapplication1.R
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 压暗+边框为单窗口且 [FLAG_NOT_TOUCHABLE]；交互为窄条/手柄/底部按钮等小窗口。
 * 触摸坐标与 [windowBounds] 对齐，避免 raw 与 displayMetrics 不一致导致误吞触摸。
 */
class RegionOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null

    private var visualView: RegionVisualOverlayView? = null
    private val stripViews = arrayOfNulls<View>(4)
    private var stripParams = arrayOfNulls<WindowManager.LayoutParams>(4)
    private var handleView: View? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var controlBar: View? = null

    /** 来自 [onStartCommand] 的 [Intent]，[Service.onCreate] 中不可用。 */
    private var overlayTarget: RegionOverlayTarget = RegionOverlayTarget.MAIN

    private lateinit var gesture: RegionGestureHandler
    private var overlayType = 0

    private val configurationChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            relayoutAfterDisplayChange()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val themed = ContextThemeWrapper(this, R.style.Theme_MyApplication1)
        val density = themed.resources.displayMetrics.density

        overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        gesture = RegionGestureHandler(density) {
            visualView?.invalidate()
            layoutInteractionWindows()
        }

        val visual = RegionVisualOverlayView(themed) { gesture.getNormalizedRect() }
        visualView = visual
        addNotTouchableFullScreen(visual)

        for (i in 0 until 4) {
            val v = View(themed).apply {
                isClickable = false
                setOnTouchListener { _, ev ->
                    gesture.onTouchEvent(ev, windowBounds())
                }
            }
            stripViews[i] = v
            val p = newTouchableLayoutParams(1, 1, 0, 0)
            stripParams[i] = p
            windowManager?.addView(v, p)
        }

        val hv = View(themed).apply {
            isClickable = false
            setOnTouchListener { _, ev ->
                gesture.onTouchEvent(ev, windowBounds())
            }
        }
        handleView = hv
        val hp = newTouchableLayoutParams(1, 1, 0, 0)
        handleParams = hp
        windowManager?.addView(hv, hp)

        val inflater = LayoutInflater.from(themed)
        val bar = inflater.inflate(R.layout.overlay_region_control_bar, null, false)
        controlBar = bar
        val bp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            touchableOverlayFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        windowManager?.addView(bar, bp)

        layoutInteractionWindows()
        registerConfigurationReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        overlayTarget = runCatching {
            RegionOverlayTarget.valueOf(intent?.getStringExtra(EXTRA_TARGET) ?: RegionOverlayTarget.MAIN.name)
        }.getOrDefault(RegionOverlayTarget.MAIN)
        applyOverlayTarget()
        return START_NOT_STICKY
    }

    private fun applyOverlayTarget() {
        val bar = controlBar ?: return
        val target = overlayTarget
        bar.findViewById<TextView>(R.id.tv_overlay_region_title).setText(
            when (target) {
                RegionOverlayTarget.MAIN -> R.string.overlay_region_title_main
                RegionOverlayTarget.NAME -> R.string.overlay_region_title_name
            },
        )

        bar.findViewById<MaterialButton>(R.id.btn_cancel).setOnClickListener { stopSelf() }
        bar.findViewById<MaterialButton>(R.id.btn_ok).setOnClickListener {
            scope.launch {
                when (target) {
                    RegionOverlayTarget.MAIN -> RegionPreferences(this@RegionOverlayService).save(gesture.getNormalizedRect())
                    RegionOverlayTarget.NAME -> NameRegionPreferences(this@RegionOverlayService).save(gesture.getNormalizedRect())
                }
                stopSelf()
            }
        }

        scope.launch {
            val existing = when (target) {
                RegionOverlayTarget.MAIN -> RegionPreferences(this@RegionOverlayService).getRegionOrNull()
                RegionOverlayTarget.NAME -> NameRegionPreferences(this@RegionOverlayService).getRegionOrNull()
            }
            val initial = existing ?: NormalizedRect.defaultTextArea()
            gesture.setInitialRegion(initial)
        }

        layoutInteractionWindows()
    }

    private fun registerConfigurationReceiver() {
        ContextCompat.registerReceiver(
            this,
            configurationChangedReceiver,
            IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /**
     * 横竖屏等导致 [windowBounds] 变化时，刷新全屏叠加层尺寸与条/手柄位置；归一化选区不变，仅重映射像素。
     */
    private fun relayoutAfterDisplayChange() {
        val wm = windowManager ?: return
        visualView?.let { v ->
            val lp = v.layoutParams as? WindowManager.LayoutParams ?: return@let
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.height = WindowManager.LayoutParams.MATCH_PARENT
            runCatching { wm.updateViewLayout(v, lp) }
        }
        controlBar?.let { bar ->
            val lp = bar.layoutParams as? WindowManager.LayoutParams ?: return@let
            runCatching { wm.updateViewLayout(bar, lp) }
        }
        layoutInteractionWindows()
        visualView?.invalidate()
    }

    /** 与 raw 触摸一致，且与截屏管线均用 [WindowManager.getCurrentWindowMetrics]，保证归一化选区与 Bitmap 尺寸对齐。 */
    private fun windowBounds(): Rect =
        windowManager?.currentWindowMetrics?.bounds
            ?: run {
                val m = resources.displayMetrics
                Rect(0, 0, m.widthPixels, m.heightPixels)
            }

    private fun touchableOverlayFlags(): Int =
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

    private fun addNotTouchableFullScreen(view: View) {
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        windowManager?.addView(view, p)
    }

    private fun newTouchableLayoutParams(w: Int, h: Int, x: Int, y: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            w,
            h,
            overlayType,
            touchableOverlayFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
    }

    private fun layoutInteractionWindows() {
        val wm = windowManager ?: return
        val b = windowBounds()
        val sw = b.width()
        val sh = b.height()
        if (sw <= 0 || sh <= 0) return
        val r = gesture.getNormalizedRect()
        val d = resources.displayMetrics.density
        val slopT = RegionOverlayTouchSpec.SLOP_LEFT_TOP_DP * d
        val slopL = RegionOverlayTouchSpec.SLOP_LEFT_TOP_DP * d
        val slopR = RegionOverlayTouchSpec.SLOP_RIGHT_DP * d
        val slopB = RegionOverlayTouchSpec.SLOP_BOTTOM_DP * d
        val handleTouchHalf = RegionOverlayTouchSpec.HANDLE_TOUCH_HALF_DP * d

        val nl = r.left * sw
        val nt = r.top * sh
        val nr = r.right * sw
        val nb = r.bottom * sh

        val topH = max(1, slopT.roundToInt())
        val botH = max(1, slopB.roundToInt())
        val leftW = max(1, slopL.roundToInt())
        val rightW = max(1, slopR.roundToInt())

        val leftTop = (nt + slopT).roundToInt()
        val leftBottom = (nb - slopB).roundToInt()
        val midHeight = max(1, leftBottom - leftTop)

        val ox = b.left
        val oy = b.top

        updateStrip(0, ox + 0, oy + nt.roundToInt(), sw, topH, wm)
        updateStrip(1, ox + 0, oy + (nb - slopB).roundToInt(), sw, botH, wm)
        updateStrip(2, ox + nl.roundToInt(), oy + leftTop, leftW, midHeight, wm)
        updateStrip(3, ox + (nr - slopR).roundToInt(), oy + leftTop, rightW, midHeight, wm)

        val hh = max(1, (handleTouchHalf * 2).roundToInt())
        val hp = handleParams ?: return
        hp.x = ox + (nr - handleTouchHalf * 2).roundToInt()
        hp.y = oy + (nb - handleTouchHalf * 2).roundToInt()
        hp.width = hh
        hp.height = hh
        handleView?.let { runCatching { wm.updateViewLayout(it, hp) } }
    }

    private fun updateStrip(
        index: Int,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        wm: WindowManager,
    ) {
        val v = stripViews[index] ?: return
        val p = stripParams[index] ?: return
        p.x = x
        p.y = y
        p.width = max(1, w)
        p.height = max(1, h)
        runCatching { wm.updateViewLayout(v, p) }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(configurationChangedReceiver) }
        scope.cancel()
        val wm = windowManager
        if (wm != null) {
            controlBar?.let { runCatching { wm.removeView(it) } }
            handleView?.let { runCatching { wm.removeView(it) } }
            stripViews.forEach { v -> v?.let { runCatching { wm.removeView(it) } } }
            visualView?.let { runCatching { wm.removeView(it) } }
        }
        controlBar = null
        handleView = null
        handleParams = null
        for (i in stripViews.indices) {
            stripViews[i] = null
            stripParams[i] = null
        }
        visualView = null
        windowManager = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_TARGET = "region_overlay_target"

        fun start(context: Context, target: RegionOverlayTarget = RegionOverlayTarget.MAIN) {
            context.startService(
                Intent(context, RegionOverlayService::class.java).putExtra(EXTRA_TARGET, target.name),
            )
        }
    }
}

enum class RegionOverlayTarget {
    MAIN,
    NAME,
}
