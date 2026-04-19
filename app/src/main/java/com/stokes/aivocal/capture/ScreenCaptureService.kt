package com.stokes.aivocal.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.stokes.aivocal.MyApplication
import com.stokes.aivocal.debug.AgentDebugLog
import com.stokes.aivocal.NormalizedRect
import com.stokes.aivocal.R
import com.stokes.aivocal.ocr.LocalOcrRecognizer
import com.stokes.aivocal.ocr.OcrFloatOverlayPreferences
import com.stokes.aivocal.ocr.OcrLanguagePreferences
import com.stokes.aivocal.ocr.OcrEngine
import com.stokes.aivocal.ocr.OcrResultBus
import com.stokes.aivocal.ocr.OcrResultOverlayService
import com.stokes.aivocal.region.NameRegionPreferences
import com.stokes.aivocal.tts.TtsEngineKind
import com.stokes.aivocal.tts.TtsReadAloudPreferences
import com.stokes.aivocal.tts.VoiceMapping
import com.stokes.aivocal.tts.VoiceMappingPreferences
import com.stokes.aivocal.tts.VoiceMatcher
import com.stokes.aivocal.tts.toTtsEngineKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import kotlin.math.max

class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var recognizer: LocalOcrRecognizer? = null
    private var region: NormalizedRect? = null

    private val processExecutor = Executors.newSingleThreadExecutor()
    private var lastOcrElapsed = 0L

    /** 归一化后连续相等的样本（用于稳定判定）。 */
    private var lastOcrSampleNorm = ""
    private var ocrStableSinceElapsed = 0L
    /** 上次已下发到 [OcrResultBus] 的归一化文本，用于去重。 */
    private var lastOcrEmittedNorm = ""

    private val mainHandler = Handler(Looper.getMainLooper())

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)
    private val ocrLanguagePrefs by lazy { OcrLanguagePreferences(this) }
    private var languageCollectJob: Job? = null

    @Volatile
    private var cachedOcrEngine: OcrEngine = OcrEngine.CHINESE

    private val nameRegionPrefs by lazy { NameRegionPreferences(this) }
    private val voiceMappingPrefs by lazy { VoiceMappingPreferences(this) }
    private val ttsReadAloudPrefs by lazy { TtsReadAloudPreferences(this) }
    private val ocrFloatOverlayPrefs by lazy { OcrFloatOverlayPreferences(this) }
    private val ocrTextRuleCorrector get() = (application as MyApplication).ocrTextRuleCorrector
    private var voiceCollectJob: Job? = null
    private var readAloudCollectJob: Job? = null
    private var floatOverlayCollectJob: Job? = null

    @Volatile
    private var cachedReadAloudEnabled: Boolean = true

    @Volatile
    private var cachedNameRegion: NormalizedRect? = null

    @Volatile
    private var cachedDefaultSid: Int = 0

    @Volatile
    private var cachedMappings: List<VoiceMapping> = emptyList()

    @Volatile
    private var lastResolvedSpeakerId: Int = 0

    /** 人名区稳定 OCR 与映射表匹配成功时的展示人名；无人名区域或未匹配时为 null。 */
    @Volatile
    private var lastMatchedPersonDisplayName: String? = null

    /** 与当前声线配置绑定的识别语言；切换时重置 [lastResolvedSpeakerId] 为对应引擎默认 speaker。 */
    @Volatile
    private var lastVoiceProfileEngine: OcrEngine? = null

    private var lastNameOcrElapsed = 0L
    private var lastNameSampleNorm = ""
    private var nameStableSinceElapsed = 0L
    private var lastNameEmittedNorm = ""

    private val ttsController get() = (application as MyApplication).ttsController

    @Volatile
    private var released = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        when (intent.action) {
            ACTION_STOP -> {
                shutdownAndStop()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (projection != null) return START_NOT_STICKY
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
                val left = intent.getFloatExtra(EXTRA_LEFT, Float.NaN)
                val top = intent.getFloatExtra(EXTRA_TOP, Float.NaN)
                val right = intent.getFloatExtra(EXTRA_RIGHT, Float.NaN)
                val bottom = intent.getFloatExtra(EXTRA_BOTTOM, Float.NaN)
                if (code == 0 || data == null || left.isNaN()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                region = runCatching { NormalizedRect(left, top, right, bottom) }.getOrNull()
                if (region == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                lastOcrSampleNorm = ""
                ocrStableSinceElapsed = 0L
                lastOcrEmittedNorm = ""

                lastNameOcrElapsed = 0L
                lastNameSampleNorm = ""
                nameStableSinceElapsed = 0L
                lastNameEmittedNorm = ""
                lastMatchedPersonDisplayName = null

                ensureChannel()
                startForeground(NOTIFICATION_ID, buildNotification())

                val initialEngine = runBlocking { ocrLanguagePrefs.getEngine() }
                var appliedEngine = initialEngine
                cachedOcrEngine = initialEngine
                recognizer = LocalOcrRecognizer(initialEngine)
                languageCollectJob = serviceScope.launch {
                    ocrLanguagePrefs.engineFlow.collect { engine ->
                        if (engine == appliedEngine) return@collect
                        synchronized(this@ScreenCaptureService) {
                            if (released) return@collect
                            appliedEngine = engine
                            cachedOcrEngine = engine
                            recognizer?.close()
                            recognizer = LocalOcrRecognizer(engine)
                            lastOcrSampleNorm = ""
                            ocrStableSinceElapsed = 0L
                            lastOcrEmittedNorm = ""
                            lastNameSampleNorm = ""
                            nameStableSinceElapsed = 0L
                            lastNameEmittedNorm = ""
                            lastMatchedPersonDisplayName = null
                        }
                    }
                }

                cachedReadAloudEnabled = runBlocking { ttsReadAloudPrefs.isReadAloudEnabled() }
                readAloudCollectJob = serviceScope.launch {
                    ttsReadAloudPrefs.readAloudEnabledFlow.collect { enabled ->
                        synchronized(this@ScreenCaptureService) {
                            if (released) return@collect
                            cachedReadAloudEnabled = enabled
                            if (!enabled) {
                                ttsController.stop()
                            }
                        }
                    }
                }

                floatOverlayCollectJob = serviceScope.launch {
                    ocrFloatOverlayPrefs.floatOverlayEnabledFlow.collect { enabled ->
                        mainHandler.post {
                            if (released) return@post
                            if (enabled && Settings.canDrawOverlays(this@ScreenCaptureService)) {
                                OcrResultOverlayService.start(this@ScreenCaptureService)
                            } else {
                                OcrResultOverlayService.stop(this@ScreenCaptureService)
                            }
                        }
                    }
                }

                lastResolvedSpeakerId = runBlocking {
                    voiceMappingPrefs.migrateFromLegacyIfNeeded()
                    val engine = ocrLanguagePrefs.getEngine()
                    voiceMappingPrefs.getDefaultSpeakerId(engine.toTtsEngineKind())
                }
                voiceCollectJob = serviceScope.launch {
                    ocrLanguagePrefs.engineFlow.flatMapLatest { engine ->
                        val kind = engine.toTtsEngineKind()
                        combine(
                            nameRegionPrefs.regionFlow,
                            voiceMappingPrefs.defaultSpeakerIdFlow(kind),
                            voiceMappingPrefs.mappingsFlow(kind),
                        ) { nr, def, maps ->
                            Triple(engine, nr, def to maps)
                        }
                    }.collect { triple ->
                        val engine = triple.first
                        val nr = triple.second
                        val (def, maps) = triple.third
                        synchronized(this@ScreenCaptureService) {
                            if (released) return@collect
                            cachedNameRegion = nr
                            cachedDefaultSid = def
                            cachedMappings = maps
                            if (lastVoiceProfileEngine != engine) {
                                lastResolvedSpeakerId = def
                                lastVoiceProfileEngine = engine
                            }
                        }
                    }
                }

                val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val proj = mgr.getMediaProjection(code, data)
                if (proj == null) {
                    recognizer?.close()
                    recognizer = null
                    safeStopForegroundAndDetach()
                    stopSelf()
                    return START_NOT_STICKY
                }
                projection = proj

                projectionCallback = object : MediaProjection.Callback() {
                    override fun onStop() {
                        mainHandler.post {
                            shutdownAndStop()
                        }
                    }
                }
                proj.registerCallback(projectionCallback!!, mainHandler)

                val (width, height, dpi) = displaySize()
                val reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
                imageReader = reader
                virtualDisplay = proj.createVirtualDisplay(
                    "vn-ocr",
                    width,
                    height,
                    dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    mainHandler,
                )

                reader.setOnImageAvailableListener(
                    { r ->
                        processExecutor.execute {
                            if (released) return@execute
                            processFrame(r)
                        }
                    },
                    null,
                )

            }
        }
        return START_NOT_STICKY
    }

    private fun processFrame(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            if (released) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastOcrElapsed < OCR_INTERVAL_MS) return
            lastOcrElapsed = now

            val full = image.toBitmapArgb8888()
            val mainNorm = region ?: run {
                full.recycle()
                return
            }
            val w = full.width
            val h = full.height
            val mainRect = mainNorm.toPixelRect(w, h)
            if (mainRect.width() <= 1 || mainRect.height() <= 1) {
                full.recycle()
                return
            }
            val mainCropped = Bitmap.createBitmap(full, mainRect.left, mainRect.top, mainRect.width(), mainRect.height())

            val ocr = recognizer ?: run {
                mainCropped.recycle()
                full.recycle()
                return
            }
            val text = runBlocking { ocr.recognize(mainCropped) }
            mainCropped.recycle()

            val nr = cachedNameRegion
            if (nr != null && now - lastNameOcrElapsed >= NAME_OCR_INTERVAL_MS) {
                lastNameOcrElapsed = now
                val nameRect = nr.toPixelRect(w, h)
                if (nameRect.width() > 1 && nameRect.height() > 1) {
                    val nameCropped = Bitmap.createBitmap(full, nameRect.left, nameRect.top, nameRect.width(), nameRect.height())
                    val nameText = runBlocking { ocr.recognize(nameCropped) }
                    nameCropped.recycle()
                    considerEmittingNameOcr(nameText)?.let { stableName ->
                        val r = VoiceMatcher.resolveWithDisplayName(
                            cachedMappings,
                            cachedDefaultSid,
                            stableName,
                        )
                        lastResolvedSpeakerId = r.speakerId
                        // 与主文一致：稳定后的字符串才展示；映射命中用配置名，否则用人名区 OCR 文本（避免未配映射时恒为 null）
                        lastMatchedPersonDisplayName =
                            r.matchedDisplayName?.takeIf { it.isNotBlank() } ?: stableName
                    }
                }
            }
            full.recycle()

            if (!released) {
                considerEmittingOcr(text)?.let { mainText ->
                    val personLabel = if (cachedNameRegion == null) null else lastMatchedPersonDisplayName
                    OcrResultBus.post(mainText, lastResolvedSpeakerId, personLabel)
                    val ttsKind = when (cachedOcrEngine) {
                        OcrEngine.ENGLISH -> TtsEngineKind.English
                        OcrEngine.CHINESE -> TtsEngineKind.Chinese
                    }
                    if (cachedReadAloudEnabled && ttsController.isModelInstalled(ttsKind)) {
                        // #region agent log
                        AgentDebugLog.emit(
                            "H1",
                            "ScreenCaptureService.kt:processFrame",
                            "before_tts_speak",
                            mapOf(
                                "speakerId" to lastResolvedSpeakerId,
                                "textLen" to mainText.length,
                                "engine" to ttsKind.name,
                            ),
                        )
                        // #endregion
                        ttsController.speak(mainText, lastResolvedSpeakerId, ttsKind)
                    }
                }
            }
        } finally {
            image.close()
        }
    }

    private fun considerEmittingNameOcr(raw: String): String? {
        val n = normalizeOcrText(raw)
        if (n.isEmpty()) return null
        val t = SystemClock.elapsedRealtime()
        if (lastNameEmittedNorm.isNotEmpty() && ocrTextRoughlySame(n, lastNameEmittedNorm)) return null
        if (!ocrTextRoughlySame(n, lastNameSampleNorm)) {
            lastNameSampleNorm = n
            nameStableSinceElapsed = t
            return null
        }
        if (t - nameStableSinceElapsed >= OCR_STABLE_MS) {
            lastNameEmittedNorm = n
            return n
        }
        return null
    }

    private fun normalizeOcrText(s: String): String {
        val corrected = ocrTextRuleCorrector.correct(cachedOcrEngine, s)
        return corrected.trim().replace(Regex("\\s+"), " ")
    }

    /** ML Kit 连续帧常有空格/标点细微差别，去掉空白后比较可避免永远无法「稳定」500ms。 */
    private fun ocrTextRoughlySame(a: String, b: String): Boolean {
        if (a == b) return true
        val ca = a.replace(Regex("\\s+"), "")
        val cb = b.replace(Regex("\\s+"), "")
        return ca == cb
    }

    /**
     * 同一归一化内容需持续至少 [OCR_STABLE_MS] 才输出；与上次已输出内容 [ocrTextRoughlySame] 则不重复输出。
     */
    private fun considerEmittingOcr(raw: String): String? {
        val n = normalizeOcrText(raw)
        if (n.isEmpty()) return null
        val now = SystemClock.elapsedRealtime()
        if (lastOcrEmittedNorm.isNotEmpty() && ocrTextRoughlySame(n, lastOcrEmittedNorm)) return null
        if (!ocrTextRoughlySame(n, lastOcrSampleNorm)) {
            lastOcrSampleNorm = n
            ocrStableSinceElapsed = now
            return null
        }
        if (now - ocrStableSinceElapsed >= OCR_STABLE_MS) {
            lastOcrEmittedNorm = n
            return n
        }
        return null
    }

    private fun displaySize(): Triple<Int, Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val bounds = wm.currentWindowMetrics.bounds
        val w = max(1, bounds.width())
        val h = max(1, bounds.height())
        val dpi = resources.displayMetrics.densityDpi
        return Triple(w, h, dpi)
    }

    private fun shutdownAndStop() {
        if (released) return
        synchronized(this) {
            if (released) return
            released = true
        }

        OcrResultOverlayService.stop(this)

        languageCollectJob?.cancel()
        languageCollectJob = null
        voiceCollectJob?.cancel()
        voiceCollectJob = null
        readAloudCollectJob?.cancel()
        readAloudCollectJob = null
        floatOverlayCollectJob?.cancel()
        floatOverlayCollectJob = null
        ttsController.stop()
        serviceJob.cancel()

        imageReader?.setOnImageAvailableListener(null, null)
        processExecutor.shutdownNow()

        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null

        projectionCallback?.let { cb ->
            projection?.unregisterCallback(cb)
        }
        projectionCallback = null
        projection?.stop()
        projection = null

        recognizer?.close()
        recognizer = null

        safeStopForegroundAndDetach()
        stopSelf()
    }

    override fun onDestroy() {
        if (!released) {
            synchronized(this) {
                if (!released) {
                    released = true
                    languageCollectJob?.cancel()
                    languageCollectJob = null
                    voiceCollectJob?.cancel()
                    voiceCollectJob = null
                    readAloudCollectJob?.cancel()
                    readAloudCollectJob = null
                    floatOverlayCollectJob?.cancel()
                    floatOverlayCollectJob = null
                    runCatching { (application as MyApplication).ttsController.stop() }
                    serviceJob.cancel()
                    OcrResultOverlayService.stop(this)
                    imageReader?.setOnImageAvailableListener(null, null)
                    processExecutor.shutdownNow()
                    virtualDisplay?.release()
                    imageReader?.close()
                    projectionCallback?.let { projection?.unregisterCallback(it) }
                    projection?.stop()
                    recognizer?.close()
                    safeStopForegroundAndDetach()
                }
            }
        }
        super.onDestroy()
    }

    private fun safeStopForegroundAndDetach() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.capture_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .addAction(0, getString(R.string.capture_stop), stop)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 1001
        private const val OCR_INTERVAL_MS = 300L
        /** 人名区 OCR 与主区同频，便于同一帧内更新声线与展示前缀。 */
        private const val NAME_OCR_INTERVAL_MS = OCR_INTERVAL_MS
        /** 归一化识别结果连续不变至少该时长后才输出。 */
        private const val OCR_STABLE_MS = 400L

        const val ACTION_START = "com.stokes.aivocal.capture.START"
        const val ACTION_STOP = "com.stokes.aivocal.capture.STOP"

        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_LEFT = "left"
        private const val EXTRA_TOP = "top"
        private const val EXTRA_RIGHT = "right"
        private const val EXTRA_BOTTOM = "bottom"

        fun startIntent(context: Context, resultCode: Int, data: Intent, region: NormalizedRect): Intent =
            Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
                putExtra(EXTRA_LEFT, region.left)
                putExtra(EXTRA_TOP, region.top)
                putExtra(EXTRA_RIGHT, region.right)
                putExtra(EXTRA_BOTTOM, region.bottom)
            }

        /** 与通知栏「停止」一致：通过 [ACTION_STOP] 进入 [shutdownAndStop]（与 [Context.stopService] 直停相比路径更明确）。 */
        fun buildStopIntent(context: Context): Intent =
            Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP)
    }
}
