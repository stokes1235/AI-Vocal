package com.stokes.aivocal.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.stokes.aivocal.BuildConfig
import com.stokes.aivocal.MainActivity
import com.stokes.aivocal.R
import com.stokes.aivocal.capture.ScreenCaptureService
import com.stokes.aivocal.databinding.OverlayAssistantBubbleBinding
import com.stokes.aivocal.ocr.OcrResultBus
import com.stokes.aivocal.capture.MediaProjectionRequestActivity
import com.stokes.aivocal.region.RegionOverlayService
import com.stokes.aivocal.region.RegionOverlayTarget
import com.stokes.aivocal.ocr.OcrEngine
import com.stokes.aivocal.ocr.OcrLanguagePreferences
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 离开 [MainActivity] 后显示球形悬浮按钮，展开为框选主区域 / 框选人名区域 / 开始识别 / 结束识别。
 * 小球：长按约 0.3s 进入拖动模式并随手指移动，松手结束；短按（0.3s 内抬起且自按下起位移未超过 slop）切换菜单。长按不因小幅移动而取消。
 * 首次布局后把悬浮窗从 BOTTOM|END 转为 TOP|START + 屏幕绝对 x/y，避免部分机型上 updateViewLayout 成功但窗口不位移的问题。
 * 使用前台服务以满足自后台启动的限制。
 */
class AssistantBubbleService : Service() {

