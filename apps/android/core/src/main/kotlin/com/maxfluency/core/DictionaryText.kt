package com.maxfluency.core

import java.text.Normalizer
import java.util.logging.Logger

object DictionaryText {
    private val numericReference = Regex("""\s*\^\(\[(?:\d+(?:[\s,;-]+\d+)*|note\s+\d+)]\)""", RegexOption.IGNORE_CASE)
    private val annotation = Regex("""\s*\^\(\[([^]]+)]\)""")
    private val brokenMarkup = Regex("""\{\{|\}\}|\[\[|]]|\uFFFD|</?[a-z][^>]*>""", RegexOption.IGNORE_CASE)
    private val citationPrefix = Regex(
        """^(?:\d{3,4}(?:\s+[\p{L}-]+)?\s*[,:\u2014]|(?:\d{1,2}\s+)?(?:January|February|March|April|May|June|July|August|September|October|November|December)\s+(?:\d{1,2},?\s+)?\d{4}\s*[,:\u2014])""",
        RegexOption.IGNORE_CASE,
    )
    private val whitespace = Regex("\\s+")
    private val namedLink = Regex("""\[\[([^\[\]|]+)\|([^\[\]]+)]]""")
    private val simpleLink = Regex("""\[\[([^\[\]|]+)]]""")
    private val unclosedLink = Regex("""\[\[([^\[\]|]+)](?!])""")
    private val spaceBeforePeriod = Regex("""\s+\.(?!\.)""")
    private val url = Regex("""https?://|www\.""", RegexOption.IGNORE_CASE)
    private val sentenceEnd = Regex("""[.!?…][”"')»\]]?$""")
    private val logger = Logger.getLogger("DictionaryText")

    fun definition(value: String): String {
        val text = Normalizer.normalize(value, Normalizer.Form.NFC)
            .replace(numericReference, "").replace(annotation, " ($1)")
            .replace(namedLink, "$2").replace(simpleLink, "$1").replace(unclosedLink, "$1")
            .replace(whitespace, " ").replace(spaceBeforePeriod, ".").trim()
        if (brokenMarkup.containsMatchIn(text) || text.none { it.isLetterOrDigit() }) {
            logger.warning("Omitting a dictionary gloss with unresolved markup or no lexical content")
            return ""
        }
        return text
    }

    fun examples(values: List<String>): List<String> = values
        .map { Normalizer.normalize(it, Normalizer.Form.NFC).replace(whitespace, " ").trim() }
        .filter { it.isNotBlank() && it.length <= 600 && !brokenMarkup.containsMatchIn(it) &&
            !url.containsMatchIn(it) && !citationPrefix.containsMatchIn(it) }
        .distinct().sortedByDescending { sentenceEnd.containsMatchIn(it) && it.split(' ').size >= 3 }.take(3)
}
