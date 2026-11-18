package com.maxfluency.app.ui

import com.maxfluency.app.data.VocabularyLevel
import java.time.LocalDate

data class ArchivedWordTarget(val id: String, val date: LocalDate, val level: VocabularyLevel)
