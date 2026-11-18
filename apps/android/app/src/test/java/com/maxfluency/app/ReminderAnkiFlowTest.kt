package com.maxfluency.app

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.maxfluency.app.data.DailyReminders
import com.maxfluency.app.data.SettingsStore
import java.time.LocalDate
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w390dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReminderAnkiFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[MaxFluencyViewModel::class.java]

    @Test
    fun reminderCanBeEnabledAndDisabledWithoutChangingToday() {
        await { !model.state.value.loading && model.state.value.daily != null }
        val application = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        compose.onNodeWithTag("nav-settings").performClick()
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("daily-reminder-switch"))
        compose.onNodeWithTag("daily-reminder-switch").assertIsOff().performClick()
        await { model.state.value.settings.reminderEnabled }
        assertEquals(540, model.state.value.settings.reminderMinute)
        compose.runOnIdle { model.reminderTime(0) }
        await { model.state.value.settings.reminderMinute == 0 }
        runBlocking {
            SettingsStore(application).releaseReminder(LocalDate.now())
            assertTrue(DailyReminders.deliver(application, LocalDate.now()))
        }
        val notification = shadowOf(application.getSystemService(NotificationManager::class.java))
            .getNotification(DailyReminders.NOTIFICATION_ID)
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(model.state.value.daily?.featured?.id, intent.getStringExtra("daily_word_id"))
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("daily-reminder-switch"))
        compose.onNodeWithTag("daily-reminder-switch").performClick()
        await { !model.state.value.settings.reminderEnabled }
        assertFalse(runBlocking { SettingsStore(application).settings.first().reminderEnabled })
    }

    @Test
    fun exportOffersBothMethodsWithoutRequiringAnkiForADeckFile() {
        await { !model.state.value.loading && model.state.value.daily != null }
        val word = requireNotNull(model.state.value.daily).featured
        compose.runOnIdle { model.openEntry(word.id) }
        compose.runOnIdle { model.requestAnkiExport(listOf(requireNotNull(model.state.value.entry))) }
        compose.onNodeWithText("Save deck file (.apkg)").assertIsDisplayed()
        compose.onNodeWithText("Send to AnkiDroid").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Install and open AnkiDroid", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        assertNull(model.state.value.ankiExport)
        compose.runOnIdle { model.toggleFavorite(word) }
        await { word.id in model.state.value.favoriteIds }
        compose.onNodeWithTag("nav-library").performClick()
        compose.onNodeWithText("Export saved words to Anki").performClick()
        assertEquals(listOf(word.id), model.state.value.ankiExport?.entries?.map { it.id })
        val file = File(compose.activity.cacheDir, "anki-flow.apkg")
        try {
            compose.runOnIdle { model.saveAnkiDeck(Uri.fromFile(file)) }
            await { model.state.value.ankiExport == null || model.state.value.ankiExport?.failed == true }
            assertNull(model.state.value.ankiExport)
            ZipFile(file).use { assertNotNull(it.getEntry("collection.anki2")) }
        } finally { file.delete() }
    }

    private fun await(predicate: () -> Boolean) = compose.waitUntil(30_000) {
        compose.waitForIdle()
        predicate()
    }
}
