package com.stokes.aivocal

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle
import android.provider.Settings
import com.stokes.aivocal.assistant.AssistantBubbleService
import com.stokes.aivocal.debug.AgentDebugLog
import com.stokes.aivocal.ocr.OcrTextRuleCorrector
import com.stokes.aivocal.tts.BundledTtsModels
import com.stokes.aivocal.tts.TtsController
import com.stokes.aivocal.tts.VoiceMappingPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MyApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var ttsController: TtsController
        private set

    val ocrTextRuleCorrector: OcrTextRuleCorrector by lazy { OcrTextRuleCorrector(this) }

    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            // #region agent log
            runCatching {
                AgentDebugLog.emit(
                    "H4",
                    "MyApplication.kt:uncaughtException",
                    "java_uncaught",
                    mapOf(
                        "thread" to thread.name,
                        "type" to e.javaClass.name,
                        "msg" to (e.message ?: "").take(400),
                    ),
                )
            }
            // #endregion
            defaultHandler?.uncaughtException(thread, e)
        }
        applicationScope.launch {
            BundledTtsModels.ensureFromAssets(this@MyApplication)
            VoiceMappingPreferences(this@MyApplication).migrateFromLegacyIfNeeded()
        }
        ttsController = TtsController(this)
        registerActivityLifecycleCallbacks(BubbleCallbacks())
    }

    private class BubbleCallbacks : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {
            if (activity is MainActivity && Settings.canDrawOverlays(activity.applicationContext)) {
                AssistantBubbleService.start(activity.applicationContext)
            }
        }
        /**
         * 须在 [MainActivity] 真正位于前台（resumed）时再停悬浮球。
         * 若用 [Activity.onStart]，从其它应用经悬浮球打开 [com.stokes.aivocal.capture.MediaProjectionRequestActivity]
         * 时，任务回到前台会使下方的 MainActivity 也收到 onStart，从而误停悬浮球、干扰系统投屏授权界面。
         */
        override fun onActivityResumed(activity: Activity) {
            if (activity is MainActivity) {
                AssistantBubbleService.stop(activity.applicationContext)
            }
        }
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
}
