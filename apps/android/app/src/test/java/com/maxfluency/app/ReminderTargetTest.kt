package com.maxfluency.app

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.maxfluency.app.data.ArchivedDailyWord
import com.maxfluency.app.data.PersonalStore
import com.maxfluency.app.data.VocabularyLevel
import com.maxfluency.app.ui.ArchivedWordTarget
import com.maxfluency.app.ui.MaxFluencyApp
import com.maxfluency.app.ui.MaxFluencyTheme
import com.maxfluency.core.Language
import com.maxfluency.core.Sense
import com.maxfluency.core.WordEntry
import java.time.LocalDate
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
class ReminderTargetTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun notificationTargetOpensItsSnapshotAndIsConsumedBeforeBackNavigation() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val date = LocalDate.now().minusDays(1)
        val entry = WordEntry("fr:notification", Language.FRENCH, "notification", "",
            listOf(Sense("noun", "Une définition conservée.")), emptyList(), "fr")
        val archive = ArchivedDailyWord(date, VocabularyLevel.INTERMEDIATE, entry)
        runBlocking {
            val personal = PersonalStore(application)
            try { personal.saveArchivedWords(listOf(archive)) } finally { personal.close() }
        }
        val store = ViewModelStore()
        val model = compose.runOnIdle {
            ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))
                .get(MaxFluencyViewModel::class.java)
        }
        var target by mutableStateOf<ArchivedWordTarget?>(ArchivedWordTarget(entry.id, date, archive.level))
        try {
            compose.setContent {
                val state by model.state.collectAsState()
                MaxFluencyTheme { MaxFluencyApp(state, model, target) { target = null } }
            }
            compose.waitUntil(30_000) {
                compose.waitForIdle()
                model.state.value.entryArchive == archive && !model.state.value.entryLoading
            }
            compose.onNodeWithTag("entry-screen").assertIsDisplayed()
            compose.onNodeWithTag("archived-word-date").assertIsDisplayed()
            assertNull(target)
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitUntil(30_000) {
                compose.onAllNodesWithTag("daily-grid").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("entry-screen").assertDoesNotExist()
        } finally { compose.runOnIdle { store.clear() } }
    }
}
