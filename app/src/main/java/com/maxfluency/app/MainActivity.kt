package com.maxfluency.app

import android.os.Bundle
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.maxfluency.app.ui.ArchivedWordTarget
import com.maxfluency.app.data.VocabularyLevel
import java.time.LocalDate
import android.view.ViewTreeObserver
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxfluency.app.ui.MaxFluencyApp
import com.maxfluency.app.ui.MaxFluencyTheme

class MainActivity : AppCompatActivity() {
    private val viewModel: MaxFluencyViewModel by viewModels()
    private var reminderTarget by mutableStateOf<ArchivedWordTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readReminderIntent(intent)
        enableEdgeToEdge()
        val content = window.decorView
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val state = viewModel.state.value
                if (state.loading || state.entryLoading) return false
                content.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
        val model = viewModel
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            ReportDrawnWhen { !state.loading && !state.entryLoading }
            MaxFluencyTheme(state.settings) {
                val lightBackground = MaterialTheme.colorScheme.background.luminance() > 0.5f
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightBackground
                        isAppearanceLightNavigationBars = lightBackground
                    }
                }
                MaxFluencyApp(state, model, reminderTarget) {
                    reminderTarget = null
                    intent.action = null
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readReminderIntent(intent)
    }

    private fun readReminderIntent(intent: Intent?) {
        if (intent?.action != "com.maxfluency.app.OPEN_DAILY_WORD") return
        val id = intent.getStringExtra("daily_word_id")
        val day = intent.getStringExtra("daily_word_date")
        val level = intent.getStringExtra("daily_word_level")
        try {
            if (id.isNullOrBlank() || id.length > 120 || day == null || level == null) {
                throw IllegalArgumentException("Incomplete daily reminder target")
            }
            val date = LocalDate.parse(day)
            if (date > LocalDate.now()) throw IllegalArgumentException("Future daily reminder target")
            reminderTarget = ArchivedWordTarget(id, date, VocabularyLevel.valueOf(level))
        } catch (error: IllegalArgumentException) {
            Log.e("DailyReminder", "Invalid notification target", error)
            viewModel.message(R.string.error_entry)
        } catch (error: java.time.format.DateTimeParseException) {
            Log.e("DailyReminder", "Invalid notification date", error)
            viewModel.message(R.string.error_entry)
        }
    }
}
