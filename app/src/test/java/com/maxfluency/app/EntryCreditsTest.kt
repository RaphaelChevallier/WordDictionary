package com.maxfluency.app

import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.maxfluency.app.ui.EntryScreen
import com.maxfluency.app.ui.CreditsScreen
import com.maxfluency.app.ui.MaxFluencyTheme
import com.maxfluency.core.Language
import com.maxfluency.core.Sense
import com.maxfluency.core.WordEntry
import com.maxfluency.core.DailyWords
import com.maxfluency.core.SavedWord
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w390dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EntryCreditsTest {
    @get:Rule val compose = createComposeRule()
    private val word = WordEntry("en:lucid", Language.ENGLISH, "lucid", "/lu\u02d0s\u026ad/",
        listOf(Sense("adj", "Easy to understand.")), emptyList(), "en")

    @Test
    fun definitionHasNoCreditsFooter() {
        var copied: WordEntry? = null
        compose.setContent {
            MaxFluencyTheme {
                Surface {
                    EntryScreen(AppUiState(loading = false, entry = word),
                        onFavorite = {}, onSpeak = {}, onCopy = { copied = it },
                        onRelated = { _, _ -> }, onRetry = {})
                }
            }
        }
        compose.onNodeWithText("Easy to understand.").assertIsDisplayed()
        compose.onNodeWithText("Adapted from Wiktionary contributors", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Online source", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Listen").assertIsDisplayed()
        compose.onNodeWithText("Copy").performClick()
        assertEquals(word, copied)
    }

    @Test
    fun settingsRetainAttributionAndOriginalLinks() {
        val featured = word.copy(id = "en:bright", word = "bright")
        val state = AppUiState(loading = false, saved = listOf(SavedWord(word, false, 123)),
            daily = DailyWords(LocalDate.of(2026, 11, 13), featured, emptyList()))
        var opened: String? = null
        compose.setContent {
            MaxFluencyTheme {
                Surface { CreditsScreen(state, onSource = { opened = it }) }
            }
        }
        compose.onNodeWithText("Adapted from Wiktionary contributors", substring = true).assertIsDisplayed()
        compose.onNodeWithText("CC BY-SA 4.0").performClick()
        assertEquals("https://creativecommons.org/licenses/by-sa/4.0/", opened)
        compose.onNodeWithTag("word-sources-${featured.id}").assertIsDisplayed()
        compose.onNode(hasText("Online source", substring = true) and
            hasAnyAncestor(hasTestTag("word-sources-${word.id}"))).performClick()
        assertEquals(word.sourceLinks.first().second, opened)
    }
}
