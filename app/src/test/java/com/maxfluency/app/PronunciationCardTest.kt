package com.maxfluency.app

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.maxfluency.app.data.AppSettings
import com.maxfluency.app.data.HomeView
import com.maxfluency.app.data.ReadingSize
import com.maxfluency.app.data.ThemeMode
import com.maxfluency.app.ui.MaxFluencyTheme
import com.maxfluency.app.ui.TodayScreen
import com.maxfluency.core.*
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h891dp")
class PronunciationCardTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h844dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun largeTextKeepsListenReadable() {
        val ipa = "/d\u026a\u02c8z\u0251\u02d0s.t\u0259\u02d0/"
        val word = WordEntry("en:disaster", Language.ENGLISH, "disaster", ipa,
            listOf(Sense("noun", "An event causing great damage.")), emptyList(), "en",
            pronunciationGuide = "dih-ZAS-ter")
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                MaxFluencyTheme {
                    Surface(Modifier.width(360.dp)) {
                        TodayScreen(
                            state = AppUiState(settings = AppSettings(homeView = HomeView.WORD_OF_DAY),
                                loading = false, daily = DailyWords(LocalDate.of(2026, 11, 12), word, emptyList())),
                            onWord = {}, onQuery = {}, onLanguage = {}, onMode = {}, onRetry = {},
                            onOnlineSearch = {}, onRefresh = {}, onSpeak = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("today-screen").performScrollToNode(hasText("Listen (device voice)"))
        compose.onNodeWithText(ipa).assertDoesNotExist()
        compose.onNodeWithText("Pronunciation: dih-ZAS-ter").assertIsDisplayed()
        compose.onNodeWithText("disaster").assertIsDisplayed()
        compose.onNodeWithText("Listen (device voice)").assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Listen (device voice)", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("The pronunciation action should remain readable with large text", 1, layouts.single().lineCount)
    }

    @Test
    fun listenFromCardDoesNotNavigate() {
        val word = WordEntry("fr:d\u00e9sastre", Language.FRENCH, "d\u00e9sastre", "/de.zast\u0281/",
            listOf(Sense("noun", "Un grand malheur.")), emptyList(), "fr",
            pronunciationGuide = "not-for-french")
        var spoken: WordEntry? = null
        var navigations = 0
        compose.setContent {
            MaxFluencyTheme {
                Surface {
                    TodayScreen(
                        state = AppUiState(settings = AppSettings(homeView = HomeView.WORD_OF_DAY),
                            loading = false, daily = DailyWords(LocalDate.of(2026, 11, 12), word, emptyList())),
                        onWord = { navigations++ }, onQuery = {}, onLanguage = {}, onMode = {}, onRetry = {},
                        onOnlineSearch = {}, onRefresh = {}, onSpeak = { spoken = it },
                    )
                }
            }
        }
        compose.onNodeWithTag("today-screen").performScrollToNode(hasText("Listen (device voice)"))
        compose.onNodeWithText(word.pronunciation).assertDoesNotExist()
        compose.onNodeWithText("not-for-french", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Listen (device voice)").performClick()
        assertEquals(word, spoken)
        assertEquals(0, navigations)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun previewHintsAtAdditionalMeaningsAndOpensTheWord() {
        val meanings = listOf(
            Sense("noun", "A first meaning for the preview."),
            Sense("noun", "A second meaning on the full card."),
            Sense("verb", "A third meaning on the full card."),
        )
        val word = WordEntry("en:example", Language.ENGLISH, "example", "",
            meanings, emptyList(), "en")
        var senseCount by mutableStateOf(3)
        var opened: String? = null
        compose.setContent {
            MaxFluencyTheme {
                Surface {
                    TodayScreen(
                        state = AppUiState(settings = AppSettings(homeView = HomeView.WORD_OF_DAY),
                            loading = false, daily = DailyWords(LocalDate.of(2026, 11, 17),
                                word.copy(senses = meanings.take(senseCount).take(1)), emptyList(),
                                featuredMeaningCount = senseCount)),
                        onWord = { opened = it }, onQuery = {}, onLanguage = {}, onMode = {}, onRetry = {},
                        onOnlineSearch = {}, onRefresh = {}, onSpeak = {},
                    )
                }
            }
        }
        compose.onNodeWithText(meanings.first().definition).assertIsDisplayed()
        compose.onNodeWithText(meanings[1].definition).assertDoesNotExist()
        compose.onNodeWithText(meanings[2].definition).assertDoesNotExist()
        compose.onNodeWithText("2 more meanings").assertIsDisplayed()
        compose.onNodeWithTag("more-meanings", useUnmergedTree = true).performTouchInput { click() }
        assertEquals(word.id, opened)
        compose.runOnIdle { senseCount = 2 }
        compose.onNodeWithText("1 more meaning").assertIsDisplayed()
        compose.onNodeWithText("2 more meanings").assertDoesNotExist()
        compose.runOnIdle { senseCount = 1 }
        compose.onNodeWithTag("more-meanings", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(meanings.first().definition).assertIsDisplayed()
        compose.runOnIdle { senseCount = 0 }
        compose.onNodeWithTag("more-meanings", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "en-rUS-w320dp-h844dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun moreMeaningsHintFitsLocalizedLargeTextPreviews() {
        var language by mutableStateOf("en")
        var theme by mutableStateOf(ThemeMode.LIGHT)
        lateinit var localized: Context
        val word = WordEntry("en:disaster", Language.ENGLISH, "disaster", "",
            listOf(Sense("noun", "A sudden event causing great damage or distress.")),
            emptyList(), "en", pronunciationGuide = "dih-ZAS-ter")
        compose.setContent {
            val base = LocalContext.current
            val configuration = Configuration(LocalConfiguration.current).apply {
                setLocale(Locale.forLanguageTag(language))
            }
            localized = base.createConfigurationContext(configuration)
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                MaxFluencyTheme(AppSettings(theme = theme, readingSize = ReadingSize.LARGE)) {
                    Surface {
                        TodayScreen(
                            state = AppUiState(settings = AppSettings(homeView = HomeView.WORD_OF_DAY),
                                loading = false, daily = DailyWords(LocalDate.of(2026, 11, 17), word, emptyList(),
                                    featuredMeaningCount = 64)),
                            onWord = {}, onQuery = {}, onLanguage = {}, onMode = {}, onRetry = {},
                            onOnlineSearch = {}, onRefresh = {}, onSpeak = {},
                        )
                    }
                }
            }
        }
        for (locale in listOf("en", "fr", "es")) {
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                compose.runOnIdle { language = locale; theme = mode }
                compose.onNodeWithTag("more-meanings", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
                val label = localized.resources.getQuantityString(R.plurals.more_meanings, 63, 63)
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(label, useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                assertEquals("$locale hint should stay compact", 1, layout.lineCount)
                assertFalse("$locale hint height must not be clipped", layout.didOverflowHeight)
                assertTrue("$locale full hint must fit its pixel-rounded bounds",
                    layout.getLineRight(0) <= layout.size.width + 1f)
            }
        }
    }
}
