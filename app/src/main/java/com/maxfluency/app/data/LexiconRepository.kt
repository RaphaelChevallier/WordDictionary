package com.maxfluency.app.data

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdException
import com.maxfluency.app.BuildConfig
import com.maxfluency.core.BlockLexicon
import com.maxfluency.core.DailyWords
import com.maxfluency.core.Language
import com.maxfluency.core.LanguagePack
import com.maxfluency.core.PackManifest
import com.maxfluency.core.SearchPlan
import com.maxfluency.core.WordEntry
import com.maxfluency.core.discoveryPreview
import com.maxfluency.core.dailyIndices
import com.maxfluency.core.dictionaryJson
import com.maxfluency.core.withUsageExamples
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PreparedEntry(val entry: WordEntry, val archived: Boolean = false)

class LexiconRepository(context: Context) {
    private val context = context.applicationContext
    private val mutex = Mutex()
    private var loadedManifest: PackManifest? = null
    private var reader: BlockLexicon? = null
    private var input: ParcelFileDescriptor.AutoCloseInputStream? = null
    private var openedLanguage: Language? = null
    private val cacheDelegate = lazy {
        val remaining = 500_000 - readManifest().languages.sumOf { it.entries }
        if (remaining <= 0) throw IOException("The starter bank exceeds the temporary word-storage limit")
        OnlineWordCache(context, remaining)
    }
    private val cache by cacheDelegate
    private val personal = PersonalStore(context)
    private val api = PublicDictionaryApi()
    private val ranks = VocabularyRanks(context)
    private val full = FullDictionaryStore.get(context)
    private val pronunciationGuides = PronunciationGuides(context)
    private val prepared = LruCache<String, PreparedEntry>(128)
    @Volatile private var preparedDaily: Map<String, PreparedEntry> = emptyMap()

    fun preparedEntry(id: String): PreparedEntry? = preparedDaily[id] ?: prepared.get(id)

    suspend fun manifest(): PackManifest = withContext(Dispatchers.IO) {
        mutex.withLock { readManifest() }
    }

    suspend fun daily(language: Language, date: LocalDate, level: VocabularyLevel = VocabularyLevel.INTERMEDIATE): DailyWords = withContext(Dispatchers.IO) {
        mutex.withLock {
            val key = "$date:${language.code}:${level.name}"
            val archived = personal.archivedWord(date, language, level)
            personal.daily(key)?.let {
                val daily = prepareDaily(it.words(date))
                personal.saveArchivedWords(listOf(ArchivedDailyWord(date, level,
                    preparedEntry(daily.featured.id)?.entry ?: daily.featured)))
                return@withLock daily
            }
            val candidates = discoveryCandidates(language, date, level, 0).take(21)
            val featured = archived?.entry?.let {
                discoveryPreview(it, readManifest().discovery) ?: it.also {
                    Log.i("LexiconRepository", "Preserving a previously assigned daily word under a changed discovery policy")
                }
            } ?: candidates.firstOrNull() ?: throw IOException("No local daily word is available")
            val entries = listOf(featured) + candidates.filter { it.id != featured.id }.take(20)
            if (entries.size < 21) throw IOException("Not enough local words for daily discovery")
            personal.saveDaily(key, entries, date.minusDays(7).toString(),
                archived?.entry ?: readEntry(featured.id) ?: featured)
            val chosen = personal.daily(key) ?: throw IOException("The selected daily words were not saved")
            cache.seen(chosen.entries.map { it.id })
            prepareDaily(chosen.words(date))
        }
    }

