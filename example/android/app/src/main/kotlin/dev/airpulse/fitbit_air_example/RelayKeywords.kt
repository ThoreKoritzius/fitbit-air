package dev.airpulse.fitbit_air_example

import java.util.Locale

/**
 * A user-defined message rule: a message that contains any of [words] gets [preset]
 * (MUTE keeps it silent). Words and phrases match whole words, ignoring case and
 * umlauts ("Büro" also matches "buero"); a trailing `*` matches word forms ("call*"
 * matches "calling"). Emoji and other symbols match anywhere in the text.
 */
internal data class RelayKeywordRule(val words: List<String>, val preset: RelayPreset) {
    init {
        require(words.isNotEmpty() && words.size <= MAX_WORDS) { "A keyword rule needs 1 to $MAX_WORDS words" }
        require(words.all { it.isNotBlank() && it.length <= MAX_WORD_LENGTH }) { "Keywords must be 1 to $MAX_WORD_LENGTH characters" }
    }

    companion object {
        const val MAX_RULES = 20
        const val MAX_WORDS = 20
        const val MAX_WORD_LENGTH = 40
    }
}

/** Local matching only; the message text is neither kept nor logged. */
internal object RelayKeywordMatcher {
    private const val MAX_CHARS = 1000
    private val tokenSplit = Regex("[^\\p{L}\\p{N}]+")
    private val patternSplit = Regex("[^\\p{L}\\p{N}*]+")

    /** Index of the first rule with a keyword in [text], or null. Rules are checked in order. */
    fun firstMatch(rules: List<RelayKeywordRule>, text: String?): Int? {
        if (rules.isEmpty() || text.isNullOrBlank()) return null
        val raw = text.take(MAX_CHARS)
        val symbols = normalizeSymbols(raw)
        val tokens = tokens(raw)
        return rules.indexOfFirst { rule -> rule.words.any { matches(it, tokens, symbols) } }.takeIf { it >= 0 }
    }

    private fun matches(keyword: String, tokens: List<String>, symbols: String): Boolean {
        // A word ending in * matches its forms; the star is kept apart from other punctuation.
        val wanted = RelayVader.foldUmlauts(keyword.lowercase(Locale.ROOT)).split(patternSplit)
            .map { it.trimEnd('*') to it.endsWith("*") }.filter { it.first.isNotEmpty() }
        // Emoji, "!!" and other keywords without letters or digits match anywhere.
        if (wanted.isEmpty()) return normalizeSymbols(keyword.trim()).let { it.isNotEmpty() && it in symbols }
        if (wanted.size > tokens.size) return false
        return (0..tokens.size - wanted.size).any { start ->
            wanted.indices.all { i ->
                val (word, prefix) = wanted[i]
                if (prefix) tokens[start + i].startsWith(word) else tokens[start + i] == word
            }
        }
    }

    private fun tokens(text: String): List<String> =
        RelayVader.foldUmlauts(text.lowercase(Locale.ROOT)).split(tokenSplit).filter { it.isNotEmpty() }

    /** Drops emoji presentation selectors so "⚠️" and "⚠" are the same keyword. */
    private fun normalizeSymbols(text: String) = text.replace("️", "").replace("︎", "")
}
