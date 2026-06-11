package com.google.ai.edge.gallery.customtasks.baotranslate.translate

import com.google.ai.edge.gallery.customtasks.baotranslate.validation.isSourceEcho
import com.google.ai.edge.gallery.customtasks.baotranslate.validation.isValidTranslation
import com.google.ai.edge.gallery.testkit.Strict
import org.junit.experimental.categories.Category
import org.junit.Assert.*
import org.junit.Test

@Category(Strict::class)
class TranslationValidationTest {

    @Test
    fun testValidTranslations() {
        assertTrue(isValidTranslation("Hola", "Hello"))
        assertTrue(isValidTranslation("Sí", "Yes"))
        assertTrue(isValidTranslation("Gracias", "Thank you"))
        assertTrue(isValidTranslation("Buenos días", "Good morning"))
        assertTrue(isValidTranslation("¿Cómo estás?", "How are you?"))
        assertTrue(isValidTranslation("Me gustaría un café, por favor", "I would like a coffee, please"))
    }

    @Test
    fun testInvalidTranslations() {
        assertFalse(isValidTranslation("", "Hello"))
        assertFalse(isValidTranslation("   ", "Hello"))
        assertFalse(isValidTranslation("a", "Hello"))

        assertFalse(isValidTranslation("the the the the the the the the the the", "Hello"))
        assertFalse(isValidTranslation("hola hola hola hola hola hola hola hola", "Hello"))

        assertFalse(isValidTranslation("hello world hello world hello world", "Hello"))
    }

    @Test
    fun testRepeatedWordSequenceDetection() {
        assertFalse(isValidTranslation("hello world hello world", "Hello"))
        assertFalse(isValidTranslation("ab ab ab ab", "Hello"))
        assertFalse(isValidTranslation("x y z x y z", "Hello"))
        assertTrue(isValidTranslation("hello world hello there", "Hello"))
    }

    @Test
    fun testEchoDetectedWhenLanguagesDiffer() {
        // Verbatim echo across different languages is the model failing to translate.
        assertTrue(isSourceEcho("Hello world", "Hello world", "en", "es"))
        assertTrue(isSourceEcho("  Hello world ", "Hello world", "en", "es"))
        assertTrue(isSourceEcho("HELLO WORLD", "hello world", "en", "fr"))
    }

    @Test
    fun testEchoNotFlaggedForLegitimateCases() {
        // A real translation differs from the source.
        assertFalse(isSourceEcho("Hola mundo", "Hello world", "en", "es"))
        // Same source/target language: unchanged text is legitimate passthrough.
        assertFalse(isSourceEcho("Hello world", "Hello world", "en", "en"))
        // Auto-detect resolving to the target should not be treated as an echo failure.
        assertFalse(isSourceEcho("Bonjour", "Bonjour", "fr", "fr"))
    }

    // ----- BRUTALISATION -----

    // ----- Translation empty/blank edge cases: trim()-then-equal-to-source.
    @Test
    fun translation_emptyAndWhitespace_rejected() {
        assertFalse(isValidTranslation("", "Hello"))
        assertFalse(isValidTranslation("   ", "Hello"))
        assertFalse(isValidTranslation("\n\t", "Hello"))
        assertFalse(isValidTranslation("\r\n", "Hello"))
    }

    // ----- Unit-boundary exact: 6 words in 2-unit repeat (3 cycles of 2-word unit). The for-loop
    // is `unitSize in 1..words.size / 2 = 3`; the unit=2 path is the one exercised here.
    @Test
    fun translation_unitBoundary_exact_rejected() {
        assertFalse("6 words in 2-word unit (3 cycles)", isValidTranslation("ab ab ab ab ab ab", "x"))
        assertFalse("4 words in 2-word unit (2 cycles)", isValidTranslation("ab ab ab ab", "x"))
        assertFalse("6 words in 3-word unit (2 cycles)", isValidTranslation("abc abc abc abc abc abc", "x"))
    }

    // ----- Short translations (below the >=3 / >=4 word thresholds) are accept-by-design.
    @Test
    fun translation_shortAccepted_byDesign() {
        assertTrue(isValidTranslation("Hi", "Hello"))                          // 1 word
        assertTrue(isValidTranslation("Yes please", "Hola"))                    // 2 words
        assertTrue(isValidTranslation("Of course, no problem", "Gracias"))     // 4 words but no repetition
    }

