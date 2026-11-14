package com.maxfluency.core

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

object DictionaryHtml {
    private fun part(label: String): String? {
        val text = normalizeSearch(label).substringBefore('|').trim()
        return when {
            text.contains("synonym") || text.contains("sinonim") -> null
            text.contains("adverb") || text.contains("adverbial") -> "adv"
            text.contains("adject") || text.contains("adjetiv") -> "adj"
            text.startsWith("noun") || text.startsWith("proper noun") || text.startsWith("nom") ||
                text.startsWith("sustantiv") -> "noun"
            text.startsWith("verb") || text.startsWith("forme de verbe") ||
                text.startsWith("forme verbale") || text.startsWith("forma verbal") -> "verb"
            text.startsWith("forme de nom") -> "noun"
            text.startsWith("article") || text.startsWith("articulo") -> "article"
            text.startsWith("pron") -> "pron"
            text.startsWith("determin") -> "det"
            text.startsWith("prepos") -> "prep"
            text.startsWith("conj") -> "conj"
            text.startsWith("interj") -> "intj"
            text.startsWith("prefix") || text.startsWith("prefij") -> "prefix"
            text.startsWith("suffix") || text.startsWith("sufij") -> "suffix"
            text.startsWith("phrase") || text.startsWith("locution") || text.startsWith("locucion") -> "phrase"
            text.startsWith("proverb") || text.startsWith("refran") -> "proverb"
            text.startsWith("numer") -> "num"
            text.startsWith("particul") -> "particle"
            text.startsWith("contract") -> "contraction"
            text.startsWith("symbol") || text.startsWith("simbol") ||
                text.startsWith("letter") || text.startsWith("lettre") || text.startsWith("letra") -> "other:$label"
            else -> null
        }
    }

    private fun definition(element: Element): String = element.clone().apply {
        select("ol, ul, dl, table, script, style, .reference, .citation, .h-quotation, .mw-editsection").remove()
    }.text().replace(Regex("\\s+"), " ").trim()

    fun wiki(language: Language, word: String, html: String): WordEntry? {
        val body = Jsoup.parseBodyFragment(html).body()
        val heading = when (language) {
            Language.ENGLISH -> "english"
            Language.FRENCH -> "francais"
            Language.SPANISH -> "espanol"
        }
        val senses = mutableListOf<Sense>()
        val related = linkedSetOf<String>()
        var active = false
        var currentPart: String? = null
        var pronunciation = ""
        var pronunciationDepth: Int? = null
        body.select("h2, h3, h4, h5, h6, ol, dl, .IPA, .API, table.pron-graf").forEach { element ->
            when {
                element.normalName() == "h2" -> {
                    active = normalizeSearch(element.text()) == heading
                    currentPart = null
                    pronunciationDepth = null
                }
                !active -> Unit
                element.normalName().startsWith("h") -> {
                    val label = normalizeSearch(element.text())
                    val depth = element.normalName().last().digitToInt()
                    if (label.startsWith("pronunciation") || label.startsWith("prononciation") || label.startsWith("pronunciacion")) {
                        pronunciationDepth = depth
                    } else if (pronunciationDepth?.let { depth <= it } == true) {
                        pronunciationDepth = null
                    }
                    currentPart = part(element.text())
                }
                pronunciation.isEmpty() && (element.hasClass("IPA") || element.hasClass("API")) &&
                    (pronunciationDepth != null || (language == Language.FRENCH && currentPart != null &&
                        element.parents().none { it.normalName() in setOf("li", "ol", "dl", "ul") })) -> {
                    pronunciation = element.text().trim().take(200)
                }
                pronunciation.isEmpty() && element.hasClass("pron-graf") -> {
                    pronunciation = element.select("tr").firstNotNullOfOrNull { row ->
                        val cells = row.children().filter { it.normalName() == "td" }
                        if (cells.size >= 2 && cells[0].text().contains("AFI")) cells[1].ownText().trim().takeIf { it.isNotEmpty() }
                        else null
                    }.orEmpty().take(200)
                }
                currentPart != null && element.normalName() == "ol" &&
                    element.parents().none { it.normalName() == "ol" || it.normalName() == "ul" } -> {
                    element.select("li").filter { it.parent()?.normalName() == "ol" }.forEach { item ->
                        val text = definition(item)
                        if (text.isNotBlank()) senses += Sense(checkNotNull(currentPart), text)
                    }
                }
                currentPart != null && language == Language.SPANISH && element.normalName() == "dl" &&
                    element.children().any { it.normalName() == "dt" && it.text().firstOrNull()?.isDigit() == true } -> {
                    element.children().filter { it.normalName() == "dd" }.forEach {
                        val text = definition(it)
                        if (text.isNotBlank()) senses += Sense(checkNotNull(currentPart), text)
                    }
                }
            }
        }
        if (senses.isEmpty()) return null
        return entry(language, word, pronunciation, senses, related, false)
    }

    fun starDict(language: Language, word: String, html: String): WordEntry? {
        val body = Jsoup.parseBodyFragment(html).body()
        val senses = mutableListOf<Sense>()
        val related = linkedSetOf<String>()
        var currentPart: String? = null
        var synonyms = false
        body.children().forEach { element ->
            when (element.normalName()) {
                "p" -> {
                    val label = element.selectFirst("b")?.text().orEmpty()
                    synonyms = normalizeSearch(label).let {
                        it.startsWith("synonym") || it.startsWith("sinonim")
                    }
                    currentPart = if (label.isBlank() || synonyms) null else part(label) ?: "other:$label"
                }
                "ol" -> element.select("li").forEach { item ->
                    val text = definition(item)
                    if (text.isNotBlank()) {
                        if (synonyms) related += text
                        else currentPart?.let { senses += Sense(it, text) }
                    }
                }
            }
        }
        if (senses.isEmpty()) return null
        val firstText = body.textNodes().firstOrNull()?.text()?.trim().orEmpty()
        val pronunciation = firstText.takeIf { it.length < 200 && (it.startsWith("/") || it.startsWith("\\") || it.startsWith("[")) }.orEmpty()
        return entry(language, word, pronunciation, senses, related, true)
    }

    fun entry(language: Language, word: String, pronunciation: String, senses: List<Sense>,
        related: Set<String>, downloaded: Boolean): WordEntry {
        val source = DictionarySource(if (downloaded) "reader-dict" else "wiktionary",
            language.code, if (downloaded) "Wiktionary / reader.dict" else "Wiktionary", "CC-BY-SA-4.0")
        return WordEntry("${language.code}:$word", language, word, pronunciation,
            senses.distinctBy { it.partOfSpeech to it.definition }, related.take(16), language.code, listOf(source))
    }
}
