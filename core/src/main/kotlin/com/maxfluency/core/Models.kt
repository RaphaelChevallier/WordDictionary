package com.maxfluency.core

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
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
data class Sense(val partOfSpeech: String, val definition: String, val sourceIds: List<String> = emptyList())

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
        }
}

data class DailyWords(
    val date: LocalDate,
    val featured: WordEntry,
    val grid: List<WordEntry>,
    val explored: Set<String> = emptySet(),
    val round: Int = 0,
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
