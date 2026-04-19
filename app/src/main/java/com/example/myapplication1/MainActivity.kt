package com.example.myapplication1

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.myapplication1.capture.ScreenCaptureService
import com.example.myapplication1.databinding.ActivityMainBinding
import com.example.myapplication1.databinding.ItemVoiceMappingBinding
import com.example.myapplication1.ocr.OcrEngine
import com.example.myapplication1.ocr.OcrFloatOverlayPreferences
import com.example.myapplication1.ocr.OcrLanguagePreferences
import com.example.myapplication1.ocr.OcrResultBus
import com.example.myapplication1.region.NameRegionPreferences
import com.example.myapplication1.region.RegionOverlayService
import com.example.myapplication1.region.RegionOverlayTarget
import com.example.myapplication1.region.RegionPreferences
import com.example.myapplication1.tts.VoiceMapping
import com.example.myapplication1.tts.TtsReadAloudPreferences
import com.example.myapplication1.tts.VoiceMappingPreferences
import com.example.myapplication1.tts.toTtsEngineKind
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val regionPrefs by lazy { RegionPreferences(this) }
    private val nameRegionPrefs by lazy { NameRegionPreferences(this) }
    private val voiceMappingPrefs by lazy { VoiceMappingPreferences(this) }
    private val ocrLanguagePrefs by lazy { OcrLanguagePreferences(this) }
    private val ttsReadAloudPrefs by lazy { TtsReadAloudPreferences(this) }
    private val ocrFloatOverlayPrefs by lazy { OcrFloatOverlayPreferences(this) }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) return@registerForActivityResult
        lifecycleScope.launch {
            val region = regionPrefs.getRegionOrNull() ?: NormalizedRect.defaultTextArea()
            ContextCompat.startForegroundService(
                this@MainActivity,
                ScreenCaptureService.startIntent(
                    this@MainActivity,
                    result.resultCode,
                    result.data!!,
                    region,
                ),
            )
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, R.string.capture_notification_text, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSelectRegion.setOnClickListener { openRegionOverlay(RegionOverlayTarget.MAIN) }
        binding.btnSelectNameRegion.setOnClickListener { openRegionOverlay(RegionOverlayTarget.NAME) }
        binding.btnStart.setOnClickListener { startCapturePipeline() }
        binding.btnStop.setOnClickListener { stopCapture() }

        binding.btnAddVoiceMapping.setOnClickListener { addVoiceMappingRow() }
        binding.btnSaveVoice.setOnClickListener { saveVoiceSettings() }

        ensureOverlayPermission()

        setupOcrLanguageSpinner()
        setupReadAloudSwitch()
        setupFloatOcrOverlaySwitch()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch {
                    regionPrefs.regionFlow.collect { region ->
                        binding.tvRegionStatus.text = if (region == null) {
                            getString(R.string.region_status_none)
                        } else {
                            getString(
                                R.string.region_status_saved,
                                region.left,
                                region.top,
                                region.right,
                                region.bottom,
                            )
                        }
                    }
                }
                launch {
                    nameRegionPrefs.regionFlow.collect { region ->
                        binding.tvNameRegionStatus.text = if (region == null) {
                            getString(R.string.name_region_status_none)
                        } else {
                            getString(
                                R.string.name_region_status_saved,
                                region.left,
                                region.top,
                                region.right,
                                region.bottom,
                            )
                        }
                    }
                }
                launch {
                    OcrResultBus.publish.collect { pub ->
                        binding.tvOcr.text = OcrResultBus.formatDisplayLine(
                            pub,
                            getString(R.string.ocr_result_empty_body),
                        )
                        binding.tvSpeakerLine.text = getString(R.string.ocr_speaker_line, pub.speakerId)
                    }
                }
            }
        }

        ensureNotificationPermission()
    }

    private fun loadVoiceUi(ocrEngine: OcrEngine) {
        lifecycleScope.launch {
            val kind = ocrEngine.toTtsEngineKind()
            binding.tvVoiceScope.setText(
                when (ocrEngine) {
                    OcrEngine.CHINESE -> R.string.voice_settings_scope_chinese
                    OcrEngine.ENGLISH -> R.string.voice_settings_scope_english
                },
            )
            val def = voiceMappingPrefs.getDefaultSpeakerId(kind)
            binding.etDefaultSpeakerId.setText(def.toString())
            binding.llVoiceRows.removeAllViews()
            val maps = voiceMappingPrefs.getMappings(kind)
            if (maps.isEmpty()) {
                addVoiceMappingRow()
            } else {
                for (m in maps) {
                    addVoiceMappingRow(m)
                }
            }
        }
    }

    private fun addVoiceMappingRow(mapping: VoiceMapping = VoiceMapping("", 0)) {
        val rowBinding = ItemVoiceMappingBinding.inflate(layoutInflater, binding.llVoiceRows, false)
        rowBinding.etPersonName.setText(mapping.displayName)
        rowBinding.etSpeakerId.setText(
            if (mapping.displayName.isNotEmpty()) mapping.speakerId.toString() else "",
        )
        rowBinding.btnRemoveMapping.setOnClickListener {
            binding.llVoiceRows.removeView(rowBinding.root)
            if (binding.llVoiceRows.childCount == 0) {
                addVoiceMappingRow()
            }
        }
        binding.llVoiceRows.addView(rowBinding.root)
    }

    private fun saveVoiceSettings() {
        val engines = listOf(OcrEngine.CHINESE, OcrEngine.ENGLISH)
        val ocrEngine =
            engines.getOrNull(binding.spinnerOcrLanguage.selectedItemPosition) ?: OcrEngine.CHINESE
        val kind = ocrEngine.toTtsEngineKind()
        val def = binding.etDefaultSpeakerId.text?.toString()?.toIntOrNull() ?: 0
        val list = mutableListOf<VoiceMapping>()
        for (i in 0 until binding.llVoiceRows.childCount) {
            val row = binding.llVoiceRows.getChildAt(i)
            val name = row.findViewById<TextInputEditText>(R.id.et_person_name).text?.toString()?.trim().orEmpty()
            val sid = row.findViewById<TextInputEditText>(R.id.et_speaker_id).text?.toString()?.toIntOrNull()
            if (name.isNotEmpty() && sid != null) {
                list.add(VoiceMapping(name, sid))
            }
        }
        lifecycleScope.launch {
            voiceMappingPrefs.setDefaultSpeakerId(kind, def)
            voiceMappingPrefs.setMappings(kind, list)
            Toast.makeText(this@MainActivity, R.string.voice_saved_toast, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    /**
     * 启动时若未授予「显示在其他应用上层」，则提示并打开系统设置页（与 [openRegionOverlay] 使用同一 Intent）。
     */
    private fun ensureOverlayPermission() {
        if (Settings.canDrawOverlays(this)) return
        Toast.makeText(this, R.string.toast_request_overlay_on_start, Toast.LENGTH_LONG).show()
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED -> Unit

            shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) ->
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

            else -> notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun setupOcrLanguageSpinner() {
        val engines = listOf(OcrEngine.CHINESE, OcrEngine.ENGLISH)
        val labels = listOf(
            getString(R.string.ocr_lang_chinese),
            getString(R.string.ocr_lang_english),
        )
        binding.spinnerOcrLanguage.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )
        lifecycleScope.launch {
            voiceMappingPrefs.migrateFromLegacyIfNeeded()
            val saved = ocrLanguagePrefs.getEngine()
            val idx = engines.indexOf(saved).coerceIn(0, engines.lastIndex)
            binding.spinnerOcrLanguage.setSelection(idx, false)
            binding.spinnerOcrLanguage.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long,
                    ) {
                        val engine = engines[position]
                        lifecycleScope.launch {
                            ocrLanguagePrefs.setEngine(engine)
                            loadVoiceUi(engine)
                        }
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            loadVoiceUi(saved)
        }
    }

    private fun setupReadAloudSwitch() {
        lifecycleScope.launch {
            val enabled = ttsReadAloudPrefs.isReadAloudEnabled()
            binding.switchReadAloud.setOnCheckedChangeListener(null)
            binding.switchReadAloud.isChecked = enabled
            binding.switchReadAloud.setOnCheckedChangeListener { _, checked ->
                lifecycleScope.launch {
                    ttsReadAloudPrefs.setReadAloudEnabled(checked)
                }
            }
        }
    }

    private fun setupFloatOcrOverlaySwitch() {
        lifecycleScope.launch {
            val enabled = ocrFloatOverlayPrefs.isFloatOverlayEnabled()
            binding.switchFloatOcrOverlay.setOnCheckedChangeListener(null)
            binding.switchFloatOcrOverlay.isChecked = enabled
            binding.switchFloatOcrOverlay.setOnCheckedChangeListener { _, checked ->
                lifecycleScope.launch {
                    ocrFloatOverlayPrefs.setFloatOverlayEnabled(checked)
                }
            }
        }
    }

    private fun openRegionOverlay(target: RegionOverlayTarget) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.toast_need_overlay, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
            return
        }
        RegionOverlayService.start(this, target)
    }

    private fun startCapturePipeline() {
        if (PermissionChecker.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PermissionChecker.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = manager.createScreenCaptureIntent()
        projectionLauncher.launch(captureIntent)
    }

    private fun stopCapture() {
        startService(ScreenCaptureService.buildStopIntent(this))
        OcrResultBus.clear()
        binding.tvOcr.text = getString(R.string.ocr_stopped_line)
    }
}
