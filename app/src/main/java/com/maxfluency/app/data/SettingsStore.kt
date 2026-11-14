package com.maxfluency.app.data

import android.content.Context
import android.content.res.Resources
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.maxfluency.core.Language
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class Accent { MONOCHROME, BLUE, GREEN, ROSE }
enum class ReadingSize(val scale: Float) { STANDARD(1f), LARGE(1.2f) }
enum class HomeView { GARDEN, WORD_OF_DAY }

data class AppSettings(
    val language: Language = Language.ENGLISH,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val accent: Accent = Accent.MONOCHROME,
    val readingSize: ReadingSize = ReadingSize.STANDARD,
    val levels: Map<Language, VocabularyLevel> = Language.entries.associateWith { VocabularyLevel.INTERMEDIATE },
    val homeView: HomeView = HomeView.GARDEN,
)

class SettingsStore(context: Context) {
    private val store = context.applicationContext.settingsDataStore
    private val languageKey = stringPreferencesKey("dictionary_language")
    private val themeKey = stringPreferencesKey("theme")
    private val accentKey = stringPreferencesKey("accent")
    private val readingSizeKey = stringPreferencesKey("reading_size")
    private val homeViewKey = stringPreferencesKey("home_view")
    private val initialLanguage = Language.fromCode(Resources.getSystem().configuration.locales[0].language)
        ?: Language.ENGLISH

    val settings: Flow<AppSettings> = store.data.map { preferences ->
        AppSettings(
            language = preferences[languageKey]?.let {
                Language.fromCode(it) ?: throw IOException("Invalid saved dictionary language")
            } ?: initialLanguage,
            theme = enumPreference(preferences, themeKey, ThemeMode.SYSTEM),
            accent = enumPreference(preferences, accentKey, Accent.MONOCHROME),
            readingSize = enumPreference(preferences, readingSizeKey, ReadingSize.STANDARD),
            homeView = enumPreference(preferences, homeViewKey, HomeView.GARDEN),
            levels = Language.entries.associateWith {
                enumPreference(preferences, stringPreferencesKey("level_${it.code}"), VocabularyLevel.INTERMEDIATE)
            },
        )
    }

    suspend fun initialize() {
        store.edit { if (it[languageKey] == null) it[languageKey] = initialLanguage.code }
    }

    suspend fun language(language: Language) { store.edit { it[languageKey] = language.code } }
    suspend fun theme(theme: ThemeMode) { store.edit { it[themeKey] = theme.name } }
    suspend fun accent(accent: Accent) { store.edit { it[accentKey] = accent.name } }
    suspend fun readingSize(size: ReadingSize) { store.edit { it[readingSizeKey] = size.name } }
    suspend fun homeView(view: HomeView) { store.edit { it[homeViewKey] = view.name } }
    suspend fun level(language: Language, level: VocabularyLevel) {
        store.edit { it[stringPreferencesKey("level_${language.code}")] = level.name }
    }

    private inline fun <reified T : Enum<T>> enumPreference(
        preferences: Preferences, key: Preferences.Key<String>, default: T,
    ): T = preferences[key]?.let { value ->
        enumValues<T>().find { it.name == value } ?: throw IOException("Invalid saved setting ${key.name}")
    } ?: default
}
