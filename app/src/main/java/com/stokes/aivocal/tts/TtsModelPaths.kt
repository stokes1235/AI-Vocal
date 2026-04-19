package com.stokes.aivocal.tts

import android.content.Context
import java.io.File

/**
 * 与 [TtsModelPaths] 目录对应的离线引擎种类（中文 aishell3 / 英文 VCTK）。
 */
enum class TtsEngineKind {
    /** vits-icefall-zh-aishell3 */
    Chinese,

    /** vits-vctk */
    English,
}

/**
 * 随 APK 的 [BundledTtsModels] 将模型从 assets 复制到 [android.content.Context.getFilesDir] 下，
 * 路径与 [tts-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models) 解压结构一致。
 */
object TtsModelPaths {

    const val ASSET_DIR_ZH = "vits-icefall-zh-aishell3"
    const val ASSET_DIR_VCTK = "vits-vctk"

    private const val REL_ZH = "tts/$ASSET_DIR_ZH"
    private const val REL_VCTK = "tts/$ASSET_DIR_VCTK"

    fun zhModelRoot(context: Context): File = File(context.filesDir, REL_ZH)

    /** 兼容旧调用：中文 aishell3 根目录。 */
    fun modelRoot(context: Context): File = zhModelRoot(context)

    /** 英文 VCTK 模型根目录（与 [enModelRoot] 为同一路径）。 */
    fun vctkModelRoot(context: Context): File = File(context.filesDir, REL_VCTK)

    /** 英文引擎根目录，与 [vctkModelRoot] 相同，便于与 [zhModelRoot] 成对理解。 */
    fun enModelRoot(context: Context): File = vctkModelRoot(context)

    fun modelRoot(context: Context, kind: TtsEngineKind): File = when (kind) {
        TtsEngineKind.Chinese -> zhModelRoot(context)
        TtsEngineKind.English -> vctkModelRoot(context)
    }

    fun isModelFilesPresent(context: Context, kind: TtsEngineKind): Boolean = when (kind) {
        TtsEngineKind.Chinese -> isZhModelFilesPresent(context)
        TtsEngineKind.English -> isVctkModelFilesPresent(context)
    }

    fun isZhModelFilesPresent(context: Context): Boolean {
        val root = zhModelRoot(context)
        return File(root, "model.onnx").isFile &&
            File(root, "tokens.txt").isFile &&
            File(root, "lexicon.txt").isFile &&
            File(root, "rule.far").isFile
    }

    fun isVctkModelFilesPresent(context: Context): Boolean {
        val root = vctkModelRoot(context)
        return File(root, "vits-vctk.onnx").isFile &&
            File(root, "tokens.txt").isFile &&
            File(root, "lexicon.txt").isFile
    }
}
