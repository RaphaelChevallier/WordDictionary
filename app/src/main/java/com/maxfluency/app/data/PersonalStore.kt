package com.maxfluency.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.maxfluency.core.DailyWords
import com.maxfluency.core.Language
import com.maxfluency.core.SavedWord
import com.maxfluency.core.WordEntry
import com.maxfluency.core.dictionaryJson
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

data class DailySelection(
    val entries: List<WordEntry>,
    val round: Int = 0,
    val explored: Set<String> = emptySet(),
    val shown: Set<String> = emptySet(),
) {
    fun words(date: LocalDate) = DailyWords(date, entries.first(), entries.drop(1), explored, round)
}

class PersonalStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "personal.sqlite", null, 4) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE words (
                id TEXT PRIMARY KEY NOT NULL,
                snapshot TEXT NOT NULL,
                favorite INTEGER NOT NULL DEFAULT 0,
                last_viewed INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_recent ON words(last_viewed DESC)")
        db.execSQL("CREATE TABLE learning(id TEXT PRIMARY KEY,views INTEGER NOT NULL DEFAULT 0,correct INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("""CREATE TABLE daily_selection(id TEXT PRIMARY KEY,payload TEXT NOT NULL,
            garden_round INTEGER NOT NULL DEFAULT 0,garden_explored TEXT NOT NULL DEFAULT '[]',
            garden_shown TEXT NOT NULL DEFAULT '[]')""")
        createArchive(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (newVersion != 4 || oldVersion !in 1..3) {
            throw SQLiteException("A non-destructive personal-data migration is required: $oldVersion to $newVersion")
        }
        if (oldVersion == 1) {
            db.execSQL("CREATE TABLE learning(id TEXT PRIMARY KEY,views INTEGER NOT NULL DEFAULT 0,correct INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("INSERT INTO learning(id,views) SELECT id,1 FROM words")
            db.execSQL("CREATE TABLE daily_selection(id TEXT PRIMARY KEY,payload TEXT NOT NULL)")
        }
        if (oldVersion <= 2) {
            db.execSQL("ALTER TABLE daily_selection ADD COLUMN garden_round INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE daily_selection ADD COLUMN garden_explored TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE daily_selection ADD COLUMN garden_shown TEXT NOT NULL DEFAULT '[]'")
        }
        createArchive(db)
        db.rawQuery("SELECT id FROM daily_selection ORDER BY id", null).use {
            while (it.moveToNext()) {
                val key = it.getString(0)
                val (date, language, level) = dailyContext(key)
                val entry = readDaily(db, key)?.entries?.first() ?: throw IOException("Stored daily words are missing")
                if (entry.language != language) throw IOException("Stored daily word language does not match its date")
                writeArchive(db, ArchivedDailyWord(date, level, entry))
            }
        }
    }

    private fun createArchive(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE daily_word_archive(
            day TEXT NOT NULL,language TEXT NOT NULL,level TEXT NOT NULL,snapshot TEXT NOT NULL,
            backfilled INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(day,language,level)) WITHOUT ROWID""")
    }

    private fun archiveDate(value: String): LocalDate = try {
        LocalDate.parse(value)
    } catch (error: DateTimeParseException) {
        throw IOException("Stored daily word date is invalid", error)
    }

    private fun dailyContext(key: String): Triple<LocalDate, Language, VocabularyLevel> {
        val parts = key.split(':')
        if (parts.size != 3) throw IOException("Stored daily word key is invalid")
        return Triple(archiveDate(parts[0]),
            Language.fromCode(parts[1]) ?: throw IOException("Stored daily word language is invalid"),
            VocabularyLevel.entries.find { it.name == parts[2] } ?: throw IOException("Stored daily word level is invalid"))
    }

    private fun readArchive(db: SQLiteDatabase, date: LocalDate, language: Language, level: VocabularyLevel): ArchivedDailyWord? =
        db.rawQuery("SELECT snapshot,backfilled FROM daily_word_archive WHERE day=? AND language=? AND level=?",
            arrayOf(date.toString(), language.code, level.name)).use {
            if (!it.moveToFirst()) null else archivedWord(date, language, level, it.getString(0), it.getInt(1))
        }

    private fun archivedWord(date: LocalDate, language: Language, level: VocabularyLevel,
        snapshot: String, backfilled: Int): ArchivedDailyWord {
        val entry = dictionaryJson.decodeFromString<WordEntry>(snapshot)
        if (entry.language != language || entry.id != "${language.code}:${entry.word}" ||
            entry.word.isBlank() || entry.senses.isEmpty() || backfilled !in 0..1) {
            throw IOException("Stored daily word snapshot is invalid")
        }
        return ArchivedDailyWord(date, level, entry, backfilled == 1)
    }

    private fun writeArchive(db: SQLiteDatabase, word: ArchivedDailyWord) {
        if (word.entry.word.isBlank() || word.entry.senses.isEmpty() ||
            word.entry.id != "${word.entry.language.code}:${word.entry.word}") {
            throw IOException("A daily word archive needs a complete matching snapshot")
        }
        val values = ContentValues().apply {
            put("day", word.date.toString())
            put("language", word.entry.language.code)
            put("level", word.level.name)
            put("snapshot", dictionaryJson.encodeToString(word.entry))
            put("backfilled", if (word.backfilled) 1 else 0)
        }
        if (db.insertWithOnConflict("daily_word_archive", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
            val stored = readArchive(db, word.date, word.entry.language, word.level)
                ?: throw IOException("Daily word archive write failed")
            if (!word.backfilled && stored.entry.id != word.entry.id) throw IOException("A saved daily word cannot be replaced")
            if (!word.backfilled && stored.backfilled) db.execSQL(
                "UPDATE daily_word_archive SET backfilled=0 WHERE day=? AND language=? AND level=?",
                arrayOf(word.date.toString(), word.entry.language.code, word.level.name))
        }
    }

    suspend fun archivedWord(date: LocalDate, language: Language, level: VocabularyLevel): ArchivedDailyWord? =
        withContext(Dispatchers.IO) { readArchive(readableDatabase, date, language, level) }

    suspend fun archivedMonth(month: YearMonth, language: Language, level: VocabularyLevel): List<ArchivedDailyWord> =
        withContext(Dispatchers.IO) {
            readableDatabase.rawQuery("""SELECT day,snapshot,backfilled FROM daily_word_archive
                WHERE day>=? AND day<=? AND language=? AND level=? ORDER BY day""",
                arrayOf(month.atDay(1).toString(), month.atEndOfMonth().toString(), language.code, level.name)).use {
                buildList {
                    while (it.moveToNext()) add(archivedWord(archiveDate(it.getString(0)), language, level,
                        it.getString(1), it.getInt(2)))
                }
            }
        }

    suspend fun saveArchivedWords(words: List<ArchivedDailyWord>) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            words.forEach { writeArchive(db, it) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    suspend fun words(): List<SavedWord> = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT snapshot, favorite, last_viewed FROM words ORDER BY last_viewed DESC", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(SavedWord(
                    dictionaryJson.decodeFromString<WordEntry>(cursor.getString(0)),
                    cursor.getInt(1) == 1,
                    cursor.getLong(2),
                ))
            }
        }
    }

    suspend fun snapshot(id: String): WordEntry? = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT snapshot FROM words WHERE id = ?", arrayOf(id)).use {
            if (it.moveToFirst()) dictionaryJson.decodeFromString<WordEntry>(it.getString(0)) else null
        }
    }

    suspend fun recordViewed(entry: WordEntry) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("id", entry.id)
                put("snapshot", dictionaryJson.encodeToString(entry))
                put("last_viewed", System.currentTimeMillis())
            }
            if (db.update("words", values, "id = ?", arrayOf(entry.id)) == 0) db.insertOrThrow("words", null, values)
            db.execSQL("INSERT OR IGNORE INTO learning(id) VALUES (?)", arrayOf(entry.id))
            db.execSQL("UPDATE learning SET views=views+1 WHERE id=?", arrayOf(entry.id))
            db.execSQL("""
                DELETE FROM words WHERE favorite = 0 AND id NOT IN
                    (SELECT id FROM words ORDER BY last_viewed DESC LIMIT 100)
            """.trimIndent())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun setFavorite(entry: WordEntry, favorite: Boolean) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("id", entry.id)
            put("snapshot", dictionaryJson.encodeToString(entry))
            put("favorite", if (favorite) 1 else 0)
        }
        if (db.update("words", values, "id = ?", arrayOf(entry.id)) == 0) {
            values.put("last_viewed", System.currentTimeMillis())
            db.insertOrThrow("words", null, values)
        }
    }

    suspend fun familiarity(ids: List<String>): Map<String, Pair<Int, Int>> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, Pair<Int, Int>>()
        ids.chunked(400).forEach { group ->
            if (group.isEmpty()) return@forEach
            readableDatabase.rawQuery("SELECT id,views,correct FROM learning WHERE id IN (${group.joinToString(",") { "?" }})",
                group.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) result[cursor.getString(0)] = cursor.getInt(1) to cursor.getInt(2)
            }
        }
        result
    }

    suspend fun answer(id: String, correct: Boolean) = withContext(Dispatchers.IO) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO learning(id) VALUES (?)", arrayOf(id))
        writableDatabase.execSQL("UPDATE learning SET views=views+1,correct=correct+? WHERE id=?",
            arrayOf<Any>(if (correct) 1 else 0, id))
    }

    suspend fun protectedIds(date: String): Set<String> = withContext(Dispatchers.IO) {
        buildSet {
            readableDatabase.rawQuery("SELECT id FROM words WHERE favorite=1", null).use {
                while (it.moveToNext()) add(it.getString(0))
            }
            readableDatabase.rawQuery("SELECT payload FROM daily_selection WHERE id>=? AND id<?",
                arrayOf(date, date + "\uFFFF")).use {
                while (it.moveToNext()) addAll(dictionaryJson.decodeFromString<List<WordEntry>>(it.getString(0)).map { entry -> entry.id })
            }
        }
    }

    suspend fun daily(key: String): DailySelection? = withContext(Dispatchers.IO) { readDaily(readableDatabase, key) }

    private fun readDaily(db: SQLiteDatabase, key: String): DailySelection? =
        db.rawQuery("SELECT payload,garden_round,garden_explored,garden_shown FROM daily_selection WHERE id=?", arrayOf(key)).use {
            if (!it.moveToFirst()) return@use null
            val selection = DailySelection(
                dictionaryJson.decodeFromString<List<WordEntry>>(it.getString(0)), it.getInt(1),
                dictionaryJson.decodeFromString<Set<String>>(it.getString(2)),
                dictionaryJson.decodeFromString<Set<String>>(it.getString(3)),
            )
            if (selection.entries.size !in 2..21 || selection.round < 0 ||
                selection.entries.map { entry -> entry.id }.distinct().size != selection.entries.size ||
                !selection.entries.drop(1).map { entry -> entry.id }.containsAll(selection.explored)) {
                throw IOException("Stored daily discovery is invalid")
            }
            selection
        }

    suspend fun exploreDaily(key: String, id: String, round: Int): DailySelection = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val current = readDaily(db, key) ?: throw IOException("The garden selection is missing")
            val result = if (current.round != round || current.entries.drop(1).none { it.id == id }) {
                Log.i("PersonalStore", "A word from an older garden was opened; current progress was preserved")
                current
            } else {
                val next = current.copy(explored = current.explored + id)
                val values = ContentValues().apply { put("garden_explored", dictionaryJson.encodeToString(next.explored)) }
                if (db.update("daily_selection", values, "id=?", arrayOf(key)) != 1) throw IOException("Garden progress was not saved")
                next
            }
            db.setTransactionSuccessful()
            result
        } finally { db.endTransaction() }
    }

    suspend fun replaceGarden(key: String, previous: DailySelection, entries: List<WordEntry>,
        shown: Set<String>): DailySelection = withContext(Dispatchers.IO) {
        if (previous.round == Int.MAX_VALUE) throw IOException("The garden round counter is exhausted")
        val next = DailySelection(entries, previous.round + 1, emptySet(), shown)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("payload", dictionaryJson.encodeToString(entries))
                put("garden_round", next.round)
                put("garden_explored", "[]")
                put("garden_shown", dictionaryJson.encodeToString(shown))
            }
            if (db.update("daily_selection", values, "id=? AND garden_round=?",
                    arrayOf(key, previous.round.toString())) != 1) throw IOException("The garden changed before refresh completed")
            entries.drop(1).forEach {
                db.execSQL("INSERT OR IGNORE INTO learning(id) VALUES (?)", arrayOf(it.id))
                db.execSQL("UPDATE learning SET views=views+1 WHERE id=?", arrayOf(it.id))
            }
            db.setTransactionSuccessful()
            next
        } finally { db.endTransaction() }
    }

    suspend fun saveDaily(key: String, entries: List<WordEntry>, oldestDate: String,
        featuredSnapshot: WordEntry? = null) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply { put("id", key); put("payload", dictionaryJson.encodeToString(entries)) }
            val inserted = db.insertWithOnConflict("daily_selection", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            if (inserted != -1L) entries.forEach {
                db.execSQL("INSERT OR IGNORE INTO learning(id) VALUES (?)", arrayOf(it.id))
                db.execSQL("UPDATE learning SET views=views+1 WHERE id=?", arrayOf(it.id))
            }
            val (date, language, level) = dailyContext(key)
            val chosen = readDaily(db, key)?.entries?.first() ?: throw IOException("Daily words were not saved")
            val snapshot = if (inserted == -1L) chosen else featuredSnapshot ?: chosen
            if (snapshot.id != chosen.id || snapshot.language != language) throw IOException("Daily word snapshot does not match")
            writeArchive(db, ArchivedDailyWord(date, level, snapshot))
            db.delete("daily_selection", "id<?", arrayOf(oldestDate))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
