package com.stokes.aivocal.capture

import android.Manifest
import android.media.projection.MediaProjectionManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.lifecycleScope
import com.stokes.aivocal.NormalizedRect
import com.stokes.aivocal.R
import com.stokes.aivocal.region.RegionPreferences
import kotlinx.coroutines.launch

/**
 * 仅用于拉起系统屏幕投射授权并启动 [ScreenCaptureService]，无界面；结束后 finish，不进入主界面。
 * 须在 Manifest 中与 [MainActivity] 使用不同 taskAffinity，否则半透明窗口会与主界面同栈，透出主界面 UI。
 */
class MediaProjectionRequestActivity : AppCompatActivity() {

    private val regionPrefs by lazy { RegionPreferences(this) }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) {
            finish()
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val region = regionPrefs.getRegionOrNull() ?: NormalizedRect.defaultTextArea()
            ContextCompat.startForegroundService(
                this@MediaProjectionRequestActivity,
                ScreenCaptureService.startIntent(
                    this@MediaProjectionRequestActivity,
                    result.resultCode,
                    result.data!!,
                    region,
                ),
            )
            finish()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, R.string.capture_notification_text, Toast.LENGTH_SHORT).show()
            finish()
            return@registerForActivityResult
        }
        launchProjectionIntent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_projection_request)
        window.decorView.post { openProjectionFlow() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        window.decorView.post { openProjectionFlow() }
    }

    private fun openProjectionFlow() {
        if (isFinishing) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            PermissionChecker.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PermissionChecker.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchProjectionIntent()
    }

    private fun launchProjectionIntent() {
        if (isFinishing) return
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }
}
