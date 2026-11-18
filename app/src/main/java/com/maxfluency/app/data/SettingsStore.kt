package com.maxfluency.app.data

import android.content.Context
import android.content.res.Resources
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.maxfluency.core.Language
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalDate

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
    val reminderEnabled: Boolean = false,
    val reminderMinute: Int = 9 * 60,
)

class SettingsStore(context: Context) {
    private val store = context.applicationContext.settingsDataStore
    private val languageKey = stringPreferencesKey("dictionary_language")
    private val themeKey = stringPreferencesKey("theme")
    private val accentKey = stringPreferencesKey("accent")
    private val readingSizeKey = stringPreferencesKey("reading_size")
    private val homeViewKey = stringPreferencesKey("home_view")
    private val reminderKey = booleanPreferencesKey("daily_reminder")
    private val reminderTimeKey = intPreferencesKey("daily_reminder_minute")
    private val reminderSentKey = longPreferencesKey("daily_reminder_last_day")
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
            reminderEnabled = preferences[reminderKey] ?: false,
            reminderMinute = (preferences[reminderTimeKey] ?: 540).also {
                if (it !in 0..1439) throw IOException("Invalid reminder time")
            },
            levels = Language.entries.associateWith {
                enumPreference(preferences, stringPreferencesKey("level_${it.code}"), VocabularyLevel.INTERMEDIATE)
            },
        )
    }.distinctUntilChanged()

    suspend fun initialize() {
        store.edit { if (it[languageKey] == null) it[languageKey] = initialLanguage.code }
    }

    suspend fun language(language: Language) { store.edit { it[languageKey] = language.code } }
    suspend fun theme(theme: ThemeMode) { store.edit { it[themeKey] = theme.name } }
    suspend fun accent(accent: Accent) { store.edit { it[accentKey] = accent.name } }
    suspend fun readingSize(size: ReadingSize) { store.edit { it[readingSizeKey] = size.name } }
    suspend fun homeView(view: HomeView) { store.edit { it[homeViewKey] = view.name } }
    suspend fun reminderEnabled(enabled: Boolean) { store.edit { it[reminderKey] = enabled } }
    suspend fun reminderTime(minute: Int) {
        require(minute in 0..1439)
        store.edit { it[reminderTimeKey] = minute }
    }

    suspend fun lastReminderDay(): Long? = store.data.first()[reminderSentKey]

    suspend fun claimReminder(date: LocalDate, expected: AppSettings): Boolean {
        var claimed = false
        store.edit {
            val language = it[languageKey]?.let { code ->
                Language.fromCode(code) ?: throw IOException("Invalid saved dictionary language")
            } ?: initialLanguage
            val level = enumPreference(it, stringPreferencesKey("level_${language.code}"), VocabularyLevel.INTERMEDIATE)
            if (it[reminderKey] == true && (it[reminderTimeKey] ?: 540) == expected.reminderMinute &&
                language == expected.language && level == expected.levels.getValue(language) &&
                (it[reminderSentKey] ?: Long.MIN_VALUE) < date.toEpochDay()) {
                it[reminderSentKey] = date.toEpochDay()
                claimed = true
            }
        }
        return claimed
    }

    suspend fun releaseReminder(date: LocalDate) {
        store.edit { if (it[reminderSentKey] == date.toEpochDay()) it.remove(reminderSentKey) }
    }
    suspend fun level(language: Language, level: VocabularyLevel) {
        store.edit { it[stringPreferencesKey("level_${language.code}")] = level.name }
    }

    private inline fun <reified T : Enum<T>> enumPreference(
        preferences: Preferences, key: Preferences.Key<String>, default: T,
    ): T = preferences[key]?.let { value ->
        enumValues<T>().find { it.name == value } ?: throw IOException("Invalid saved setting ${key.name}")
    } ?: default
}
