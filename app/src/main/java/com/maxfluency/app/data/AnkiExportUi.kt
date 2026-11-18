package com.maxfluency.app.data

import com.maxfluency.core.WordEntry

data class AnkiExportUi(
    val entries: List<WordEntry>,
    val busy: Boolean = false,
    val progress: Int = 0,
    val failed: Boolean = false,
)
