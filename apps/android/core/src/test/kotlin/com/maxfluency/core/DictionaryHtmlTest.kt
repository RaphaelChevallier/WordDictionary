package com.maxfluency.core

import org.junit.Assert.*
import org.junit.Test

class DictionaryHtmlTest {
    @Test
    fun `online definitions stay in the selected native language and omit quotations`() {
        val html = """
            <h2>English</h2><h3>Pronunciation</h3><span class="IPA">/wrong-language/</span><h3>Noun</h3><ol><li>An English definition.</li></ol>
            <h2><span>Français</span></h2><h3>Nom commun</h3><span class="API">\ʃa\</span>
            <ol><li>Un petit mammifère.<ul><li>A quoted copyrighted example.</li></ul></li></ol>
            <h3>Adverbe</h3><ol><li>Une autre acception.</li></ol>
            <h2>Español</h2><h3>Sustantivo</h3><dl><dt>1</dt><dd>Una definición.</dd></dl>
        """
        val entry = DictionaryHtml.wiki(Language.FRENCH, "chat", html)!!
        assertEquals(listOf("noun", "adv"), entry.senses.map { it.partOfSpeech })
        assertEquals("Un petit mammifère.", entry.senses.first().definition)
        assertEquals("\\ʃa\\", entry.pronunciation)
        assertFalse(entry.senses.any { it.definition.contains("quoted") || it.definition.contains("English") })
        assertNull(DictionaryHtml.wiki(Language.SPANISH, "absent", "<h2>English</h2><h3>Noun</h3><ol><li>A word.</li></ol>"))
    }

    @Test
    fun `Spanish numbered definitions and downloaded dictionary parts parse safely`() {
        val online = DictionaryHtml.wiki(Language.SPANISH, "gato",
            "<h2>Español</h2><table class='pron-graf'><tr><td>pronunciación (AFI)</td><td>[ˈgato]<span>recording</span></td></tr></table><h4>Sustantivo masculino</h4><dl><dt>1 Zoología</dt><dd>Un mamífero.<ul><li>Un ejemplo.</li></ul></dd></dl>")!!
        assertEquals("Un mamífero.", online.senses.single().definition)
        assertEquals("[ˈgato]", online.pronunciation)
        val offline = DictionaryHtml.starDict(Language.FRENCH, "chat",
            "\\ʃa\\<p><b>Nom</b> <i>m.</i></p><ol><li>Un félin.</li></ol><p><b>Synonymes</b></p><ol><li>matou</li></ol>")!!
        assertEquals("noun", offline.senses.single().partOfSpeech)
        assertEquals("Un félin.", offline.senses.single().definition)
        assertEquals("\\ʃa\\", offline.pronunciation)
        assertTrue(offline.related.contains("matou"))
        assertEquals("reader-dict", offline.sources.single().id)
    }

    @Test
    fun `examples stay with their own meaning and citations never become examples or extra senses`() {
        val html = """
            <h2>Français</h2><h3>Verbe</h3><ol>
            <li>Première définition.<ul>
              <li><span class="example"><q>Un premier exemple.</q></span></li>
              <li><span class="example"><q>Une citation.</q><span class="sources">Un ouvrage.</span></span></li>
            </ul><ol><li>Définition plus précise.<ul><li><span class="example">Un exemple précis.</span></li></ul></li></ol></li>
            <li>Deuxième définition.<dl><dd><span class="h-usage-example"><span class="e-example">Un deuxième exemple.</span>
              <span class="e-translation">Not the source language.</span></span></dd></dl></li>
            </ol><h2>English</h2><h3>Verb</h3><ol><li>Not French.</li></ol>
        """
        val entry = requireNotNull(DictionaryHtml.wiki(Language.FRENCH, "tester", html))
        assertEquals(listOf("Première définition.", "Définition plus précise.", "Deuxième définition."),
            entry.senses.map { it.definition })
        assertEquals(listOf(listOf("Un premier exemple."), listOf("Un exemple précis."), listOf("Un deuxième exemple.")),
            entry.senses.map { it.examples })
        val spanish = requireNotNull(DictionaryHtml.wiki(Language.SPANISH, "prueba", """
            <h2>Español</h2><h3>Sustantivo</h3><dl><dt>1</dt>
            <dd>Primera definición.<ul><li><b>Ejemplo:</b> Primer ejemplo.</li></ul></dd>
            <dt>2</dt><dd>Segunda definición.<ul><li><b>Ejemplo:</b><blockquote>
              <span class="cita">Una cita publicada.</span><span class="ref">Un libro.</span>
            </blockquote></li></ul></dd></dl>
        """))
        assertEquals(listOf(listOf("Primer ejemplo."), emptyList<String>()), spanish.senses.map { it.examples })
        val downloaded = requireNotNull(DictionaryHtml.starDict(Language.ENGLISH, "test",
            "<p><b>Noun</b></p><ol><li>A test.<dl><dd><span class='usage-example'>This is a test.</span></dd></dl>" +
                "<ul><li>A usage note, not another meaning.</li></ul></li></ol>"))
        assertEquals(listOf("This is a test."), downloaded.senses.single().examples)
    }

    @Test
    fun `old saved senses decode without examples and merged meanings preserve them`() {
        val old = dictionaryJson.decodeFromString<Sense>("""{"partOfSpeech":"verb","definition":"A meaning."}""")
        assertTrue(old.examples.isEmpty())
        val merged = mergeSenses(listOf(old.copy(sourceIds = listOf("one"), examples = listOf("First example.")),
            old.copy(sourceIds = listOf("two"), examples = listOf("Second example.", "First example."))))
        assertEquals(1, merged.size)
        assertEquals(listOf("First example.", "Second example."), merged.single().examples)
        assertEquals(listOf("one", "two"), merged.single().sourceIds)
    }

    @Test
    fun `bundled examples enrich only unambiguous matching meanings without replacing full definitions`() {
        val source = DictionarySource("fr-wiktionary", "fr", "Wiktionnaire", "CC-BY-SA-4.0")
        val base = WordEntry("fr:mot", Language.FRENCH, "mot", "", listOf(
            Sense("verb", "(Sens figuré) Une définition."),
            Sense("verb", "Une autre définition."),
        ), emptyList(), "fr", listOf(source))
        val bundled = base.copy(senses = listOf(
            Sense("verb", "Une définition.", listOf(source.id), listOf("Un exemple figuré.")),
            Sense("verb", "Un sens différent.", listOf(source.id), listOf("Ne pas associer cet exemple.")),
        ))
        val enriched = base.withUsageExamples(bundled)
        assertEquals(base.senses.map { it.definition }, enriched.senses.map { it.definition })
        assertEquals(listOf("Un exemple figuré."), enriched.senses.first().examples)
        assertTrue(enriched.senses[1].examples.isEmpty())
        assertEquals(base, base.withUsageExamples(bundled.copy(id = "fr:autre", word = "autre")))
        val ambiguous = bundled.copy(senses = bundled.senses + bundled.senses.first().copy(definition = "(Rare) Une définition."))
        assertEquals(base, base.withUsageExamples(ambiguous))
    }
}
