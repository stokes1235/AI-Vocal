package com.stokes.aivocal.ocr

import android.content.Context
import com.stokes.aivocal.R
import java.io.File

/**
 * 用户可编辑的 OCR 短语/白名单：存于应用私有目录，存在则优先于 [res/raw] 内置文件。
 */
object OcrRuleUserFiles {
    const val PHRASES_FILE = "ocr_rule_phrases.txt"
    const val WHITELIST_FILE = "ocr_rule_whitelist.txt"

    fun phrasesFile(ctx: Context): File = File(ctx.filesDir, PHRASES_FILE)

    fun whitelistFile(ctx: Context): File = File(ctx.filesDir, WHITELIST_FILE)

    fun loadPhrasesEditorText(ctx: Context): String {
        val f = phrasesFile(ctx)
        return if (f.isFile) f.readText(Charsets.UTF_8) else readRawText(ctx, R.raw.ocr_rule_phrases)
    }

    fun loadWhitelistEditorText(ctx: Context): String {
        val f = whitelistFile(ctx)
        return if (f.isFile) f.readText(Charsets.UTF_8) else readRawText(ctx, R.raw.ocr_rule_whitelist)
    }

    fun savePhrases(ctx: Context, text: String) {
        phrasesFile(ctx).writeText(text, Charsets.UTF_8)
    }

    fun saveWhitelist(ctx: Context, text: String) {
        whitelistFile(ctx).writeText(text, Charsets.UTF_8)
    }

    private fun readRawText(ctx: Context, resId: Int): String =
        ctx.resources.openRawResource(resId).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
