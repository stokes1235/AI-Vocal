package com.stokes.aivocal.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceMatcherTest {

    private val maps = listOf(
        VoiceMapping("爱丽丝", 10, listOf("愛麗絲")),
        VoiceMapping("王小明", 20),
    )

    @Test
    fun containsMatch_prefersFirst() {
        val r = VoiceMatcher.resolveWithDisplayName(maps, 0, " 王 小 明 的 话 ")
        assertEquals(20, r.speakerId)
        assertEquals("王小明", r.matchedDisplayName)
    }

    @Test
    fun aliasContainsMatch() {
        val r = VoiceMatcher.resolveWithDisplayName(maps, 0, "出现愛麗絲")
        assertEquals(10, r.speakerId)
        assertEquals("爱丽丝", r.matchedDisplayName)
    }

    @Test
    fun fuzzyMatch_oneTypoInLongName() {
        // 「王小明」vs「王小民」：一字之差，长度 3，相似度 2/3 > 0.66
        val r = VoiceMatcher.resolveWithDisplayName(maps, 0, "王小民")
        assertEquals(20, r.speakerId)
        assertEquals("王小明", r.matchedDisplayName)
    }

    @Test
    fun fuzzyNoMatch_twoChar_oneTypo() {
        val short = listOf(VoiceMapping("李四", 5))
        val r = VoiceMatcher.resolveWithDisplayName(short, 0, "李巳")
        assertEquals(0, r.speakerId)
        assertNull(r.matchedDisplayName)
    }

    @Test
    fun englishCaseInsensitive_contains() {
        val en = listOf(VoiceMapping("Alice", 7))
        val r = VoiceMatcher.resolveWithDisplayName(en, 0, "  ALICE  ")
        assertEquals(7, r.speakerId)
        assertEquals("Alice", r.matchedDisplayName)
    }

    @Test
    fun englishCaseInsensitive_fuzzy() {
        val en = listOf(VoiceMapping("McDonald", 8))
        val r = VoiceMatcher.resolveWithDisplayName(en, 0, "mcdonald")
        assertEquals(8, r.speakerId)
        assertEquals("McDonald", r.matchedDisplayName)
    }

    @Test
    fun similarityByCodePoints_supplementary() {
        val s = VoiceMatcher.similarityByCodePoints("A\uD83D\uDE00", "A\uD83D\uDE01")
        assert(s < 1.0 && s > 0.0)
    }
}
