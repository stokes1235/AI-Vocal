package com.example.myapplication1.ocr

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.WindowManager
import com.example.myapplication1.R
import com.example.myapplication1.databinding.OverlayOcrFloatBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 在其他应用上层显示实时 OCR 文本；标题栏拖动位置，右下角拖动调节大小。
 * 需 [android.permission.SYSTEM_ALERT_WINDOW]，由 [com.example.myapplication1.capture.ScreenCaptureService] 在采集时启动。
 */
class OcrResultOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var binding: OverlayOcrFloatBinding? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val themed = ContextThemeWrapper(this, R.style.Theme_MyApplication1)
        val b = OverlayOcrFloatBinding.inflate(LayoutInflater.from(themed))
        binding = b

        val wm = windowManager!!
        val bounds = wm.currentWindowMetrics.bounds
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()

        val minW = dp(140)
        val minH = dp(100)
        val maxW = (bounds.width() - dp(16)).coerceAtLeast(minW)
        val maxH = (bounds.height() - dp(48)).coerceAtLeast(minH)
        val initW = dp(300).coerceIn(minW, maxW)
        val initH = dp(220).coerceIn(minH, maxH)

        val params = WindowManager.LayoutParams(
            initW,
            initH,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(80)
        }
        layoutParams = params

        wm.addView(b.root, params)

        scope.launch {
            OcrResultBus.publish.collect { pub ->
                withContext(Dispatchers.Main) {
                    binding?.ocrFloatText?.text = OcrResultBus.formatDisplayLine(
                        pub,
                        getString(R.string.ocr_float_empty),
                    )
                }
            }
        }

        var dragLastX = 0f
        var dragLastY = 0f
        b.ocrFloatHeader.setOnTouchListener { _, e ->
            val p = layoutParams ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragLastX = e.rawX
                    dragLastY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - dragLastX
                    val dy = e.rawY - dragLastY
                    dragLastX = e.rawX
                    dragLastY = e.rawY
                    p.x = (p.x + dx).toInt()
                    p.y = (p.y + dy).toInt()
                    windowManager?.updateViewLayout(b.root, p)
                    true
                }
                else -> false
            }
        }

        var resizeStartRawX = 0f
        var resizeStartRawY = 0f
        var resizeStartW = 0
        var resizeStartH = 0
        b.ocrFloatResize.setOnTouchListener { _, e ->
            val p = layoutParams ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resizeStartRawX = e.rawX
                    resizeStartRawY = e.rawY
                    resizeStartW = p.width
                    resizeStartH = p.height
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dw = (e.rawX - resizeStartRawX).toInt()
                    val dh = (e.rawY - resizeStartRawY).toInt()
                    p.width = (resizeStartW + dw).coerceIn(minW, maxW)
                    p.height = (resizeStartH + dh).coerceIn(minH, maxH)
                    windowManager?.updateViewLayout(b.root, p)
                    true
                }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        binding?.let { b ->
            runCatching { windowManager?.removeView(b.root) }
        }
        binding = null
        layoutParams = null
        windowManager = null
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            context.startService(Intent(context, OcrResultOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OcrResultOverlayService::class.java))
        }
    }
}
