package com.maxfluency.app

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.maxfluency.app.data.DailyReminders
import com.maxfluency.app.data.PersonalStore
import com.maxfluency.app.data.LexiconRepository
import com.maxfluency.app.data.SettingsStore
import com.maxfluency.core.discoveryPreview
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class DailyReminderTest {
    private lateinit var application: Application
    private lateinit var settings: SettingsStore

    @Before
    fun setUp() = runBlocking {
        application = ApplicationProvider.getApplicationContext()
        settings = SettingsStore(application)
        settings.reminderEnabled(false)
        settings.reminderTime(540)
        settings.releaseReminder(LocalDate.now())
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        application.getSystemService(NotificationManager::class.java).cancelAll()
    }

    @Test
    fun schedulingUsesLocalNineAmAcrossDaylightSavingChanges() {
        for ((date, expectedHours) in listOf("2026-03-07T09:00:00-05:00" to 23L, "2026-10-31T09:00:00-04:00" to 25L)) {
            val now = ZonedDateTime.parse(date).withZoneSameInstant(ZoneId.of("America/New_York"))
            val next = DailyReminders.nextTime(now, 540, null)
            assertEquals(9, next.hour)
            assertEquals(now.toLocalDate().plusDays(1), next.toLocalDate())
            assertEquals(expectedHours, Duration.between(now, next).toHours())
        }
        val now = ZonedDateTime.parse("2026-11-17T08:00:00Z")
        val first = DailyReminders.nextTime(now, 540, null)
        assertEquals(now.toLocalDate(), first.toLocalDate())
        assertEquals(now.toLocalDate().plusDays(1),
            DailyReminders.nextTime(now, 540, now.toLocalDate().toEpochDay()).toLocalDate())
    }

    @Test
    fun notificationShowsTheActualDailyWordAndDefinitionAndDoesNotRepeat() = runBlocking {
        assertFalse(DailyReminders.deliver(application, LocalDate.now()))
        settings.reminderTime(0)
        settings.reminderEnabled(true)
        assertTrue(DailyReminders.deliver(application, LocalDate.now()))
        val preferences = settings.settings.first()
        val personal = PersonalStore(application)
        val archived = try { requireNotNull(personal.archivedWord(LocalDate.now(), preferences.language,
            preferences.levels.getValue(preferences.language))) } finally { personal.close() }
        val notification = application.getSystemService(NotificationManager::class.java)
            .let { shadowOf(it).getNotification(DailyReminders.NOTIFICATION_ID) }
        assertEquals(archived.entry.word, notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val lexicon = LexiconRepository(application)
        val expected = try {
            requireNotNull(discoveryPreview(archived.entry, lexicon.manifest().discovery)).senses.first().definition
        } finally { lexicon.close() }
        assertEquals(expected, notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(notification.extras.getCharSequence(Notification.EXTRA_TEXT),
            notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(archived.entry.id, intent.getStringExtra("daily_word_id"))
        assertEquals(LocalDate.now().toString(), intent.getStringExtra("daily_word_date"))
        assertFalse(DailyReminders.deliver(application, LocalDate.now()))
        assertFalse(DailyReminders.deliver(application, LocalDate.now().minusDays(1)))
        assertEquals(1, shadowOf(application.getSystemService(NotificationManager::class.java)).size())
        settings.reminderEnabled(false)
        DailyReminders.schedule(application, replace = true)
        assertEquals(0, shadowOf(application.getSystemService(NotificationManager::class.java)).size())
    }

    @Test
    fun notificationPermissionAndChangedSettingsPreventDelivery() = runBlocking {
        settings.reminderEnabled(true)
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(DailyReminders.allowed(application))
        assertFalse(DailyReminders.deliver(application, LocalDate.now()))
        assertNull(settings.lastReminderDay())
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val original = settings.settings.first()
        settings.reminderTime(600)
        assertFalse(settings.claimReminder(LocalDate.now(), original))
        assertTrue(settings.claimReminder(LocalDate.now(), settings.settings.first()))
        assertFalse(settings.claimReminder(LocalDate.now(), settings.settings.first()))
    }

    @Test
    fun onlyOnePendingReminderSurvivesRepeatedSchedulingAndOffCancelsIt() = runBlocking {
        settings.reminderEnabled(true)
        DailyReminders.schedule(application, replace = true)
        DailyReminders.schedule(application)
        val manager = WorkManager.getInstance(application)
        assertEquals(1, manager.getWorkInfosByTag(DailyReminders.TAG).get().count { !it.state.isFinished })
        settings.reminderEnabled(false)
        DailyReminders.schedule(application, replace = true)
        assertEquals(0, manager.getWorkInfosByTag(DailyReminders.TAG).get().count { !it.state.isFinished })
    }
}