    private fun logD(msg: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, msg)
    }

    private fun logI(msg: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, msg)
    }

    private fun logE(msg: String, e: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        if (e != null) Log.e(TAG, msg, e) else Log.e(TAG, msg)
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ocrLanguagePrefs by lazy { OcrLanguagePreferences(this) }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var ballLongPressRunnable: Runnable? = null

    private var windowManager: WindowManager? = null
    private var binding: OverlayAssistantBubbleBinding? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var overlayType = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        ensureChannel()
    }

    private fun safeUpdateOverlayLayout(
        wm: WindowManager,
        root: View,
        params: WindowManager.LayoutParams,
        reason: String,
    ) {
        runCatching { wm.updateViewLayout(root, params) }
            .onFailure { e ->
                logE("updateViewLayout failed ($reason) x=${params.x} y=${params.y}", e)
            }
            .onSuccess {
                logD("updateViewLayout ok ($reason) x=${params.x} y=${params.y}")
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = buildNotification()
        startForeground(
            NOTIFICATION_ID,
            n,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (binding == null) {
            attachBubbleWindow()
        }
        return START_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.bubble_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.bubble_notification_title))
            .setContentText(getString(R.string.bubble_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun attachBubbleWindow() {
        val themed = ContextThemeWrapper(this, R.style.Theme_AiVocal)
        val b = OverlayAssistantBubbleBinding.inflate(LayoutInflater.from(themed))
        binding = b

        val wm = windowManager ?: return
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = -dp(12)
            y = -dp(24)
        }
        layoutParams = params

        wm.addView(b.root, params)
        logD("addView overlay root=${b.root} params x=${params.x} y=${params.y} gravity=${params.gravity}")

        var migratedOverlayToTopStart = false
        b.root.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (migratedOverlayToTopStart || b.root.width == 0 || b.root.height == 0) return
                migratedOverlayToTopStart = true
                b.root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val p = layoutParams ?: return
                val loc = IntArray(2)
                b.root.getLocationOnScreen(loc)
                p.gravity = Gravity.TOP or Gravity.START
                p.x = loc[0]
                p.y = loc[1]
                safeUpdateOverlayLayout(wm, b.root, p, "migrate-overlay-top-start")
            }
        })

        var dragStartX = 0f
        var dragStartY = 0f
        var paramStartX = 0
        var paramStartY = 0
        var dragging = false
        val slop = ViewConfiguration.get(themed).scaledTouchSlop.toFloat()

        /** 球与展开菜单共用一套位移状态，整窗随 [b.root] 一起移动；菜单空白处也可拖。 */
        fun handleWindowDragTouch(
            e: MotionEvent,
            onTapWhenNotDrag: () -> Unit,
        ): Boolean {
            val p = layoutParams ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = e.rawX
                    dragStartY = e.rawY
                    paramStartX = p.x
                    paramStartY = p.y
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - dragStartX
                    val dy = e.rawY - dragStartY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                    }
                    if (dragging) {
                        p.x = paramStartX + (e.rawX - dragStartX).roundToInt()
                        p.y = paramStartY + (e.rawY - dragStartY).roundToInt()
                        safeUpdateOverlayLayout(wm, b.root, p, "rootOrMenu-drag")
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        onTapWhenNotDrag()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }

        b.assistantRoot.setOnTouchListener { _, e ->
            handleWindowDragTouch(e) { }
        }

        val longPressMs = 300L
        var ballDragMode = false
        var ballMovedBeyondSlop = false
        var ballDownRawX = 0f
        var ballDownRawY = 0f
        var ballDownAt = 0L
        var lastTouchRawX = 0f
        var lastTouchRawY = 0f
        var trackLastRawX = 0f
        var trackLastRawY = 0f
        var ballDragMoveLogged = false

        // 长按计时器不要在「小幅移动」时取消，否则手抖/屏边缘会永远进不了拖动。
        ballLongPressRunnable = Runnable {
            ballDragMode = true
            trackLastRawX = lastTouchRawX
            trackLastRawY = lastTouchRawY
            ballDragMoveLogged = false
            logI("longPress: enter drag mode lastTouch=($lastTouchRawX,$lastTouchRawY) params=(${layoutParams?.x},${layoutParams?.y})")
        }

        b.assistantBall.setOnTouchListener { _, e ->
            val p = layoutParams ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ballDragMode = false
                    ballMovedBeyondSlop = false
                    ballDragMoveLogged = false
                    ballDownRawX = e.rawX
                    ballDownRawY = e.rawY
                    ballDownAt = SystemClock.uptimeMillis()
                    lastTouchRawX = e.rawX
                    lastTouchRawY = e.rawY
                    logD("ball DOWN raw=(${e.rawX},${e.rawY}) postDelayed ${longPressMs}ms")
                    ballLongPressRunnable?.let { r ->
                        mainHandler.removeCallbacks(r)
                        mainHandler.postDelayed(r, longPressMs)
                    }
                    return@setOnTouchListener true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - ballDownRawX
                    val dy = e.rawY - ballDownRawY
                    if (abs(dx) > slop || abs(dy) > slop) {
                        ballMovedBeyondSlop = true
                    }
                    lastTouchRawX = e.rawX
                    lastTouchRawY = e.rawY
                    if (ballDragMode) {
                        if (!ballDragMoveLogged) {
                            ballDragMoveLogged = true
                            logD("ball MOVE first in drag mode raw=(${e.rawX},${e.rawY})")
                        }
                        p.x += (e.rawX - trackLastRawX).roundToInt()
                        p.y += (e.rawY - trackLastRawY).roundToInt()
                        trackLastRawX = e.rawX
                        trackLastRawY = e.rawY
                        safeUpdateOverlayLayout(wm, b.root, p, "ball-longPress-drag")
                    }
                    return@setOnTouchListener true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    ballLongPressRunnable?.let(mainHandler::removeCallbacks)
                    logD(
                        "ball ${if (e.actionMasked == MotionEvent.ACTION_UP) "UP" else "CANCEL"} " +
                            "ballDragMode=$ballDragMode movedBeyondSlop=$ballMovedBeyondSlop",
                    )
                    if (!ballDragMode) {
                        val tapMs = SystemClock.uptimeMillis() - ballDownAt
                        if (!ballMovedBeyondSlop && tapMs < longPressMs) {
                            toggleMenu(b)
                        }
                    }
                    ballDragMode = false
                    return@setOnTouchListener true
                }
            }
            false
        }

        b.assistantMenu.setOnTouchListener { _, e ->
            handleWindowDragTouch(e) { }
        }

        setupOcrLanguageSpinner(b)

        fun startRegionOverlayIfPermitted(target: RegionOverlayTarget) {
            hideMenu(b)
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return
            }
            RegionOverlayService.start(this, target)
        }

        b.btnMenuRegion.setOnClickListener {
            startRegionOverlayIfPermitted(RegionOverlayTarget.MAIN)
        }
        b.btnMenuNameRegion.setOnClickListener {
            startRegionOverlayIfPermitted(RegionOverlayTarget.NAME)
        }
        b.btnMenuStart.setOnClickListener {
            hideMenu(b)
            // 仅拉起系统屏幕投射授权（系统内可选整屏/单应用等）；识别区域沿用「框选区域」已保存的选区，不再进入本应用主界面或二次框选。
            startActivity(
                Intent(this, MediaProjectionRequestActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
        b.btnMenuStop.setOnClickListener {
            hideMenu(b)
            // 仅停止采集与浮窗 OCR；不 stop AssistantBubbleService，悬浮球保留
            startService(ScreenCaptureService.buildStopIntent(this))
            OcrResultBus.clear()
        }
    }

    private fun setupOcrLanguageSpinner(b: OverlayAssistantBubbleBinding) {
        val engines = listOf(OcrEngine.CHINESE, OcrEngine.ENGLISH)
        val labels = listOf(
            getString(R.string.ocr_lang_chinese),
            getString(R.string.ocr_lang_english),
        )
        b.bubbleSpinnerOcrLanguage.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )
        serviceScope.launch {
            val saved = ocrLanguagePrefs.getEngine()
            val idx = engines.indexOf(saved).coerceIn(0, engines.lastIndex)
            b.bubbleSpinnerOcrLanguage.setSelection(idx, false)
            b.bubbleSpinnerOcrLanguage.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long,
                    ) {
                        val engine = engines[position]
                        serviceScope.launch {
                            ocrLanguagePrefs.setEngine(engine)
                        }
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
        }
    }

    private fun toggleMenu(b: OverlayAssistantBubbleBinding) {
        val menu = b.assistantMenu
        menu.visibility = if (menu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun hideMenu(b: OverlayAssistantBubbleBinding) {
        b.assistantMenu.visibility = View.GONE
    }

    override fun onDestroy() {
        serviceScope.cancel()
        ballLongPressRunnable?.let { mainHandler.removeCallbacks(it) }
        ballLongPressRunnable = null
        binding?.let { b ->
            runCatching { windowManager?.removeView(b.root) }
                .onFailure { logE("removeView failed", it) }
        }
        binding = null
        layoutParams = null
        windowManager = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AssistantBubble"
        private const val CHANNEL_ID = "assistant_bubble"
        private const val NOTIFICATION_ID = 1002

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AssistantBubbleService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AssistantBubbleService::class.java))
        }
    }
}
