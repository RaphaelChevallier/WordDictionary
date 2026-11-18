package com.maxfluency.app

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.maxfluency.app.ui.MaxFluencyTheme
import com.maxfluency.app.ui.TodayScreen
import com.maxfluency.core.DailyWords
import com.maxfluency.core.Language
import com.maxfluency.core.Sense
import com.maxfluency.core.WordEntry
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w390dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GardenScreenTest {
    @get:Rule val compose = createComposeRule()

    private val words = listOf("curiosity", "clarity", "wonder", "resilience", "tranquil", "luminous",
        "kindness", "wander", "solace", "flourish", "serene", "courage", "delight", "harmony",
        "gentle", "vivid", "insight", "patient", "discover", "thoughtful", "bloom").map {
        WordEntry("en:$it", Language.ENGLISH, it, "", listOf(Sense("noun", "A test meaning.")), emptyList(), "en")
    }

    @Test
    fun homeHasNoDownloadControls() {
        val state = AppUiState(loading = false,
            daily = DailyWords(LocalDate.of(2026, 11, 13), words.first(), words.drop(1)),
            packs = mapOf(Language.ENGLISH to DownloadUi(active = true, percent = 42)))
        compose.setContent {
            MaxFluencyTheme {
                Surface {
                    TodayScreen(state, {}, {}, {}, {}, {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithTag("daily-grid").assertIsDisplayed()
        compose.onNodeWithText("Cancel download").assertDoesNotExist()
        compose.onNodeWithText("Download full language dictionary").assertDoesNotExist()
        compose.onNodeWithText("Offline when you need it. Online when you choose. No ads.").assertDoesNotExist()
    }

    @Test
    fun searchExpandsAndKeepsLanguageChoices() {
        var state by mutableStateOf(AppUiState(loading = false,
            daily = DailyWords(LocalDate.of(2026, 11, 13), words.first(), words.drop(1))))
        lateinit var focus: FocusManager
        compose.setContent {
            focus = LocalFocusManager.current
            MaxFluencyTheme {
                Surface {
                    TodayScreen(state, onWord = {}, onQuery = { state = state.copy(query = it) },
                        onLanguage = { state = state.copy(settings = state.settings.copy(language = it)) },
                        onMode = {}, onRetry = {}, onOnlineSearch = {}, onRefresh = {}, onSpeak = {})
                }
            }
        }
        val initialSearch = compose.onNodeWithTag("search-field").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("search-field").performClick()
        compose.waitForIdle()
        val compactLanguage = compose.onNodeWithText("English").getUnclippedBoundsInRoot()
        val expandedSearch = compose.onNodeWithTag("search-field").getUnclippedBoundsInRoot()
        val languageWidth = compactLanguage.right - compactLanguage.left
        val searchWidth = expandedSearch.right - expandedSearch.left
        assertTrue("The focused language control should be small", languageWidth <= 72.dp)
        assertTrue("Search should gain useful typing space",
            searchWidth > initialSearch.right - initialSearch.left + 40.dp)
        assertTrue("Search should occupy most of the header", searchWidth >= (searchWidth + languageWidth) * 0.75f)

        compose.onNodeWithTag("search-field").performTextInput("cat")
        compose.onNodeWithText("English").performClick()
        compose.onNodeWithText("Français").assertIsDisplayed().performClick()
        compose.onNodeWithText("Français").assertIsDisplayed()
        assertEquals(Language.FRENCH, state.settings.language)
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.runOnIdle { focus.clearFocus() }
        compose.waitForIdle()
        val restoredLanguage = compose.onNodeWithText("Français").getUnclippedBoundsInRoot()
        assertTrue("The full language name should return when search ends",
            restoredLanguage.right - restoredLanguage.left > languageWidth + 20.dp)
    }

    @Test
    fun onlyACompletedNewWordsRoundAnimatesTheGarden() {
        var state by mutableStateOf(AppUiState(loading = false,
            daily = DailyWords(LocalDate.of(2026, 11, 13), words.first(), words.drop(1))))
        compose.setContent {
            MaxFluencyTheme {
                Surface {
                    TodayScreen(state, onWord = { id ->
                        val daily = requireNotNull(state.daily)
                        state = state.copy(daily = daily.copy(explored = daily.explored + id))
                    }, onQuery = {}, onLanguage = {}, onMode = {}, onRetry = {}, onOnlineSearch = {},
                        onRefresh = { state = state.copy(refreshingGarden = true) }, onSpeak = {})
                }
            }
        }
        var wordTag = "garden-word-en:clarity"
        fun wordTop() = compose.onNodeWithTag(wordTag).getUnclippedBoundsInRoot().top
        val restingTop = wordTop()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("garden-word-en:clarity").performClick()
            compose.mainClock.advanceTimeByFrame()
            assertEquals("Opening a word must not animate the garden", restingTop,
                wordTop())
            compose.onNodeWithTag("refresh-garden").performClick()
            compose.mainClock.advanceTimeByFrame()
            assertEquals("Keep existing words steady while a replacement loads", restingTop,
                wordTop())
            compose.runOnIdle {
                val daily = requireNotNull(state.daily)
                state = state.copy(refreshingGarden = false, daily = daily.copy(round = daily.round + 1,
                    grid = daily.grid.map { it.copy(id = "en:new-${it.word}") }, explored = emptySet()))
            }
            wordTag = "garden-word-en:new-clarity"
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithTag("daily-grid").assertCountEquals(1)
            val enteringTop = wordTop()
            assertTrue("New words should enter with a small movement: $enteringTop vs $restingTop", enteringTop > restingTop)
            compose.mainClock.advanceTimeBy(96)
            val middleTop = wordTop()
            assertTrue("The refresh movement should settle smoothly", middleTop < enteringTop && middleTop > restingTop)
            compose.onNodeWithTag(wordTag).performClick()
            compose.mainClock.advanceTimeByFrame()
            assertTrue("Fresh words stay tappable while settling", "en:new-clarity" in requireNotNull(state.daily).explored)
            assertTrue("Reading a fresh word must not restart its entrance", wordTop() < middleTop)
            compose.mainClock.advanceTimeBy(240)
            assertEquals(restingTop, wordTop())
            compose.runOnIdle {
                state = state.copy(daily = requireNotNull(state.daily).copy(date = LocalDate.of(2026, 11, 14), round = 0))
            }
            compose.mainClock.advanceTimeByFrame()
            assertEquals("A different day must not replay a manual refresh animation", restingTop,
                wordTop())
            compose.runOnIdle { state = state.copy(loading = true) }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                state = state.copy(loading = false, daily = requireNotNull(state.daily).copy(round = 4))
            }
            compose.mainClock.advanceTimeByFrame()
            assertEquals("Loading another saved selection must not act like New words", restingTop, wordTop())
            compose.onNodeWithTag("refresh-garden").performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { state = state.copy(refreshingGarden = false) }
            compose.mainClock.advanceTimeByFrame()
            assertEquals("An unsuccessful refresh must leave the garden steady", restingTop, wordTop())
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test
    fun newWordsAreAlwaysOptional() {
        var state by mutableStateOf(AppUiState(loading = false,
            daily = DailyWords(LocalDate.of(2026, 11, 13), words.first(), words.drop(1))))
        var requested: Set<String>? = null
        compose.setContent {
            MaxFluencyTheme {
                Surface {
                    TodayScreen(state, onWord = { id ->
                        val daily = requireNotNull(state.daily)
                        state = state.copy(daily = daily.copy(explored = daily.explored + id))
                    }, onQuery = {}, onLanguage = {}, onMode = {}, onRetry = {}, onOnlineSearch = {},
                        onRefresh = { requested = it; state = state.copy(refreshingGarden = true) }, onSpeak = {})
                }
            }
        }
        val ids = compose.onNodeWithTag("daily-grid").onChildren().fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.TestTag].removePrefix("garden-word-") }.toSet()
        assertTrue(ids.size in 8..20)
        compose.onNodeWithTag("refresh-garden").assertIsDisplayed().assertIsEnabled()
        val initialText = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("clarity", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(initialText) }
        ids.forEach { id ->
            compose.onNodeWithTag("garden-word-$id").performClick()
        }
        assertNull("Opening words must never request a replacement set", requested)
        assertEquals(ids, compose.onNodeWithTag("daily-grid").onChildren().fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.TestTag].removePrefix("garden-word-") }.toSet())
        val reviewedText = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("clarity", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(reviewedText) }
        assertEquals("Words should remain at normal contrast for repeat review",
            initialText.single().layoutInput.style.color, reviewedText.single().layoutInput.style.color)
        compose.onNodeWithTag("refresh-garden").assertIsDisplayed().performClick()
        assertEquals(ids, requested)
        compose.onNodeWithTag("refresh-garden").assertIsNotEnabled()
        compose.runOnIdle {
            val daily = requireNotNull(state.daily)
            state = state.copy(refreshingGarden = false,
                daily = daily.copy(round = 1, explored = emptySet(), grid = daily.grid.map {
                    it.copy(id = "en:new-${it.word}", word = "new-${it.word}")
                }))
        }
        compose.onNodeWithTag("refresh-garden").assertIsDisplayed().assertIsEnabled()
    }
}
