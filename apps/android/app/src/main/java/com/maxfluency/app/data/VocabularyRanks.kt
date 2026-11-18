package com.maxfluency.app.data

import android.content.Context
import com.maxfluency.core.Language
import com.maxfluency.core.normalizeSearch

enum class VocabularyLevel { BEGINNER, INTERMEDIATE, ADVANCED }

class VocabularyRanks(private val context: Context) {
    private var language: Language? = null
    private var ranks: Map<String, Int> = emptyMap()

    @Synchronized
    fun level(language: Language, word: String): VocabularyLevel {
        if (this.language != language) {
            ranks = context.assets.open("ranks/${language.code}.txt").bufferedReader().useLines { lines ->
                lines.withIndex().associate { normalizeSearch(it.value) to it.index + 1 }
            }
            this.language = language
        }
        return when (ranks[normalizeSearch(word)] ?: Int.MAX_VALUE) {
            in 1..2000 -> VocabularyLevel.BEGINNER
            in 2001..10000 -> VocabularyLevel.INTERMEDIATE
            else -> VocabularyLevel.ADVANCED
        }
    }
}
