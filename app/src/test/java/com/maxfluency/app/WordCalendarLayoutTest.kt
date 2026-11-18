package com.maxfluency.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.maxfluency.app.data.AppSettings
import com.maxfluency.app.data.ArchivedDailyWord
import com.maxfluency.app.data.ReadingSize
import com.maxfluency.app.data.VocabularyLevel
import com.maxfluency.app.data.WordCalendarUi
import com.maxfluency.app.ui.MaxFluencyTheme
import com.maxfluency.app.ui.WordCalendarScreen
import com.maxfluency.core.Language
import com.maxfluency.core.Sense
import com.maxfluency.core.WordEntry
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "fr-rFR-w320dp-h760dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WordCalendarLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun leapMonthWeekdaysAndLargeTextKeepDistinctTouchTargetsAndHideFutureWords() {
        val month = YearMonth.of(2024, 2)
        val word = WordEntry("fr:mot", Language.FRENCH, "mot", "",
            listOf(Sense("noun", "Une définition.")), emptyList(), "fr")
        val days = (1..month.lengthOfMonth()).map {
            ArchivedDailyWord(month.atDay(it), VocabularyLevel.INTERMEDIATE, word.copy(word = "mot$it", id = "fr:mot$it"))
        }
        var state by mutableStateOf(WordCalendarUi(month, Language.FRENCH, VocabularyLevel.INTERMEDIATE,
            LocalDate.of(2024, 2, 18), days, loading = false))
        var selected: ArchivedDailyWord? = null
        var requested: YearMonth? = null
        compose.setContent {
            MaxFluencyTheme(AppSettings(readingSize = ReadingSize.LARGE)) {
                WordCalendarScreen(state, onMonth = { requested = it }, onLanguage = {}, onLevel = {},
                    onToday = {}, onWord = { selected = it }, onRetry = {})
            }
        }
        val first = compose.onNodeWithTag("calendar-day-2024-02-01").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("calendar-day-2024-02-02").fetchSemanticsNode().boundsInRoot
        val monday = compose.onNodeWithTag("calendar-day-2024-02-05").fetchSemanticsNode().boundsInRoot
        assertTrue("French weeks start on Monday", monday.left < first.left && monday.top > first.top)
        assertTrue(first.right <= second.left)
        assertTrue(first.width >= with(compose.density) { 48.dp.toPx() } - 1f)
        assertTrue(first.height >= with(compose.density) { 48.dp.toPx() } - 1f)
        compose.onNodeWithTag("calendar-next-month").assertIsNotEnabled()
        compose.onNodeWithTag("calendar-day-2024-02-29").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("mot29").assertDoesNotExist()
        compose.onNodeWithTag("calendar-day-2024-02-16").performScrollTo().performClick()
        assertEquals(month.atDay(16), selected?.date)
        compose.runOnIdle { state = state.copy(month = YearMonth.of(2024, 1), words = emptyList()) }
        compose.onNodeWithTag("calendar-previous-month").performScrollTo().performClick()
        assertEquals(YearMonth.of(2023, 12), requested)
        compose.onNodeWithTag("calendar-next-month").performClick()
        assertEquals(month, requested)
    }
}
