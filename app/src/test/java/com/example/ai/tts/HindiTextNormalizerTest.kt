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
    fun readsDecimalPercentages() {
        assertEquals("तीन दशमलव पाँच प्रतिशत", HindiTextNormalizer.normalize("3.5%"))
    }

    @Test
    fun spellsOutLongIdentifiersInsteadOfThrowingOrDroppingDigits() {
        assertEquals(
            "एक दो तीन चार पाँच छह सात आठ नौ शून्य",
            HindiTextNormalizer.normalize("1234567890")
        )
    }

    @Test
    fun readsPhoneNumbersDigitByDigitWhenLeadingZeroIsPresent() {
        assertEquals(
            "शून्य नौ आठ सात छह पाँच चार तीन दो एक",
            HindiTextNormalizer.normalize("0987654321")
        )
    }

    @Test
    fun readsYearsUsingHindiYearConvention() {
        assertEquals("उन्नीस सौ अट्ठानवे", HindiTextNormalizer.normalize("1998"))
    }
}
