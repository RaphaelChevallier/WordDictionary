package com.maxfluency.core

import org.junit.Assert.*
import org.junit.Test

class DictionaryTextTest {
    @Test
    fun `clean glosses remove numeric references but keep uncertainty warnings and readable links`() {
        assertEquals("Une définition.", DictionaryText.definition("Une définition ^([2])."))
        assertEquals("Un sens.", DictionaryText.definition("Un sens ^([note 1])."))
        assertEquals("Un pez. (definición imprecisa)", DictionaryText.definition("Un pez. ^([definición imprecisa])"))
        assertEquals("Una acepción. (cita requerida)", DictionaryText.definition("Una acepción.^([cita requerida])"))
        assertEquals("Bigger than normal.", DictionaryText.definition("Bigger than [[normal]."))
        assertEquals("A display and link.", DictionaryText.definition("A [[target|display]] and [[link]]."))
        assertEquals("", DictionaryText.definition("A mouth that is [[|bit]]."))
        assertEquals("", DictionaryText.definition("."))
        assertEquals("(colloquial) ...because it is known.", DictionaryText.definition("(colloquial) ...because it is known."))
    }

    @Test
    fun `plain examples reject embedded citations and raw source templates but retain useful phrases`() {
        assertEquals(listOf("This is a full example.", "Another full example is retained.", "A third example stays."),
            DictionaryText.examples(listOf(
                "a useful phrase", "2014, An Author, A Published Work. A passage.",
                "July 18, 2012, An Author, A passage.", "A passage. https://example.org/source",
                "{{exemple|A broken template", "This is a full example.", " This is a full example. ",
                "Another full example is retained.", "A third example stays.",
            )))
        assertEquals(listOf("¡Qué calor!", "abandoned building"),
            DictionaryText.examples(listOf("¡Qué calor!", "abandoned building")))
    }

    @Test
    fun `cited nested spans cannot bypass their parent citation and examples stay attached to the leaf meaning`() {
        val entry = requireNotNull(DictionaryHtml.wiki(Language.FRENCH, "tester", """
            <h2>Français</h2><h3>Verbe</h3><ol><li>Un sens.<ul><li>
              <span class="h-usage-example"><span class="e-example">Une citation publiée.</span>
                <span class="sources">Un ouvrage.</span></span>
            </li></ul><ol><li>Un sens précis.<ul><li><span class="example">Un exemple précis.</span></li></ul>
            </li></ol></li></ol>
        """))
        assertEquals(2, entry.senses.size)
        assertTrue(entry.senses.first().examples.isEmpty())
        assertEquals(listOf("Un exemple précis."), entry.senses[1].examples)
    }
}
