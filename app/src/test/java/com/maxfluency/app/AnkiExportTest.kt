package com.maxfluency.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.maxfluency.app.data.AnkiCards
import com.maxfluency.app.data.AnkiDeckExporter
import com.maxfluency.app.data.AnkiDroidExporter
import com.maxfluency.core.Language
import com.maxfluency.core.Sense
import com.maxfluency.core.WordEntry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class AnkiExportTest {
    private fun word(value: String = "abjurer", language: Language = Language.FRENCH) =
        WordEntry("${language.code}:$value", language, value, "", listOf(
            Sense("verb", "A <safe> meaning & a first sense.", examples = listOf("An example with \"quotes\".")),
            Sense("verb", "Another meaning.", examples = listOf("Another example.")),
        ), emptyList(), language.code)

    @Test
    fun packagesContainAllMeaningsExamplesAndCreditsWithStableIdsAndNoStudyHistory(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val entries = listOf(word(), word(), word("café"), word("cafe"), word("café", Language.SPANISH))
        val notes = AnkiCards.notes(entries)
        assertEquals(4, notes.size)
        assertEquals(notes.map { it.guid }, AnkiCards.notes(entries).map { it.guid })
        assertEquals(4, notes.map { it.guid }.distinct().size)
        assertTrue(notes.first().meaning.contains("&lt;safe&gt;"))
        assertFalse(notes.first().meaning.contains("<safe>"))
        assertTrue(notes.first().meaning.contains("Another example."))
        assertTrue(notes.first().meaning.contains("https://fr.wiktionary.org/wiki/abjurer"))
        assertTrue(notes.first().meaning.contains("CC BY-SA 4.0"))
        val output = ByteArrayOutputStream()
        AnkiDeckExporter.write(context, notes, output)
        val contents = linkedMapOf<String, ByteArray>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                contents[entry.name] = zip.readBytes()
            }
        }
        assertEquals(setOf("collection.anki2", "media"), contents.keys)
        assertEquals("{}", contents.getValue("media").decodeToString())
        val file = File(context.cacheDir, "anki-export-check.sqlite")
        file.writeBytes(contents.getValue("collection.anki2"))
        try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                fun count(table: String) = db.rawQuery("SELECT count(*) FROM $table", null).use {
                    it.moveToFirst(); it.getInt(0)
                }
                assertEquals(4, count("notes"))
                assertEquals(4, count("cards"))
                assertEquals(0, count("revlog"))
                db.rawQuery("SELECT type,queue,reps,lapses FROM cards", null).use {
                    while (it.moveToNext()) repeat(4) { column -> assertEquals(0, it.getInt(column)) }
                }
                db.rawQuery("SELECT flds,guid FROM notes ORDER BY id", null).use {
                    var index = 0
                    while (it.moveToNext()) {
                        assertEquals(notes[index].fields, it.getString(0).split('\u001f'))
                        assertEquals(notes[index++].guid, it.getString(1))
                    }
                }
            }
            System.getProperty("maxfluency.ankiFixture")?.let { File(it).writeBytes(output.toByteArray()) }
        } finally { SQLiteDatabase.deleteDatabase(file) }
    }

    @Test
    fun directSendsUseBoundedBatchesAndSkipDuplicatesIncludingConcurrentRequests() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = RecordingAnkiProvider()
        ShadowContentResolver.registerProviderInternal("com.ichi2.anki.flashcards", provider)
        val notes = AnkiCards.notes((1..41).map { word("word$it") })
        val exporter = AnkiDroidExporter(context.contentResolver)
        val first = async { exporter.send(notes) {} }
        val duplicate = async { exporter.send(notes) {} }
        assertEquals(41, first.await().added)
        assertEquals(41, duplicate.await().skipped)
        assertEquals(41, provider.records.size)
        assertTrue(provider.batches.all { it in 1..20 })
        assertEquals(listOf(20, 20, 1), provider.batches)
        assertTrue(provider.batchTimes.zipWithNext().all { (a, b) -> b - a >= 450_000_000 })
        assertTrue(provider.records.all { it.getAsString("flds").split('\u001f').size == 3 })
    }

    @Test
    fun directExportCreatesItsOwnDeckAndTypeBeforeAddingNotes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = RecordingAnkiProvider().apply { modelExists = false; deckExists = false }
        ShadowContentResolver.registerProviderInternal("com.ichi2.anki.flashcards", provider)
        val result = AnkiDroidExporter(context.contentResolver).send(AnkiCards.notes(listOf(word()))) {}
        assertEquals(1, result.added)
        assertTrue(provider.modelExists)
        assertTrue(provider.deckExists)
        assertEquals(AnkiCards.QUESTION, provider.template?.getAsString("question_format"))
        assertEquals(AnkiCards.ANSWER, provider.template?.getAsString("answer_format"))
    }

    @Test
    fun disabledAnkiDroidApiReportsAnExportErrorInsteadOfCrashing() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = RecordingAnkiProvider().apply { apiDisabled = true }
        ShadowContentResolver.registerProviderInternal("com.ichi2.anki.flashcards", provider)
        assertThrows(IOException::class.java) {
            runBlocking { AnkiDroidExporter(context.contentResolver).send(AnkiCards.notes(listOf(word()))) {} }
        }
        assertTrue(provider.records.isEmpty())
    }

    @Test
    fun partialProviderFailureIsExplicitAndRetryDoesNotDuplicateTheAcceptedNote() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = RecordingAnkiProvider().apply { failNextBatch = true }
        ShadowContentResolver.registerProviderInternal("com.ichi2.anki.flashcards", provider)
        val notes = AnkiCards.notes(listOf(word("one"), word("two")))
        val exporter = AnkiDroidExporter(context.contentResolver)
        assertThrows(IOException::class.java) { runBlocking { exporter.send(notes) {} } }
        assertEquals(1, provider.records.size)
        val retry = exporter.send(notes) {}
        assertEquals(1, retry.added)
        assertEquals(1, retry.skipped)
        assertEquals(2, provider.records.size)
    }
}

