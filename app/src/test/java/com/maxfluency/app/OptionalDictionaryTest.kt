package com.maxfluency.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.maxfluency.app.data.*
import com.maxfluency.core.*
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class OptionalDictionaryTest {
    @Test
    fun oldSavedWordsMigrate() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("personal.sqlite")
        val file = context.getDatabasePath("personal.sqlite")
        file.parentFile!!.mkdirs()
        val entry = WordEntry("en:legacy", Language.ENGLISH, "legacy", "",
            listOf(Sense("noun", "A preserved saved definition.")), emptyList(), "en")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE words(id TEXT PRIMARY KEY NOT NULL,snapshot TEXT NOT NULL,favorite INTEGER NOT NULL DEFAULT 0,last_viewed INTEGER NOT NULL)")
            db.execSQL("INSERT INTO words VALUES (?,?,1,123)", arrayOf(entry.id, dictionaryJson.encodeToString(entry)))
            db.version = 1
        }
        val store = PersonalStore(context)
        try {
            assertEquals(entry, store.words().single().entry)
            assertTrue(store.words().single().favorite)
            assertEquals(1, store.familiarity(listOf(entry.id))[entry.id]!!.first)
            store.answer(entry.id, true)
            assertEquals(1, store.familiarity(listOf(entry.id))[entry.id]!!.second)
        } finally { store.close(); context.deleteDatabase("personal.sqlite") }
    }

    @Test
    fun cacheEviction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("online-words.sqlite")
        val cache = OnlineWordCache(context, 3)
        fun word(language: Language, text: String) = WordEntry("${language.code}:$text", language, text, "",
            listOf(Sense("noun", "A test definition.")), emptyList(), language.code)
        val a = word(Language.ENGLISH, "alpha")
        val b = word(Language.FRENCH, "bravo")
        val c = word(Language.SPANISH, "charlie")
        val d = word(Language.ENGLISH, "delta")
        try {
            listOf(a, b, c).forEach { assertTrue(cache.put(it, VocabularyLevel.INTERMEDIATE, emptySet())) }
            cache.seen(b.id); cache.seen(b.id); cache.correct(b.id)
            assertTrue(cache.put(d, VocabularyLevel.INTERMEDIATE, setOf(a.id)))
            assertNull(cache.get(b.id))
            assertNotNull(cache.get(a.id))
            assertFalse(cache.put(word(Language.FRENCH, "extra"), VocabularyLevel.INTERMEDIATE, setOf(a.id, c.id, d.id)))
            assertNotNull(cache.get(c.id))
            assertNotNull(cache.get(d.id))
        } finally { cache.close(); context.deleteDatabase("online-words.sqlite") }
    }

    @Test
    fun publicNativeApis() = runBlocking {
        Assume.assumeTrue(System.getProperty("maxfluency.publicDictionaryIntegration") == "true")
        val api = PublicDictionaryApi()
        for ((language, word) in listOf(Language.ENGLISH to "cat", Language.FRENCH to "chat", Language.SPANISH to "gato")) {
            val entry = api.lookup(language, word)
            assertNotNull("Public ${language.code} definitions", entry)
            assertTrue(entry!!.senses.any { it.partOfSpeech == "noun" })
            assertTrue("Public ${language.code} pronunciation", entry.pronunciation.isNotBlank())
            assertFalse(entry.senses.any { it.definition.contains("quoted copyrighted") })
        }
    }

    @Test
    fun exactCacheBoundary() {
        Assume.assumeTrue(System.getProperty("maxfluency.publicDictionaryIntegration") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manifest = context.assets.open("lexicon/manifest.json").bufferedReader().use {
            dictionaryJson.decodeFromString<PackManifest>(it.readText())
        }
        val starterWords = manifest.languages.sumOf { it.entries }
        val capacity = 500_000 - starterWords
        context.deleteDatabase("online-words.sqlite")
        val cache = OnlineWordCache(context, capacity)
        val seed = WordEntry("en:seed", Language.ENGLISH, "seed", "",
            listOf(Sense("noun", "A test definition.")), emptyList(), "en")
        try {
            cache.writableDatabase.execSQL("""
                WITH RECURSIVE n(i) AS (SELECT 0 UNION ALL SELECT i+1 FROM n WHERE i<?)
                INSERT INTO cache(id,payload,language,word,norm,anagram,level,last_access)
                  SELECT 'en:row'||i,replace(?,'seed','row'||i),'en','row'||i,'row'||i,'orw',1,0 FROM n
            """.trimIndent(), arrayOf<Any>(capacity - 1, dictionaryJson.encodeToString(seed)))
            assertTrue(cache.put(seed, VocabularyLevel.INTERMEDIATE, emptySet()))
            val actual = cache.readableDatabase.rawQuery("SELECT count(*) FROM cache", null).use {
                it.moveToFirst(); it.getInt(0)
            }
            assertEquals(500_000, actual + starterWords)
            assertEquals(seed, cache.get(seed.id))
        } finally { cache.close(); context.deleteDatabase("online-words.sqlite") }
    }

    @Test
    fun fullFrenchDictionaryRetainsAllMeaningsAndBundledExamples() = runBlocking {
        Assume.assumeTrue(System.getProperty("maxfluency.publicDictionaryIntegration") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val lexicon = LexiconRepository(context)
        val store = FullDictionaryStore.get(context)
        val manifest = lexicon.manifest()
        val zip = File(File(File(System.getProperty("maxfluency.projectDir"), ".cache"), "public-dictionaries"), "fr.zip")
        assertTrue("This opt-in test needs the cached public French archive", zip.isFile)
        try {
            val sha = MessageDigest.getInstance("SHA-256").digest(zip.readBytes()).joinToString("") { "%02x".format(it) }
            store.install(Language.FRENCH, zip, sha, manifest.discovery, {}, {})
            val raw = requireNotNull(store.entry(Language.FRENCH, "abjurer"))
            assertEquals(2, raw.senses.size)
            assertTrue(raw.senses.all { it.examples.isEmpty() })
            val enriched = requireNotNull(lexicon.entry("fr:abjurer"))
            assertEquals(raw.senses.map { it.definition }, enriched.senses.map { it.definition })
            assertEquals(2, enriched.senses[1].examples.size)
            assertTrue(enriched.senses[0].examples.isEmpty())
            val matches = lexicon.search(Language.FRENCH, SearchPlan.Prefix("abjurer")).filter { it.word == "abjurer" }
            assertEquals(1, matches.size)
            assertEquals(enriched, matches.single())
            assertTrue(enriched.sourceLinks.any { it.second == "https://fr.wiktionary.org/wiki/abjurer" })
        } finally { lexicon.close(); store.remove(Language.FRENCH); store.close() }
    }

    @Test
    fun fullPackPreparesVisibleDefinitions() = runBlocking {
        Assume.assumeTrue(System.getProperty("maxfluency.publicDictionaryIntegration") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("personal.sqlite")
        val store = FullDictionaryStore.get(context)
        val lexicon = LexiconRepository(context)
        val language = Language.SPANISH
        val manifest = lexicon.manifest()
        val zip = File(File(File(System.getProperty("maxfluency.projectDir"), ".cache"), "public-dictionaries"), "es.zip")
        assertTrue("This opt-in test needs the cached public Spanish archive", zip.isFile)
        try {
            val sha = MessageDigest.getInstance("SHA-256").digest(zip.readBytes()).joinToString("") { "%02x".format(it) }
            store.install(language, zip, sha, manifest.discovery, {}, {})
            assertNull("This spelling was verified absent from the source archive", store.entry(language, "aeromozo"))
            val added = requireNotNull(lexicon.entry("es:aeromozo"))
            assertTrue(added.senses.any { it.definition == "Persona que atiende a los pasajeros en un avión." })
            assertEquals(1, lexicon.search(language, SearchPlan.Prefix("aeromozo")).count { it.word == "aeromozo" })
            val date = java.time.LocalDate.of(2026, 11, 13)
            val started = System.nanoTime()
            val daily = lexicon.daily(language, date)
            println("FULL_PACK_PREPARE es daily_ms=${(System.nanoTime() - started) / 1_000_000}")
            val words = daily.grid + daily.featured
            assertEquals(21, words.size)
            var downloadedDefinitions = 0
            words.forEach { word ->
                val prepared = requireNotNull(lexicon.preparedEntry(word.id))
                assertFalse(prepared.archived)
                val downloaded = store.entry(language, word.word)
                if (downloaded != null) {
                    assertEquals("Examples must not replace or reorder the full dictionary's meanings",
                        downloaded.senses.map { it.partOfSpeech to it.definition },
                        prepared.entry.senses.map { it.partOfSpeech to it.definition })
                    assertEquals(downloaded, prepared.entry.copy(senses = downloaded.senses, sources = downloaded.sources))
                    assertTrue(prepared.entry.sources.containsAll(downloaded.sources))
                    downloadedDefinitions++
                }
            }
            assertTrue("Preparation must include full downloaded definitions", downloadedDefinitions > 0)
            val refreshed = requireNotNull(lexicon.refreshGarden(language, date, VocabularyLevel.INTERMEDIATE,
                daily.round, daily.grid.take(15).map { it.id }.toSet()))
            assertEquals(daily.featured, refreshed.featured)
            (refreshed.grid + refreshed.featured).forEach {
                assertNotNull("New garden definitions must also be ready before publication", lexicon.preparedEntry(it.id))
            }
        } finally {
            lexicon.close()
            store.remove(language)
            store.close()
            context.deleteDatabase("personal.sqlite")
        }
    }

    @Test
    fun fullPacksWorkOffline() = runBlocking {
        Assume.assumeTrue(System.getProperty("maxfluency.publicDictionaryIntegration") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = FullDictionaryStore.get(context)
        val personal = PersonalStore(context)
        val manifest = context.assets.open("lexicon/manifest.json").bufferedReader().use {
            dictionaryJson.decodeFromString<PackManifest>(it.readText())
        }
        try {
            for ((language, word) in listOf(Language.ENGLISH to "cat", Language.FRENCH to "école", Language.SPANISH to "mañana")) {
                val zip = File(File(File(System.getProperty("maxfluency.projectDir"), ".cache"), "public-dictionaries"), "${language.code}.zip")
                assertTrue("The explicit full-source test needs $zip", zip.isFile)
                val sha = MessageDigest.getInstance("SHA-256")
                zip.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) { val read = input.read(buffer); if (read < 0) break; sha.update(buffer, 0, read) }
                }
                val info = store.install(language, zip, sha.digest().joinToString("") { "%02x".format(it) },
                    manifest.discovery, {}, {})
                println("Verified public ${language.code} offline dictionary: ${info.entries} words/variants, ${info.headwords} headwords")
                assertTrue("The full dictionary must not stop at 500000 words", info.entries > 500_000)
                val date = java.time.LocalDate.of(2026, 11, 13)
                val initial = store.candidates(language, VocabularyLevel.BEGINNER, date)
                val rotated = store.candidates(language, VocabularyLevel.BEGINNER, date, round = 1)
                assertTrue(initial.isNotEmpty())
                assertTrue(rotated.isNotEmpty())
                assertNotEquals("Full-dictionary candidates should rotate for a new garden",
                    initial.map { it.id }.toSet(), rotated.map { it.id }.toSet())
                val entry = store.entry(language, word)
                assertNotNull("Offline public dictionary lookup: ${language.code}:$word", entry)
                assertTrue(entry!!.senses.isNotEmpty())
                assertTrue("Offline ${language.code} pronunciation", entry.pronunciation.isNotBlank())
                if (language == Language.ENGLISH) {
                    assertEquals("", store.entry(language, "cats")!!.pronunciation)
                }
                personal.recordViewed(entry)
                personal.setFavorite(entry, true)
                if (language == Language.FRENCH) {
                    assertTrue(store.search(language, SearchPlan.Prefix("ecole")).any { it.word == "école" })
                }
                store.remove(language)
                assertNull(store.info(language))
                assertEquals(entry, personal.snapshot(entry.id))
                assertTrue(personal.words().any { it.entry.id == entry.id && it.favorite })
            }
        } finally { store.close(); personal.close() }
    }
}
