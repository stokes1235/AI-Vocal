package com.example.myapplication1.tts

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 将 `assets` 下打包的中英 TTS 资源复制到 [android.content.Context.getFilesDir]，供 [SherpaTtsPlayer] 等使用。
 * 仅在对应目录缺少关键文件时复制，可安全多次调用。
 *
 * 使用已知文件名逐个 [AssetManager.open] 拷贝，避免 [AssetManager.list] 在部分机型上对叶子资源
 * 返回空数组导致误建目录、无法加载 ONNX 的问题。
 */
object BundledTtsModels {

    private const val TAG = "BundledTtsModels"

    /** 中文 vits-icefall-zh-aishell3：与 Sherpa 配置及官方包一致，缺一则拷贝失败。 */
    private val ZH_REQUIRED_ASSET_NAMES = arrayOf(
        "model.onnx",
        "lexicon.txt",
        "tokens.txt",
        "rule.far",
    )

    /** 同目录下常见附属资源，assets 中无则跳过。 */
    private val ZH_OPTIONAL_ASSET_NAMES = arrayOf(
        "date.fst",
        "number.fst",
        "phone.fst",
        "new_heteronym.fst",
        "speakers.txt",
    )

    /** 英文 vits-vctk：与 [SherpaTtsPlayer] / [TtsModelPaths.isVctkModelFilesPresent] 一致。 */
    private val VCTK_REQUIRED_ASSET_NAMES = arrayOf(
        "vits-vctk.onnx",
        "lexicon.txt",
        "tokens.txt",
    )

    private val VCTK_OPTIONAL_ASSET_NAMES = arrayOf(
        "vits-vctk.int8.onnx",
    )

    suspend fun ensureFromAssets(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (!TtsModelPaths.isZhModelFilesPresent(app)) {
            runCatching {
                copyZhModelFromAssets(app.assets, TtsModelPaths.zhModelRoot(app))
            }.onFailure { Log.e(TAG, "copy zh model", it) }
        }
        if (!TtsModelPaths.isVctkModelFilesPresent(app)) {
            runCatching {
                copyVctkModelFromAssets(app.assets, TtsModelPaths.vctkModelRoot(app))
            }.onFailure { Log.e(TAG, "copy vctk model", it) }
        }
    }

    private fun copyZhModelFromAssets(am: AssetManager, destRoot: File) {
        val prefix = "${TtsModelPaths.ASSET_DIR_ZH}/"
        resetDestRoot(destRoot)
        for (name in ZH_REQUIRED_ASSET_NAMES) {
            copyAssetFile(am, "$prefix$name", File(destRoot, name))
        }
        for (name in ZH_OPTIONAL_ASSET_NAMES) {
            copyOptionalAssetFile(am, "$prefix$name", File(destRoot, name))
        }
    }

    private fun copyVctkModelFromAssets(am: AssetManager, destRoot: File) {
        val prefix = "${TtsModelPaths.ASSET_DIR_VCTK}/"
        resetDestRoot(destRoot)
        for (name in VCTK_REQUIRED_ASSET_NAMES) {
            copyAssetFile(am, "$prefix$name", File(destRoot, name))
        }
        for (name in VCTK_OPTIONAL_ASSET_NAMES) {
            copyOptionalAssetFile(am, "$prefix$name", File(destRoot, name))
        }
    }

    private fun resetDestRoot(destRoot: File) {
        if (destRoot.exists()) {
            destRoot.deleteRecursively()
        }
        destRoot.mkdirs()
    }

    private fun copyAssetFile(am: AssetManager, assetPath: String, destFile: File) {
        destFile.parentFile?.mkdirs()
        if (destFile.exists() && destFile.isDirectory) {
            destFile.deleteRecursively()
        }
        am.open(assetPath).use { input ->
            FileOutputStream(destFile).use { input.copyTo(it) }
        }
    }

    private fun copyOptionalAssetFile(am: AssetManager, assetPath: String, destFile: File) {
        try {
            copyAssetFile(am, assetPath, destFile)
        } catch (e: FileNotFoundException) {
            Log.d(TAG, "optional asset missing, skip: $assetPath")
        } catch (e: IOException) {
            Log.w(TAG, "optional asset copy failed: $assetPath", e)
        }
    }
}
