package com.maxfluency.app

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.maxfluency.app.data.ArchivedDailyWord
import com.maxfluency.app.data.PersonalStore
import com.maxfluency.app.data.SettingsStore
import com.maxfluency.app.data.VocabularyLevel
import com.maxfluency.core.Language
import com.maxfluency.core.Sense
import com.maxfluency.core.WordEntry
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w390dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WordCalendarFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[MaxFluencyViewModel::class.java]

    @Test
    fun pastDatesOpenTheirPreparedSnapshotsAndBackKeepsTheMonthAndToday() {
        await { !model.state.value.loading && model.state.value.daily != null }
        val today = requireNotNull(model.state.value.daily)
        compose.onNodeWithTag("open-word-calendar").performClick()
        awaitCalendar()
        compose.onNodeWithTag("calendar-next-month").assertIsNotEnabled()
        compose.onNodeWithTag("calendar-previous-month").performClick()
        awaitCalendar()
        val calendar = requireNotNull(model.state.value.calendar)
        assertEquals(YearMonth.from(today.date).minusMonths(1), calendar.month)
        val day = calendar.words.first { it.date.dayOfMonth == 10 }
        compose.onNodeWithTag("calendar-day-${day.date}").performScrollTo()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("calendar-day-${day.date}").performClick()
            compose.mainClock.advanceTimeBy(96)
            compose.onNodeWithTag("entry-screen").assertIsDisplayed()
        } finally { compose.mainClock.autoAdvance = true }
        assertEquals(day, model.state.value.entryArchive)
        assertEquals(day.entry, model.state.value.entry)
        compose.onNodeWithTag("archived-word-date").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        awaitCalendar()
        assertEquals(calendar.month, model.state.value.calendar?.month)
        compose.onNodeWithTag("calendar-day-${day.date}").performScrollTo().assertIsSelected()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("daily-grid").assertIsDisplayed()
        assertEquals(today.featured, model.state.value.daily?.featured)
        assertEquals(today.grid.map { it.id }, model.state.value.daily?.grid?.map { it.id })
        assertEquals(today.round, model.state.value.daily?.round)
    }

    @Test
    fun calendarLanguageAndLevelDoNotChangeTheLearningPreferences() {
        await { !model.state.value.loading && model.state.value.daily != null }
        val settings = runBlocking { SettingsStore(compose.activity).settings.first() }
        compose.onNodeWithTag("open-word-calendar").performClick()
        awaitCalendar()
        compose.onNodeWithTag("calendar-language").performClick()
        compose.onNode(hasText("Français") and hasAnyAncestor(isPopup())).performClick()
        awaitCalendar()
        compose.onNodeWithTag("calendar-level").performClick()
        compose.onNode(hasText("Beginner") and hasAnyAncestor(isPopup())).performClick()
        awaitCalendar()
        assertEquals(Language.FRENCH, model.state.value.calendar?.language)
        assertEquals(VocabularyLevel.BEGINNER, model.state.value.calendar?.level)
        assertEquals(settings, runBlocking { SettingsStore(compose.activity).settings.first() })
        assertEquals(settings.language, model.state.value.daily?.featured?.language)
    }

    @Test
    fun aRestoredHistoricalRouteReadsTheSnapshotWithoutLookingUpTheCurrentDictionaryEntry() {
        await { !model.state.value.loading && model.state.value.daily != null }
        val application = ApplicationProvider.getApplicationContext<Application>()
        val date = LocalDate.now().minusMonths(2).withDayOfMonth(12)
        val word = WordEntry("fr:__calendar_retired__", Language.FRENCH, "__calendar_retired__", "",
            listOf(Sense("noun", "Une définition historique.", examples = listOf("Un exemple historique."))),
            emptyList(), "fr")
        val archived = ArchivedDailyWord(date, VocabularyLevel.ADVANCED, word)
        runBlocking {
            val personal = PersonalStore(application)
            try { personal.saveArchivedWords(listOf(archived)) } finally { personal.close() }
        }
        val store = ViewModelStore()
        val restored = compose.runOnIdle {
            ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))
                .get(MaxFluencyViewModel::class.java)
        }
        assertSame(application, restored.getApplication<Application>())
        try {
            compose.runOnIdle { restored.openArchivedEntry(word.id, date, archived.level) }
            await {
                compose.mainClock.advanceTimeByFrame()
                restored.state.value.error != null || !restored.state.value.entryLoading && restored.state.value.entry != null
            }
            assertNull("The historical snapshot must load without a dictionary lookup error", restored.state.value.error)
            assertEquals(archived, restored.state.value.entryArchive)
            assertEquals(word, restored.state.value.entry)
            compose.runOnIdle { restored.openCalendar() }
            await {
                compose.mainClock.advanceTimeByFrame()
                restored.state.value.calendar?.loading == false
            }
            assertEquals(YearMonth.from(date), restored.state.value.calendar?.month)
            assertEquals(Language.FRENCH, restored.state.value.calendar?.language)
            assertEquals(archived.level, restored.state.value.calendar?.level)
        } finally { compose.runOnIdle { store.clear() } }
    }

    private fun awaitCalendar() = await { model.state.value.calendar?.let { !it.loading && !it.failed } == true }
    private fun await(predicate: () -> Boolean) = compose.waitUntil(30_000) {
        compose.waitForIdle()
        predicate()
    }
}
