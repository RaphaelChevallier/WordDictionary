package com.maxfluency.app

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import com.maxfluency.app.data.DefinitionTranslationPhase
import com.maxfluency.app.data.DefinitionTranslationProvider
import com.maxfluency.app.data.DefinitionTranslator
import com.maxfluency.app.data.TranslationModelRequired
import com.maxfluency.app.ui.EntryScreen
import com.maxfluency.app.ui.MaxFluencyTheme
import com.maxfluency.core.Language
import com.maxfluency.core.WordEntry
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w390dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DefinitionTranslationTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private val translator = ControlledDefinitionTranslator()
    private var phoneLanguage: String? = "en-US"
    private lateinit var model: MaxFluencyViewModel
    private lateinit var original: WordEntry

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        application.deleteDatabase("personal.sqlite")
        compose.runOnIdle {
            model = ViewModelProvider(store, viewModelFactory {
                initializer { MaxFluencyViewModel(application, translator, { phoneLanguage }) }
            })[MaxFluencyViewModel::class.java]
        }
        compose.setContent {
            val state by model.state.collectAsState()
            MaxFluencyTheme {
                EntryScreen(state, onFavorite = {}, onSpeak = {}, onCopy = {}, onRelated = { _, _ -> },
                    onRetry = {}, onTranslate = { model.showTranslation() },
                    onDownloadTranslation = { model.showTranslation(downloadModels = true) },
                    onHideTranslation = model::hideTranslation)
            }
        }
        await { !model.state.value.loading && model.state.value.daily != null }
        compose.runOnIdle { model.language(Language.FRENCH) }
        await { !model.state.value.loading && model.state.value.daily?.featured?.language == Language.FRENCH }
    }

    @After
    fun tearDown() {
        translator.pending?.cancel()
        compose.runOnIdle { store.clear() }
        assertTrue(translator.closed)
    }

    @Test
    fun googleTranslationRequiresAnActionAndSeparateModelConsent() {
        translator.modelsPresent = false
        openWord()
        assertEquals("en-US", model.state.value.translation?.targetTag)
        assertTrue(translator.requests.isEmpty())
        compose.onNodeWithText("Translate with Google").assertExists()
        compose.runOnIdle { model.showTranslation(downloadModels = true) }
        assertTrue("A stale download callback must not bypass confirmation", translator.requests.isEmpty())

        translateButton().performClick()
        awaitPhase(DefinitionTranslationPhase.MODEL_REQUIRED)
        assertFalse(translator.requests.single().download)
        assertOriginal()
        compose.onNodeWithText("Cancel").performClick()
        awaitPhase(DefinitionTranslationPhase.COLLAPSED)
        assertFalse(translator.modelsPresent)

        translateButton().performClick()
        awaitPhase(DefinitionTranslationPhase.MODEL_REQUIRED)
        compose.onNode(hasText("Translate with Google") and hasAnyAncestor(isDialog())).performClick()
        awaitPhase(DefinitionTranslationPhase.READY)
        assertEquals(listOf(false, false, true), translator.requests.map { it.download })
        val inputs = listOf(original.word) + original.senses.flatMap { listOf(it.definition) + it.examples }
        assertEquals(inputs, translator.requests.last().texts)
        assertEquals(translator.result(inputs.size).first(), model.state.value.translation?.content?.word)
        assertEquals(original.senses.size, model.state.value.translation?.content?.senses?.size)
        compose.onNodeWithTag("translation-provider-credit").assertExists()
        assertOriginal()

        compose.runOnIdle { model.hideTranslation(); model.showTranslation() }
        assertEquals("Reopening a cached translation must not translate or download again", 3, translator.requests.size)
        compose.runOnIdle { model.toggleFavorite(original) }
        await { original.id in model.state.value.favoriteIds }
        compose.runOnIdle { model.removeTranslationModels() }
        await { translator.removals == 1 && model.state.value.translation?.phase == DefinitionTranslationPhase.COLLAPSED }
        assertFalse(translator.modelsPresent)
        assertTrue(original.id in model.state.value.favoriteIds)
        assertOriginal()
    }

    @Test
    fun wordMeaningsAndExamplesTranslateTogetherWithTheirOriginalSenseOrder() {
        openWord()
        assertEquals("abjurer", original.word)
        assertEquals(2, original.senses.size)
        assertTrue(original.senses.first().examples.isEmpty())
        assertTrue(original.senses[1].examples.size >= 2)
        compose.onNodeWithTag("entry-screen").performScrollToNode(hasText("2 meanings"))
        compose.onNodeWithText("2 meanings").assertIsDisplayed()
        translateButton().performClick()
        awaitPhase(DefinitionTranslationPhase.READY)
        val texts = translator.requests.single().texts
        assertEquals(original.word, texts[0])
        assertEquals(original.senses[0].definition, texts[1])
        assertEquals(original.senses[1].definition, texts[2])
        assertEquals(original.senses[1].examples, texts.drop(3))
        val values = translator.result(texts.size)
        val translated = requireNotNull(model.state.value.translation?.content)
        assertEquals(values[0], translated.word)
        assertEquals(values[1], translated.senses[0].definition)
        assertTrue(translated.senses[0].examples.isEmpty())
        assertEquals(values[2], translated.senses[1].definition)
        assertEquals(values.drop(3), translated.senses[1].examples)
        compose.onNodeWithTag("entry-screen").performScrollToNode(hasTestTag("translated-headword"))
        compose.onNodeWithTag("translated-headword").assertTextEquals(values[0])
        compose.onNodeWithTag("entry-screen").performScrollToNode(hasText(values[3]))
        compose.onNode(hasText(values[3]) and hasAnyAncestor(hasTestTag("translated-sense-1"))).assertIsDisplayed()
        compose.onNode(hasText(values[3]) and hasAnyAncestor(hasTestTag("translated-sense-0"))).assertDoesNotExist()
        compose.onNodeWithTag("entry-screen").performScrollToNode(hasText(original.senses[1].examples.first()))
        compose.onNode(hasText(original.senses[1].examples.first()) and
            hasAnyAncestor(hasTestTag("original-sense-1"))).assertIsDisplayed()
        assertEquals(original, model.state.value.entry)
    }

    @Test
    fun readySystemTranslationHasNoGooglePromptOrModelDownload() {
        translator.available = DefinitionTranslationProvider.DEVICE
        translator.modelsPresent = false
        openWord()
        compose.onNodeWithText("Translate with Google").assertDoesNotExist()
        translateButton().performClick()
        awaitPhase(DefinitionTranslationPhase.READY)
        assertEquals(DefinitionTranslationProvider.DEVICE, translator.requests.single().provider)
        assertFalse(translator.requests.single().download)
        assertFalse(translator.modelsPresent)
        compose.onNodeWithTag("translation-provider-credit").assertDoesNotExist()
        assertOriginal()
    }

    @Test
    fun failedSystemTranslationRequiresAnAttributedRetryBeforeGoogle() {
        translator.available = DefinitionTranslationProvider.DEVICE
        translator.failSystem = true
        openWord()
        translateButton().performClick()
        awaitPhase(DefinitionTranslationPhase.FAILED)
        assertOriginal()
        assertNull(model.state.value.error)
        assertEquals(1, translator.requests.size)
        translateButton().performClick()
        awaitPhase(DefinitionTranslationPhase.COLLAPSED)
        assertEquals("Rechecking availability must not silently invoke Google", 1, translator.requests.size)
        compose.onNodeWithText("Translate with Google").assertExists()
        translateButton().performClick()
        awaitPhase(DefinitionTranslationPhase.READY)
        assertEquals(listOf(DefinitionTranslationProvider.DEVICE, DefinitionTranslationProvider.GOOGLE),
            translator.requests.map { it.provider })
        assertOriginal()
    }

    @Test
    fun lateResultsCannotAttachToAnotherWordOrPhoneLanguage() {
        openWord()
        translator.ignoreCancellation = true
        val first = original
        val firstResult = CompletableDeferred<List<String>>()
        translator.pending = firstResult
        compose.runOnIdle { model.showTranslation() }
        await { translator.requests.size == 1 }
        val secondId = requireNotNull(model.state.value.daily).grid.first { it.id != first.id }.id
        compose.runOnIdle { model.openEntry(secondId) }
        awaitPhase(DefinitionTranslationPhase.COLLAPSED)
        firstResult.complete(translator.result(translator.requests[0].texts.size))
        await { translator.returned == 1 }
        assertEquals(secondId, model.state.value.entry?.id)
        assertNull(model.state.value.translation?.content)

        val second = requireNotNull(model.state.value.entry)
        val secondResult = CompletableDeferred<List<String>>()
        translator.pending = secondResult
        compose.runOnIdle { model.showTranslation() }
        await { translator.requests.size == 2 }
        compose.runOnIdle { phoneLanguage = "es-ES"; model.refreshSystemLanguage() }
        awaitPhase(DefinitionTranslationPhase.COLLAPSED)
        secondResult.complete(translator.result(translator.requests[1].texts.size))
        await { translator.returned == 2 }
        assertEquals("es-ES", model.state.value.translation?.targetTag)
        assertNull(model.state.value.translation?.content)
        assertEquals(second, model.state.value.entry)
    }

    @Test
    fun cancellingTranslationDoesNotTurnItIntoAnError() {
        openWord()
        translator.pending = CompletableDeferred()
        compose.runOnIdle { model.showTranslation() }
        await { translator.requests.size == 1 }
        compose.runOnIdle { model.hideTranslation() }
        await { translator.cancelled == 1 }
        assertEquals(DefinitionTranslationPhase.COLLAPSED, model.state.value.translation?.phase)
        assertFalse(ShadowLog.getLogsForTag("DefinitionTranslation").any { it.throwable is CancellationException })
        assertOriginal()
    }

    @Test
    fun samePhoneLanguageHidesActionAndUnsupportedLanguageNeverDefaultsToEnglish() {
        compose.runOnIdle { phoneLanguage = "fr-CA"; model.refreshSystemLanguage() }
        openWord(expectTranslation = false)
        assertTrue(translator.probes.isEmpty())
        compose.onNodeWithTag("show-translated-definition").assertDoesNotExist()
        compose.runOnIdle {
            translator.available = null
            phoneLanguage = "zz"
            model.refreshSystemLanguage()
        }
        awaitPhase(DefinitionTranslationPhase.UNSUPPORTED)
        assertEquals("zz", model.state.value.translation?.targetTag)
        assertTrue(translator.requests.isEmpty())
        assertOriginal()
    }

    @Test
    fun incompleteTranslationFailsInlineWithoutChangingTheDictionaryEntry() {
        translator.invalidResult = true
        openWord()
        translateButton().performClick()
        awaitPhase(DefinitionTranslationPhase.FAILED)
        assertNull(model.state.value.error)
        assertNull(model.state.value.translation?.content)
        assertOriginal()
    }

    private fun openWord(expectTranslation: Boolean = true) {
        compose.runOnIdle { model.query("abjurer") }
        await { !model.state.value.searching && model.state.value.matches.any { it.word == "abjurer" } }
        val id = model.state.value.matches.first { it.word == "abjurer" }.id
        compose.runOnIdle { model.openEntry(id) }
        await { model.state.value.entry?.id == id && !model.state.value.entryLoading }
        original = requireNotNull(model.state.value.entry)
        if (expectTranslation) awaitPhase(DefinitionTranslationPhase.COLLAPSED)
        else compose.waitForIdle()
    }

    private fun assertOriginal() {
        assertEquals(original, model.state.value.entry)
        compose.onNodeWithTag("entry-screen").performScrollToNode(hasText(original.senses.first().definition))
        compose.onNodeWithText(original.senses.first().definition).assertIsDisplayed()
    }

    private fun translateButton(): SemanticsNodeInteraction {
        compose.onNodeWithTag("entry-screen").performScrollToNode(hasTestTag("show-translated-definition"))
        return compose.onNodeWithTag("show-translated-definition")
    }

    private fun awaitPhase(phase: DefinitionTranslationPhase) = await { model.state.value.translation?.phase == phase }
    private fun await(predicate: () -> Boolean) = compose.waitUntil(30_000) {
        compose.waitForIdle()
        predicate()
    }
}

