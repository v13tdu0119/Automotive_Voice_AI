package com.sopa.viva_automotive.feature.voice.domain.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneticMatcherTest {

    private val config = ViviHotword.defaultConfig()

    @Test
    fun `maps the assistant name to the vietnamese vi vi phonemes`() {
        assertEquals(listOf("v", "iː", "v", "iː"), PhoneticMatcher.phonemesOf("vi vi"))
    }

    @Test
    fun `strips vietnamese diacritics before matching`() {
        assertEquals(listOf("v", "iː", "v", "iː"), PhoneticMatcher.phonemesOf("Ví Vì"))
    }

    @Test
    fun `accepts the vietnamese pronunciation of the wake word`() {
        val match = PhoneticMatcher.match("vi vi", config)

        assertNotNull(match)
        assertEquals(ViviHotword.KEYWORD, match!!.phrase.phrase)
        assertEquals(1f, match.confidence, 0f)
    }

    @Test
    fun `accepts the hey prefix`() {
        val match = PhoneticMatcher.match("hey vi vi", config)

        assertNotNull(match)
        assertEquals(1f, match!!.confidence, 0f)
    }

    @Test
    fun `accepts the vy spelling that vietnamese speakers pronounce as vi`() {
        assertNotNull(PhoneticMatcher.match("vy vy", config))
    }

    @Test
    fun `accepts the wake word embedded in a longer utterance`() {
        assertNotNull(PhoneticMatcher.match("hey vi vi turn on the ac", config))
    }

    @Test
    fun `rejects the english vai vai pronunciation`() {
        assertNull(PhoneticMatcher.match("vai vai", config))
    }

    @Test
    fun `rejects why why and other ai homophones`() {
        assertNull(PhoneticMatcher.match("why why", config))
        assertNull(PhoneticMatcher.match("wifi", config))
        assertNull(PhoneticMatcher.match("five five", config))
    }

    @Test
    fun `rejects a single syllable`() {
        assertNull(PhoneticMatcher.match("vi", config))
        assertNull(PhoneticMatcher.match("hey", config))
    }

    @Test
    fun `rejects unrelated commands`() {
        assertNull(PhoneticMatcher.match("turn on the air conditioning", config))
        assertNull(PhoneticMatcher.match("set temperature to twenty two", config))
    }

    @Test
    fun `sensitivity controls how forgiving near misses are`() {
        val strict = ViviHotword.defaultConfig(sensitivity = 0f)
        val permissive = ViviHotword.defaultConfig(sensitivity = 1f)

        assertTrue(strict.matchThreshold > permissive.matchThreshold)
        assertNull(PhoneticMatcher.match("we we", strict))
        assertNotNull(PhoneticMatcher.match("we we", permissive))
        assertNull(PhoneticMatcher.match("why why", permissive))
    }

    @Test
    fun `similarity is symmetric around the target length`() {
        val target = ViviHotword.VIVI.phonemes

        assertEquals(1f, PhoneticMatcher.similarity(target, target), 0f)
        assertTrue(
            PhoneticMatcher.similarity(ViviHotword.ENGLISH_VY_VY.phonemes, target) < 0.75f,
        )
    }
}
