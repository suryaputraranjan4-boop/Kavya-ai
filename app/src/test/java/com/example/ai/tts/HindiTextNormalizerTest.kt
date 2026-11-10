package com.example.ai.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class HindiTextNormalizerTest {
    @Test
    fun convertsHindiCardinalsAndPercentages() {
        assertEquals("नमस्ते पच्चीस प्रतिशत", HindiTextNormalizer.normalize("नमस्ते 25%"))
    }

    @Test
    fun convertsIndianGroupedCurrency() {
        assertEquals(
            "एक लाख तेईस हज़ार चार सौ छप्पन रुपये",
            HindiTextNormalizer.normalize("₹1,23,456")
        )
    }

    @Test
    fun convertsCommonEnglishWordsToDevanagari() {
        assertEquals("बैटरी पचहत्तर प्रतिशत", HindiTextNormalizer.normalize("battery 75%"))
    }

    @Test
    fun readsYearsUsingHindiYearConvention() {
        assertEquals("उन्नीस सौ अट्ठानवे", HindiTextNormalizer.normalize("1998"))
    }
}