private class RecordingAnkiProvider : ContentProvider() {
    val records = mutableListOf<ContentValues>()
    val batches = mutableListOf<Int>()
    val batchTimes = mutableListOf<Long>()
    var failNextBatch = false
    var apiDisabled = false
    var modelExists = true
    var deckExists = true
    var template: ContentValues? = null
    override fun onCreate() = true
    override fun getType(uri: Uri): String? = null
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        if (apiDisabled) throw UnsupportedOperationException("The local API is disabled")
        return when (uri.path) {
            "/models" -> MatrixCursor(arrayOf("_id", "name", "field_names", "num_cards")).apply {
                if (modelExists) addRow(arrayOf<Any>(123L, AnkiCards.MODEL_NAME, AnkiCards.FIELDS.joinToString("\u001f"), 1))
            }
            "/decks" -> MatrixCursor(arrayOf("deck_id", "deck_name")).apply {
                if (deckExists) addRow(arrayOf<Any>(456L, AnkiCards.DECK_NAME))
            }
            "/notes" -> MatrixCursor(arrayOf("flds")).apply { records.forEach { addRow(arrayOf(it.getAsString("flds"))) } }
            "/models/123/templates" -> MatrixCursor(arrayOf("question_format", "answer_format")).apply {
                addRow(arrayOf(template?.getAsString("question_format") ?: AnkiCards.QUESTION,
                    template?.getAsString("answer_format") ?: AnkiCards.ANSWER))
            }
            else -> throw IllegalArgumentException("Unexpected API query $uri")
        }
    }
    override fun bulkInsert(uri: Uri, values: Array<out ContentValues>): Int {
        assertEquals("456", uri.getQueryParameter("deckId"))
        batches += values.size
        batchTimes += System.nanoTime()
        val accepted = if (failNextBatch) 1 else values.size
        records.addAll(values.take(accepted).map { ContentValues(it) })
        failNextBatch = false
        return accepted
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = when (uri.path) {
        "/models" -> { modelExists = true; Uri.withAppendedPath(uri, "123") }
        "/decks" -> { deckExists = true; Uri.withAppendedPath(uri, "456") }
        else -> throw UnsupportedOperationException()
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        assertEquals("/models/123/templates/0", uri.path)
        template = values
        return 1
    }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
}
