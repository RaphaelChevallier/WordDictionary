package com.maxfluency.core

import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale

private val combiningMarks = Regex("\\p{M}+")
private val whitespace = Regex("\\s+")
const val LOOKUP_SEPARATOR: Char = '\u001f'

fun normalizeSearch(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(combiningMarks, "")
        .lowercase(Locale.ROOT)
        .replace("\u0153", "oe")
        .replace("\u00e6", "ae")
        .replace("\u00df", "ss")
        .replace('\u2019', '\'')
        .trim()
        .replace(whitespace, " ")

fun anagramKey(value: String): String =
    normalizeSearch(value).filter(Char::isLetter).toCharArray().sortedArray().concatToString()

fun lookupKey(word: String): String =
    normalizeSearch(word) + LOOKUP_SEPARATOR + Normalizer.normalize(word, Normalizer.Form.NFC)

fun discoveryPreview(entry: WordEntry, policy: DiscoveryPolicy): WordEntry? {
    val word = Normalizer.normalize(entry.word, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    if (policy.excludedWords[entry.language.code].orEmpty().any {
        Normalizer.normalize(it, Normalizer.Form.NFC).lowercase(Locale.ROOT) == word
    }) return null
    val senses = entry.senses.filter {
        it.partOfSpeech !in policy.excludedPartsOfSpeech && !it.partOfSpeech.startsWith("other:")
    }
    return if (senses.isEmpty()) null else entry.copy(senses = senses)
}

enum class SearchMode { WORDS, ANAGRAMS }

sealed interface SearchPlan {
    data object Empty : SearchPlan
    data object NoLetters : SearchPlan
    data class Prefix(val value: String) : SearchPlan
    data class Wildcard(val value: String) : SearchPlan
    data class Anagram(val value: String) : SearchPlan
}

fun searchPlan(query: String, mode: SearchMode): SearchPlan {
    val normalized = normalizeSearch(query)
    if (normalized.isEmpty()) return SearchPlan.Empty
    if (mode == SearchMode.ANAGRAMS) {
        val key = anagramKey(normalized)
        return if (key.isEmpty()) SearchPlan.NoLetters else SearchPlan.Anagram(key)
    }
    if (normalized.none { it == '*' || it == '?' || it == '.' }) return SearchPlan.Prefix(normalized)
    val pattern = buildString {
        normalized.forEach { character ->
            append(when (character) {
                '.' -> "?"
                '[' -> "[[]"
                ']' -> "[]]"
                else -> character.toString()
            })
        }
    }
    return SearchPlan.Wildcard(pattern)
}

fun dailyIndices(date: LocalDate, language: Language, total: Int): List<Int> {
    require(total > 0) { "A daily dictionary must contain eligible words" }
    val count = minOf(21, total)
    var stride = count
    while (greatestCommonDivisor(stride, total) != 1) stride++
    val offset = language.code.fold(0L) { hash, character -> hash * 31 + character.code }
    val start = Math.floorMod(date.toEpochDay() * stride + offset, total.toLong()).toInt()
    return List(count) { index -> (start + index) % total }
}

private fun greatestCommonDivisor(first: Int, second: Int): Int {
    var a = first
    var b = second
    while (b != 0) {
        val remainder = a % b
        a = b
        b = remainder
    }
    return a
}

fun dailyGridSize(widthDp: Float, fontScale: Float, heightDp: Float = Float.POSITIVE_INFINITY): Int =
    when {
        heightDp / fontScale.coerceAtLeast(1f) < 260f -> 8
        heightDp / fontScale.coerceAtLeast(1f) < 440f -> 12
        widthDp / fontScale.coerceAtLeast(1f) >= 600f -> 20
        widthDp / fontScale.coerceAtLeast(1f) >= 400f -> 18
        else -> 15
    }
