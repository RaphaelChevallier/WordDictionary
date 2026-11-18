package com.maxfluency.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.maxfluency.app.data.AppSettings
import com.maxfluency.app.data.ThemeMode
import com.maxfluency.app.data.AnkiExportUi
import com.maxfluency.app.data.ReadingSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
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
import org.junit.Assert.assertTrue
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
        listOf(Sense("adj", "Easy to understand.")), emptyList(), "en", pronunciationGuide = "LOO-sid")

    @Test
    fun definitionHasNoCreditsFooter() {
        var copied: WordEntry? = null
        var spoken: WordEntry? = null
        compose.setContent {
            MaxFluencyTheme {
                Surface {
                    EntryScreen(AppUiState(loading = false, entry = word),
                        onFavorite = {}, onSpeak = { spoken = it }, onCopy = { copied = it },
                        onRelated = { _, _ -> }, onRetry = {})
                }
            }
        }
        compose.onNodeWithText("Easy to understand.").assertIsDisplayed()
        compose.onNodeWithText("Adapted from Wiktionary contributors", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Online source", substring = true).assertDoesNotExist()
        compose.onNodeWithText(word.pronunciation).assertDoesNotExist()
        compose.onNodeWithText("Pronunciation: LOO-sid").assertIsDisplayed()
        compose.onNodeWithText("Listen").assertIsDisplayed().performClick()
        assertEquals(word, spoken)
        compose.onNodeWithText("Copy").performClick()
        assertEquals(word, copied)
    }

    @Test
    @Config(qualifiers = "en-rUS-w320dp-h844dp")
    fun ankiIsInAnInlineOverflowMenuAndTracksTheCurrentWord() {
        var state by mutableStateOf(AppUiState(loading = false, entry = word))
        var exported: WordEntry? = null
        compose.setContent {
            MaxFluencyTheme(AppSettings(readingSize = ReadingSize.LARGE)) {
                Surface(Modifier.width(320.dp)) {
                    EntryScreen(state, onFavorite = {}, onSpeak = {}, onCopy = {}, onRelated = { _, _ -> },
                        onRetry = {}, onAnkiExport = { exported = it })
                }
            }
        }
        compose.onNodeWithText("Export to Anki").assertDoesNotExist()
        val controls = listOf(
            compose.onNodeWithContentDescription("Save word"),
            compose.onNodeWithText("Listen"),
            compose.onNodeWithText("Copy"),
            compose.onNodeWithContentDescription("More word actions"),
        ).map { it.assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        assertTrue("All actions must share one row", controls.maxOf { it.center.y } - controls.minOf { it.center.y } <= 1f)
        assertTrue("The overflow must fit the narrow card", controls.last().right <= with(compose.density) { 320.dp.toPx() })
        compose.onNodeWithContentDescription("More word actions").performClick()
        compose.onNodeWithText("Export to Anki").assertIsDisplayed().performClick()
        assertEquals(word, exported)
        compose.onNodeWithText("Export to Anki").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(ankiExport = AnkiExportUi(listOf(word), busy = true)) }
        compose.onNodeWithContentDescription("More word actions").performClick()
        compose.onNodeWithText("Export to Anki").assertIsNotEnabled()
        val next = word.copy(id = "en:bright", word = "bright")
        compose.runOnIdle { state = state.copy(entry = next, ankiExport = null) }
        compose.onNodeWithText("Export to Anki").assertDoesNotExist()
        compose.onNodeWithContentDescription("More word actions").performClick()
        compose.onNodeWithText("Export to Anki").performClick()
        assertEquals(next, exported)
    }

    @Test
    fun definitionPageCoversPreviousScreen() {
        var state by mutableStateOf(AppUiState(loading = false, entry = word))
        var theme by mutableStateOf(ThemeMode.LIGHT)
        lateinit var view: View
        var background = 0
        compose.setContent {
            MaxFluencyTheme(AppSettings(theme = theme)) {
                view = LocalView.current
                background = MaterialTheme.colorScheme.surface.toArgb()
                Box(Modifier.fillMaxSize().background(Color.Magenta)) {
                    EntryScreen(state, onFavorite = {}, onSpeak = {}, onCopy = {},
                        onRelated = { _, _ -> }, onRetry = {})
                }
            }
        }
        val states = listOf(
            AppUiState(loading = false, entry = word),
            AppUiState(loading = false, entryLoading = true),
            AppUiState(loading = false, error = R.string.error_entry),
        )
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            for (next in states) {
                compose.runOnIdle { theme = mode; state = next }
                compose.waitForIdle()
                val (expected, bitmap) = compose.runOnIdle {
                    background to Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                        .also { view.draw(Canvas(it)) }
                }
                try {
                    assertEquals("The previous header must not show through the definition page",
                        expected, bitmap.getPixel(1, 1))
                    assertEquals("Blank space must cover the previous page, including loading and error states",
                        expected, bitmap.getPixel(bitmap.width - 2, bitmap.height - 2))
                } finally { bitmap.recycle() }
            }
        }
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
        compose.onNodeWithTag("credits-screen").performScrollToNode(hasText("CMUdict"))
        compose.onNodeWithText("CMUdict").performClick()
        assertEquals("https://github.com/cmusphinx/cmudict", opened)
    }
}
