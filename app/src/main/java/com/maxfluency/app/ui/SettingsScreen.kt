package com.maxfluency.app.ui

import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.maxfluency.app.AppUiState
import com.maxfluency.app.MaxFluencyViewModel
import com.maxfluency.app.R
import com.maxfluency.app.data.Accent
import com.maxfluency.app.data.HomeView
import com.maxfluency.app.data.ReadingSize
import com.maxfluency.app.data.ThemeMode
import com.maxfluency.app.data.VocabularyLevel
import com.maxfluency.app.DownloadUi
import com.maxfluency.core.Language
import java.io.IOException
import java.text.NumberFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(state: AppUiState, viewModel: MaxFluencyViewModel, onCredits: () -> Unit, onAssessment: () -> Unit) {
    var removeTranslationModels by remember { mutableStateOf(false) }
    val interfaceLanguage = AppCompatDelegate.getApplicationLocales()[0]?.language.orEmpty()
    LazyColumn(Modifier.testTag("settings-screen"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            SettingChoice(stringResource(R.string.home_view), state.settings.homeView,
                listOf(HomeView.GARDEN to stringResource(R.string.home_word_garden),
                    HomeView.WORD_OF_DAY to stringResource(R.string.home_word_of_day)), viewModel::homeView)
        }
        item { SectionTitle(stringResource(R.string.languages)) }
        item {
            SettingChoice(stringResource(R.string.dictionary_language), state.settings.language,
                Language.entries.map { it to it.nativeName }, viewModel::language)
        }
        item {
            SettingChoice(stringResource(R.string.interface_language), interfaceLanguage,
                listOf("" to stringResource(R.string.system_language)) + Language.entries.map { it.code to it.nativeName },
                { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(it)) })
        }
        item {
            Text(stringResource(R.string.language_independence), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            SettingChoice(stringResource(R.string.vocabulary_level), state.settings.levels.getValue(state.settings.language),
                listOf(VocabularyLevel.BEGINNER to stringResource(R.string.level_beginner),
                    VocabularyLevel.INTERMEDIATE to stringResource(R.string.level_intermediate),
                    VocabularyLevel.ADVANCED to stringResource(R.string.level_advanced)), viewModel::level)
            TextButton(onClick = onAssessment) { Text(stringResource(R.string.assessment_title)) }
        }
        item { SectionTitle(stringResource(R.string.offline_dictionaries)) }
        items(Language.entries, key = { it.code }) { language ->
            FullDictionaryCard(language, state.packs[language] ?: DownloadUi(), viewModel::download,
                viewModel::cancelDownload, viewModel::removeDownload)
        }
        item { SectionTitle(stringResource(R.string.appearance)) }
        item {
            SettingChoice(stringResource(R.string.theme), state.settings.theme,
                listOf(ThemeMode.SYSTEM to stringResource(R.string.theme_system),
                    ThemeMode.LIGHT to stringResource(R.string.theme_light),
                    ThemeMode.DARK to stringResource(R.string.theme_dark)), viewModel::theme)
        }
        item {
            SettingChoice(stringResource(R.string.accent), state.settings.accent,
                listOf(Accent.MONOCHROME to stringResource(R.string.accent_mono),
                    Accent.BLUE to stringResource(R.string.accent_blue),
                    Accent.GREEN to stringResource(R.string.accent_green),
                    Accent.ROSE to stringResource(R.string.accent_rose)), viewModel::accent)
        }
        item {
            SettingChoice(stringResource(R.string.text_size), state.settings.readingSize,
                listOf(ReadingSize.STANDARD to stringResource(R.string.text_standard),
                    ReadingSize.LARGE to stringResource(R.string.text_large)), viewModel::readingSize)
        }
        item {
            TextButton(onClick = onCredits) { Text(stringResource(R.string.data_licenses)) }
            TextButton(onClick = { removeTranslationModels = true }) { Text(stringResource(R.string.remove_translation_models)) }
            Text(stringResource(R.string.privacy_note), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { ReminderSettings(state.settings, viewModel) }
    }
    if (removeTranslationModels) AlertDialog(
        onDismissRequest = { removeTranslationModels = false },
        title = { Text(stringResource(R.string.remove_translation_models)) },
        text = { Text(stringResource(R.string.remove_translation_models_message)) },
        confirmButton = {
            TextButton(onClick = { removeTranslationModels = false; viewModel.removeTranslationModels() }) {
                Text(stringResource(R.string.remove_translation_models))
            }
        },
        dismissButton = { TextButton(onClick = { removeTranslationModels = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun <T> SettingChoice(title: String, value: T, choices: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        ChoiceMenu(value, choices, onSelect)
    }
}

@Composable
fun CreditsScreen(state: AppUiState, onSource: (String) -> Unit) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    var license by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val lastViewed = state.entry ?: state.saved.firstOrNull()?.entry
    val creditedWords = listOfNotNull(lastViewed, state.daily?.featured).distinctBy { it.id }
    LaunchedEffect(context) {
        try {
            license = withContext(Dispatchers.IO) {
                val names = context.assets.list("licenses")?.sorted()
                    ?: throw IOException("Offline licenses are missing")
                val required = setOf("CC-BY-SA-4.0.txt", "ZSTD-JNI-BSD-2-Clause.txt", "ZSTANDARD-BSD-3-Clause.txt",
                    "CMUDICT-LICENSE.txt", "GOOGLE-TRANSLATE-NOTICE.txt")
                if (!names.containsAll(required)) throw IOException("An offline dependency license is missing")
                names.joinToString("\n\n") { name ->
                    name + "\n\n" + context.assets.open("licenses/$name").bufferedReader().use { it.readText() }
                }
            }
        } catch (error: IOException) {
            Log.e("MaxFluency", "Offline license could not be opened", error)
            failed = true
        }
    }
    LazyColumn(Modifier.testTag("credits-screen"), contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { SectionTitle(stringResource(R.string.credits)) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.source_note), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { onSource("https://creativecommons.org/licenses/by-sa/4.0/") }) {
                    Text(stringResource(R.string.cc_by_sa_license))
                }
            }
        }
        items(creditedWords, key = { it.id }) { word ->
            Column(Modifier.fillMaxWidth().testTag("word-sources-${word.id}"),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionTitle(stringResource(R.string.word_sources, word.word))
                word.sourceLinks.forEach { (title, url) ->
                    TextButton(onClick = { onSource(url) }) { Text("${stringResource(R.string.source)} · $title") }
                }
            }
        }
        state.manifest?.let { manifest ->
            item {
                Text(stringResource(R.string.data_version, manifest.releaseVersion))
                Text(manifest.attribution, modifier = Modifier.padding(top = 8.dp))
                Text(manifest.modifications, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }
            items(manifest.languages, key = { it.language }) { pack ->
                val size = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }
                    .format(pack.compressedBytes / 1e6)
                Text(stringResource(R.string.pack_info,
                    Language.fromCode(pack.language)?.nativeName ?: pack.language, pack.entries, size))
            }
        }
        item {
            Text(stringResource(R.string.pronunciation_guide_credit), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { onSource("https://github.com/cmusphinx/cmudict") }) {
                Text(stringResource(R.string.cmudict_source))
            }
        }
        item {
            Text(stringResource(R.string.translation_credit), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { onSource("https://developers.google.com/ml-kit/language/translation") }) {
                Text(stringResource(R.string.translate_with_google))
            }
        }
        item { SectionTitle(stringResource(R.string.license_title)) }
        item {
            when {
                failed -> Text(stringResource(R.string.error_dictionary), color = MaterialTheme.colorScheme.error)
                license == null -> LoadingPanel()
                else -> Text(checkNotNull(license), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
