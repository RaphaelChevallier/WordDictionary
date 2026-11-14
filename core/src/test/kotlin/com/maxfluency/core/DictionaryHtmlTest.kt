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
}
