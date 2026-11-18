package com.maxfluency.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteException
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.maxfluency.app.MainActivity
import com.maxfluency.app.R
import com.maxfluency.core.discoveryPreview
import java.io.IOException
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException

object DailyReminders {
    const val CHANNEL = "daily-word"
    const val NOTIFICATION_ID = 4701
    const val TAG = "daily-word-reminder"
    private val scheduling = Mutex()

    fun nextTime(now: ZonedDateTime, minute: Int, lastSentDay: Long?): ZonedDateTime {
        require(minute in 0..1439)
        var date = now.toLocalDate()
        var candidate = date.atTime(LocalTime.of(minute / 60, minute % 60)).atZone(now.zone)
        if (!candidate.isAfter(now) || lastSentDay != null && date.toEpochDay() <= lastSentDay) {
            date = maxOf(date.plusDays(1), lastSentDay?.let { LocalDate.ofEpochDay(it).plusDays(1) } ?: date)
            candidate = date.atTime(LocalTime.of(minute / 60, minute % 60)).atZone(now.zone)
        }
        return candidate
    }

    fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun request(now: ZonedDateTime, next: ZonedDateTime): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<DailyReminderWorker>()
            .setInputData(workDataOf("day" to next.toLocalDate().toString()))
            .setInitialDelay(Duration.between(now, next).toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .addTag(TAG).build()

    suspend fun schedule(context: Context, replace: Boolean = false) = withContext(Dispatchers.IO) {
        scheduling.withLock {
            val settings = SettingsStore(context).settings.first()
            val manager = WorkManager.getInstance(context)
            try {
                if (replace || !settings.reminderEnabled) manager.cancelAllWorkByTag(TAG).result.get()
                if (!settings.reminderEnabled) {
                    context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
                    return@withLock
                }
                val now = ZonedDateTime.now()
                val next = nextTime(now, settings.reminderMinute, SettingsStore(context).lastReminderDay())
                manager.enqueueUniqueWork("$TAG-${next.toLocalDate()}", ExistingWorkPolicy.KEEP, request(now, next)).result.get()
            } catch (error: ExecutionException) {
                throw IOException("The daily reminder could not be scheduled", error.cause ?: error)
            }
        }
    }

    suspend fun deliver(context: Context, date: LocalDate): Boolean {
        val preferences = SettingsStore(context)
        val settings = preferences.settings.first()
        if (!settings.reminderEnabled || date != LocalDate.now() || !allowed(context) ||
            LocalTime.now().toSecondOfDay() < settings.reminderMinute * 60) {
            Log.i("DailyReminder", "Skipping disabled, blocked, early or out-of-date reminder")
            return false
        }
        val lexicon = LexiconRepository(context)
        val word = try {
            val daily = lexicon.daily(settings.language, date, settings.levels.getValue(settings.language))
            val archived = lexicon.archivedWord(date, settings.language, settings.levels.getValue(settings.language))
            val preview = discoveryPreview(archived.entry, lexicon.manifest().discovery) ?: daily.featured
            archived to preview.senses.first().definition
        } finally { lexicon.close() }
        currentCoroutineContext().ensureActive()
        if (!preferences.claimReminder(date, settings)) {
            Log.i("DailyReminder", "Daily reminder was already delivered or its settings changed")
            return false
        }
        try {
            currentCoroutineContext().ensureActive()
            val current = preferences.settings.first()
            if (!current.reminderEnabled || current.reminderMinute != settings.reminderMinute ||
                current.language != settings.language || current.levels[current.language] != settings.levels[settings.language] ||
                date != LocalDate.now() || !allowed(context)) {
                preferences.releaseReminder(date)
                return false
            }
            val manager = context.getSystemService(NotificationManager::class.java)
                ?: throw IOException("Notification service is unavailable")
            manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.reminder_channel),
                NotificationManager.IMPORTANCE_DEFAULT))
            val archived = word.first
            val intent = Intent(context, MainActivity::class.java).apply {
                action = "com.maxfluency.app.OPEN_DAILY_WORD"
                putExtra("daily_word_id", archived.entry.id)
                putExtra("daily_word_date", date.toString())
                putExtra("daily_word_level", archived.level.name)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pending = PendingIntent.getActivity(context, NOTIFICATION_ID, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_daily_word)
                .setContentTitle(archived.entry.word)
                .setContentText(word.second)
                .setStyle(NotificationCompat.BigTextStyle().bigText(word.second))
                .setSubText(context.getString(R.string.home_word_of_day))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setTimeoutAfter(Duration.between(ZonedDateTime.now(), date.plusDays(1)
                    .atStartOfDay(java.time.ZoneId.systemDefault())).toMillis().coerceAtLeast(1))
                .build()
            manager.notify(NOTIFICATION_ID, notification)
            return true
        } catch (error: IOException) {
            preferences.releaseReminder(date)
            throw error
        } catch (error: SecurityException) {
            preferences.releaseReminder(date)
            throw IOException("Notification permission was revoked", error)
        } catch (error: CancellationException) {
            withContext(NonCancellable) { preferences.releaseReminder(date) }
            throw error
        }
    }
}

class DailyReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        var failure: Exception? = null
        try {
            val value = inputData.getString("day") ?: throw IOException("Reminder date is missing")
            val date = try { LocalDate.parse(value) } catch (error: java.time.format.DateTimeParseException) {
                throw IOException("Reminder date is invalid", error)
            }
            DailyReminders.deliver(applicationContext, date)
        } catch (error: IOException) {
            Log.e("DailyReminder", "Daily word reminder failed", error)
            if (runAttemptCount < 2) return Result.retry()
            failure = error
        } catch (error: SQLiteException) {
            Log.e("DailyReminder", "Daily word storage could not be opened", error)
            failure = error
        } catch (error: SerializationException) {
            Log.e("DailyReminder", "Daily word data is invalid", error)
            failure = error
        }
        try {
            DailyReminders.schedule(applicationContext)
        } catch (error: IOException) {
            Log.e("DailyReminder", "The next daily reminder could not be scheduled", error)
            return if (runAttemptCount < 2) Result.retry() else Result.failure(workDataOf("error" to error.message))
        }
        return failure?.let { Result.failure(workDataOf("error" to (it.message ?: "Daily word reminder failed"))) }
            ?: Result.success()
    }
}

class ReminderTimeChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED)) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                DailyReminders.schedule(context, replace = true)
            } catch (error: IOException) {
                Log.e("DailyReminder", "Reminder could not follow the new local time", error)
            } finally { pending.finish() }
        }
    }
}
