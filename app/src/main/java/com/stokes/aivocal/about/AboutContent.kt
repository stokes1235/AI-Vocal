package com.stokes.aivocal.about

import android.content.Context
import com.stokes.aivocal.BuildConfig
import com.stokes.aivocal.R
import java.nio.charset.StandardCharsets

/** 关于页与首次启动说明共用的正文（来自 [R.raw.about_content]）。 */
object AboutContent {

    fun load(context: Context): String {
        val raw = context.resources.openRawResource(R.raw.about_content).use { it.readBytes() }
        return String(raw, StandardCharsets.UTF_8)
            .replace("{{VERSION}}", "v${BuildConfig.VERSION_NAME}")
            .trimEnd()
    }
}
