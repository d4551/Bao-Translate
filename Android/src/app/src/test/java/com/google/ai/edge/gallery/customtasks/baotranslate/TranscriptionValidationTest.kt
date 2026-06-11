package com.google.ai.edge.gallery.customtasks.baotranslate

import com.google.ai.edge.gallery.customtasks.baotranslate.validation.HALLUCINATION_TAGS_FOR_TEST
import com.google.ai.edge.gallery.customtasks.baotranslate.validation.isValidTranscription
import com.google.ai.edge.gallery.testkit.CorpusFixture
import com.google.ai.edge.gallery.testkit.Strict
import org.junit.experimental.categories.Category
import org.junit.Assert.*
import org.junit.Test

@Category(Strict::class)
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

    // ----- BRUTALISATION: every entry in the production HALLUCINATION_TAGS_FOR_TEST list must be
    // rejected case-insensitively. The previous test only sampled 5 tags. If a future contributor
    // adds a tag and forgets to verify it, this test fails.
    @Test
    fun testEveryProductionHallucinationTag_isRejectedCaseInsensitive() {
        for (tag in HALLUCINATION_TAGS_FOR_TEST) {
            assertFalse("exact-case tag must be rejected: $tag", isValidTranscription(tag))
            assertFalse("lower-case tag must be rejected: $tag", isValidTranscription(tag.lowercase()))
            assertFalse("upper-case tag must be rejected: $tag", isValidTranscription(tag.uppercase()))
        }
    }

    // ----- Case-variant of the filler-words regex (`^(hmm+|uh+|um+|ah+|oh+)$` with IGNORE_CASE).
    @Test
    fun filler_hmmHMM_caseInsensitive_rejected() {
        assertFalse("HmM hMm HMM is filler in any case", isValidTranscription("HmM hMm HMM"))
        assertFalse("mixed case 'Uhhh' rejected", isValidTranscription("Uhhh"))
        assertFalse("mixed case 'aH' rejected", isValidTranscription("aH"))
    }

    // ----- Cyrillic homoglyph filler. STT produces these for "hmm" in Cyrillic-script
    // segments. Cyrillic letters are non-ASCII, so they are matched by `\W` (which is ASCII-only
    // word-char negation) in the `^[\s\d\W]+$` noise pattern: a string of only Cyrillic letters
    // contains no ASCII word characters and is therefore rejected as content-free noise.
    @Test
    fun filler_cyrillicHmm_isRejectedAsFiller() {
        // Cyrillic filler "хмм" is rejected by the filled-pause lexicon (хм+), NOT by the old
        // ASCII-\W noise bug (which also dropped real Cyrillic words — see nonLatinRealText_isAccepted).
        assertFalse(
            "Cyrillic 'хмм' must be rejected as hesitation filler",
            isValidTranscription(CorpusFixture.cyrillicFiller),
        )
    }

    // ----- Numeric content in otherwise-real sentence. The current filter is `^[\s\d\W]+$`
    // (only matches if the WHOLE string is digits/whitespace), so "order 42 ready" is fine.
    @Test
    fun numericCode_inSentence_isValid() {
        assertTrue(isValidTranscription("order 42 ready"))
        assertTrue(isValidTranscription("Room 101 is on floor 3"))
    }

    // ----- Below-word-threshold edge: exactly 2 words bypasses the repetition gate by design
    // (the gate only fires at >=3 words). Document that the 2-word case is accept-by-design
    // AND that the gap is intentional, not a bug.
    @Test
    fun repetition_belowWordThreshold_isAcceptedByDesign() {
        assertTrue("2-word input is below the >=3-word repetition threshold",
            isValidTranscription("a b"))
        assertTrue("2 repeated words below threshold",
            isValidTranscription("hello hello"))
    }

    // ----- Whitespace torture: blank-looking inputs must be rejected even when composed
    // of Unicode whitespace (NBSP, EM SPACE, etc).
    @Test
    fun unicodeWhitespace_isRejected() {
        for (ws in CorpusFixture.variousWhitespace) {
            // Single-whitespace inputs (any flavour) must be rejected.
            assertFalse("whitespace '$ws' must be rejected", isValidTranscription(ws))
        }
    }

    // ----- Bracket-caption filter must accept the actual sound-word set documented in
    // the production regex (music, applause, laughter, silence, noise, whoosh, chirp,
    // breath, inaudible, background, bell, buzz, static, crying, soft music). Pick
    // entries not in the original test to verify the broader regex.
    @Test
    fun bracketSoundCaptions_broaderList_rejected() {
        assertFalse(isValidTranscription("[noise]"))
        assertFalse(isValidTranscription("[background]"))
        assertFalse(isValidTranscription("[crying]"))
        assertFalse(isValidTranscription("[static]"))
        assertFalse(isValidTranscription("[chirp]"))
    }

    // ----- Paren (round-bracket) caption: the production regex covers this too.
    @Test
    fun parenSoundCaptions_rejected() {
        assertFalse(isValidTranscription("(music)"))
        assertFalse(isValidTranscription("(inaudible)"))
        assertFalse(isValidTranscription("(background noise)"))
    }

    // ----- Negative: bracket-caption with a non-sound word is NOT rejected (it's a real
    // bracket text). This pins the filter to sound-words only, not all brackets.
    @Test
    fun bracketNonSoundText_isAccepted() {
        // "[hello world]" is not a sound caption — must pass.
        assertTrue(isValidTranscription("[hello world]"))
    }

    // ----- Edge: single non-Latin codepoint with leading/trailing whitespace.
    @Test
    fun singleNonLatinWithPadding_isRejected() {
        // Length-2 threshold rejects any input shorter than 2 chars after trim.
        assertFalse(isValidTranscription("   中   "))  // trimmed length=1
    }

    // ----- Long single word, no spaces (not a repetition). Must pass.
    @Test
    fun longSingleWord_isValid() {
        val long = "a".repeat(200)
        assertTrue(isValidTranscription(long))
    }

    // ----- Real non-Latin SOURCE text must be ACCEPTED. STT recognizes the speaker's source
    // language, so Russian/Chinese/Arabic/Hindi/Japanese utterances flow through this filter. A
    // naive `^[\s\d\W]+$` noise check (ASCII-only \W) would classify whole non-Latin utterances as
    // "noise" and silently drop them. Pin acceptance for every supported script.
    @Test
    fun nonLatinRealText_isAccepted() {
        assertTrue("Russian", isValidTranscription("привет как дела"))
        assertTrue("Chinese", isValidTranscription("你好世界"))
        assertTrue("Arabic", isValidTranscription("مرحبا بالعالم"))
        assertTrue("Hindi", isValidTranscription("नमस्ते दुनिया"))
        assertTrue("Japanese", isValidTranscription("こんにちは世界"))
    }

    // ----- Cross-linguistic filled-pause filler (whole utterance) must be rejected.
    @Test
    fun nonEnglishFiller_isRejected() {
        assertFalse("German äh/ähm", isValidTranscription("äh ähm"))
        assertFalse("French euh", isValidTranscription("euh euh"))
        assertFalse("Russian эм/ээ", isValidTranscription("эм ээ"))
        assertFalse("Russian мм", isValidTranscription("мм"))
    }

    // ----- Real short non-Latin words must NOT be mistaken for filler (over-rejection guard).
    @Test
    fun realShortNonLatinWords_areNotFiller() {
        assertTrue("Russian да (yes)", isValidTranscription("да"))
        assertTrue("Russian нет (no)", isValidTranscription("нет"))
        assertTrue("German ja", isValidTranscription("ja"))
        assertTrue("French oui", isValidTranscription("oui"))
    }
}