    suspend fun calendarMonth(month: YearMonth, language: Language, level: VocabularyLevel): List<ArchivedDailyWord> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val today = LocalDate.now()
                if (month.year !in 1..9999 || month > YearMonth.from(today)) {
                    throw IOException("A daily word calendar month must not be in the future")
                }
                val existing = personal.archivedMonth(month, language, level).associateBy { it.date }
                val dictionary = openReader(language)
                val positions = dictionary.daily.filter { ranks.level(language, dictionary.wordAt(it)) == level }
                if (positions.isEmpty()) throw IOException("No offline words are available for this calendar level")
                val last = minOf(month.atEndOfMonth(), today)
                val additions = buildList {
                    for (day in 1..last.dayOfMonth) {
                        currentCoroutineContext().ensureActive()
                        val date = month.atDay(day)
                        if (date in existing) continue
                        val saved = personal.daily("$date:${language.code}:${level.name}")?.entries?.first()
                        val entry = saved?.let { readEntry(it.id) ?: it } ?: pronunciationGuides.enrich(
                            dictionary.entryAt(positions[dailyIndices(date, language, positions.size).first()]))
                        add(ArchivedDailyWord(date, level, entry, backfilled = saved == null))
                    }
                }
                personal.saveArchivedWords(additions)
                personal.archivedMonth(month, language, level).filter { it.date <= today }
            }
        }

    suspend fun archivedWord(date: LocalDate, language: Language, level: VocabularyLevel): ArchivedDailyWord =
        personal.archivedWord(date, language, level) ?: throw IOException("The archived daily word is missing")

    suspend fun exploreGarden(language: Language, date: LocalDate, level: VocabularyLevel, round: Int,
        id: String): DailyWords = withContext(Dispatchers.IO) {
        mutex.withLock { applyPreparedDetails(personal.exploreDaily("$date:${language.code}:${level.name}", id, round).words(date)) }
    }

    suspend fun refreshGarden(language: Language, date: LocalDate, level: VocabularyLevel, round: Int,
        visible: Set<String>): DailyWords? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val key = "$date:${language.code}:${level.name}"
            val current = personal.daily(key) ?: throw IOException("The garden selection is missing")
            if (current.round != round) {
                Log.i("LexiconRepository", "Reloading an already refreshed garden")
                return@withLock prepareDaily(current.words(date))
            }
            val ids = current.entries.drop(1).map { it.id }.toSet()
            if (visible.isEmpty() || !ids.containsAll(visible)) {
                throw IOException("Refresh requires words from the current garden")
            }
            val shown = current.shown + current.explored + visible
            val fresh = discoveryCandidates(language, date, level, round + 1, shown + current.entries.first().id, limit = 20)
            if (fresh.isEmpty()) {
                Log.i("LexiconRepository", "No fresh garden candidates are currently available")
                return@withLock null
            }
            cache.seen(fresh.map { it.id })
            val next = personal.replaceGarden(key, current, listOf(current.entries.first()) + fresh, shown)
            prepareDaily(next.words(date))
        }
    }

    private suspend fun discoveryCandidates(language: Language, date: LocalDate, level: VocabularyLevel,
        round: Int, excluded: Set<String> = emptySet(), limit: Int = 21): List<WordEntry> {
        val dictionary = openReader(language)
        val policy = readManifest().discovery
        val candidates = (full.candidateWords(language, level, date, round).map { "${language.code}:$it" } +
            cache.candidateIds(language, level) + dictionary.daily.map { "${language.code}:${dictionary.wordAt(it)}" })
            .distinct().filter { it !in excluded }
        val levels = candidates.associateWith { ranks.level(language, it.substringAfter(':')) }
        val familiarity = personal.familiarity(candidates)
        val seed = if (round == 0) date.toString() else "$date:$round"
        val ordered = candidates.sortedWith(
            compareBy<String> { kotlin.math.abs(levels.getValue(it).ordinal - level.ordinal) }
                .thenBy { (familiarity[it]?.second ?: 0) >= 2 }
                .thenBy { familiarity[it]?.first ?: 0 }
                .thenBy { "$seed:$it".hashCode() xor 0x5f3759df },
        )
        return buildList {
            for (id in ordered) {
                currentCoroutineContext().ensureActive()
                val entry = readEntry(id) ?: continue
                val preview = discoveryPreview(entry, policy) ?: continue
                add(preview)
                if (size == limit) break
            }
        }
    }

    private suspend fun prepareDaily(daily: DailyWords): DailyWords {
        preparedDaily = (daily.grid + daily.featured).associate { word ->
            word.id to (preparedEntry(word.id) ?: readEntry(word.id)?.let { PreparedEntry(it) }
                ?: PreparedEntry(enrich(personal.snapshot(word.id) ?: word), archived = true))
        }
        return applyPreparedDetails(daily)
    }

    private fun applyPreparedDetails(daily: DailyWords): DailyWords {
        fun guide(word: WordEntry) = word.copy(pronunciationGuide = preparedEntry(word.id)?.entry?.pronunciationGuide.orEmpty())
        return daily.copy(featured = guide(daily.featured), grid = daily.grid.map(::guide),
            featuredMeaningCount = (preparedEntry(daily.featured.id)?.entry ?: daily.featured).senses.size)
    }

    suspend fun search(language: Language, plan: SearchPlan): List<WordEntry> = withContext(Dispatchers.IO) {
        val operation = currentCoroutineContext()
        mutex.withLock {
            (full.search(language, plan) + cache.search(language, plan) +
                openReader(language).search(plan) { operation.ensureActive() }).distinctBy { it.id }.take(60)
                .map(::enrich)
                .also { entries -> entries.forEach { prepared.put(it.id, PreparedEntry(it)) } }
        }
    }

    suspend fun entry(id: String): WordEntry? = withContext(Dispatchers.IO) {
        mutex.withLock { readEntry(id) }
    }

    private suspend fun readEntry(id: String): WordEntry? {
        preparedEntry(id)?.let { return if (it.archived) null else it.entry }
        val language = Language.fromCode(id.substringBefore(':'))
            ?: throw IOException("Unsupported dictionary entry language")
        val external = full.entry(language, id.substringAfter(':')) ?: cache.get(id)
        val entry = if (external != null) enrich(external)
            else openReader(language).entry(id.substringAfter(':'))?.let(pronunciationGuides::enrich)
        if (entry != null) prepared.put(id, PreparedEntry(entry))
        return entry
    }

    suspend fun resetCache() = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeReader()
            loadedManifest = null
            preparedDaily = emptyMap()
            prepared.evictAll()
            pronunciationGuides.close()
            removeLegacyCache()
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeReader()
            preparedDaily = emptyMap()
            prepared.evictAll()
            pronunciationGuides.close()
            if (cacheDelegate.isInitialized()) cache.close()
            personal.close()
        }
    }

    suspend fun lookupOnline(language: Language, word: String): Pair<WordEntry?, Boolean> = withContext(Dispatchers.IO) {
        val local = entry("${language.code}:${word.trim()}")
        if (local != null) return@withContext local to true
        val entry = api.lookup(language, word)?.let(pronunciationGuides::enrich) ?: return@withContext null to true
        val protected = personal.protectedIds(LocalDate.now().toString())
        val saved = cache.put(entry, ranks.level(language, entry.word), protected)
        prepared.put(entry.id, PreparedEntry(entry))
        if (!saved) Log.w("LexiconRepository", "Online cache is full; saved words were protected")
        entry to saved
    }

    suspend fun seen(entry: WordEntry) = withContext(Dispatchers.IO) { cache.seen(entry.id) }

    suspend fun withPronunciationGuide(entry: WordEntry): WordEntry =
        withContext(Dispatchers.IO) { mutex.withLock { enrich(entry) } }

    private fun enrich(entry: WordEntry): WordEntry =
        pronunciationGuides.enrich(entry.withUsageExamples(openReader(entry.language).entry(entry.word)))

    suspend fun answer(entry: WordEntry, correct: Boolean) {
        personal.answer(entry.id, correct)
        withContext(Dispatchers.IO) { if (correct) cache.correct(entry.id) }
    }

    suspend fun assessment(language: Language): List<WordEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val dictionary = openReader(language)
            val candidates = dictionary.daily.map { dictionary.entryAt(it) }
            VocabularyLevel.entries.flatMap { level ->
                candidates.filter { ranks.level(language, it.word) == level }.shuffled().take(2)
            }
        }
    }

    private fun closeReader() {
        input?.close()
        input = null
        reader = null
        openedLanguage = null
    }

    private fun readManifest(): PackManifest {
        loadedManifest?.let { return it }
        val manifest = context.assets.open("lexicon/manifest.json").bufferedReader().use {
            dictionaryJson.decodeFromString<PackManifest>(it.readText())
        }
        if (manifest.schemaVersion != 3 || manifest.compression != "zstd-blocks"
            || manifest.releaseVersion != BuildConfig.VERSION_NAME
            || manifest.languages.map { it.language }.toSet() != Language.entries.map { it.code }.toSet()
            || manifest.languages.size != Language.entries.size
            || manifest.discovery.excludedWords.keys != Language.entries.map { it.code }.toSet()) {
            throw IOException("Bundled dictionary manifest is incompatible with this app release")
        }
        manifest.languages.forEach { pack ->
            if (pack.edition != pack.language || pack.releaseVersion != manifest.releaseVersion
                || !pack.file.matches(Regex("${pack.language}-[a-f0-9]{16}\\.mflx"))
                || !pack.sha256.matches(Regex("[a-f0-9]{64}")) || pack.sha256 != pack.compressedSha256
                || pack.entries < 1 || pack.dailyEntries < 21 || pack.compressedBytes !in 64..536_870_912L) {
                throw IOException("Invalid offline language-pack metadata")
            }
        }
        loadedManifest = manifest
        return manifest
    }

    private fun pack(language: Language): LanguagePack =
        readManifest().languages.single { it.language == language.code }

    private fun openReader(language: Language): BlockLexicon {
        if (openedLanguage == language) return reader ?: throw IOException("Dictionary handle was closed")
        val pack = pack(language)
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        context.assets.open("lexicon/${pack.file}").use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                size += count
                if (size > pack.compressedBytes) throw IOException("Dictionary exceeds its expected size")
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (size != pack.compressedBytes || hash != pack.sha256) throw IOException("Dictionary content hash mismatch")

        val asset = context.assets.openFd("lexicon/${pack.file}")
        val offset = asset.startOffset
        val length = asset.length
        val stream = try {
            ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(asset.fileDescriptor))
        } finally { asset.close() }
        var accepted = false
        try {
            if (length != pack.compressedBytes) throw IOException("Dictionary asset length mismatch")
            val dictionary = BlockLexicon(length, { position, bytes ->
                val buffer = ByteBuffer.allocate(bytes)
                var location = offset + position
                while (buffer.hasRemaining()) {
                    val count = stream.channel.read(buffer, location)
                    if (count <= 0) throw IOException("Truncated dictionary asset")
                    location += count
                }
                buffer.array()
            }, { bytes, expected ->
                try {
                    Zstd.decompress(bytes, expected)
                } catch (error: ZstdException) {
                    throw IOException("Compressed dictionary block is corrupt", error)
                }
            })
            if (dictionary.metadata.language != language.code || dictionary.entries != pack.entries
                || dictionary.daily.size != pack.dailyEntries) throw IOException("Dictionary pack metadata mismatch")
            closeReader()
            input = stream
            reader = dictionary
            openedLanguage = language
            accepted = true
            removeLegacyCache()
            return dictionary
        } finally {
            if (!accepted) stream.close()
        }
    }

    private fun removeLegacyCache() {
        File(context.cacheDir, "lexicons").listFiles()?.filter {
            it.name.matches(Regex("[a-z]{2,3}-[a-f0-9]{64}\\.sqlite"))
        }?.forEach {
            if (!it.delete()) Log.w("LexiconRepository", "Could not remove an obsolete dictionary cache")
        }
    }
}
