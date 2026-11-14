package com.maxfluency.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.maxfluency.core.*
import kotlinx.serialization.encodeToString

class OnlineWordCache(context: Context, private val maximumWords: Int = 500_000) :
    SQLiteOpenHelper(context.applicationContext, "online-words.sqlite", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE cache(id TEXT PRIMARY KEY,payload TEXT NOT NULL,language TEXT NOT NULL,
            word TEXT NOT NULL,norm TEXT NOT NULL,anagram TEXT NOT NULL,level INTEGER NOT NULL,
            views INTEGER NOT NULL DEFAULT 0,correct INTEGER NOT NULL DEFAULT 0,last_access INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX idx_words ON cache(language,norm,word)")
        db.execSQL("CREATE INDEX idx_anagram ON cache(language,anagram)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw android.database.sqlite.SQLiteException("A non-destructive word-cache migration is required")
    }

    @Synchronized fun get(id: String): WordEntry? = readableDatabase.rawQuery("SELECT payload FROM cache WHERE id=?", arrayOf(id)).use {
        if (it.moveToFirst()) dictionaryJson.decodeFromString<WordEntry>(it.getString(0)) else null
    }

    @Synchronized fun put(entry: WordEntry, level: VocabularyLevel, protectedIds: Set<String>): Boolean {
        require(maximumWords > 0)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = db.rawQuery("SELECT 1 FROM cache WHERE id=?", arrayOf(entry.id)).use { it.moveToFirst() }
            if (!existing) {
                val count = db.rawQuery("SELECT count(*) FROM cache", null).use { it.moveToFirst(); it.getInt(0) }
                if (count >= maximumWords) {
                    db.execSQL("CREATE TEMP TABLE IF NOT EXISTS protected(id TEXT PRIMARY KEY)")
                    db.delete("protected", null, null)
                    val insert = db.compileStatement("INSERT INTO protected VALUES (?)")
                    try { protectedIds.forEach { insert.bindString(1, it); insert.executeInsert() } } finally { insert.close() }
                    db.execSQL("""DELETE FROM cache WHERE id IN (SELECT id FROM cache WHERE id NOT IN (SELECT id FROM protected)
                        ORDER BY correct DESC,views DESC,last_access ASC LIMIT ?)""", arrayOf(count - maximumWords + 1))
                    val after = db.rawQuery("SELECT count(*) FROM cache", null).use { it.moveToFirst(); it.getInt(0) }
                    if (after >= maximumWords) { db.setTransactionSuccessful(); return false }
                }
            }
            val values = ContentValues().apply {
                put("payload", dictionaryJson.encodeToString(entry)); put("language", entry.language.code)
                put("word", entry.word); put("norm", normalizeSearch(entry.word)); put("anagram", anagramKey(entry.word))
                put("level", level.ordinal); put("last_access", System.currentTimeMillis())
            }
            if (db.update("cache", values, "id=?", arrayOf(entry.id)) == 0) {
                values.put("id", entry.id)
                db.insertOrThrow("cache", null, values)
            }
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    @Synchronized fun seen(id: String) {
        writableDatabase.execSQL("UPDATE cache SET views=views+1,last_access=? WHERE id=?", arrayOf<Any>(System.currentTimeMillis(), id))
    }

    @Synchronized fun seen(ids: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { seen(it) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun correct(id: String) { writableDatabase.execSQL("UPDATE cache SET correct=correct+1 WHERE id=?", arrayOf(id)) }

    @Synchronized fun search(language: Language, plan: SearchPlan): List<WordEntry> {
        val (where, args) = when (plan) {
            is SearchPlan.Prefix -> "norm>=? AND norm<?" to arrayOf(plan.value, "${plan.value}\uFFFF")
            is SearchPlan.Wildcard -> "norm GLOB ?" to arrayOf(plan.value)
            is SearchPlan.Anagram -> "anagram=?" to arrayOf(plan.value)
            else -> return emptyList()
        }
        return readableDatabase.rawQuery("SELECT payload FROM cache WHERE language=? AND $where ORDER BY norm,word LIMIT 60",
            arrayOf(language.code, *args)).use {
            buildList { while (it.moveToNext()) add(dictionaryJson.decodeFromString<WordEntry>(it.getString(0))) }
        }
    }

    @Synchronized fun candidateIds(language: Language, level: VocabularyLevel): List<String> =
        readableDatabase.rawQuery("SELECT id FROM cache WHERE language=? AND level=? ORDER BY correct,views,last_access DESC LIMIT 200",
            arrayOf(language.code, level.ordinal.toString())).use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }
}
