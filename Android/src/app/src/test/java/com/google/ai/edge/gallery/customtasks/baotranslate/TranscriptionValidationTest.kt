package com.google.ai.edge.gallery.customtasks.baotranslate

import com.google.ai.edge.gallery.customtasks.baotranslate.validation.isValidTranscription
import org.junit.Assert.*
import org.junit.Test

class TranscriptionValidationTest {

    @Test
    fun testValidTranscriptions() {
        assertTrue(isValidTranscription("Hello, how are you?"))
        assertTrue(isValidTranscription("I would like a coffee please"))
        assertTrue(isValidTranscription("Where is the nearest restaurant?"))
        assertTrue(isValidTranscription("Thank you very much"))
        assertTrue(isValidTranscription("hi"))
        assertTrue(isValidTranscription("OK"))
    }

    @Test
    fun testInvalidTranscriptions() {
        assertFalse(isValidTranscription(""))
        assertFalse(isValidTranscription("   "))
        assertFalse(isValidTranscription("a"))

        assertFalse(isValidTranscription("[Music]"))
        assertFalse(isValidTranscription("[Applause]"))
        assertFalse(isValidTranscription("[Laughter]"))
        assertFalse(isValidTranscription("[BLANK_AUDIO]"))
        assertFalse(isValidTranscription("(silence)"))

        assertFalse(isValidTranscription("the the the the the the the the the the"))
        assertFalse(isValidTranscription("hola hola hola hola hola hola hola hola"))

        assertFalse(isValidTranscription("1/2 pulgada"))
        assertFalse(isValidTranscription("1/2 tbps"))
        assertFalse(isValidTranscription("hmm"))
        assertFalse(isValidTranscription("uh"))
        assertFalse(isValidTranscription("um"))
        assertFalse(isValidTranscription("ah"))
        assertFalse(isValidTranscription("oh"))

        assertFalse(isValidTranscription("123"))
        assertFalse(isValidTranscription("!!!"))
        assertFalse(isValidTranscription("   42   "))
    }

    @Test
    fun testRepeatedPatternFiltering() {
        assertFalse(isValidTranscription("hello hello hello hello"))
        assertFalse(isValidTranscription("the the the the"))
    }
}
