package com.google.ai.edge.gallery.customtasks.baotranslate.translate

import com.google.ai.edge.gallery.customtasks.baotranslate.validation.isValidTranslation
import org.junit.Assert.*
import org.junit.Test

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
}
