package com.maxfluency.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoveryTest {
    @Test
    fun `search normalizes accents and ligatures without changing display text`() {
        assertEquals("coeur", normalizeSearch("  C\u0152UR "))
        assertEquals("cote", normalizeSearch("c\u00f4t\u00e9"))
        assertEquals("manana", normalizeSearch("ma\u00f1ana"))
        assertEquals("l'amour", normalizeSearch("L\u2019amour"))
        assertEquals("eet", anagramKey("\u00c9t\u00e9!"))
        assertNotEquals(lookupKey("cote"), lookupKey("c\u00f4t\u00e9"))
        assertEquals(lookupKey("caf\u00e9"), lookupKey("cafe\u0301"))
    }

    @Test
    fun `word search supports explicit wildcards but not SQL or glob syntax injection`() {
        assertEquals(SearchPlan.Prefix("l'amour"), searchPlan("L'amour", SearchMode.WORDS))
        assertEquals(SearchPlan.Wildcard("c?t*"), searchPlan("c.t*", SearchMode.WORDS))
        assertEquals(SearchPlan.Wildcard("[[]a[]]?"), searchPlan("[a]?", SearchMode.WORDS))
        assertEquals(SearchPlan.Anagram("aest"), searchPlan("Seat", SearchMode.ANAGRAMS))
        assertEquals(SearchPlan.NoLetters, searchPlan("123", SearchMode.ANAGRAMS))
        assertEquals(SearchPlan.Empty, searchPlan("  ", SearchMode.WORDS))
    }

    @Test
    fun `daily window is stable and has unique words across wraparound`() {
        val date = LocalDate.of(2026, 11, 11)
        val first = dailyIndices(date, Language.FRENCH, 23)
        assertEquals(first, dailyIndices(date, Language.FRENCH, 23))
        assertEquals(21, first.size)
        assertEquals(21, first.toSet().size)
        assertTrue(first.all { it in 0 until 23 })
        assertNotEquals(first, dailyIndices(date.plusDays(1), Language.FRENCH, 23))
        assertNotEquals(first, dailyIndices(date, Language.SPANISH, 23))
    }

    @Test
    fun `featured word cycles through the whole pool before repeating`() {
        val date = LocalDate.of(2026, 1, 1)
        for (pool in listOf(1, 7, 21, 42, 97)) {
            val featured = (0 until pool).map { day ->
                dailyIndices(date.plusDays(day.toLong()), Language.ENGLISH, pool).first()
            }
            assertEquals(pool, featured.toSet().size)
            assertEquals(minOf(21, pool), dailyIndices(date, Language.ENGLISH, pool).size)
        }
    }

    @Test
    fun `grid adapts to screen width and accessibility text scale`() {
        assertEquals(15, dailyGridSize(360f, 1f))
        assertEquals(18, dailyGridSize(430f, 1f))
        assertEquals(20, dailyGridSize(800f, 1f))
        assertEquals(15, dailyGridSize(600f, 2f))
        assertEquals(8, dailyGridSize(800f, 1f, 180f))
        assertEquals(12, dailyGridSize(350f, 1f, 350f))
        assertEquals(12, dailyGridSize(350f, 1.3f, 532f))
        assertEquals(20, dailyGridSize(1600f, 1f, 900f))
    }

    @Test
    fun `daily previews hide grammar senses and basic words but preserve full lookup entries`() {
        val policy = DiscoveryPolicy(setOf("adv", "article", "det"), mapOf("en" to listOf("the")))
        val mixed = WordEntry("en:fast", Language.ENGLISH, "fast", "",
            listOf(Sense("adv", "At high speed."), Sense("noun", "A period without food.")), emptyList(), "en")
        val preview = discoveryPreview(mixed, policy)!!
        assertEquals(listOf("noun"), preview.senses.map { it.partOfSpeech })
        assertEquals(listOf("adv", "noun"), mixed.senses.map { it.partOfSpeech })
        assertNull(discoveryPreview(mixed.copy(word = "THE"), policy))
        assertNull(discoveryPreview(mixed.copy(senses = listOf(Sense("adv", "At high speed."))), policy))
    }
}
