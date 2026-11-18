package com.maxfluency.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.maxfluency.app.AppUiState
import com.maxfluency.app.R
import com.maxfluency.app.data.AppSettings
import com.maxfluency.app.data.HomeView
import com.maxfluency.core.DailyWords
import com.maxfluency.core.Language
import com.maxfluency.core.Sense
import com.maxfluency.core.WordEntry
import java.time.LocalDate

@Preview(name = "Phone light", widthDp = 390, heightDp = 844)
@Preview(name = "Phone dark", widthDp = 390, heightDp = 844, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Tablet", widthDp = 800, heightDp = 1040)
@Preview(name = "Large text", widthDp = 360, heightDp = 844, fontScale = 1.3f)
@Preview(name = "Landscape", widthDp = 844, heightDp = 390)
@Composable
fun DailyPreview() {
    HomePreview(HomeView.GARDEN)
}

@Preview(name = "Word of the day", widthDp = 390, heightDp = 844)
@Preview(name = "Word of the day large", widthDp = 360, heightDp = 844, fontScale = 1.3f)
@Composable
fun WordOfDayPreview() {
    HomePreview(HomeView.WORD_OF_DAY)
}

@Preview(name = "Reviewed garden", widthDp = 390, heightDp = 844)
@Composable
fun ReviewedGardenPreview() {
    HomePreview(HomeView.GARDEN, reviewed = true)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomePreview(homeView: HomeView, reviewed: Boolean = false) {
    val words = listOf("curiosity", "clarity", "wonder", "resilience", "tranquil", "luminous",
        "kindness", "wander", "solace", "flourish", "serene", "courage", "delight", "harmony",
        "gentle", "vivid", "insight", "patient", "discover", "thoughtful", "bloom").map { word ->
        WordEntry("en:$word", Language.ENGLISH, word, if (word == "curiosity") "/\u02cckj\u028a\u0259ri\u02c8\u0252s\u026ati/" else "",
            listOf(Sense("noun", "A wish to understand something unfamiliar.")), emptyList(), "en")
    }
    val state = AppUiState(settings = AppSettings(homeView = homeView), loading = false,
        daily = DailyWords(LocalDate.of(2026, 11, 11), words.first(), words.drop(1),
            explored = if (reviewed) words.drop(1).map { it.id }.toSet() else emptySet()))
    MaxFluencyTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(title = { Text(stringResource(R.string.app_name)) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    ))
            },
            bottomBar = { AppNavigationBar("today", {}, homeView = homeView) },
        ) { padding ->
            Box(Modifier.padding(padding)) { TodayScreen(state, {}, {}, {}, {}, {}, {}, {}, {}) }
        }
    }
}
