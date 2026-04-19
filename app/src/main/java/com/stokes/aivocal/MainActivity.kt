package com.stokes.aivocal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.stokes.aivocal.MyApplication
import com.stokes.aivocal.about.AboutContent
import com.stokes.aivocal.capture.ScreenCaptureService
import com.stokes.aivocal.databinding.ActivityMainBinding
import com.stokes.aivocal.databinding.DialogEditOcrRulesBinding
import com.stokes.aivocal.databinding.DialogOcrTimingBinding
import com.stokes.aivocal.databinding.ItemVoiceMappingBinding
import com.stokes.aivocal.ocr.OcrEngine
import com.stokes.aivocal.ocr.OcrRuleUserFiles
import com.stokes.aivocal.ocr.OcrTimingPreferences
import com.stokes.aivocal.ocr.OcrFloatOverlayPreferences
import com.stokes.aivocal.ocr.OcrLanguagePreferences
import com.stokes.aivocal.ocr.OcrResultBus
import com.stokes.aivocal.region.NameRegionPreferences
import com.stokes.aivocal.region.RegionOverlayService
import com.stokes.aivocal.region.RegionOverlayTarget
import com.stokes.aivocal.region.RegionPreferences
import com.stokes.aivocal.tts.VoiceMapping
import com.stokes.aivocal.tts.TtsReadAloudPreferences
import com.stokes.aivocal.tts.VoiceMappingPreferences
import com.stokes.aivocal.tts.toTtsEngineKind
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    companion object {
        private const val PREFS_ONBOARDING = "aivocal_onboarding"
        private const val KEY_FIRST_ABOUT_DONE = "first_about_ack"
    }
    private val regionPrefs by lazy { RegionPreferences(this) }
    private val nameRegionPrefs by lazy { NameRegionPreferences(this) }
    private val voiceMappingPrefs by lazy { VoiceMappingPreferences(this) }
    private val ocrLanguagePrefs by lazy { OcrLanguagePreferences(this) }
    private val ttsReadAloudPrefs by lazy { TtsReadAloudPreferences(this) }
    private val ocrFloatOverlayPrefs by lazy { OcrFloatOverlayPreferences(this) }
    private val ocrTimingPrefs by lazy { OcrTimingPreferences(this) }

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

        setupActionBarTitleWithMenuHint()

        binding.btnSelectRegion.setOnClickListener { openRegionOverlay(RegionOverlayTarget.MAIN) }
        binding.btnSelectNameRegion.setOnClickListener { openRegionOverlay(RegionOverlayTarget.NAME) }
        binding.btnStart.setOnClickListener { startCapturePipeline() }
        binding.btnStop.setOnClickListener { stopCapture() }

        binding.btnAddVoiceMapping.setOnClickListener { addVoiceMappingRow() }
        binding.btnSaveVoice.setOnClickListener { saveVoiceSettings() }
        ensureOverlayPermission()

        setupOcrLanguageSpinner()
        setupReadAloudSwitch(binding.switchReadAloudSettings)
        setupFloatOcrOverlaySwitch(binding.switchFloatOcrSettings)
        binding.btnAboutSettings.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        binding.btnOperationInstructions.setOnClickListener { showOperationInstructionsDialog() }
        binding.btnOcrTiming.setOnClickListener { showOcrTimingDialog() }
        binding.btnEditPhrases.setOnClickListener { showEditPhrasesDialog() }
        binding.btnEditWhitelist.setOnClickListener { showEditWhitelistDialog() }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (binding.slideHost.isSettingsOpen()) {
                        binding.slideHost.closeSettings()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            },
        )

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

        binding.root.post { showFirstLaunchAboutIfNeeded() }
    }

    private fun setupActionBarTitleWithMenuHint() {
        val bar = supportActionBar ?: return
        val custom = layoutInflater.inflate(R.layout.custom_action_bar_title, null)
        val lp = ActionBar.LayoutParams(
            ActionBar.LayoutParams.WRAP_CONTENT,
            ActionBar.LayoutParams.WRAP_CONTENT,
            Gravity.START or Gravity.CENTER_VERTICAL,
        )
        bar.setDisplayShowCustomEnabled(true)
        bar.setDisplayShowTitleEnabled(false)
        bar.setCustomView(custom, lp)
    }

    private fun showFirstLaunchAboutIfNeeded() {
        val prefs = getSharedPreferences(PREFS_ONBOARDING, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_FIRST_ABOUT_DONE, false)) return
        val dialogView = layoutInflater.inflate(R.layout.dialog_first_launch_about, binding.root, false)
        val dm = resources.displayMetrics
        val density = dm.density
        // 为滚动区指定固定高度，保证「关闭」在下方可见；不修改父容器 LayoutParams 类型，避免 FrameLayout 强转崩溃
        val reservedPx = (200 * density).toInt()
        val scrollH = ((dm.heightPixels * 0.58f).toInt() - reservedPx)
            .coerceIn((160 * density).toInt(), (460 * density).toInt())
        dialogView.findViewById<ScrollView>(R.id.scroll_first_about).layoutParams =
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, scrollH)
        dialogView.findViewById<TextView>(R.id.tv_first_about_body).text = AboutContent.load(this)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialogView.findViewById<MaterialButton>(R.id.btn_first_about_close).setOnClickListener {
            prefs.edit().putBoolean(KEY_FIRST_ABOUT_DONE, true).apply()
            dialog.dismiss()
        }
        dialog.show()
        val w = (dm.widthPixels * 0.92f).toInt().coerceAtMost(dm.widthPixels)
        dialog.window?.setLayout(w, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
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
        rowBinding.etPersonAliases.setText(mapping.aliases.joinToString("，"))
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
            val aliasesRaw = row.findViewById<TextInputEditText>(R.id.et_person_aliases).text?.toString().orEmpty()
            val sid = row.findViewById<TextInputEditText>(R.id.et_speaker_id).text?.toString()?.toIntOrNull()
            if (name.isNotEmpty() && sid != null) {
                list.add(VoiceMapping(name, sid, parseVoiceAliases(aliasesRaw)))
            }
        }
        lifecycleScope.launch {
            voiceMappingPrefs.setDefaultSpeakerId(kind, def)
            voiceMappingPrefs.setMappings(kind, list)
            Toast.makeText(this@MainActivity, R.string.voice_saved_toast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseVoiceAliases(raw: String): List<String> =
        raw.split(Regex("[,，、;；|]")).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

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

    private fun setupReadAloudSwitch(target: SwitchMaterial) {
        lifecycleScope.launch {
            val enabled = ttsReadAloudPrefs.isReadAloudEnabled()
            target.setOnCheckedChangeListener(null)
            target.isChecked = enabled
            target.setOnCheckedChangeListener { _, checked ->
                lifecycleScope.launch {
                    ttsReadAloudPrefs.setReadAloudEnabled(checked)
                }
            }
        }
    }

    private fun setupFloatOcrOverlaySwitch(target: SwitchMaterial) {
        lifecycleScope.launch {
            val enabled = ocrFloatOverlayPrefs.isFloatOverlayEnabled()
            target.setOnCheckedChangeListener(null)
            target.isChecked = enabled
            target.setOnCheckedChangeListener { _, checked ->
                lifecycleScope.launch {
                    ocrFloatOverlayPrefs.setFloatOverlayEnabled(checked)
                }
            }
        }
    }

    private fun loadOperationInstructionsText(): String =
        resources.openRawResource(R.raw.operation_instructions).bufferedReader().use { it.readText() }

    private fun showOcrTimingDialog() {
        lifecycleScope.launch {
            val (storedI, storedS) = ocrTimingPrefs.timingFlow.first()
            val builder = MaterialAlertDialogBuilder(this@MainActivity)
            val dialogInflater = LayoutInflater.from(builder.context)
            val dialogBinding = DialogOcrTimingBinding.inflate(dialogInflater)
            dialogBinding.etOcrIntervalMs.setText(storedI.toString())
            dialogBinding.etOcrStableMs.setText(storedS.toString())
            val dialog = builder
                .setTitle(R.string.settings_ocr_timing_dialog_title)
                .setView(dialogBinding.root)
                .setPositiveButton(R.string.dialog_save, null)
                .setNegativeButton(R.string.dialog_cancel, null)
                .create()
            dialog.setOnShowListener {
                val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE) ?: return@setOnShowListener
                positive.setOnClickListener {
                    lifecycleScope.launch {
                        val (liveI, liveS) = ocrTimingPrefs.timingFlow.first()
                        val intervalStr = dialogBinding.etOcrIntervalMs.text?.toString()?.trim().orEmpty()
                        val stableStr = dialogBinding.etOcrStableMs.text?.toString()?.trim().orEmpty()

                        val effI = if (intervalStr.isEmpty()) {
                            liveI
                        } else {
                            val v = intervalStr.toIntOrNull()
                            if (v == null || v < OcrTimingPreferences.INTERVAL_MIN || v > OcrTimingPreferences.INTERVAL_MAX) {
                                Toast.makeText(
                                    this@MainActivity,
                                    R.string.toast_ocr_interval_invalid,
                                    Toast.LENGTH_SHORT,
                                ).show()
                                return@launch
                            }
                            v
                        }

                        val effS = if (stableStr.isEmpty()) {
                            liveS
                        } else {
                            val v = stableStr.toIntOrNull()
                            if (v == null || v < OcrTimingPreferences.STABLE_MIN || v > OcrTimingPreferences.STABLE_MAX) {
                                Toast.makeText(
                                    this@MainActivity,
                                    R.string.toast_ocr_stable_invalid,
                                    Toast.LENGTH_SHORT,
                                ).show()
                                return@launch
                            }
                            v
                        }

                        if (effS < 1.1 * effI || effS > 2.5 * effI) {
                            Toast.makeText(
                                this@MainActivity,
                                R.string.toast_ocr_timing_ratio_invalid,
                                Toast.LENGTH_SHORT,
                            ).show()
                            return@launch
                        }

                        ocrTimingPrefs.setTiming(effI, effS)
                        Toast.makeText(
                            this@MainActivity,
                            R.string.toast_ocr_timing_saved,
                            Toast.LENGTH_SHORT,
                        ).show()
                        dialog.dismiss()
                    }
                }
            }
            dialog.show()
        }
    }

    private fun showOperationInstructionsDialog() {
        val scroll = android.widget.ScrollView(this)
        val tv = TextView(this).apply {
            text = loadOperationInstructionsText()
            textSize = 14f
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.START
        }
        scroll.addView(
            tv,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_operation_instructions)
            .setView(scroll)
            .setPositiveButton(R.string.first_launch_about_close, null)
            .show()
    }

    private fun showEditPhrasesDialog() {
        val dialogBinding = DialogEditOcrRulesBinding.inflate(layoutInflater)
        dialogBinding.etRulesBody.setText(OcrRuleUserFiles.loadPhrasesEditorText(this))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_phrases_dialog_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.dialog_save) { d, _ ->
                val text = dialogBinding.etRulesBody.text?.toString() ?: ""
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        OcrRuleUserFiles.savePhrases(this@MainActivity, text)
                    }
                    (application as MyApplication).ocrTextRuleCorrector.reload()
                    Toast.makeText(this@MainActivity, R.string.settings_rules_saved, Toast.LENGTH_SHORT).show()
                }
                d.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showEditWhitelistDialog() {
        val dialogBinding = DialogEditOcrRulesBinding.inflate(layoutInflater)
        dialogBinding.etRulesBody.setText(OcrRuleUserFiles.loadWhitelistEditorText(this))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_whitelist_dialog_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.dialog_save) { d, _ ->
                val text = dialogBinding.etRulesBody.text?.toString() ?: ""
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        OcrRuleUserFiles.saveWhitelist(this@MainActivity, text)
                    }
                    (application as MyApplication).ocrTextRuleCorrector.reload()
                    Toast.makeText(this@MainActivity, R.string.settings_rules_saved, Toast.LENGTH_SHORT).show()
                }
                d.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
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
