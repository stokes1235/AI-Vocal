package com.stokes.aivocal.tts

import java.util.Locale

/**
 * 人名区 OCR 与配置人名匹配：归一化后双向 contains，失败时再按 Unicode 码点 Levenshtein 相似度择优。
 * 比较前对整串做 [Locale.ROOT] 小写化，英文人名不区分大小写（中文等不受影响）。
 */
object VoiceMatcher {

    /** 相似度 = 1 - 距离/max(len1,len2)，达到该阈值则视为模糊命中（短名建议配合 [VoiceMapping.aliases]）。 */
    private const val FUZZY_MIN_SIMILARITY = 0.66

    data class ResolveResult(
        val speakerId: Int,
        /** 与某条映射的人名字段匹配成功时的展示名；未匹配或人名为空时为 null。 */
        val matchedDisplayName: String?,
    )

    fun resolveWithDisplayName(
        mappings: List<VoiceMapping>,
        defaultSpeakerId: Int,
        nameOcrText: String,
    ): ResolveResult {
        val norm = nameOcrText.trim().replace(Regex("\\s+"), " ")
        if (norm.isEmpty()) return ResolveResult(defaultSpeakerId, null)
        val ocrCompact = compactKey(norm)
        if (ocrCompact.isEmpty()) return ResolveResult(defaultSpeakerId, null)

        for (m in mappings) {
            for (key in m.compactCandidateKeys()) {
                if (key.isEmpty()) continue
                if (ocrCompact.contains(key) || key.contains(ocrCompact)) {
                    return ResolveResult(m.speakerId, m.displayName.trim())
                }
            }
        }

        var bestMapping: VoiceMapping? = null
        var bestSim = -1.0
        for (m in mappings) {
            for (key in m.compactCandidateKeys()) {
                if (key.isEmpty()) continue
                val sim = similarityByCodePoints(ocrCompact, key)
                if (sim > bestSim) {
                    bestSim = sim
                    bestMapping = m
                }
            }
        }
        if (bestMapping != null && bestSim >= FUZZY_MIN_SIMILARITY) {
            return ResolveResult(bestMapping.speakerId, bestMapping.displayName.trim())
        }
        return ResolveResult(defaultSpeakerId, null)
    }

    fun resolve(
        mappings: List<VoiceMapping>,
        defaultSpeakerId: Int,
        nameOcrText: String,
    ): Int = resolveWithDisplayName(mappings, defaultSpeakerId, nameOcrText).speakerId

    internal fun compactKey(s: String): String =
        s.trim().replace(Regex("\\s+"), " ").replace(" ", "").lowercase(Locale.ROOT)

    private fun VoiceMapping.compactCandidateKeys(): List<String> {
        val keys = LinkedHashSet<String>()
        keys.add(compactKey(displayName))
        for (a in aliases) {
            val k = compactKey(a)
            if (k.isNotEmpty()) keys.add(k)
        }
        return keys.toList()
    }

    internal fun similarityByCodePoints(a: String, b: String): Double {
        val ca = a.toCodePointArray()
        val cb = b.toCodePointArray()
        val dist = levenshtein(ca, cb)
        val denom = maxOf(ca.size, cb.size).coerceAtLeast(1)
        return 1.0 - dist.toDouble() / denom
    }

    private fun String.toCodePointArray(): IntArray {
        val cnt = codePointCount(0, length)
        val out = IntArray(cnt)
        var oi = 0
        var i = 0
        while (i < length) {
            val cp = codePointAt(i)
            out[oi++] = cp
            i += Character.charCount(cp)
        }
        return out
    }

    private fun levenshtein(a: IntArray, b: IntArray): Int {
        val m = a.size
        val n = b.size
        if (m == 0) return n
        if (n == 0) return m
        var prev = IntArray(n + 1) { it }
        var cur = IntArray(n + 1)
        for (i in 1..m) {
            cur[0] = i
            val ca = a[i - 1]
            for (j in 1..n) {
                val cost = if (ca == b[j - 1]) 0 else 1
                cur[j] = minOf(
                    cur[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + cost,
                )
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[n]
    }
}
