package com.maxfluency.app

import android.content.ClipboardManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import com.maxfluency.app.data.VocabularyLevel
import com.maxfluency.app.data.SettingsStore
import com.maxfluency.app.data.HomeView
import com.maxfluency.core.Language
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Assert.assertTrue
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiscoveryFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun gardenWordCanBeOpenedSavedAndFoundInTheLibrary() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("daily-grid").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("word-of-day").assertDoesNotExist()
        compose.onNodeWithText("Download full language dictionary").assertDoesNotExist()
        compose.onNodeWithText("Offline when you need it. Online when you choose. No ads.").assertDoesNotExist()
        compose.onNodeWithTag("today-screen").performScrollToNode(hasTestTag("daily-grid"))
        assertTrue(compose.onNodeWithTag("daily-grid").onChildren().fetchSemanticsNodes().size in 8..20)
        compose.onNodeWithTag("daily-grid").onChildren()[0].performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("entry-screen").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Save word").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Unsave word").fetchSemanticsNodes().isNotEmpty()
        }
        val entry = requireNotNull(ViewModelProvider(compose.activity)[MaxFluencyViewModel::class.java].state.value.entry)
        val word = entry.word
        compose.onNodeWithText("Copy").performClick()
        val clipboard = checkNotNull(compose.activity.getSystemService(ClipboardManager::class.java))
        val copied = checkNotNull(checkNotNull(clipboard.primaryClip).getItemAt(0).text).toString()
        entry.sourceLinks.forEach { assertTrue(copied.contains(it.second)) }
        assertTrue(copied.contains("https://creativecommons.org/licenses/by-sa/4.0/"))
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Library").performClick()
        compose.onNodeWithText(word).assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithTag("settings-screen").performScrollToIndex(6)
        compose.onNodeWithText("500,000", substring = true).assertDoesNotExist()
        compose.onNodeWithText("additional free storage", substring = true).assertDoesNotExist()
        compose.onAllNodesWithText("Download full language dictionary")[0].assertIsDisplayed().performClick()
        compose.onNodeWithText("Download the English dictionary?").assertIsDisplayed()
        compose.onNodeWithText("additional free storage", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasText("Offline data & licenses"))
        compose.onNodeWithText("Offline data & licenses").performClick()
        compose.onNodeWithTag("credits-screen").assertIsDisplayed()
        compose.onNodeWithText("Adapted from Wiktionary contributors", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("word-sources-${entry.id}").assertIsDisplayed()
    }

    @Test
    fun gardenRefreshIsAlwaysManual() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("daily-grid").fetchSemanticsNodes().isNotEmpty()
        }
        val ids = compose.onNodeWithTag("daily-grid").onChildren().fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.TestTag].removePrefix("garden-word-") }.toSet()
        compose.onNodeWithTag("refresh-garden").assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(10_000) {
            val fresh = compose.onNodeWithTag("daily-grid").onChildren().fetchSemanticsNodes()
                .map { it.config[SemanticsProperties.TestTag].removePrefix("garden-word-") }.toSet()
            fresh.isNotEmpty() && fresh.none { it in ids }
        }
        compose.onNodeWithTag("refresh-garden").assertIsDisplayed().assertIsEnabled()
        val fresh = compose.onNodeWithTag("daily-grid").onChildren().fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.TestTag].removePrefix("garden-word-") }.toSet()
        compose.onNodeWithTag("garden-word-${fresh.first()}").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("entry-screen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("daily-grid").fetchSemanticsNodes().isNotEmpty() }
        org.junit.Assert.assertEquals("Reviewing a definition must keep the same garden", fresh,
            compose.onNodeWithTag("daily-grid").onChildren().fetchSemanticsNodes()
                .map { it.config[SemanticsProperties.TestTag].removePrefix("garden-word-") }.toSet())
        compose.onNodeWithTag("refresh-garden").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun searchToolsAreSecondary() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("daily-grid").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Anagrams").assertDoesNotExist()
        compose.onNodeWithTag("word-of-day").assertDoesNotExist()
        compose.onNodeWithTag("daily-grid").assertIsDisplayed()
        val grid = compose.onNodeWithTag("daily-grid").fetchSemanticsNode().boundsInRoot
        val navigation = compose.onNodeWithTag("bottom-navigation").fetchSemanticsNode().boundsInRoot
        assertTrue("Daily words should be visible above the bottom navigation", grid.top < navigation.top)
        val language = compose.onNodeWithText("English").fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithTag("search-field").fetchSemanticsNode().boundsInRoot
        assertTrue("Search should share the language selector's row", abs(language.center.y - search.center.y) <= 1f)
        assertTrue("Search should stay compact", search.height <= with(compose.density) { 48.dp.toPx() } + 1f)

        compose.onNodeWithTag("search-field").performClick()
        compose.onNodeWithText("Anagrams").assertIsDisplayed()
        compose.onNodeWithText("Anagrams").performClick()
        compose.onNodeWithTag("search-field").performTextInput("act")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("word-row-en:cat").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("word-row-en:cat").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("daily-grid").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("daily-grid").assertIsDisplayed()
    }

    @Test
    fun vocabularyCheckStaysPerLanguage() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("daily-grid").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasText("Quick vocabulary check"))
        compose.onNodeWithText("Quick vocabulary check").performClick()
        compose.onNodeWithTag("assessment-screen").assertIsDisplayed()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Question 1 of 6").fetchSemanticsNodes().isNotEmpty()
        }
        repeat(6) { index ->
            compose.onNodeWithText("Question ${index + 1} of 6").assertIsDisplayed()
            compose.onNodeWithTag("assessment-screen").performScrollToNode(hasText("I do not know"))
            compose.onNodeWithText("I do not know").performClick()
            if (index < 5) compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Question ${index + 2} of 6").fetchSemanticsNodes().isNotEmpty()
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("You answered 0 of 6 correctly.").fetchSemanticsNodes().isNotEmpty()
        }
        val settings = runBlocking { SettingsStore(compose.activity).settings.first() }
        org.junit.Assert.assertEquals(VocabularyLevel.BEGINNER, settings.levels[Language.ENGLISH])
        org.junit.Assert.assertEquals(VocabularyLevel.INTERMEDIATE, settings.levels[Language.FRENCH])
    }

    @Test
    fun alternateTabShowsItsViewDuringSearch() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("daily-grid").fetchSemanticsNodes().isNotEmpty()
        }
        val model = ViewModelProvider(compose.activity)[MaxFluencyViewModel::class.java]
        val daily = requireNotNull(model.state.value.daily)
        compose.onNodeWithTag("search-field").performClick().performTextInput("act")
        compose.onNodeWithText("Anagrams").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("word-row-en:cat").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("nav-alternate").performClick()
        compose.onNodeWithTag("word-of-day").assertIsDisplayed()
        compose.onNodeWithTag("daily-grid").assertDoesNotExist()
        org.junit.Assert.assertEquals("", model.state.value.query)
        org.junit.Assert.assertEquals(com.maxfluency.core.SearchMode.WORDS, model.state.value.mode)
        org.junit.Assert.assertEquals(HomeView.GARDEN,
            runBlocking { SettingsStore(compose.activity).settings.first().homeView })
        compose.onNodeWithTag("nav-today").performClick()
        compose.onNodeWithTag("daily-grid").assertIsDisplayed()
        org.junit.Assert.assertEquals(daily, model.state.value.daily)
    }

    @Test
    fun homeViewChoicePersists() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("daily-grid").fetchSemanticsNodes().isNotEmpty()
        }
        val model = ViewModelProvider(compose.activity)[MaxFluencyViewModel::class.java]
        val featured = requireNotNull(model.state.value.daily).featured
        compose.onNodeWithTag("nav-alternate").assertTextContains("Word of the day").performClick()
        compose.onNodeWithTag("nav-alternate").assertIsSelected()
        compose.onNodeWithTag("word-of-day").assertIsDisplayed()
        compose.onNodeWithTag("daily-grid").assertDoesNotExist()
        compose.onNodeWithText(featured.word, useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("entry-screen").fetchSemanticsNodes().isNotEmpty() }
        org.junit.Assert.assertEquals(featured, model.state.value.daily?.featured)
        org.junit.Assert.assertEquals(featured.id, model.state.value.entry?.id)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("nav-alternate").assertIsSelected()
        compose.onNodeWithTag("word-of-day").assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasText("Home screen"))
        compose.onNodeWithText("Word garden").performClick()
        compose.onNode(hasText("Word of the day") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText("Today").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("word-of-day").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("daily-grid").assertDoesNotExist()
        org.junit.Assert.assertEquals(HomeView.WORD_OF_DAY,
            runBlocking { SettingsStore(compose.activity).settings.first().homeView })
        compose.onNodeWithTag("nav-alternate").assertTextContains("Word garden").performClick()
        compose.onNodeWithTag("nav-alternate").assertIsSelected()
        compose.onNodeWithTag("daily-grid").assertIsDisplayed()
        compose.onNodeWithTag("word-of-day").assertDoesNotExist()
        val garden = requireNotNull(model.state.value.daily)
        compose.onNodeWithTag("refresh-garden").assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("daily-grid").fetchSemanticsNodes().isNotEmpty() &&
                model.state.value.daily?.round != garden.round
        }
        org.junit.Assert.assertEquals(featured, model.state.value.daily?.featured)
        compose.onNodeWithTag("nav-today").performClick()
        compose.onNodeWithTag("word-of-day").assertIsDisplayed()
        org.junit.Assert.assertEquals(HomeView.WORD_OF_DAY,
            runBlocking { SettingsStore(compose.activity).settings.first().homeView })

        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Word of the day").performClick()
        compose.onNode(hasText("Word garden") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText("Today").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("daily-grid").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("word-of-day").assertDoesNotExist()
        org.junit.Assert.assertEquals(HomeView.GARDEN,
            runBlocking { SettingsStore(compose.activity).settings.first().homeView })
        compose.onNodeWithTag("nav-alternate").assertTextContains("Word of the day")
    }
}
