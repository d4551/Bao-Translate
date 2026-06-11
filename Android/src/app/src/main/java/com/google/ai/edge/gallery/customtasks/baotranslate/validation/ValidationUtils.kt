package com.google.ai.edge.gallery.customtasks.baotranslate.validation

import com.google.ai.edge.gallery.common.BaoLog

private const val TAG = "ValidationUtils"

internal val HALLUCINATION_TAGS_FOR_TEST: List<String> =
    listOf("[Music]", "[Applause]", "[Laughter]", "[BLANK_AUDIO]", "(silence)")

internal fun isValidTranscription(text: String): Boolean {
    val trimmed = text.trim()

    if (trimmed.isBlank()) {
        BaoLog.d(TAG, "Filtered: blank transcription")
        return false
    }

    if (trimmed.length < 2) {
        BaoLog.d(TAG, "Filtered: too short (${trimmed.length} chars)")
        return false
    }

    val hallucinationTags = HALLUCINATION_TAGS_FOR_TEST
    if (hallucinationTags.any { trimmed.equals(it, ignoreCase = true) }) {
        BaoLog.d(TAG, "Filtered: hallucination tag '$trimmed'")
        return false
    }

    val soundWords = "music|applause|laughter|silence|noise|whoosh|chirp|breath|inaudible|background|bell|buzz|static|crying|soft music"
    val soundCaptionPattern = Regex(
        "^\\([^)]*($soundWords)[^)]*\\)(\\s+\\([^)]*\\))*$",
        RegexOption.IGNORE_CASE,
    )
    if (soundCaptionPattern.matches(trimmed)) {
        BaoLog.d(TAG, "Filtered: hallucinated sound caption '$trimmed'")
        return false
    }

    val bracketedSoundCaptionPattern = Regex(
        "^\\[[^]]*($soundWords)[^]]*\\]$",
        RegexOption.IGNORE_CASE,
    )
    if (bracketedSoundCaptionPattern.matches(trimmed)) {
        BaoLog.d(TAG, "Filtered: hallucinated bracket caption '$trimmed'")
        return false
    }

    val words = trimmed.split("\\s+".toRegex())
    val uniqueWords = words.toSet()
    val repetitionRatio = uniqueWords.size.toFloat() / words.size.toFloat()

    if (words.size >= 3 && repetitionRatio < 0.3f) {
        BaoLog.d(TAG, "Filtered: excessive repetition (${words.size} words, ${uniqueWords.size} unique)")
        return false
    }

    val repeatedPattern = Regex("(\\w+\\s+){3,}\\1")
    if (repeatedPattern.containsMatchIn(trimmed)) {
        BaoLog.d(TAG, "Filtered: repeated pattern in '$trimmed'")
        return false
    }

    // Filler-word noise. The filler token set is matched case-insensitively, and a whole
    // transcription made entirely of filler tokens (e.g. "HmM hMm HMM", "oh um") is rejected,
    // not just a single token. This catches multi-token filler that an STT model emits during
    // hesitation, which the single-token-anchored variant let through.
    // Cross-linguistic filled-pause lexicon (hesitation markers). Only CLEARLY non-lexical sounds,
    // matched case-insensitively; whole-utterance-only (anchored) so a real word is never dropped.
    // Latin: English/German/French. Cyrillic: elongation required where a 1-letter form is a real word.
    val fillerToken =
        "(?:hmm+|uh+|um+|ah+|oh+|er+|ähm+|äh+|öh+|euh+|heu+|хм+|ух+|эм+|э+|мм+|ыы+)"
    val noisePatterns = listOf(
        Regex("^\\d+/\\d+\\s+\\w+$"),
        Regex("^$fillerToken(?:\\s+$fillerToken)*$", RegexOption.IGNORE_CASE),
        // "No letters of ANY script" = pure noise (whitespace/digits/punctuation/symbols). Uses the
        // Unicode letter property \p{L}; a plain ASCII [\s\d\W] would treat every Cyrillic/CJK/Arabic
        // letter as non-word and wrongly drop whole non-Latin utterances (regression-pinned by
        // TranscriptionValidationTest.nonLatinRealText_isAccepted).
        Regex("^[^\\p{L}]+$"),
    )

    if (noisePatterns.any { it.matches(trimmed) }) {
        BaoLog.d(TAG, "Filtered: noise pattern '$trimmed'")
        return false
    }

    BaoLog.d(TAG, "Valid transcription: '$trimmed'")
    return true
}

/**
 * Detects the common on-device LLM failure where the model echoes the source text verbatim instead
 * of translating it. Only treats it as an echo when the languages actually differ, so legitimately
 * unchanged output (proper nouns, numbers, same-language passthrough) is not flagged.
 */
internal fun isSourceEcho(
    translated: String,
    source: String,
    sourceLanguage: String,
    targetLanguage: String,
): Boolean {
    if (sourceLanguage.equals(targetLanguage, ignoreCase = true)) return false
    val trimmedTranslated = translated.trim()
    val trimmedSource = source.trim()
    // Empty content (including whitespace-only inputs such as NBSP/EM-SPACE, which
    // Kotlin's Unicode-aware trim() collapses to "") has nothing to echo, so two empty
    // sides must never be flagged as a verbatim echo failure.
    if (trimmedTranslated.isEmpty() || trimmedSource.isEmpty()) return false
    return trimmedTranslated.equals(trimmedSource, ignoreCase = true)
}

internal fun isValidTranslation(translated: String, source: String): Boolean {
    if (translated.isBlank()) return false
    if (translated.length < 2) return false

    val words = translated.split("\\s+".toRegex())

    if (words.size >= 3) {
        val uniqueWords = words.toSet()
        val repetitionRatio = uniqueWords.size.toFloat() / words.size.toFloat()
        if (repetitionRatio < 0.3f) {
            BaoLog.w(TAG, "Translation has excessive repetition: ${words.size} words, ${uniqueWords.size} unique")
            return false
        }
    }

    // Exact-repetition detector: reject a translation that is N>=2 copies of a k-word unit (e.g.
    // "hello hello hello" = 3x a 1-word unit). Runs at size >= 3 so degenerate 3-word repeated
    // hallucinations are rejected even though their ratio (0.333) sits just above the ratio gate.
    if (words.size >= 3) {
        for (unitSize in 1..words.size / 2) {
            if (words.size % unitSize == 0) {
                val unit = words.take(unitSize)
                val isRepeating = (0 until words.size step unitSize).all { start ->
                    words.subList(start, start + unitSize) == unit
                }
                if (isRepeating && words.size / unitSize >= 2) {
                    BaoLog.w(TAG, "Translation contains repeated word sequence (unit size: $unitSize)")
                    return false
                }
            }
        }
    }

    return true
}
