package com.maxfluency.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.maxfluency.core.*
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.Normalizer
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class FullDictionaryInfo(
    val language: String, val entries: Int, val headwords: Int, val variants: Int,
    val version: String, val archiveSha256: String, val installedAt: Long,
    val indexVersion: Int = 1,
)

object PublicDictionaries {
    fun url(language: Language) = "https://www.reader-dict.com/file/${language.code}/dict-${language.code}-${language.code}-noetym.zip"
    fun estimatedBytes(language: Language): Long = when (language) {
        Language.ENGLISH -> 49_837_964
        Language.FRENCH -> 32_142_939
        Language.SPANISH -> 9_545_437
    }
}

class FullDictionaryStore private constructor(private val context: Context) {
    private val base = File(context.filesDir, "full-dictionaries")
    private val mutex = Mutex()
    private var opened: Language? = null
    private var database: SQLiteDatabase? = null
    private var dictionary: DictZipReader? = null
    private var hasArticleSpellings = false
    private val ranks = VocabularyRanks(context)

    suspend fun info(language: Language): FullDictionaryInfo? = withContext(Dispatchers.IO) {
        mutex.withLock { readInfo(language) }
    }

    private fun readInfo(language: Language): FullDictionaryInfo? {
        val directory = File(base, language.code)
        val file = File(directory, "info.json")
        if (!file.exists()) return null
        val info = dictionaryJson.decodeFromString<FullDictionaryInfo>(file.readText())
        if (info.language != language.code || !File(directory, "index.sqlite").isFile || !File(directory, "data.dict.dz").isFile) {
            throw IOException("The downloaded dictionary is incomplete")
        }
        return info
    }

