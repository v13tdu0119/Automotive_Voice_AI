package com.sopa.viva_automotive.feature.voice.domain.wakeword

import java.text.Normalizer

data class PhoneticMatch(
    val phrase: HotwordPhrase,
    val confidence: Float,
)

object PhoneticMatcher {

    fun match(transcript: String, config: HotwordConfig): PhoneticMatch? {
        val hypothesis = phonemesOf(transcript)
        if (hypothesis.isEmpty()) return null

        val best = config.accepted
            .map { phrase -> PhoneticMatch(phrase, similarity(hypothesis, phrase.phonemes)) }
            .maxByOrNull { it.confidence }
            ?: return null
        if (best.confidence < config.matchThreshold) return null

        val rejected = config.rejected.maxOfOrNull { similarity(hypothesis, it.phonemes) } ?: 0f
        if (rejected >= best.confidence) return null

        return best
    }

    fun phonemesOf(transcript: String): List<String> =
        tokenize(transcript).flatMap { token -> LEXICON[token] ?: guessPhonemes(token) }

    fun similarity(hypothesis: List<String>, target: List<String>): Float {
        if (hypothesis.isEmpty() || target.isEmpty()) return 0f
        val shortest = (target.size - WINDOW_SLACK).coerceAtLeast(1)
        val longest = (target.size + WINDOW_SLACK).coerceAtMost(hypothesis.size)
        if (shortest > hypothesis.size) return score(hypothesis, target)

        var best = 0f
        for (length in shortest..longest) {
            for (start in 0..hypothesis.size - length) {
                best = maxOf(best, score(hypothesis.subList(start, start + length), target))
                if (best == 1f) return best
            }
        }
        return best
    }

    private fun score(window: List<String>, target: List<String>): Float {
        val distance = editDistance(window, target)
        val span = maxOf(window.size, target.size).toFloat()
        return (1f - distance / span).coerceIn(0f, 1f)
    }

    private fun editDistance(source: List<String>, target: List<String>): Float {
        var previous = FloatArray(target.size + 1) { it.toFloat() }
        var current = FloatArray(target.size + 1)
        for (i in 1..source.size) {
            current[0] = i.toFloat()
            for (j in 1..target.size) {
                current[j] = minOf(
                    previous[j] + 1f,
                    current[j - 1] + 1f,
                    previous[j - 1] + substitutionCost(source[i - 1], target[j - 1]),
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[target.size]
    }

    private fun substitutionCost(source: String, target: String): Float = when {
        source == target -> 0f
        else -> NEAR_MATCHES[setOf(source, target)] ?: 1f
    }

    private fun tokenize(transcript: String): List<String> =
        stripDiacritics(transcript.lowercase())
            .split(NON_LETTER)
            .filter { it.isNotEmpty() }

    private fun stripDiacritics(text: String): String =
        Normalizer.normalize(text.replace('đ', 'd'), Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")

    private fun guessPhonemes(word: String): List<String> {
        val phonemes = mutableListOf<String>()
        var index = 0
        while (index < word.length) {
            val digraph = if (index + 1 < word.length) word.substring(index, index + 2) else null
            val mapped = digraph?.let(DIGRAPHS::get)
            if (mapped != null) {
                phonemes += mapped
                index += 2
            } else {
                LETTERS[word[index]]?.let { phonemes += it }
                index++
            }
        }
        return phonemes
    }

    private val NON_LETTER = Regex("[^a-z]+")
    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private const val WINDOW_SLACK = 1

    private val LEXICON: Map<String, List<String>> = mapOf(
        "vi" to listOf("v", "iː"),
        "vy" to listOf("v", "iː"),
        "vee" to listOf("v", "iː"),
        "v" to listOf("v", "iː"),
        "vivi" to listOf("v", "iː", "v", "iː"),
        "vivy" to listOf("v", "iː", "v", "iː"),
        "bi" to listOf("b", "iː"),
        "be" to listOf("b", "iː"),
        "wi" to listOf("w", "iː"),
        "we" to listOf("w", "iː"),
        "wee" to listOf("w", "iː"),
        "hey" to listOf("h", "eɪ"),
        "hay" to listOf("h", "eɪ"),
        "hei" to listOf("h", "eɪ"),
        "he" to listOf("h", "iː"),
        "a" to listOf("eɪ"),
        "hi" to listOf("h", "aɪ"),
        "high" to listOf("h", "aɪ"),
        "why" to listOf("w", "aɪ"),
        "y" to listOf("w", "aɪ"),
        "wifi" to listOf("w", "aɪ", "f", "aɪ"),
        "five" to listOf("f", "aɪ", "v"),
        "vai" to listOf("v", "aɪ"),
        "bye" to listOf("b", "aɪ"),
        "buy" to listOf("b", "aɪ"),
        "my" to listOf("m", "aɪ"),
        "fly" to listOf("f", "l", "aɪ"),
    )

    private val NEAR_MATCHES: Map<Set<String>, Float> = mapOf(
        setOf("v", "w") to 0.5f,
        setOf("v", "b") to 0.5f,
        setOf("v", "f") to 0.5f,
        setOf("b", "p") to 0.5f,
        setOf("iː", "ɪ") to 0.25f,
        setOf("iː", "i") to 0.25f,
        setOf("eɪ", "e") to 0.25f,
        setOf("eɪ", "æ") to 0.5f,
    )

    private val DIGRAPHS: Map<String, List<String>> = mapOf(
        "ee" to listOf("iː"),
        "ea" to listOf("iː"),
        "ie" to listOf("iː"),
        "ey" to listOf("eɪ"),
        "ay" to listOf("eɪ"),
        "ai" to listOf("aɪ"),
        "oo" to listOf("uː"),
        "ch" to listOf("tʃ"),
        "sh" to listOf("ʃ"),
        "th" to listOf("θ"),
        "ng" to listOf("ŋ"),
    )

    private val LETTERS: Map<Char, List<String>> = mapOf(
        'a' to listOf("æ"),
        'b' to listOf("b"),
        'c' to listOf("k"),
        'd' to listOf("d"),
        'e' to listOf("e"),
        'f' to listOf("f"),
        'g' to listOf("ɡ"),
        'h' to listOf("h"),
        'i' to listOf("ɪ"),
        'j' to listOf("dʒ"),
        'k' to listOf("k"),
        'l' to listOf("l"),
        'm' to listOf("m"),
        'n' to listOf("n"),
        'o' to listOf("ɒ"),
        'p' to listOf("p"),
        'q' to listOf("k"),
        'r' to listOf("r"),
        's' to listOf("s"),
        't' to listOf("t"),
        'u' to listOf("ʌ"),
        'v' to listOf("v"),
        'w' to listOf("w"),
        'x' to listOf("k", "s"),
        'y' to listOf("j"),
        'z' to listOf("z"),
    )
}
