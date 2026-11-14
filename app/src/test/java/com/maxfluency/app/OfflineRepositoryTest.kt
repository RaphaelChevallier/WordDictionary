package com.maxfluency.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.maxfluency.app.data.LexiconRepository
import com.maxfluency.app.data.PersonalStore
import com.maxfluency.app.data.VocabularyLevel
import com.maxfluency.core.Language
import com.maxfluency.core.SearchPlan
import com.maxfluency.core.Sense
import com.maxfluency.core.WordEntry
import java.time.LocalDate
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdException
import com.maxfluency.core.BlockLexicon
import com.maxfluency.core.PackManifest
import com.maxfluency.core.dictionaryJson
import com.maxfluency.core.discoveryPreview
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class OfflineRepositoryTest {
    private lateinit var context: Context
    private lateinit var lexicon: LexiconRepository
    private lateinit var personal: PersonalStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("personal.sqlite")
        lexicon = LexiconRepository(context)
        personal = PersonalStore(context)
    }

    @After
    fun tearDown() = runBlocking {
        lexicon.close()
        personal.close()
        context.deleteDatabase("personal.sqlite")
        Unit
    }

    @Test
    fun offlineLookupsAndDailyWords() = runBlocking {
        val date = LocalDate.of(2026, 11, 11)
        val policy = lexicon.manifest().discovery
        for (language in Language.entries) {
            val first = lexicon.daily(language, date)
            assertEquals(20, first.grid.size)
            assertEquals(21, (first.grid.map { it.id } + first.featured.id).toSet().size)
            assertEquals(first, lexicon.daily(language, date))
            assertNotEquals(first.featured.id, lexicon.daily(language, date.plusDays(1)).featured.id)
            assertTrue(first.featured.senses.isNotEmpty())
            assertTrue((first.grid + first.featured).all { discoveryPreview(it, policy) == it })
            assertEquals(6, lexicon.assessment(language).size)
        }
        assertTrue(lexicon.search(Language.FRENCH, SearchPlan.Prefix("ecole")).any { it.word == "école" })
        assertTrue(lexicon.search(Language.SPANISH, SearchPlan.Prefix("manana")).any { it.word == "mañana" })
        assertTrue(lexicon.search(Language.ENGLISH, SearchPlan.Prefix("zz' OR 1=1 --")).isEmpty())
        assertTrue(lexicon.search(Language.ENGLISH, SearchPlan.Wildcard("c?t")).any { it.word == "cat" })
        assertTrue(lexicon.search(Language.ENGLISH, SearchPlan.Anagram("act")).any { it.word == "cat" })
        val cat = lexicon.entry("en:cat")!!
        assertTrue(cat.sourceLinks.any { it.second.contains("en.wiktionary.org") })
        assertTrue(cat.sourceLinks.any { it.second.contains("simple.wiktionary.org") })
        assertTrue(lexicon.entry("en:the")!!.senses.any { it.partOfSpeech == "article" })
        assertTrue(lexicon.entry("en:quickly")!!.senses.any { it.partOfSpeech == "adv" })
        assertTrue(lexicon.entry("fr:le")!!.senses.any { it.partOfSpeech == "article" })
        assertTrue(lexicon.entry("fr:la")!!.senses.any { it.partOfSpeech == "noun" })
    }

    @Test
    fun savedWordsSurvivePackReplacement() = runBlocking {
        val retired = WordEntry(
            id = "fr:__maxfluency_retired_fixture__",
            language = Language.FRENCH,
            word = "ancien mot",
            pronunciation = "",
            senses = listOf(Sense("noun", "Une définition conservée dans la collection personnelle.")),
            related = emptyList(),
            sourceEdition = "fr",
        )
        personal.recordViewed(retired)
        personal.setFavorite(retired, true)
        val oldCache = File(context.cacheDir, "lexicons/en-${"0".repeat(64)}.sqlite")
        oldCache.parentFile!!.mkdirs()
        oldCache.writeText("obsolete dictionary cache")
        lexicon.daily(Language.ENGLISH, LocalDate.of(2026, 11, 11))
        assertFalse(oldCache.exists())
        assertTrue(oldCache.parentFile!!.listFiles().orEmpty().none { it.extension == "sqlite" })
        lexicon.resetCache()
        lexicon.daily(Language.SPANISH, LocalDate.of(2026, 11, 11))
        assertNull(lexicon.entry(retired.id))
        personal.close()
        personal = PersonalStore(context)
        assertEquals(retired, personal.snapshot(retired.id))
        assertTrue(personal.words().single().favorite)
    }

    @Test
    fun gardenRoundsPersist() = runBlocking {
        val language = Language.ENGLISH
        val level = VocabularyLevel.INTERMEDIATE
        val date = LocalDate.of(2026, 11, 13)
        val first = lexicon.daily(language, date, level)
        val visible = first.grid.take(15).map { it.id }.toSet()
        assertThrows(IOException::class.java) {
            runBlocking { lexicon.refreshGarden(language, date, level, first.round, emptySet()) }
        }
        assertThrows(IOException::class.java) {
            runBlocking { lexicon.refreshGarden(language, date, level, first.round, setOf("en:not-in-this-garden")) }
        }
        first.grid.take(14).forEach { lexicon.exploreGarden(language, date, level, first.round, it.id) }
        personal.recordViewed(first.grid.first())
        personal.setFavorite(first.grid.first(), true)
        lexicon.close()
        lexicon = LexiconRepository(context)
        val resumed = lexicon.daily(language, date, level)
        assertEquals(14, resumed.explored.size)
        assertEquals(first.featured, resumed.featured)
        assertTrue(lexicon.daily(Language.FRENCH, date, level).explored.isEmpty())
        assertTrue(lexicon.daily(language, date.plusDays(1), level).explored.isEmpty())
        val fresh = requireNotNull(lexicon.refreshGarden(language, date, level, first.round, visible))
        assertEquals(1, fresh.round)
        assertEquals(first.featured, fresh.featured)
        assertTrue(fresh.explored.isEmpty())
        assertTrue(fresh.grid.none { it.id in visible })
        assertEquals(fresh, lexicon.daily(language, date, level))
        assertEquals(fresh, lexicon.refreshGarden(language, date, level, first.round, visible))
        assertEquals(fresh, lexicon.exploreGarden(language, date, level, first.round, visible.first()))
        assertTrue(personal.words().single().favorite)
        val nextVisible = fresh.grid.take(15).map { it.id }.toSet()
        assertTrue("A new set must be available without opening any current words", fresh.explored.isEmpty())
        val second = requireNotNull(lexicon.refreshGarden(language, date, level, fresh.round, nextVisible))
        assertEquals(first.featured, second.featured)
        assertTrue(second.grid.none { it.id in visible || it.id in nextVisible })
        assertEquals(2, second.round)
    }

    @Test
    fun versionTwoDailyDataMigrates() = runBlocking {
        val entry = WordEntry("en:steady", Language.ENGLISH, "steady", "",
            listOf(Sense("noun", "A preserved definition.")), emptyList(), "en")
        val grid = entry.copy(id = "en:fresh", word = "fresh")
        val file = context.getDatabasePath("personal.sqlite")
        file.parentFile!!.mkdirs()
        val key = "2026-11-13:en:INTERMEDIATE"
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE words(id TEXT PRIMARY KEY NOT NULL,snapshot TEXT NOT NULL,favorite INTEGER NOT NULL DEFAULT 0,last_viewed INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE learning(id TEXT PRIMARY KEY,views INTEGER NOT NULL DEFAULT 0,correct INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE daily_selection(id TEXT PRIMARY KEY,payload TEXT NOT NULL)")
            db.execSQL("INSERT INTO words VALUES(?,?,1,123)", arrayOf(entry.id, dictionaryJson.encodeToString(entry)))
            db.execSQL("INSERT INTO learning VALUES(?,5,2)", arrayOf(entry.id))
            db.execSQL("INSERT INTO daily_selection VALUES(?,?)", arrayOf(key, dictionaryJson.encodeToString(listOf(entry, grid))))
            db.version = 2
        }
        val migrated = requireNotNull(personal.daily(key))
        assertEquals(3, personal.readableDatabase.version)
        assertEquals(listOf(entry, grid), migrated.entries)
        assertEquals(0, migrated.round)
        assertTrue(migrated.explored.isEmpty())
        assertTrue(personal.words().single().favorite)
        assertEquals(5 to 2, personal.familiarity(listOf(entry.id))[entry.id])
        assertEquals(setOf(grid.id), personal.exploreDaily(key, grid.id, 0).explored)
    }

    @Test
    fun exhaustedGardenStaysIntact() = runBlocking {
        context.deleteDatabase("online-words.sqlite")
        val date = LocalDate.of(2026, 11, 13)
        val level = VocabularyLevel.INTERMEDIATE
        val daily = lexicon.daily(Language.ENGLISH, date, level)
        val pack = lexicon.manifest().languages.single { it.language == "en" }
        val dictionary = reader(context.assets.open("lexicon/${pack.file}").use { it.readBytes() })
        val all = dictionary.daily.map { dictionary.entryAt(it).id }.toSet()
        val visible = daily.grid.take(15).map { it.id }.toSet()
        visible.forEach { lexicon.exploreGarden(Language.ENGLISH, date, level, 0, it) }
        personal.writableDatabase.execSQL("UPDATE daily_selection SET garden_shown=? WHERE id=?",
            arrayOf(dictionaryJson.encodeToString(all), "$date:en:INTERMEDIATE"))
        val before = lexicon.daily(Language.ENGLISH, date, level)
        assertNull(lexicon.refreshGarden(Language.ENGLISH, date, level, 0, visible))
        assertEquals(before, lexicon.daily(Language.ENGLISH, date, level))
    }

    private fun reader(bytes: ByteArray) = BlockLexicon(bytes.size.toLong(),
        { offset, count -> bytes.copyOfRange(offset.toInt(), offset.toInt() + count) },
        { bytes_, expected ->
            try { Zstd.decompress(bytes_, expected) }
            catch (error: ZstdException) { throw IOException("Corrupt compressed frame", error) }
        })

    @Test
    fun preparedGardenSurvivesSearchAndRestart() = runBlocking {
        val date = LocalDate.of(2026, 11, 13)
        val daily = lexicon.daily(Language.ENGLISH, date)
        val full = (daily.grid + daily.featured).associate { it.id to requireNotNull(lexicon.entry(it.id)) }
        for (prefix in listOf("a", "b", "c", "d", "e", "f", "g")) {
            lexicon.search(Language.ENGLISH, SearchPlan.Prefix(prefix))
        }
        full.forEach { (id, entry) -> assertEquals(entry, requireNotNull(lexicon.preparedEntry(id)).entry) }
        lexicon.close()
        lexicon = LexiconRepository(context)
        assertEquals(daily, lexicon.daily(Language.ENGLISH, date))
        full.forEach { (id, entry) -> assertEquals(entry, requireNotNull(lexicon.preparedEntry(id)).entry) }
    }

    @Test
    fun headwordsDoNotReadDefinitionBlocks() {
        val manifest = context.assets.open("lexicon/manifest.json").bufferedReader().use {
            dictionaryJson.decodeFromString<PackManifest>(it.readText())
        }
        val pack = manifest.languages.first()
        val bytes = context.assets.open("lexicon/${pack.file}").use { it.readBytes() }
        val dataOffset = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(40)
        var definitionReads = 0
        val dictionary = BlockLexicon(bytes.size.toLong(), { offset, count ->
            if (offset >= dataOffset) definitionReads++
            bytes.copyOfRange(offset.toInt(), offset.toInt() + count)
        }, { compressed, expected -> Zstd.decompress(compressed, expected) })
        val words = dictionary.daily.map { dictionary.wordAt(it) }
        assertTrue(words.size > 1000)
        assertTrue(words.all { it.isNotBlank() })
        assertEquals("Daily selection must use the word index, not decode every definition", 0, definitionReads)
        assertEquals(words.first(), dictionary.entryAt(dictionary.daily.first()).word)
        assertTrue(definitionReads > 0)
    }

    @Test
    fun packedReaderRejectsInvalidSizesAndCorruptFrames() {
        val manifest = context.assets.open("lexicon/manifest.json").bufferedReader().use {
            dictionaryJson.decodeFromString<PackManifest>(it.readText())
        }
        val pack = manifest.languages.last()
        val original = context.assets.open("lexicon/${pack.file}").use { it.readBytes() }
        val header = original.copyOf()
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(28, Int.MAX_VALUE)
        assertThrows(IOException::class.java) { reader(header) }
        assertThrows(IOException::class.java) { reader(original.copyOf(original.size - 1)) }
        val frame = original.copyOf()
        frame[frame.lastIndex] = (frame.last().toInt() xor 0xff).toByte()
        assertThrows(IOException::class.java) { reader(frame).entryAt(pack.entries - 1) }
    }
}