    // ----- isSourceEcho: empty-source and empty-translated edge cases.
    @Test
    fun isSourceEcho_emptySource_neverEcho() {
        // Empty source means nothing to echo — must NOT flag.
        assertFalse(isSourceEcho("Hola", "", "es", "en"))
    }

    @Test
    fun isSourceEcho_emptyTranslated_neverEcho() {
        // Empty translated can't echo anything.
        assertFalse(isSourceEcho("", "Hello", "en", "es"))
    }

    @Test
    fun isSourceEcho_bothEmpty_neverEcho() {
        assertFalse(isSourceEcho("", "", "en", "es"))
    }

    // ----- Case insensitivity: source vs translated in different cases.
    @Test
    fun isSourceEcho_caseInsensitive_detected() {
        assertTrue(isSourceEcho("HELLO WORLD", "hello world", "en", "es"))
        assertTrue(isSourceEcho("Hello World", "hello world", "en", "es"))
        assertTrue(isSourceEcho("hello world", "HELLO WORLD", "en", "es"))
    }

    // ----- Unicode whitespace edge: NBSP-only inputs are content-free. Kotlin's Unicode-aware
    // trim() collapses NBSP to "", so both sides become empty, and empty content has nothing
    // to echo, so isSourceEcho must NOT flag it.
    @Test
    fun isSourceEcho_nbspOnly_neverEcho() {
        // Kotlin's trim() strips NBSP (Character.isSpaceChar), so both sides trim to "",
        // and empty content is never an echo.
        val nbsp = "\u00A0"
        assertFalse(
            "NBSP-only inputs trim to empty, so they are never detected as echo",
            isSourceEcho("$nbsp$nbsp$nbsp$nbsp", "$nbsp", "en", "es"),
        )
    }

    // ----- Same-language passthrough is never echo (covers all 2-letter code variants).
    @Test
    fun isSourceEcho_sameLanguage_variants_neverEcho() {
        assertFalse(isSourceEcho("Hello world", "Hello world", "en", "en"))
        assertFalse(isSourceEcho("Hello world", "Hello world", "EN", "en"))
        assertFalse(isSourceEcho("Hello world", "Hello world", "en", "EN"))
        assertFalse(isSourceEcho("Hello world", "Hello world", "EN", "EN"))
    }

    // ----- Source language uppercase target lowercase: production uses
    // `equals(targetLanguage, ignoreCase = true)`, so these must be treated as same.
    @Test
    fun isSourceEcho_localeCaseInsensitive_documentedAsDifferent() {
        // DOCUMENTED BEHAVIOR: production treats "en" and "en-US" as DIFFERENT (locale
        // variants not normalized). Document current behavior — if locale normalization
        // is added, this flips to assertFalse.
        assertTrue(
            "en vs en-US is currently treated as different → echo is flagged",
            isSourceEcho("Hello", "Hello", "en", "en-US"),
        )
    }

    // ----- Translation: real-world LLM outputs that contain punctuation/unicode.
    @Test
    fun translation_unicodeAndPunctuation_accepted() {
        assertTrue(isValidTranslation("Hola, ¿cómo estás?", "Hello, how are you?"))
        assertTrue(isValidTranslation("你好世界", "Hello world"))
        assertTrue(isValidTranslation("Привет, мир", "Hello, world"))
    }

    // ----- Translation: 3 identical words must be REJECTED. The ratio gate (`ratio < 0.3`) does
    // not fire for 3 all-same words (ratio = 1/3 = 0.333), so the exact-repetition detector must —
    // it runs at words.size >= 3 and rejects any input that is N>=2 copies of a k-word unit. Pins
    // the reject contract so a degenerate "word word word" hallucination cannot pass.
    @Test
    fun translation_repetitionThresholdBoundary() {
        assertFalse(
            "3 identical words must be rejected",
            isValidTranslation("hello hello hello", "x"),
        )
    }

    // ----- Stress: pathological input that's both repetitive and short of the unit loop.
    @Test
    fun translation_longerPathologicalInput_rejected() {
        // 12 words in 2-unit pattern (6 cycles): unitSize in 1..6, unit=2 matches at start=0,2,4,...
        val pathological = "ab cd ".repeat(6).trim()
        assertFalse("12 words in 2-unit repeat", isValidTranslation(pathological, "x"))
    }
}
