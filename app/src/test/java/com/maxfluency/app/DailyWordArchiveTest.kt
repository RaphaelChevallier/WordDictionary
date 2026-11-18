package com.maxfluency.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.maxfluency.app.data.LexiconRepository
import com.maxfluency.app.data.PersonalStore
import com.maxfluency.app.data.VocabularyLevel
import com.maxfluency.core.Language
import com.maxfluency.core.Sense
import com.maxfluency.core.WordEntry
import com.maxfluency.core.dictionaryJson
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class DailyWordArchiveTest {
    private lateinit var context: Context
    private lateinit var store: PersonalStore
    private lateinit var lexicon: LexiconRepository
    private val level = VocabularyLevel.INTERMEDIATE

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("personal.sqlite")
        store = PersonalStore(context)
        lexicon = LexiconRepository(context)
    }

    @After
    fun tearDown() = runBlocking {
        lexicon.close()
        store.close()
        context.deleteDatabase("personal.sqlite")
        Unit
    }

    @Test
    fun actualWordsAndFullSnapshotsOutliveGardenPruningAndRecentHistory() = runBlocking {
        val date = LocalDate.now().minusDays(40)
        val old = word("past")
        store.saveDaily("$date:en:INTERMEDIATE", listOf(old, word("grid")), date.minusDays(7).toString(), old)
        store.recordViewed(old)
        val today = LocalDate.now()
        store.saveDaily("$today:en:INTERMEDIATE", listOf(word("todayword"), word("todaygrid")), today.minusDays(7).toString())
        store.writableDatabase.delete("words", null, null)
        assertNull(store.daily("$date:en:INTERMEDIATE"))
        assertTrue(store.words().isEmpty())
        store.close()
        store = PersonalStore(context)
        val saved = requireNotNull(store.archivedWord(date, Language.ENGLISH, level))
        assertEquals(old, saved.entry)
        assertFalse(saved.backfilled)
        val month = lexicon.calendarMonth(YearMonth.from(date), Language.ENGLISH, level)
        assertEquals(saved, month.single { it.date == date })
    }

    @Test
    fun fillingPastMonthsIsStableAndDoesNotMutateTodayOrLearning() = runBlocking {
        val today = LocalDate.now()
        val daily = lexicon.daily(Language.ENGLISH, today, level)
        val prepared = (daily.grid + daily.featured).associate { it.id to requireNotNull(lexicon.preparedEntry(it.id)) }
        val familiarity = store.familiarity(prepared.keys.toList())
        val month = YearMonth.from(today).minusMonths(1)
        val words = lexicon.calendarMonth(month, Language.ENGLISH, level)
        assertEquals(month.lengthOfMonth(), words.size)
        assertTrue(words.all { it.backfilled && it.entry.language == Language.ENGLISH })
        assertEquals(familiarity, store.familiarity(prepared.keys.toList()))
        prepared.forEach { (id, value) -> assertEquals(value, lexicon.preparedEntry(id)) }
        assertEquals(daily.featured.id, store.daily("$today:en:INTERMEDIATE")?.entries?.first()?.id)
        store.answer(words.first().entry.id, true)
        lexicon.close()
        lexicon = LexiconRepository(context)
        assertEquals(words, lexicon.calendarMonth(month, Language.ENGLISH, level))
        assertEquals(daily, lexicon.daily(Language.ENGLISH, today, level))
    }

    @Test
    fun futureDaysAreUnavailableAndLanguageAndLevelArchivesAreIndependent() = runBlocking {
        val today = LocalDate.now()
        val month = YearMonth.from(today)
        val english = lexicon.calendarMonth(month, Language.ENGLISH, level)
        assertEquals(today.dayOfMonth, english.size)
        assertTrue(english.all { it.date <= today })
        assertThrows(IOException::class.java) {
            runBlocking { lexicon.calendarMonth(month.plusMonths(1), Language.ENGLISH, level) }
        }
        val french = lexicon.calendarMonth(month, Language.FRENCH, VocabularyLevel.BEGINNER)
        assertTrue(french.all { it.entry.language == Language.FRENCH && it.level == VocabularyLevel.BEGINNER })
        assertEquals(english, store.archivedMonth(month, Language.ENGLISH, level))
        assertNull(store.archivedWord(today, Language.ENGLISH, VocabularyLevel.ADVANCED))
    }

    @Test
    fun anAssignedWordStaysTheDailyWordWhenItsLearningContextIsOpenedLater() = runBlocking {
        val today = LocalDate.now()
        val language = Language.FRENCH
        val assigned = lexicon.calendarMonth(YearMonth.from(today), language, level).single { it.date == today }
        store.answer(assigned.entry.id, true)
        val daily = lexicon.daily(language, today, level)
        assertEquals(assigned.entry.id, daily.featured.id)
        assertEquals(20, daily.grid.size)
        assertTrue(daily.grid.none { it.id == daily.featured.id })
        val recorded = requireNotNull(store.archivedWord(today, language, level))
        assertEquals(assigned.entry, recorded.entry)
        assertFalse(recorded.backfilled)
    }

    @Test
    fun versionThreeMigrationPreservesSelectionsFavouritesProgressAndExamples() = runBlocking {
        lexicon.close()
        store.close()
        context.deleteDatabase("personal.sqlite")
        val date = LocalDate.now().minusDays(3)
        val entry = word("legacy")
        val grid = word("legacygrid")
        val key = "$date:en:INTERMEDIATE"
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("personal.sqlite"), null).use { db ->
            db.execSQL("CREATE TABLE words(id TEXT PRIMARY KEY,snapshot TEXT NOT NULL,favorite INTEGER NOT NULL,last_viewed INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE learning(id TEXT PRIMARY KEY,views INTEGER NOT NULL,correct INTEGER NOT NULL)")
            db.execSQL("""CREATE TABLE daily_selection(id TEXT PRIMARY KEY,payload TEXT NOT NULL,
                garden_round INTEGER NOT NULL,garden_explored TEXT NOT NULL,garden_shown TEXT NOT NULL)""")
            db.execSQL("INSERT INTO words VALUES(?,?,1,123)", arrayOf(entry.id, dictionaryJson.encodeToString(entry)))
            db.execSQL("INSERT INTO learning VALUES(?,7,2)", arrayOf(entry.id))
            db.execSQL("INSERT INTO daily_selection VALUES(?,?,2,?,?)", arrayOf(key,
                dictionaryJson.encodeToString(listOf(entry, grid)),
                dictionaryJson.encodeToString(setOf(grid.id)), dictionaryJson.encodeToString(setOf(grid.id))))
            db.version = 3
        }
        store = PersonalStore(context)
        lexicon = LexiconRepository(context)
        assertEquals(entry, store.archivedWord(date, Language.ENGLISH, level)?.entry)
        assertEquals(4, store.readableDatabase.version)
        assertTrue(store.words().single().favorite)
        assertEquals(7 to 2, store.familiarity(listOf(entry.id))[entry.id])
        val selection = requireNotNull(store.daily(key))
        assertEquals(2, selection.round)
        assertEquals(setOf(grid.id), selection.explored)
        assertEquals(listOf(entry, grid), selection.entries)
    }

    private fun word(text: String) = WordEntry("en:$text", Language.ENGLISH, text, "",
        listOf(Sense("noun", "A complete retained meaning.", examples = listOf("A retained example."))),
        emptyList(), "en", pronunciationGuide = "REH-taynd")
}
