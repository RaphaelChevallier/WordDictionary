package com.maxfluency.app

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.maxfluency.app.data.AppSettings
import com.maxfluency.app.data.HomeView
import com.maxfluency.app.ui.MaxFluencyTheme
import com.maxfluency.app.ui.TodayScreen
import com.maxfluency.core.*
import java.time.LocalDate
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
    fun largeTextKeepsIpaReadable() {
        val ipa = "/\u02cckj\u028a\u0259ri\u02c8\u0252s\u026ati/"
        val word = WordEntry("en:curiosity", Language.ENGLISH, "curiosity", ipa,
            listOf(Sense("noun", "A wish to understand something unfamiliar.")), emptyList(), "en")
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
        compose.onNodeWithTag("today-screen").performScrollToNode(hasText(ipa))
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(ipa, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("The Listen button should not squeeze a short IPA onto multiple lines", 1, layouts.single().lineCount)
    }

    @Test
    fun listenFromCardDoesNotNavigate() {
        val word = WordEntry("en:test", Language.ENGLISH, "test", "/t\u025bst/",
            listOf(Sense("noun", "A short examination.")), emptyList(), "en")
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
        compose.onNodeWithText("/t\u025bst/").assertIsDisplayed()
        compose.onNodeWithText("Listen (device voice)").performClick()
        assertEquals(word, spoken)
        assertEquals(0, navigations)
    }
}
