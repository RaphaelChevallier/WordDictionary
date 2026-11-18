package com.maxfluency.app

import android.content.Context
import android.content.res.Configuration
import android.view.View
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.maxfluency.app.data.AppSettings
import com.maxfluency.app.data.ReadingSize
import com.maxfluency.app.data.ThemeMode
import com.maxfluency.app.ui.EntryScreen
import com.maxfluency.app.ui.MaxFluencyTheme
import com.maxfluency.core.Language
import com.maxfluency.core.Sense
import com.maxfluency.core.WordEntry
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w320dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WordActionsLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun localizedActionsRemainVisibleAtLargeFontSizes() {
        var language by mutableStateOf("en")
        var theme by mutableStateOf(ThemeMode.LIGHT)
        lateinit var view: View
        lateinit var localized: Context
        val word = WordEntry("en:disaster", Language.ENGLISH, "disaster", "",
            listOf(Sense("noun", "A sudden event causing great damage or distress.",
                examples = listOf("The team prepared for a natural disaster."))),
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
                    view = LocalView.current
                    Surface {
                        EntryScreen(AppUiState(loading = false, entry = word), onFavorite = {}, onSpeak = {},
                            onCopy = {}, onRelated = { _, _ -> }, onRetry = {}, onAnkiExport = {})
                    }
                }
            }
        }
        for (locale in listOf("en", "fr", "es")) {
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                compose.runOnIdle { language = locale; theme = mode }
                compose.waitForIdle()
                val overflow = compose.onNodeWithContentDescription(localized.getString(R.string.more_word_actions))
                    .assertIsDisplayed().fetchSemanticsNode()
                val target = overflow.touchBoundsInRoot
                assertTrue("$locale overflow must retain a 48 dp touch target: $target", target.width >= with(compose.density) { 48.dp.toPx() })
                assertTrue("$locale overflow touch target must fit the card: $target", target.right <= view.width)
                val icon = compose.onNodeWithContentDescription(localized.getString(R.string.more_word_actions),
                    useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue("$locale overflow icon must be fully visible: $icon", icon.width >= with(compose.density) { 24.dp.toPx() })
                compose.onNodeWithText(localized.getString(R.string.pronunciation_guide, "dih-ZAS-ter")).assertIsDisplayed()
            }
        }
    }
}
