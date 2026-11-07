package com.saara.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTest {

    @Test
    fun normalizesCasePunctuationAndSpacing() {
        assertEquals("hey saara play music", WakeWord.normalize("  Hey,  SAARA! play music. "))
        assertEquals("", WakeWord.normalize("!!!"))
    }

    @Test
    fun matchesEnglishWakePhrasesAtStartOrInsideUtterance() {
        assertTrue(WakeWord.isWake("hey saara"))
        assertTrue(WakeWord.isWake("Hey, Saara!"))
        assertTrue(WakeWord.isWake("hey saara play music"))
        assertTrue(WakeWord.isWake("okay sara call mom"))
        assertTrue(WakeWord.isWake("hello saara"))
    }

    @Test
    fun matchesBareName() {
        assertTrue(WakeWord.isWake("saara"))
        assertTrue(WakeWord.isWake("Sara"))
    }

    @Test
    fun matchesHindiAndBengaliVariants() {
        assertTrue(WakeWord.isWake("अरे सारा कॉल करो"))
        assertTrue(WakeWord.isWake("হে সারা"))
    }

    @Test
    fun doesNotTriggerOnUnrelatedSpeech() {
        assertFalse(WakeWord.isWake("hey jarvis"))
        assertFalse(WakeWord.isWake("play some music"))
        assertFalse(WakeWord.isWake("sarah"))
        assertFalse(WakeWord.isWake(""))
    }
}
