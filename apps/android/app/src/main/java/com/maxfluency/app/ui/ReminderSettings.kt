package com.maxfluency.app.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.ActivityNotFoundException
import android.util.Log
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.maxfluency.app.MaxFluencyViewModel
import com.maxfluency.app.R
import com.maxfluency.app.data.AppSettings
import com.maxfluency.app.data.DailyReminders
import java.text.DateFormat
import java.util.Calendar

@Composable
internal fun ReminderSettings(settings: AppSettings, viewModel: MaxFluencyViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var allowed by remember { mutableStateOf(DailyReminders.allowed(context)) }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) allowed = DailyReminders.allowed(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted && DailyReminders.allowed(context)
        if (allowed) viewModel.reminderEnabled(true) else viewModel.message(R.string.reminder_permission_needed)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.daily_reminder), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = settings.reminderEnabled, modifier = Modifier.testTag("daily-reminder-switch")
                .semantics { contentDescription = context.getString(R.string.daily_reminder) },
                onCheckedChange = { enabled ->
                    if (!enabled) viewModel.reminderEnabled(false)
                    else if (DailyReminders.allowed(context)) viewModel.reminderEnabled(true)
                    else if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else viewModel.message(R.string.reminder_permission_needed)
                })
        }
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, settings.reminderMinute / 60)
            set(Calendar.MINUTE, settings.reminderMinute % 60)
        }
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(calendar.time)
        TextButton(onClick = {
            TimePickerDialog(context, { _, hour, minute -> viewModel.reminderTime(hour * 60 + minute) },
                settings.reminderMinute / 60, settings.reminderMinute % 60, android.text.format.DateFormat.is24HourFormat(context)).show()
        }, modifier = Modifier.testTag("daily-reminder-time")) {
            Text(stringResource(R.string.reminder_time, time))
        }
        Text(stringResource(R.string.reminder_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!allowed) {
            Text(stringResource(R.string.reminder_permission_needed), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                try {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                } catch (error: ActivityNotFoundException) {
                    Log.e("DailyReminder", "Android notification settings are unavailable", error)
                    viewModel.message(R.string.error_reminder)
                }
            }) {
                Text(stringResource(R.string.reminder_system_settings))
            }
        }
    }
}
