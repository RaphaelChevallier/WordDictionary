package com.maxfluency.app.data

import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import com.maxfluency.core.WordEntry
import java.io.IOException
import java.util.Locale

enum class DefinitionTranslationProvider { DEVICE, GOOGLE }
enum class DefinitionTranslationPhase { CHECKING, COLLAPSED, TRANSLATING, MODEL_REQUIRED, READY, FAILED, UNSUPPORTED }

data class TranslatedSense(val definition: String, val examples: List<String>)
data class TranslatedWordCard(val word: String, val senses: List<TranslatedSense>)

internal fun WordEntry.translationTexts(): List<String> = buildList {
    add(word)
    senses.forEach { sense -> add(sense.definition); addAll(sense.examples) }
}

internal fun WordEntry.translatedWordCard(texts: List<String>): TranslatedWordCard {
    val expected = 1 + senses.sumOf { 1 + it.examples.size }
    if (texts.size != expected || texts.any { it.isBlank() }) {
        throw IOException("Translation did not preserve the word, meanings and examples")
    }
    var index = 1
    return TranslatedWordCard(texts.first(), senses.map { sense ->
        val definition = texts[index++]
        TranslatedSense(definition, List(sense.examples.size) { texts[index++] })
    })
}

data class DefinitionTranslationUi(
    val entryId: String,
    val targetTag: String,
    val phase: DefinitionTranslationPhase = DefinitionTranslationPhase.CHECKING,
    val provider: DefinitionTranslationProvider? = null,
    val content: TranslatedWordCard? = null,
    val expanded: Boolean = false,
)

interface DefinitionTranslator : AutoCloseable {
    suspend fun provider(source: String, targetTag: String): DefinitionTranslationProvider?
    suspend fun translate(
        source: String, targetTag: String, texts: List<String>,
        provider: DefinitionTranslationProvider, downloadModels: Boolean,
    ): List<String>
    suspend fun removeDownloadedModels()
}

class TranslationModelRequired : IOException("Offline translation models have not been downloaded")

fun systemLanguageTag(context: Context): String? {
    val locales = if (Build.VERSION.SDK_INT >= 33) {
        context.getSystemService(LocaleManager::class.java)?.systemLocales
    } else null
    val locale = if (locales != null && !locales.isEmpty) locales[0]
        else Resources.getSystem().configuration.locales[0]
    return locale?.toLanguageTag()?.takeUnless { it.isBlank() || it == "und" }
}

fun definitionTranslationTarget(source: String, systemTag: String?): String? {
    val tag = systemTag?.takeIf { it.isNotBlank() } ?: return null
    val locale = Locale.forLanguageTag(tag)
    return tag.takeIf { locale.language.isNotBlank() && locale.language != "und" && locale.language != source }
}
