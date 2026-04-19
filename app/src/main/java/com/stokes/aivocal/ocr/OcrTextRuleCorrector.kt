package com.stokes.aivocal.ocr

import android.content.Context
import com.stokes.aivocal.R
import java.io.File
import java.text.Normalizer
import java.util.Collections

/**
 * OCR 后处理：规则 + 可配置短语/白名单 + 常见混淆字在轻量「语言模型」打分下的局部择优。
 * 体量小、全本地；覆盖面有限，不保证语义级纠错。
 */
class OcrTextRuleCorrector(context: Context) {

    private val appContext = context.applicationContext

    @Volatile
    private var loaded = false

    private var phraseRules: List<Pair<String, String>> = emptyList()
    private var whitelistTerms: List<String> = emptyList()

    fun correct(engine: OcrEngine, raw: String): String {
        ensureLoaded()
        if (raw.isEmpty()) return raw

        var s = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        s = applyPhraseRules(s)
        s = when (engine) {
            OcrEngine.CHINESE -> applyConfusionZh(s)
            OcrEngine.ENGLISH -> applyConfusionEn(s)
        }
        return s
    }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        phraseRules = loadPhraseRules()
        whitelistTerms = loadWhitelist()
        loaded = true
    }

    /** 重新读取短语/白名单（用户从设置中保存文件后调用）。 */
    @Synchronized
    fun reload() {
        phraseRules = loadPhraseRules()
        whitelistTerms = loadWhitelist()
        loaded = true
    }

    private fun loadPhraseRules(): List<Pair<String, String>> {
        val lines = readEffectiveLines(
            R.raw.ocr_rule_phrases,
            File(appContext.filesDir, OcrRuleUserFiles.PHRASES_FILE),
        )
        val pairs = mutableListOf<Pair<String, String>>()
        for (line in lines) {
            val eq = line.indexOf('=')
            if (eq <= 0 || eq >= line.lastIndex) continue
            val wrong = line.substring(0, eq).trim()
            val right = line.substring(eq + 1).trim()
            if (wrong.isNotEmpty() && right.isNotEmpty()) {
                pairs.add(wrong to right)
            }
        }
        pairs.sortByDescending { it.first.length }
        return Collections.unmodifiableList(pairs)
    }

    private fun loadWhitelist(): List<String> {
        val lines = readEffectiveLines(
            R.raw.ocr_rule_whitelist,
            File(appContext.filesDir, OcrRuleUserFiles.WHITELIST_FILE),
        )
        val terms = lines.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return terms.sortedByDescending { it.length }
    }

    private fun readEffectiveLines(resId: Int, userFile: File): List<String> =
        runCatching {
            val reader = if (userFile.isFile) {
                userFile.bufferedReader(Charsets.UTF_8)
            } else {
                appContext.resources.openRawResource(resId).bufferedReader(Charsets.UTF_8)
            }
            reader.use { br ->
                br.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
            }
        }.getOrElse { emptyList() }

    private fun applyPhraseRules(input: String): String {
        if (phraseRules.isEmpty()) return input
        var result = input
        repeat(PHRASE_PASSES) {
            var changed = false
            for ((wrong, right) in phraseRules) {
                if (wrong.isEmpty()) continue
                var searchStart = 0
                while (searchStart <= result.length - wrong.length) {
                    val idx = result.indexOf(wrong, searchStart)
                    if (idx < 0) break
                    val end = idx + wrong.length
                    if (rangeOverlapsProtected(result, idx, end)) {
                        searchStart = idx + 1
                        continue
                    }
                    result = result.replaceRange(idx, end, right)
                    searchStart = idx + right.length
                    changed = true
                }
            }
            if (!changed) return result
        }
        return result
    }

    private fun rangeOverlapsProtected(s: String, start: Int, end: Int): Boolean {
        val mask = protectedMask(s) ?: return false
        for (i in start until end) {
            if (i in mask.indices && mask[i]) return true
        }
        return false
    }

    /** true 表示该 UTF-16 索引落在白名单匹配内，不做混淆字替换。 */
    private fun protectedMask(s: String): BooleanArray? {
        if (whitelistTerms.isEmpty()) return null
        val mask = BooleanArray(s.length)
        var any = false
        for (term in whitelistTerms) {
            if (term.isEmpty()) continue
            var from = 0
            while (from <= s.length - term.length) {
                val idx = s.indexOf(term, from)
                if (idx < 0) break
                any = true
                for (j in idx until idx + term.length) {
                    mask[j] = true
                }
                from = idx + 1
            }
        }
        return if (any) mask else null
    }

    private fun applyConfusionZh(s: String): String {
        val mask = protectedMask(s)
        val chars = s.toCharArray()
        repeat(PASSES) {
            for (i in chars.indices) {
                if (mask != null && mask.getOrNull(i) == true) continue
                val c = chars[i]
                val alts = ZH_CONFUSIONS[c] ?: continue
                if (alts.size <= 1) continue
                var best = c
                var bestScore = localScoreZh(chars, i, c)
                for (a in alts) {
                    if (a == c) continue
                    val sc = localScoreZh(chars, i, a)
                    if (sc > bestScore) {
                        bestScore = sc
                        best = a
                    }
                }
                chars[i] = best
            }
        }
        return String(chars)
    }

    private fun localScoreZh(chars: CharArray, i: Int, ch: Char): Double {
        var score = 0.0
        if (i > 0) {
            val left = chars[i - 1]
            score += zhBigramWeight(left, ch)
            score += UNIGRAM_WEIGHT * unigramBonusZh(ch)
            score += UNIGRAM_WEIGHT * unigramBonusZh(left) * 0.5
        } else {
            score += UNIGRAM_WEIGHT * unigramBonusZh(ch)
        }
        if (i < chars.size - 1) {
            val right = chars[i + 1]
            score += zhBigramWeight(ch, right)
            score += UNIGRAM_WEIGHT * unigramBonusZh(right) * 0.5
        }
        return score
    }

    private fun zhBigramWeight(a: Char, b: Char): Double =
        ZH_BIGRAM_WEIGHTS[packBigramKey(a, b)] ?: 0.0

    private fun unigramBonusZh(c: Char): Double {
        val idx = COMMON_ZH_CHARS.indexOf(c)
        return if (idx >= 0) (COMMON_ZH_CHARS.length - idx).toDouble() / COMMON_ZH_CHARS.length else 0.05
    }

    private fun applyConfusionEn(s: String): String {
        val mask = protectedMask(s)
        val chars = s.toCharArray()
        repeat(PASSES) {
            for (i in chars.indices) {
                if (mask != null && mask.getOrNull(i) == true) continue
                val c = chars[i]
                val alts = enAlternatives(chars, i, c) ?: continue
                if (alts.size <= 1) continue
                var best = c
                var bestScore = localScoreEn(chars, i, c)
                for (a in alts) {
                    if (a == c) continue
                    val sc = localScoreEn(chars, i, a)
                    if (sc > bestScore) {
                        bestScore = sc
                        best = a
                    }
                }
                chars[i] = best
            }
        }
        return String(chars)
    }

    /**
     * 仅在「疑似数字/字母混排」邻域内启用 0/O、1/l/I 互换候选，避免整句英文被误改。
     */
    private fun enAlternatives(chars: CharArray, i: Int, c: Char): List<Char>? {
        val neighborDigit = enNeighborIsDigit(chars, i)
        val neighborLetter = enNeighborIsLetter(chars, i)
        return when (c) {
            '0', 'O', 'o' -> if (neighborDigit && neighborLetter) {
                listOf('0', 'O', 'o').distinct()
            } else if (neighborDigit) {
                listOf('0', 'O', 'o')
            } else {
                null
            }
            '1', 'l', 'I', '|' -> if (neighborDigit && neighborLetter) {
                listOf('1', 'l', 'I').distinct()
            } else if (neighborDigit) {
                listOf('1', 'l', 'I')
            } else {
                null
            }
            else -> null
        }
    }

    private fun enNeighborIsDigit(chars: CharArray, i: Int): Boolean {
        if (i > 0 && chars[i - 1].isDigit()) return true
        if (i < chars.size - 1 && chars[i + 1].isDigit()) return true
        return false
    }

    private fun enNeighborIsLetter(chars: CharArray, i: Int): Boolean {
        if (i > 0 && chars[i - 1].isLetter()) return true
        if (i < chars.size - 1 && chars[i + 1].isLetter()) return true
        return false
    }

    private fun localScoreEn(chars: CharArray, i: Int, ch: Char): Double {
        fun lc(x: Char): Char = if (x in 'A'..'Z') x.lowercaseChar() else x
        var score = 0.0
        if (i > 0) {
            val a = lc(chars[i - 1])
            val b = lc(ch)
            score += enBigramWeight(a, b)
        }
        if (i < chars.size - 1) {
            val a = lc(ch)
            val b = lc(chars[i + 1])
            score += enBigramWeight(a, b)
        }
        return score
    }

    private fun enBigramWeight(a: Char, b: Char): Double =
        EN_BIGRAM_WEIGHTS[packBigramKey(a, b)] ?: 0.0

    private companion object {
        private const val PASSES = 2
        private const val UNIGRAM_WEIGHT = 0.08

        private fun packBigramKey(a: Char, b: Char): Long =
            (a.code.toLong() shl 32) or (b.code.toLong() and 0xffffffffL)

        /** 常见形近/义近混淆：含原字，供打分择优（覆盖面有限）。 */
        private val ZH_CONFUSIONS: Map<Char, List<Char>> = mapOf(
            '十' to listOf('十', '干', '于', '千'),
            '干' to listOf('干', '十', '于', '千'),
            '于' to listOf('于', '干', '十'),
            '千' to listOf('千', '十', '干'),
            '己' to listOf('己', '已', '巳'),
            '已' to listOf('已', '己', '巳'),
            '巳' to listOf('巳', '己', '已'),
            '末' to listOf('末', '未'),
            '未' to listOf('未', '末'),
            '曰' to listOf('曰', '日'),
            '日' to listOf('日', '曰'),
            '土' to listOf('土', '士', '工'),
            '士' to listOf('士', '土', '工'),
            '工' to listOf('工', '土', '士'),
            '夭' to listOf('夭', '天', '无'),
            '天' to listOf('天', '夭', '无'),
            '无' to listOf('无', '天', '元'),
            '在' to listOf('在', '再'),
            '再' to listOf('再', '在'),
            '的' to listOf('的', '地', '得'),
            '地' to listOf('地', '的', '得'),
            '得' to listOf('得', '的', '地'),
            '做' to listOf('做', '作'),
            '作' to listOf('作', '做'),
            '哪' to listOf('哪', '那'),
            '那' to listOf('那', '哪'),
            '象' to listOf('象', '像'),
            '像' to listOf('像', '象'),
            '采' to listOf('采', '彩'),
            '彩' to listOf('彩', '采'),
            '历' to listOf('历', '厉', '力'),
            '厉' to listOf('厉', '历', '力'),
            '力' to listOf('力', '历', '厉'),
        )

        /** 高频字序列（越靠前权重越高）；仅作弱 unigram 先验。 */
        private const val COMMON_ZH_CHARS =
            "的一是不了在人有我他这个们中来上大为和国地到以说时要就出会可也你对生能而子那得于着下自之年过发后作里用道行所然家如种事成方多经么去法学都同现当没动面起看定天分还进好小部其些主样理心她本前开但因只从想实日军者意无力它与长把机十民第公此已工使情明性知全三又关点正业外将两高间由问很最重并物手应战向头文体政美相见被利什二等产或新己制身果加西变干四神总何电数安少报才结反受目太量再感建接名话住位海通教儿原进世水义各入常路气五立命"

        private const val PHRASE_PASSES = 8

        /** 常见二字片段：命中即加分（轻量「语言模型」）。 */
        private val ZH_BIGRAM_WEIGHTS: Map<Long, Double> = buildMap {
            val pairs = arrayOf(
                "的是", "了我", "不在", "一个", "没有", "知道", "什么", "自己", "就是", "这么",
                "那个", "这样", "怎么", "现在", "已经", "因为", "所以", "如果", "还是", "时候",
                "可以", "这个", "你们", "他们", "我们", "今天", "明天", "昨天", "晚上", "早上",
                "大家", "起来", "说道", "看着", "心里", "脸上", "一声", "一下",
                "的人", "的事", "地说", "地道", "的话", "的笑", "的眼", "的手", "的心",
                "天地", "天干", "于是", "十分", "千里", "未来", "末日",
            )
            for (p in pairs) {
                if (p.length != 2) continue
                val k = packBigramKey(p[0], p[1])
                merge(k, 2.2, Double::plus)
            }
        }

        private val EN_BIGRAM_WEIGHTS: Map<Long, Double> = buildMap {
            val pairs = arrayOf(
                "th", "he", "in", "er", "an", "re", "nd", "at", "on", "nt",
                "ha", "es", "st", "en", "ed", "to", "it", "or", "ti", "is",
                "ou", "ea", "hi", "as", "be", "ng", "wh", "ll", "ve", "ly",
                "e ", " t", " s", " a", " i", " o", " n",
            )
            for (p in pairs) {
                if (p.length != 2) continue
                val k = packBigramKey(p[0], p[1])
                merge(k, 1.8, Double::plus)
            }
        }
    }
}