    suspend fun install(language: Language, archive: File, sha256: String,
        policy: DiscoveryPolicy, progress: (Int) -> Unit, checkCancelled: () -> Unit): FullDictionaryInfo =
        withContext(Dispatchers.IO) {
            if (!base.isDirectory && !base.mkdirs()) throw IOException("Cannot create dictionary storage")
            if (base.usableSpace < archive.length() * 5 + 80_000_000) throw IOException("Not enough free space to prepare the full dictionary")
            val staging = File(base, ".${language.code}-${UUID.randomUUID()}")
            if (!staging.mkdir()) throw IOException("Cannot create dictionary staging directory")
            try {
                val found = mutableSetOf<String>()
                var extracted = 0L
                ZipInputStream(archive.inputStream().buffered()).use { zip ->
                    var count = 0
                    while (true) {
                        checkCancelled()
                        val entry = zip.nextEntry ?: break
                        if (++count > 16 || entry.isDirectory) throw IOException("Unexpected dictionary archive contents")
                        val destination = when {
                            entry.name.endsWith(".dict.dz") -> "data.dict.dz"
                            entry.name.endsWith(".idx") -> "index.raw"
                            entry.name.endsWith(".syn") -> "variants.raw"
                            entry.name.endsWith(".ifo") -> "book.ifo"
                            else -> throw IOException("Unsupported dictionary archive entry")
                        }
                        if (!found.add(destination)) throw IOException("Duplicate dictionary archive entry")
                        File(staging, destination).outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                checkCancelled()
                                val read = zip.read(buffer)
                                if (read < 0) break
                                extracted += read
                                if (extracted > 512_000_000) throw IOException("Dictionary archive exceeds the extraction safety limit")
                                output.write(buffer, 0, read)
                            }
                        }
                        zip.closeEntry()
                    }
                }
                if (!found.containsAll(listOf("data.dict.dz", "index.raw", "book.ifo"))) throw IOException("Missing dictionary files")
                val properties = File(staging, "book.ifo").readLines().drop(1).mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
                }.toMap()
                if (properties["version"] != "3.0.0" || properties["sametypesequence"] != "h" ||
                    properties["bookname"]?.uppercase()?.endsWith(language.code.uppercase()) != true) {
                    throw IOException("Unsupported or wrong-language dictionary metadata")
                }
                val headwords = properties["wordcount"]?.toIntOrNull() ?: throw IOException("Missing dictionary word count")
                val variants = properties["synwordcount"]?.let {
                    it.toIntOrNull() ?: throw IOException("Invalid variant count")
                } ?: 0
                if (headwords !in 1..10_000_000 || variants !in 0..10_000_000 ||
                    File(staging, "index.raw").length() != properties["idxfilesize"]?.toLongOrNull()) {
                    throw IOException("Dictionary index size/count mismatch")
                }
                val indexBudget = (File(staging, "index.raw").length() + File(staging, "variants.raw").length()) * 6 +
                    (headwords.toLong() + variants) * 64 + 32_000_000
                if (base.usableSpace < indexBudget) throw IOException("More free storage is needed for the full offline search index")
                val data = DictZipReader(File(staging, "data.dict.dz"))
                try {
                    val db = SQLiteDatabase.openOrCreateDatabase(File(staging, "index.sqlite"), null)
                    try {
                        db.rawQuery("PRAGMA journal_mode=OFF", null).use {
                            if (!it.moveToFirst() || it.getString(0) != "off") throw IOException("Could not configure the temporary dictionary index")
                        }
                        db.execSQL("PRAGMA synchronous=OFF")
                        db.execSQL("CREATE TABLE articles(id INTEGER PRIMARY KEY,off INTEGER NOT NULL,size INTEGER NOT NULL,spelling TEXT NOT NULL)")
                        db.execSQL("""CREATE TABLE words(norm TEXT NOT NULL,spelling TEXT NOT NULL,anagram TEXT NOT NULL,
                            target INTEGER NOT NULL,level INTEGER NOT NULL,eligible INTEGER NOT NULL,
                            PRIMARY KEY(norm,spelling,target)) WITHOUT ROWID""")
                        val article = db.compileStatement("INSERT INTO articles VALUES (?,?,?,?)")
                        val word = db.compileStatement("INSERT OR IGNORE INTO words VALUES (?,?,?,?,?,?)")
                        val basic = policy.excludedWords[language.code].orEmpty().toSet()
                        val dailyWord = Regex("^[\\p{Ll}][\\p{L}'-]{2,19}$")
                        fun addWord(spelling: String, ordinal: Int) {
                            val text = Normalizer.normalize(spelling, Normalizer.Form.NFC)
                            word.clearBindings()
                            word.bindString(1, normalizeSearch(text))
                            word.bindString(2, text)
                            word.bindString(3, anagramKey(text))
                            word.bindLong(4, ordinal.toLong())
                            word.bindLong(5, ranks.level(language, text).ordinal.toLong())
                            word.bindLong(6, if (dailyWord.matches(text) && text !in basic) 1 else 0)
                            word.executeInsert()
                        }
                        db.beginTransaction()
                        try {
                            DataInputStream(File(staging, "index.raw").inputStream().buffered()).use { input ->
                                var ordinal = 0
                                while (true) {
                                    val spelling = readWord(input) ?: break
                                    val offset = input.readInt().toLong() and 0xffffffffL
                                    val size = input.readInt().toLong() and 0xffffffffL
                                    if (size > 2_097_152 || offset + size > data.uncompressedBytes) throw IOException("Invalid article offset")
                                    article.bindLong(1, ordinal.toLong()); article.bindLong(2, offset); article.bindLong(3, size)
                                    article.bindString(4, Normalizer.normalize(spelling, Normalizer.Form.NFC))
                                    article.executeInsert()
                                    addWord(spelling, ordinal)
                                    ordinal++
                                    if (ordinal % 10000 == 0) { checkCancelled(); progress(ordinal * 90 / (headwords + variants)) }
                                }
                                if (ordinal != headwords) throw IOException("Incomplete main dictionary index")
                            }
                            if (variants > 0) DataInputStream(File(staging, "variants.raw").inputStream().buffered()).use { input ->
                                var count = 0
                                while (true) {
                                    val spelling = readWord(input) ?: break
                                    val target = input.readInt()
                                    if (target !in 0 until headwords) throw IOException("Invalid variant target")
                                    addWord(spelling, target)
                                    count++
                                    if (count % 10000 == 0) { checkCancelled(); progress((headwords + count) * 90 / (headwords + variants)) }
                                }
                                if (count != variants) throw IOException("Incomplete dictionary variants")
                            }
                            db.setTransactionSuccessful()
                        } finally {
                            db.endTransaction()
                            article.close(); word.close()
                        }
                        progress(92)
                        db.execSQL("CREATE INDEX idx_anagram ON words(anagram)")
                        db.execSQL("CREATE INDEX idx_daily ON words(eligible,level)")
                        db.rawQuery("PRAGMA quick_check", null).use {
                            if (!it.moveToFirst() || it.getString(0) != "ok") throw IOException("Downloaded index integrity check failed")
                        }
                        val count = db.rawQuery("SELECT count(*) FROM (SELECT norm,spelling FROM words GROUP BY norm,spelling)", null).use {
                            it.moveToFirst(); it.getInt(0)
                        }
                        val info = FullDictionaryInfo(language.code, count, headwords, variants,
                            properties["date"].orEmpty(), sha256, System.currentTimeMillis(), indexVersion = 2)
                        File(staging, "info.json").writeText(dictionaryJson.encodeToString(info))
                        db.close()
                        data.close()
                        for (name in listOf("index.raw", "variants.raw")) {
                            val raw = File(staging, name)
                            if (raw.exists() && !raw.delete()) throw IOException("Could not remove a temporary dictionary index")
                        }
                        checkCancelled()
                        mutex.withLock {
                            closeReader()
                            val destination = File(base, language.code)
                            val backup = File(base, ".${language.code}-previous")
                            if (backup.exists() && !backup.deleteRecursively()) throw IOException("Cannot remove old dictionary backup")
                            if (destination.exists()) Files.move(destination.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE)
                            try { Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE) }
                            catch (error: IOException) {
                                if (backup.exists()) Files.move(backup.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                                throw error
                            }
                            if (backup.exists() && !backup.deleteRecursively()) Log.w("FullDictionary", "Could not delete old dictionary backup")
                        }
                        progress(100)
                        return@withContext info
                    } finally { if (db.isOpen) db.close() }
                } finally { data.close() }
            } finally {
                if (staging.exists() && !staging.deleteRecursively()) Log.w("FullDictionary", "Could not remove incomplete dictionary staging")
            }
        }

    suspend fun remove(language: Language) = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeReader()
            val directory = File(base, language.code)
            if (directory.exists() && !directory.deleteRecursively()) throw IOException("Could not remove downloaded dictionary")
        }
    }

    suspend fun entry(language: Language, spelling: String): WordEntry? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!open(language)) return@withLock null
            readEntry(language, spelling)
        }
    }

    suspend fun search(language: Language, plan: SearchPlan): List<WordEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!open(language)) return@withLock emptyList()
            val (where, args) = when (plan) {
                is SearchPlan.Prefix -> "norm>=? AND norm<?" to arrayOf(plan.value, "${plan.value}\uFFFF")
                is SearchPlan.Wildcard -> "norm GLOB ?" to arrayOf(plan.value)
                is SearchPlan.Anagram -> "anagram=?" to arrayOf(plan.value)
                else -> return@withLock emptyList()
            }
            val names = database!!.rawQuery("SELECT spelling FROM words WHERE $where GROUP BY norm,spelling ORDER BY norm,spelling LIMIT 60", args).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            names.mapNotNull { readEntry(language, it) }
        }
    }

    suspend fun candidates(language: Language, level: VocabularyLevel, date: LocalDate, round: Int = 0): List<WordEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!open(language)) return@withLock emptyList()
            readCandidateWords(language, level, date, round).mapNotNull { readEntry(language, it) }
        }
    }

    suspend fun candidateWords(language: Language, level: VocabularyLevel, date: LocalDate, round: Int): List<String> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!open(language)) return@withLock emptyList()
                readCandidateWords(language, level, date, round)
            }
        }

    private fun readCandidateWords(language: Language, level: VocabularyLevel, date: LocalDate, round: Int): List<String> {
        val db = database!!
        val count = db.rawQuery("SELECT count(*) FROM words WHERE eligible=1 AND level=?", arrayOf(level.ordinal.toString())).use {
            it.moveToFirst(); it.getInt(0)
        }
        if (count == 0) return emptyList()
        val offset = Math.floorMod(date.toEpochDay() * 97 + language.ordinal * 17 + round.toLong() * 160, count.toLong()).toInt()
        return db.rawQuery("""SELECT spelling FROM words WHERE eligible=1 AND level=?
            LIMIT 160 OFFSET ?""", arrayOf(level.ordinal.toString(), offset.toString())).use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }.distinct()
    }

    private fun open(language: Language): Boolean {
        if (opened == language) return true
        val info = readInfo(language) ?: return false
        closeReader()
        val directory = File(base, language.code)
        database = SQLiteDatabase.openDatabase(File(directory, "index.sqlite").path, null, SQLiteDatabase.OPEN_READONLY)
        dictionary = DictZipReader(File(directory, "data.dict.dz"))
        hasArticleSpellings = info.indexVersion >= 2
        if (!hasArticleSpellings) Log.i("FullDictionary", "Older dictionary index has no per-spelling phonetic metadata")
        opened = language
        return true
    }

    private fun readEntry(language: Language, spelling: String): WordEntry? {
        val spellingColumn = if (hasArticleSpellings) "a.spelling" else "NULL"
        val records = database!!.rawQuery("""SELECT DISTINCT a.off,a.size,$spellingColumn FROM words w JOIN articles a ON a.id=w.target
            WHERE w.norm=? AND w.spelling=?""", arrayOf(normalizeSearch(spelling), spelling)).use {
            buildList { while (it.moveToNext()) add(Triple(it.getLong(0), it.getInt(1), it.getString(2))) }
        }
        val parsed = records.mapNotNull { (offset, size, canonical) ->
            DictionaryHtml.starDict(language, spelling, dictionary!!.read(offset, size).decodeToString())?.let {
                if (canonical == spelling) it else it.copy(pronunciation = "")
            }
        }
        if (parsed.isEmpty()) return null
        return parsed.first().copy(
            pronunciation = parsed.firstNotNullOfOrNull { it.pronunciation.takeIf { value -> value.isNotBlank() } }.orEmpty(),
            senses = parsed.flatMap { it.senses }.distinctBy { it.partOfSpeech to it.definition },
            related = parsed.flatMap { it.related }.distinct().take(16))
    }

    private fun closeReader() {
        database?.close(); dictionary?.close()
        database = null; dictionary = null; opened = null; hasArticleSpellings = false
    }

    suspend fun close() = withContext(Dispatchers.IO) { mutex.withLock { closeReader() } }

    companion object {
        private val stores = mutableMapOf<String, FullDictionaryStore>()
        @Synchronized fun get(context: Context): FullDictionaryStore =
            stores.getOrPut(context.filesDir.path) { FullDictionaryStore(context.applicationContext) }

        private fun readWord(input: DataInputStream): String? {
            val first = input.read()
            if (first < 0) return null
            val bytes = ByteArrayOutputStream()
            var value = first
            while (value != 0) {
                if (value < 0 || bytes.size() >= 4096) throw IOException("Invalid dictionary index word")
                bytes.write(value)
                value = input.read()
            }
            return bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
        }
    }
}
