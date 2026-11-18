package com.maxfluency.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import android.text.Html

object AnkiDeckExporter {
    const val MODEL_ID = 1763400001001L
    const val DECK_ID = 1763400001002L

    suspend fun write(context: Context, notes: List<AnkiNote>, output: OutputStream) = withContext(Dispatchers.IO) {
        if (notes.isEmpty()) throw IOException("There are no notes to export")
        val directory = File(context.cacheDir, "anki-export-${UUID.randomUUID()}")
        if (!directory.mkdir()) throw IOException("Anki export workspace could not be created")
        val file = File(directory, "collection.anki2")
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.disableWriteAheadLogging()
                createCollection(db, notes)
                db.rawQuery("PRAGMA integrity_check", null).use {
                    if (!it.moveToFirst() || it.getString(0) != "ok") throw IOException("Anki export integrity check failed")
                }
            }
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("collection.anki2"))
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        zip.write(buffer, 0, read)
                    }
                }
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("media"))
                zip.write("{}".toByteArray())
                zip.closeEntry()
            }
        } finally {
            if (file.exists() && !SQLiteDatabase.deleteDatabase(file)) Log.w("AnkiExport", "Temporary deck database could not be removed")
            if (!directory.delete()) Log.w("AnkiExport", "Temporary deck directory could not be removed")
        }
    }

    private suspend fun createCollection(db: SQLiteDatabase, notes: List<AnkiNote>) {
        db.execSQL("""CREATE TABLE col(id INTEGER PRIMARY KEY,crt INTEGER NOT NULL,mod INTEGER NOT NULL,
            scm INTEGER NOT NULL,ver INTEGER NOT NULL,dty INTEGER NOT NULL,usn INTEGER NOT NULL,ls INTEGER NOT NULL,
            conf TEXT NOT NULL,models TEXT NOT NULL,decks TEXT NOT NULL,dconf TEXT NOT NULL,tags TEXT NOT NULL)""")
        db.execSQL("""CREATE TABLE notes(id INTEGER PRIMARY KEY,guid TEXT NOT NULL,mid INTEGER NOT NULL,
            mod INTEGER NOT NULL,usn INTEGER NOT NULL,tags TEXT NOT NULL,flds TEXT NOT NULL,sfld INTEGER NOT NULL,
            csum INTEGER NOT NULL,flags INTEGER NOT NULL,data TEXT NOT NULL)""")
        db.execSQL("""CREATE TABLE cards(id INTEGER PRIMARY KEY,nid INTEGER NOT NULL,did INTEGER NOT NULL,
            ord INTEGER NOT NULL,mod INTEGER NOT NULL,usn INTEGER NOT NULL,type INTEGER NOT NULL,queue INTEGER NOT NULL,
            due INTEGER NOT NULL,ivl INTEGER NOT NULL,factor INTEGER NOT NULL,reps INTEGER NOT NULL,
            lapses INTEGER NOT NULL,left INTEGER NOT NULL,odue INTEGER NOT NULL,odid INTEGER NOT NULL,
            flags INTEGER NOT NULL,data TEXT NOT NULL)""")
        db.execSQL("""CREATE TABLE revlog(id INTEGER PRIMARY KEY,cid INTEGER NOT NULL,usn INTEGER NOT NULL,
            ease INTEGER NOT NULL,ivl INTEGER NOT NULL,lastIvl INTEGER NOT NULL,factor INTEGER NOT NULL,
            time INTEGER NOT NULL,type INTEGER NOT NULL)""")
        db.execSQL("CREATE TABLE graves(usn INTEGER NOT NULL,oid INTEGER NOT NULL,type INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX ix_notes_csum ON notes(csum)")
        db.execSQL("CREATE INDEX ix_notes_usn ON notes(usn)")
        db.execSQL("CREATE INDEX ix_cards_nid ON cards(nid)")
        db.execSQL("CREATE INDEX ix_cards_sched ON cards(did,queue,due)")
        db.execSQL("CREATE INDEX ix_cards_usn ON cards(usn)")
        db.execSQL("CREATE INDEX ix_revlog_cid ON revlog(cid)")
        db.execSQL("CREATE INDEX ix_revlog_usn ON revlog(usn)")
        val now = System.currentTimeMillis()
        val seconds = now / 1000
        val model = JSONObject().put("id", MODEL_ID).put("name", AnkiCards.MODEL_NAME).put("type", 0)
            .put("mod", seconds).put("usn", -1).put("sortf", 1).put("did", DECK_ID).put("css", AnkiCards.CSS)
            .put("latexPre", "").put("latexPost", "").put("tags", JSONArray()).put("vers", JSONArray())
            .put("flds", JSONArray(AnkiCards.FIELDS.mapIndexed { index, name ->
                JSONObject().put("name", name).put("ord", index).put("sticky", false).put("rtl", false)
                    .put("font", "Arial").put("size", 20).put("media", JSONArray())
            })).put("tmpls", JSONArray().put(JSONObject().put("name", "Word to meanings").put("ord", 0)
                .put("qfmt", AnkiCards.QUESTION).put("afmt", AnkiCards.ANSWER)
                .put("bqfmt", "").put("bafmt", "").put("did", JSONObject.NULL)))
            .put("req", JSONArray().put(JSONArray().put(0).put("all").put(JSONArray().put(1))))
        fun deck(id: Long, name: String) = JSONObject().put("id", id).put("name", name).put("mod", seconds)
            .put("usn", -1).put("desc", "").put("dyn", 0).put("conf", 1).put("collapsed", false)
            .put("browserCollapsed", false).put("extendNew", 0).put("extendRev", 0)
            .put("newToday", JSONArray(listOf(0, 0))).put("revToday", JSONArray(listOf(0, 0)))
            .put("lrnToday", JSONArray(listOf(0, 0))).put("timeToday", JSONArray(listOf(0, 0)))
        val config = JSONObject().put("id", 1).put("name", "Default").put("mod", seconds).put("usn", -1)
            .put("maxTaken", 60).put("autoplay", true).put("timer", 0).put("replayq", true)
            .put("new", JSONObject().put("delays", JSONArray(listOf(1, 10))).put("ints", JSONArray(listOf(1, 4, 0)))
                .put("initialFactor", 2500).put("order", 1).put("perDay", 20).put("bury", false))
            .put("rev", JSONObject().put("perDay", 200).put("ease4", 1.3).put("fuzz", 0.05)
                .put("ivlFct", 1).put("maxIvl", 36500).put("minSpace", 1).put("bury", false))
            .put("lapse", JSONObject().put("delays", JSONArray().put(10)).put("mult", 0)
                .put("minInt", 1).put("leechFails", 8).put("leechAction", 0))
        val conf = JSONObject().put("curDeck", DECK_ID).put("activeDecks", JSONArray().put(DECK_ID))
            .put("curModel", MODEL_ID.toString()).put("nextPos", notes.size + 1).put("newSpread", 0)
            .put("collapseTime", 1200).put("timeLim", 0).put("estTimes", true).put("dueCounts", true)
            .put("schedVer", 2).put("addToCur", true)
        db.execSQL("INSERT INTO col VALUES(1,?,?,?,11,0,0,0,?,?,?,?,?)", arrayOf<Any>(seconds, now, now,
            conf.toString(), JSONObject().put(MODEL_ID.toString(), model).toString(),
            JSONObject().put("1", deck(1, "Default")).put(DECK_ID.toString(), deck(DECK_ID, AnkiCards.DECK_NAME)).toString(),
            JSONObject().put("1", config).toString(), "{}"))
        db.beginTransaction()
        try {
            notes.forEachIndexed { index, note ->
                currentCoroutineContext().ensureActive()
                val id = now + index
                val checksum = MessageDigest.getInstance("SHA-1").digest(note.key.toByteArray())
                    .take(4).joinToString("") { "%02x".format(it) }.toLong(16)
                db.execSQL("INSERT INTO notes VALUES(?,?,?,?,-1,?,?,?, ?,0,'')", arrayOf<Any>(id, note.guid, MODEL_ID,
                    seconds, " maxfluency ${note.tag} ", note.fields.joinToString("\u001f"),
                    Html.fromHtml(note.word, Html.FROM_HTML_MODE_LEGACY).toString().trim(), checksum))
                db.execSQL("INSERT INTO cards VALUES(?,?,?,0,?,-1,0,0,?,0,2500,0,0,0,0,0,0,'')",
                    arrayOf<Any>(id, id, DECK_ID, seconds, index + 1))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
