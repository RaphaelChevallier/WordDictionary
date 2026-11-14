package com.maxfluency.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import com.maxfluency.app.data.LexiconRepository
import com.maxfluency.core.Language
import com.maxfluency.core.Sense
import com.maxfluency.core.WordEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoadingFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun startupWaitsForPreparedWords() {
        compose.runOnIdle {
            val activity = Robolectric.buildActivity(MainActivity::class.java).create()
            try {
                val model = ViewModelProvider(activity.get())[MaxFluencyViewModel::class.java]
                assertTrue(model.state.value.loading)
                assertTrue("Keep the launch screen until words are ready",
                    activity.get().window.decorView.viewTreeObserver.dispatchOnPreDraw())
            } finally { activity.destroy() }
        }
    }

    @Test
    fun gardenTapShowsDefinitionWithin100ms() {
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("daily-grid").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("daily-grid").onChildren()[0].performClick()
            compose.mainClock.advanceTimeBy(96)
            compose.onNodeWithTag("entry-screen").assertIsDisplayed()
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test
    fun gardenDefinitionsOpenBeforeReturning() {
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("daily-grid").fetchSemanticsNodes().isNotEmpty() }
        val model = ViewModelProvider(compose.activity)[MaxFluencyViewModel::class.java]
        val daily = requireNotNull(model.state.value.daily)
        val words = listOf(daily.featured) + daily.grid
        val expected = runBlocking {
            val lexicon = LexiconRepository(compose.activity)
            try { words.associate { it.id to requireNotNull(lexicon.entry(it.id)) } }
            finally { lexicon.close() }
        }
        words.forEach { word ->
            compose.runOnIdle {
                model.openEntry(word.id)
                assertFalse("A prepared definition must not enter a loading state", model.state.value.entryLoading)
                assertEquals("Open the full entry, not its discovery preview", expected[word.id], model.state.value.entry)
            }
        }
        compose.waitUntil(30_000) {
            compose.waitForIdle()
            model.state.value.saved.map { it.entry.id }.containsAll(words.map { it.id }) &&
                model.state.value.daily?.explored.orEmpty().containsAll(daily.grid.map { it.id })
        }
        assertEquals(words.last().id, model.state.value.entry?.id)
    }

    @Test
    fun searchAndSavedDefinitionsOpenBeforeReturning() {
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("daily-grid").fetchSemanticsNodes().isNotEmpty() }
        val model = ViewModelProvider(compose.activity)[MaxFluencyViewModel::class.java]
        compose.runOnIdle { model.query("cat") }
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            !model.state.value.searching && model.state.value.matches.isNotEmpty()
        }
        val match = model.state.value.matches.first()
        compose.runOnIdle {
            model.openEntry(match.id)
            assertFalse(model.state.value.entryLoading)
            assertEquals(match, model.state.value.entry)
        }
        val archived = WordEntry("en:__retired_loading_test__", Language.ENGLISH, "Retained word", "",
            listOf(Sense("noun", "A complete saved definition from an older dictionary.")), emptyList(), "en")
        compose.runOnIdle { model.toggleFavorite(archived) }
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            archived.id in model.state.value.favoriteIds
        }
        compose.runOnIdle {
            model.openEntry(archived.id)
            assertFalse("Saved definitions must open without waiting for dictionary validation", model.state.value.entryLoading)
            assertEquals(archived, model.state.value.entry)
        }
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            model.state.value.archivedEntry
        }
        assertTrue(archived.id in model.state.value.favoriteIds)
    }
}