private class ControlledDefinitionTranslator : DefinitionTranslator {
    data class Request(val source: String, val target: String, val texts: List<String>,
        val provider: DefinitionTranslationProvider, val download: Boolean)
    var available: DefinitionTranslationProvider? = DefinitionTranslationProvider.GOOGLE
    var modelsPresent = true
    var failSystem = false
    var invalidResult = false
    var pending: CompletableDeferred<List<String>>? = null
    var ignoreCancellation = false
    var cancelled = 0
    var returned = 0
    var removals = 0
    var closed = false
    val probes = mutableListOf<Pair<String, String>>()
    val requests = mutableListOf<Request>()

    override suspend fun provider(source: String, targetTag: String): DefinitionTranslationProvider? {
        probes += source to targetTag
        return available
    }

    override suspend fun translate(source: String, targetTag: String, texts: List<String>,
        provider: DefinitionTranslationProvider, downloadModels: Boolean): List<String> {
        requests += Request(source, targetTag, texts.toList(), provider, downloadModels)
        if (provider == DefinitionTranslationProvider.DEVICE && failSystem) {
            available = DefinitionTranslationProvider.GOOGLE
            throw IOException("The test system translator is unavailable")
        }
        if (provider == DefinitionTranslationProvider.GOOGLE) {
            if (downloadModels) modelsPresent = true
            if (!modelsPresent) throw TranslationModelRequired()
        }
        val deferred = pending
        val values = if (deferred != null) {
            try {
                if (ignoreCancellation) withContext(NonCancellable) { deferred.await() } else deferred.await()
            } catch (error: CancellationException) {
                cancelled++
                throw error
            }
        } else if (invalidResult) result(texts.size).dropLast(1) else result(texts.size)
        returned++
        return values
    }

    fun result(count: Int): List<String> = List(count) { "Test English definition ${it + 1}." }
    override suspend fun removeDownloadedModels() { modelsPresent = false; removals++ }
    override fun close() { closed = true }
}
