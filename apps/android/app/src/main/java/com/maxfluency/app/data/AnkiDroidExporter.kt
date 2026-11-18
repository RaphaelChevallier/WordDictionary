package com.maxfluency.app.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AnkiSendResult(val added: Int, val skipped: Int)

class AnkiDroidExporter(private val resolver: ContentResolver) {
    suspend fun send(notes: List<AnkiNote>, onProgress: (Int) -> Unit): AnkiSendResult = try {
        withContext(Dispatchers.IO) {
            writes.withLock {
                if (notes.isEmpty()) throw IOException("There are no Anki notes to send")
                val model = model()
                val deck = deck()
                val existing = existingKeys()
                val pending = notes.distinctBy { it.key }.filter { it.key !in existing }
                var added = 0
                val uri = notesUri.buildUpon().appendQueryParameter("deckId", deck.toString()).build()
                val batches = mutableListOf<List<AnkiNote>>()
                var batch = mutableListOf<AnkiNote>()
                var bytes = 0
                pending.forEach { note ->
                    val size = note.fields.sumOf { it.toByteArray().size }
                    if (size > 200_000) throw IOException("Anki note exceeds the safe local API size")
                    if (batch.size == 20 || bytes + size > 200_000) {
                        batches += batch
                        batch = mutableListOf()
                        bytes = 0
                    }
                    batch += note
                    bytes += size
                }
                if (batch.isNotEmpty()) batches += batch
                batches.forEachIndexed { index, items ->
                    currentCoroutineContext().ensureActive()
                    if (index > 0) delay(500)
                    val values = items.map { note ->
                        ContentValues().apply {
                            put("mid", model)
                            put("flds", note.fields.joinToString("\u001f"))
                            put("tags", " maxfluency ${note.tag} ")
                        }
                    }.toTypedArray()
                    val count = resolver.bulkInsert(uri, values)
                    if (count != items.size) throw IOException(
                        "AnkiDroid only accepted part of the batch; retry skips previously added notes")
                    added += count
                    onProgress(added + notes.size - pending.size)
                }
                val verified = existingKeys()
                if (notes.any { it.key !in verified }) throw IOException("AnkiDroid did not retain every exported note")
                AnkiSendResult(added, notes.size - pending.size)
            }
        }
    } catch (error: UnsupportedOperationException) {
        throw IOException("The AnkiDroid local API is disabled or unsupported", error)
    }

    private fun existingKeys(): Set<String> =
        (resolver.query(notesUri, arrayOf("flds"), "tag:maxfluency", null, null)
            ?: throw IOException("AnkiDroid note access is unavailable")).use {
            val field = it.getColumnIndexOrThrow("flds")
            buildSet {
                while (it.moveToNext()) {
                    if (size >= 100_000) throw IOException("Too many existing MaxFluency notes to check safely")
                    add(it.getString(field).substringBefore('\u001f'))
                }
            }
        }

    private fun deck(): Long {
        val uri = Uri.withAppendedPath(base, "decks")
        (resolver.query(uri, arrayOf("deck_id", "deck_name"), null, null, null)
            ?: throw IOException("AnkiDroid decks could not be read")).use {
            while (it.moveToNext()) if (it.getString(it.getColumnIndexOrThrow("deck_name")) == AnkiCards.DECK_NAME) {
                return it.getLong(it.getColumnIndexOrThrow("deck_id"))
            }
        }
        return resolver.insert(uri, ContentValues().apply { put("deck_name", AnkiCards.DECK_NAME) })
            ?.lastPathSegment?.toLongOrNull() ?: throw IOException("The MaxFluency Anki deck could not be created")
    }

    private fun model(): Long {
        val uri = Uri.withAppendedPath(base, "models")
        (resolver.query(uri, arrayOf("_id", "name", "field_names", "num_cards"), null, null, null)
            ?: throw IOException("AnkiDroid note types could not be read")).use {
            while (it.moveToNext()) if (it.getString(it.getColumnIndexOrThrow("name")) == AnkiCards.MODEL_NAME) {
                if (it.getString(it.getColumnIndexOrThrow("field_names")) != AnkiCards.FIELDS.joinToString("\u001f") ||
                    it.getInt(it.getColumnIndexOrThrow("num_cards")) != 1) {
                    throw IOException("The existing MaxFluency Anki note type was changed; use a deck-file import instead")
                }
                val id = it.getLong(it.getColumnIndexOrThrow("_id"))
                val templates = uri.buildUpon().appendPath(id.toString()).appendPath("templates").build()
                (resolver.query(templates, arrayOf("question_format", "answer_format"), null, null, null)
                    ?: throw IOException("The MaxFluency Anki template could not be checked")).use { template ->
                    if (!template.moveToFirst() || template.getString(0) != AnkiCards.QUESTION ||
                        template.getString(1) != AnkiCards.ANSWER) {
                        throw IOException("The MaxFluency Anki template was changed or incompletely created; use a deck-file import")
                    }
                }
                return id
            }
        }
        val created = resolver.insert(uri, ContentValues().apply {
            put("name", AnkiCards.MODEL_NAME)
            put("field_names", AnkiCards.FIELDS.joinToString("\u001f"))
            put("num_cards", 1)
            put("css", AnkiCards.CSS)
            put("sort_field_index", 1)
        }) ?: throw IOException("The Anki note type could not be created")
        val template = created.buildUpon().appendPath("templates").appendPath("0").build()
        if (resolver.update(template, ContentValues().apply {
            put("card_template_name", "Word to meanings")
            put("question_format", AnkiCards.QUESTION)
            put("answer_format", AnkiCards.ANSWER)
        }, null, null) != 1) throw IOException("The Anki card template could not be saved")
        return created.lastPathSegment?.toLongOrNull() ?: throw IOException("AnkiDroid returned an invalid note type")
    }

    companion object {
        const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
        private val base = Uri.parse("content://com.ichi2.anki.flashcards")
        private val notesUri = Uri.withAppendedPath(base, "notes")
        private val writes = Mutex()

        fun installed(context: Context): Boolean =
            context.packageManager.resolveContentProvider("com.ichi2.anki.flashcards", 0) != null

        fun permitted(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED
    }
}
