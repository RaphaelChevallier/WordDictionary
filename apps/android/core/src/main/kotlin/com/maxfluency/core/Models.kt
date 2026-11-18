package com.maxfluency.core

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class Language(val code: String, val nativeName: String) {
    @SerialName("en")
    ENGLISH("en", "English"),
    @SerialName("fr")
    FRENCH("fr", "Fran\u00e7ais"),
    @SerialName("es")
    SPANISH("es", "Espa\u00f1ol");

    companion object {
        fun fromCode(code: String): Language? = entries.find { it.code == code }
    }
}

@Serializable
data class Sense(
    val partOfSpeech: String,
    val definition: String,
    val sourceIds: List<String> = emptyList(),
    val examples: List<String> = emptyList(),
)

fun mergeSenses(senses: Iterable<Sense>): List<Sense> =
    senses.groupBy { it.partOfSpeech to it.definition }.values.map { same ->
        same.first().copy(sourceIds = same.flatMap { it.sourceIds }.distinct(),
            examples = DictionaryText.examples(same.flatMap { it.examples }))
    }

private val leadingSenseLabels = Regex("^(?:\\([^()]*\\)\\s*)+")
private fun Sense.exampleKey(): Pair<String, String> = partOfSpeech to
    DictionaryText.definition(definition).replace(leadingSenseLabels, "")
        .replace('\u2019', '\'').lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

fun WordEntry.withUsageExamples(bundled: WordEntry?): WordEntry {
    if (bundled == null || id != bundled.id || language != bundled.language || word != bundled.word) return this
    val contributed = linkedSetOf<String>()
    val candidates = bundled.senses.groupBy { it.exampleKey() }
    val enriched = senses.map { sense ->
        val key = sense.exampleKey()
        val matching = if (key.second.isBlank()) null else candidates[key]?.singleOrNull()
        if (matching == null || matching.examples.isEmpty()) sense.copy(examples = DictionaryText.examples(sense.examples)) else {
            contributed.addAll(matching.sourceIds)
            sense.copy(
                examples = DictionaryText.examples(sense.examples + matching.examples),
                sourceIds = (sense.sourceIds.ifEmpty { sources.map { it.id } } + matching.sourceIds).distinct(),
            )
        }
    }
    return if (enriched == senses) this else copy(senses = enriched,
        sources = (sources + bundled.sources.filter { it.id in contributed }).distinctBy { it.id })
}

@Serializable
data class DictionarySource(val id: String, val edition: String, val title: String, val license: String)

@Serializable
data class WordEntry(
    val id: String,
    val language: Language,
    val word: String,
    val pronunciation: String,
    val senses: List<Sense>,
    val related: List<String>,
    val sourceEdition: String,
    val sources: List<DictionarySource> = emptyList(),
    val pronunciationGuide: String = "",
) {
    val sourceUrl: String
        get() = "https://$sourceEdition.wiktionary.org/wiki/" +
            URLEncoder.encode(word, StandardCharsets.UTF_8.name()).replace("+", "%20")

    val sourceLinks: List<Pair<String, String>>
        get() = sources.ifEmpty {
            listOf(DictionarySource(sourceEdition, sourceEdition, "Wiktionary", "CC-BY-SA-4.0"))
        }.flatMap { source ->
            val wiki = source.title to "https://${source.edition}.wiktionary.org/wiki/" +
                URLEncoder.encode(word, StandardCharsets.UTF_8.name()).replace("+", "%20")
            if (source.id == "reader-dict") listOf(wiki, "reader.dict" to "https://www.reader-dict.com/download/${language.code}")
            else listOf(wiki)
        }.distinctBy { it.second }
}

data class DailyWords(
    val date: LocalDate,
    val featured: WordEntry,
    val grid: List<WordEntry>,
    val explored: Set<String> = emptySet(),
    val round: Int = 0,
    val featuredMeaningCount: Int = featured.senses.size,
)

data class SavedWord(val entry: WordEntry, val favorite: Boolean, val lastViewed: Long)

@Serializable
data class LanguagePack(
    val language: String,
    val edition: String,
    val releaseVersion: String,
    val file: String,
    val sha256: String,
    val compressedSha256: String,
    val compressedBytes: Long,
    val unpackedBytes: Long,
    val entries: Int,
    val dailyEntries: Int,
)

@Serializable
data class DiscoveryPolicy(
    val excludedPartsOfSpeech: Set<String> = emptySet(),
    val excludedWords: Map<String, List<String>> = emptyMap(),
)

@Serializable
data class PackManifest(
    val schemaVersion: Int,
    val compression: String,
    val releaseVersion: String,
    val builtAt: String,
    val license: String,
    val attribution: String,
    val modifications: String,
    val languages: List<LanguagePack>,
    val discovery: DiscoveryPolicy = DiscoveryPolicy(),
)

val dictionaryJson = Json { ignoreUnknownKeys = true }
