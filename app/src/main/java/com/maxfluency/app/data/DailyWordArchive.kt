package com.maxfluency.app.data

import com.maxfluency.core.Language
import com.maxfluency.core.WordEntry
import java.time.LocalDate
import java.time.YearMonth

data class ArchivedDailyWord(
    val date: LocalDate,
    val level: VocabularyLevel,
    val entry: WordEntry,
    val backfilled: Boolean = false,
)

data class WordCalendarUi(
    val month: YearMonth,
    val language: Language,
    val level: VocabularyLevel,
    val today: LocalDate,
    val words: List<ArchivedDailyWord> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    val selectedDate: LocalDate? = null,
)
