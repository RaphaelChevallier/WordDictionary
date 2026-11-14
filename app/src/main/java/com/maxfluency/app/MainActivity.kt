package com.maxfluency.app

import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                MaxFluencyApp(state, model)
            }
        }
    }
}
