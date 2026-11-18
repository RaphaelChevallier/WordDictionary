package com.maxfluency.app.data

import com.maxfluency.core.WordEntry
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

data class AnkiNote(val key: String, val word: String, val meaning: String, val tag: String) {
    val fields: List<String> get() = listOf(key, word, meaning)
    val guid: String get() = "mf" + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).copyOf(15))
}

object AnkiCards {
    const val MODEL_NAME = "MaxFluency Vocabulary v1"
    const val DECK_NAME = "MaxFluency"
    val FIELDS = listOf("MaxFluencyId", "Word", "Meanings")
    const val QUESTION = "{{Word}}"
    const val ANSWER = "{{FrontSide}}<hr id=\"answer\">{{Meanings}}"
    const val CSS = ".card { font-family: sans-serif; font-size: 20px; text-align: left; } " +
        ".word { font-family: serif; font-size: 30px; } .language, .credits { font-size: 14px; } " +
        ".example { font-style: italic; margin: 0.4em 0; } li { margin-bottom: 0.8em; }"

    fun html(text: String): String = buildString {
        text.forEach {
            append(when (it) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&#39;"
                '\n' -> "<br>"
                else -> if (it.isISOControl()) " " else it.toString()
            })
        }
    }

    fun notes(entries: List<WordEntry>): List<AnkiNote> {
        if (entries.isEmpty() || entries.size > 5_000) throw IOException("Anki exports need 1-5000 words")
        return entries.distinctBy { it.id }.map { entry ->
            if (entry.id != "${entry.language.code}:${entry.word}" || entry.word.isBlank() || entry.senses.isEmpty()) {
                throw IOException("Anki export requires a complete word entry")
            }
            val key = "mf-${entry.language.code}-" + MessageDigest.getInstance("SHA-256")
                .digest(entry.id.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
            val front = "<div class=\"language\">${html(entry.language.nativeName)}</div>" +
                "<div class=\"word\">${html(entry.word)}</div>"
            val back = buildString {
                append("<ol>")
                entry.senses.forEach { sense ->
                    append("<li>${html(sense.definition)}")
                    sense.examples.forEach { append("<div class=\"example\">${html(it)}</div>") }
                    append("</li>")
                }
                append("</ol><div class=\"credits\">Wiktionary contributors")
                entry.sourceLinks.forEach { (title, url) ->
                    append("<br><a href=\"${html(url)}\">${html(title)}</a>")
                }
                append("<br><a href=\"https://creativecommons.org/licenses/by-sa/4.0/\">CC BY-SA 4.0</a></div>")
            }
            if (front.toByteArray().size + back.toByteArray().size > 200_000) {
                throw IOException("Anki note exceeds the safe export size")
            }
            AnkiNote(key, front, back, "maxfluency::${entry.language.code}")
        }
    }
}
