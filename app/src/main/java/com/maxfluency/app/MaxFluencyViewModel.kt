package com.maxfluency.app

import android.app.Application
import android.database.sqlite.SQLiteException
import android.util.Log
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maxfluency.app.data.Accent
import com.maxfluency.app.data.AppSettings
import com.maxfluency.app.data.HomeView
import com.maxfluency.app.data.LexiconRepository
import com.maxfluency.app.data.PersonalStore
import com.maxfluency.app.data.ReadingSize
import com.maxfluency.app.data.SettingsStore
import com.maxfluency.app.data.ThemeMode
import com.maxfluency.app.data.FullDictionaryInfo
import com.maxfluency.app.data.FullDictionaryStore
import com.maxfluency.app.data.DictionaryDownloadWorker
import com.maxfluency.app.data.VocabularyLevel
import com.maxfluency.app.data.ArchivedDailyWord
import com.maxfluency.app.data.WordCalendarUi
import com.maxfluency.app.data.DailyReminders
import com.maxfluency.app.data.AnkiCards
import com.maxfluency.app.data.AnkiDeckExporter
import com.maxfluency.app.data.AnkiDroidExporter
import com.maxfluency.app.data.AnkiExportUi
import com.maxfluency.app.data.DefinitionTranslator
import com.maxfluency.app.data.DefinitionTranslationUi
import com.maxfluency.app.data.DefinitionTranslationPhase
import com.maxfluency.app.data.OnDeviceDefinitionTranslator
import com.maxfluency.app.data.TranslationModelRequired
import com.maxfluency.app.data.definitionTranslationTarget
import com.maxfluency.app.data.systemLanguageTag
import com.maxfluency.app.data.translationTexts
import com.maxfluency.app.data.translatedWordCard
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.maxfluency.core.DailyWords
import com.maxfluency.core.Language
import com.maxfluency.core.PackManifest
import com.maxfluency.core.SavedWord
import com.maxfluency.core.SearchMode
import com.maxfluency.core.SearchPlan
import com.maxfluency.core.WordEntry
import com.maxfluency.core.searchPlan
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

data class AppUiState(
    val settings: AppSettings = AppSettings(),
    val manifest: PackManifest? = null,
    val loading: Boolean = true,
    val daily: DailyWords? = null,
    val query: String = "",
    val mode: SearchMode = SearchMode.WORDS,
    val searching: Boolean = false,
    val matches: List<WordEntry> = emptyList(),
    val saved: List<SavedWord> = emptyList(),
    val entryId: String? = null,
    val entry: WordEntry? = null,
    val entryLoading: Boolean = false,
    val archivedEntry: Boolean = false,
    val error: Int? = null,
    val packs: Map<Language, DownloadUi> = emptyMap(),
    val assessment: List<WordEntry> = emptyList(),
    val assessmentIndex: Int = 0,
    val assessmentCorrect: Int = 0,
    val answering: Boolean = false,
    val refreshingGarden: Boolean = false,
    val systemLanguageTag: String? = null,
    val translation: DefinitionTranslationUi? = null,
    val calendar: WordCalendarUi? = null,
    val entryArchive: ArchivedDailyWord? = null,
    val ankiExport: AnkiExportUi? = null,
) {
    val favoriteIds: Set<String> get() = saved.filter { it.favorite }.map { it.entry.id }.toSet()
}

private fun AppUiState.sameGardenAs(other: AppUiState): Boolean =
    settings.language == other.settings.language && daily?.date == other.daily?.date &&
        daily?.round == other.daily?.round &&
        settings.levels.getValue(settings.language) == other.settings.levels.getValue(other.settings.language)

data class DownloadUi(
    val info: FullDictionaryInfo? = null,
    val active: Boolean = false,
    val preparing: Boolean = false,
    val percent: Int = 0,
    val failed: Boolean = false,
    val workId: String? = null,
)

data class UiMessage(val resource: Int, val argument: String? = null)

class MaxFluencyViewModel internal constructor(
    application: Application,
    private val translator: DefinitionTranslator,
    private val readSystemLanguage: () -> String?,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, OnDeviceDefinitionTranslator(application),
        { systemLanguageTag(application) })
    private val lexicon = LexiconRepository(application)
    private val personal = PersonalStore(application)
    private val preferences = SettingsStore(application)
    private val mutableState = MutableStateFlow(AppUiState(systemLanguageTag = readSystemLanguage()))
    val state = mutableState.asStateFlow()
    private val messageChannel = Channel<UiMessage>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()
    private var settingsJob: Job? = null
    private var searchJob: Job? = null
    private var entryJob: Job? = null
    private var dayJob: Job? = null
    private var gardenJob: Job? = null
    private var calendarJob: Job? = null
    private var reminderJob: Job? = null
    private var scheduledReminderSettings: AppSettings? = null
    private var ankiJob: Job? = null
    private var nextAnkiExport = 0L
    private var translationJob: Job? = null
    private var translationEntry: WordEntry? = null
    private val favoriteMutex = Mutex()

    init {
        observeSettings()
        observeDownloads()
    }

    private fun observeSettings() {
        settingsJob?.cancel()
        settingsJob = launchSafely(R.string.error_settings) {
            preferences.initialize()
            preferences.settings.collectLatest { settings ->
                val oldReminder = scheduledReminderSettings
                val reminderChanged = oldReminder == null || oldReminder.reminderEnabled != settings.reminderEnabled ||
                    oldReminder.reminderMinute != settings.reminderMinute || oldReminder.language != settings.language ||
                    oldReminder.levels[settings.language] != settings.levels[settings.language]
                if (reminderChanged) {
                    scheduledReminderSettings = settings
                    reminderJob?.cancel()
                    reminderJob = launchSafely(R.string.error_reminder, notifyOnly = true) {
                        DailyReminders.schedule(getApplication(), replace = oldReminder != null)
                    }
                }
                searchJob?.cancel()
                gardenJob?.cancel()
                mutableState.update {
                    it.copy(settings = settings, loading = true, error = null, refreshingGarden = false,
                        daily = if (it.settings.language != settings.language) null else it.daily)
                }
                guarded(R.string.error_dictionary) {
                    val manifest = lexicon.manifest()
                    val daily = lexicon.daily(settings.language, LocalDate.now(), settings.levels.getValue(settings.language))
                    val saved = personal.words()
                    mutableState.update { it.copy(manifest = manifest, daily = daily, saved = saved, loading = false) }
                    startSearch()
                }
            }
        }
    }

    fun query(value: String) {
        mutableState.update { it.copy(query = value, error = null) }
        startSearch()
    }

    fun mode(value: SearchMode) {
        mutableState.update { it.copy(mode = value, error = null) }
        startSearch()
    }

    fun dismissSearch() {
        searchJob?.cancel()
        mutableState.update { it.copy(query = "", mode = SearchMode.WORDS, matches = emptyList(), searching = false) }
    }

    private fun startSearch() {
        searchJob?.cancel()
        val snapshot = state.value
        val plan = searchPlan(snapshot.query, snapshot.mode)
        if (plan == SearchPlan.Empty || plan == SearchPlan.NoLetters) {
            mutableState.update { it.copy(matches = emptyList(), searching = false) }
            return
        }
        mutableState.update { it.copy(searching = true, matches = emptyList()) }
        searchJob = launchSafely(R.string.error_search) {
            delay(160)
            val matches = lexicon.search(snapshot.settings.language, plan)
            mutableState.update { it.copy(matches = matches, searching = false) }
        }
    }

    fun language(language: Language) {
        launchSafely(R.string.error_settings) { preferences.language(language) }
    }

    fun theme(theme: ThemeMode) {
        launchSafely(R.string.error_settings) { preferences.theme(theme) }
    }

    fun accent(accent: Accent) {
        launchSafely(R.string.error_settings) { preferences.accent(accent) }
    }

    fun readingSize(size: ReadingSize) {
        launchSafely(R.string.error_settings) { preferences.readingSize(size) }
    }

    fun homeView(view: HomeView) {
        launchSafely(R.string.error_settings) { preferences.homeView(view) }
    }

    fun reminderEnabled(enabled: Boolean) {
        launchSafely(R.string.error_reminder, notifyOnly = true) { preferences.reminderEnabled(enabled) }
    }

    fun reminderTime(minute: Int) {
        launchSafely(R.string.error_reminder, notifyOnly = true) { preferences.reminderTime(minute) }
    }

    fun requestAnkiExport(entries: List<WordEntry>) {
        if (state.value.ankiExport?.busy == true || SystemClock.elapsedRealtime() < nextAnkiExport) {
            message(R.string.anki_wait)
            return
        }
        if (entries.isEmpty() || entries.size > 5_000) {
            message(R.string.anki_export_limit)
            return
        }
        mutableState.update { it.copy(ankiExport = AnkiExportUi(entries.distinctBy(WordEntry::id))) }
    }

    fun dismissAnkiExport() {
        ankiJob?.cancel()
        mutableState.update { it.copy(ankiExport = null) }
    }

    fun saveAnkiDeck(uri: Uri) = startAnkiExport { snapshot ->
        withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            var completed = false
            try {
                val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("The export file could not be opened")
                output.use { AnkiDeckExporter.write(getApplication(), AnkiCards.notes(snapshot.entries), it) }
                completed = true
            } finally {
                if (!completed) withContext(kotlinx.coroutines.NonCancellable) {
                    try {
                        if (!DocumentsContract.deleteDocument(resolver, uri)) Log.w("AnkiExport", "Incomplete export file was not removed")
                    } catch (cleanup: java.io.FileNotFoundException) {
                        Log.w("AnkiExport", "Incomplete export file could not be removed", cleanup)
                    } catch (cleanup: SecurityException) {
                        Log.w("AnkiExport", "The document provider denied cleanup", cleanup)
                    } catch (cleanup: IllegalArgumentException) {
                        Log.w("AnkiExport", "The document provider does not support cleanup", cleanup)
                    } catch (cleanup: UnsupportedOperationException) {
                        Log.w("AnkiExport", "The document provider cannot remove an incomplete export", cleanup)
                    }
                }
            }
        }
        message(R.string.anki_file_saved)
    }

    fun sendToAnkiDroid() = startAnkiExport { snapshot ->
        val context = getApplication<Application>()
        if (!AnkiDroidExporter.installed(context)) throw IOException("AnkiDroid is not installed")
        if (!AnkiDroidExporter.permitted(context)) throw SecurityException("AnkiDroid access was not granted")
        val result = AnkiDroidExporter(context.contentResolver).send(AnkiCards.notes(snapshot.entries)) { progress ->
            mutableState.update { state -> if (state.ankiExport?.entries === snapshot.entries)
                state.copy(ankiExport = state.ankiExport.copy(progress = progress)) else state }
        }
        message(R.string.anki_added_count, result.added.toString())
    }

    private fun startAnkiExport(block: suspend (AnkiExportUi) -> Unit) {
        val snapshot = state.value.ankiExport
        if (snapshot == null) {
            message(R.string.anki_export_failed)
            return
        }
        if (snapshot.busy || SystemClock.elapsedRealtime() < nextAnkiExport) {
            message(R.string.anki_wait)
            return
        }
        nextAnkiExport = SystemClock.elapsedRealtime() + 5_000
        mutableState.update { it.copy(ankiExport = snapshot.copy(busy = true, failed = false)) }
        ankiJob = viewModelScope.launch {
            try {
                block(snapshot)
                mutableState.update { if (it.ankiExport?.entries === snapshot.entries) it.copy(ankiExport = null) else it }
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                ankiFailure(snapshot, error)
            } catch (error: SQLiteException) {
                ankiFailure(snapshot, error)
            } catch (error: SecurityException) {
                ankiFailure(snapshot, error)
            } catch (error: IllegalStateException) {
                ankiFailure(snapshot, error)
            } catch (error: IllegalArgumentException) {
                ankiFailure(snapshot, error)
            }
        }
    }

    private fun ankiFailure(expected: AnkiExportUi, error: Exception) {
        Log.e("AnkiExport", "User-requested Anki export failed", error)
        mutableState.update { if (it.ankiExport?.entries === expected.entries)
            it.copy(ankiExport = it.ankiExport.copy(busy = false, failed = true)) else it }
        message(R.string.anki_export_failed)
    }

    fun relatedWord(word: String, language: Language) {
        mutableState.update { it.copy(query = word, mode = SearchMode.WORDS, matches = emptyList()) }
        launchSafely(R.string.error_settings) {
            if (state.value.settings.language != language) preferences.language(language)
            else startSearch()
        }
    }

    fun openEntry(id: String) {
        entryJob?.cancel()
        translationJob?.cancel()
        translationEntry = null
        entryJob = null
        val snapshot = state.value
        val prepared = lexicon.preparedEntry(id)
        val available = prepared?.entry ?: snapshot.matches.find { it.id == id }
            ?: snapshot.saved.find { it.entry.id == id }?.entry
        mutableState.update {
            it.copy(entryId = id, entry = available, entryLoading = available == null,
                archivedEntry = prepared?.archived ?: false, error = null, translation = null, entryArchive = null)
        }
        if (available != null) prepareTranslation(available)
        if (prepared != null) {
            recordEntry(prepared.entry, snapshot)
            return
        }
        entryJob = launchSafely(R.string.error_entry, notifyOnly = available != null) {
            val current = lexicon.entry(id)
            val entry = current ?: run {
                val fallback = available ?: personal.snapshot(id)
                    ?: snapshot.daily?.let { (it.grid + it.featured).find { word -> word.id == id } }
                    ?: throw IOException("No current or saved definition for $id")
                lexicon.withPronunciationGuide(fallback)
            }
            mutableState.update { it.copy(entry = entry, entryLoading = false, archivedEntry = current == null) }
            prepareTranslation(entry)
            recordEntry(entry, snapshot)
        }
    }

    fun openCalendar() {
        val previous = state.value.calendar
        if (previous != null) {
            if (previous.today != LocalDate.now() || previous.loading && calendarJob?.isActive != true) {
                loadCalendar(previous.month, previous.language, previous.level)
            }
            return
        }
        val archived = state.value.entryArchive
        val language = archived?.entry?.language ?: state.value.settings.language
        loadCalendar(archived?.date?.let(YearMonth::from) ?: YearMonth.now(), language,
            archived?.level ?: state.value.settings.levels.getValue(language))
    }

    fun calendarMonth(month: YearMonth) {
        val current = state.value.calendar ?: return
        loadCalendar(month, current.language, current.level)
    }

    fun calendarLanguage(language: Language) {
        val current = state.value.calendar ?: return
        loadCalendar(current.month, language, state.value.settings.levels.getValue(language))
    }

    fun calendarLevel(level: VocabularyLevel) {
        val current = state.value.calendar ?: return
        loadCalendar(current.month, current.language, level)
    }

    fun calendarToday() {
        val current = state.value.calendar ?: return
        loadCalendar(YearMonth.now(), current.language, current.level)
    }

    fun retryCalendar() {
        state.value.calendar?.let { loadCalendar(it.month, it.language, it.level) }
    }

    fun closeCalendar() { calendarJob?.cancel() }

    private fun loadCalendar(month: YearMonth, language: Language, level: VocabularyLevel) {
        calendarJob?.cancel()
        val origin = state.value.entryArchive
        val previous = state.value.calendar
        val selected = if (previous?.month == month && previous.language == language && previous.level == level)
            previous.selectedDate else origin?.takeIf {
                YearMonth.from(it.date) == month && it.entry.language == language && it.level == level
            }?.date
        val request = WordCalendarUi(month, language, level, LocalDate.now(), selectedDate = selected)
        mutableState.update { it.copy(calendar = request) }
        calendarJob = viewModelScope.launch {
            try {
                val words = lexicon.calendarMonth(month, language, level)
                if (state.value.calendar === request) mutableState.update {
                    it.copy(calendar = request.copy(words = words, loading = false))
                }
            } catch (error: IOException) {
                calendarFailure(request, error)
            } catch (error: SQLiteException) {
                calendarFailure(request, error)
            } catch (error: SerializationException) {
                calendarFailure(request, error)
            }
        }
    }

    private fun calendarFailure(request: WordCalendarUi, error: Exception) {
        Log.e("MaxFluency", "Daily word calendar could not be opened", error)
        if (state.value.calendar === request) mutableState.update {
            it.copy(calendar = request.copy(loading = false, failed = true))
        }
    }

    fun openArchivedEntry(id: String, date: LocalDate, level: VocabularyLevel) {
        entryJob?.cancel()
        translationJob?.cancel()
        translationEntry = null
        val snapshot = state.value
        val available = snapshot.calendar?.takeIf { it.level == level }?.words
            ?.find { it.date == date && it.entry.id == id }
        mutableState.update {
            it.copy(entryId = id, entry = available?.entry, entryLoading = available == null,
                archivedEntry = false, entryArchive = available, error = null, translation = null,
                calendar = it.calendar?.copy(selectedDate = date))
        }
        if (available != null) {
            prepareTranslation(available.entry)
            recordEntry(available.entry, snapshot)
            return
        }
        entryJob = launchSafely(R.string.error_entry) {
            val language = Language.fromCode(id.substringBefore(':')) ?: throw IOException("Unknown archive language")
            val archived = lexicon.archivedWord(date, language, level)
            if (archived.entry.id != id || date > LocalDate.now()) throw IOException("Archived word does not match its date")
            mutableState.update { it.copy(entry = archived.entry, entryLoading = false, entryArchive = archived) }
            prepareTranslation(archived.entry)
            recordEntry(archived.entry, snapshot)
        }
    }

    fun refreshSystemLanguage() {
        val tag = readSystemLanguage()
        if (tag == state.value.systemLanguageTag) return
        translationJob?.cancel()
        translationEntry = null
        mutableState.update { it.copy(systemLanguageTag = tag, translation = null) }
        state.value.entry?.let { prepareTranslation(it) }
    }

    private fun sameTranslation(entry: WordEntry, target: String): Boolean =
        state.value.entry?.id == entry.id && state.value.entry?.word == entry.word && state.value.entry?.senses == entry.senses &&
            state.value.systemLanguageTag == target

    private fun prepareTranslation(entry: WordEntry, force: Boolean = false) {
        val target = definitionTranslationTarget(entry.language.code, state.value.systemLanguageTag) ?: return
        val previous = state.value.translation
        if (!force && previous?.entryId == entry.id && previous.targetTag == target &&
            translationEntry?.word == entry.word && translationEntry?.senses == entry.senses) return
        translationJob?.cancel()
        translationEntry = entry
        mutableState.update { it.copy(translation = DefinitionTranslationUi(entry.id, target)) }
        translationJob = viewModelScope.launch {
            try {
                val provider = translator.provider(entry.language.code, target)
                if (sameTranslation(entry, target)) mutableState.update {
                    it.copy(translation = DefinitionTranslationUi(entry.id, target,
                        if (provider == null) DefinitionTranslationPhase.UNSUPPORTED else DefinitionTranslationPhase.COLLAPSED,
                        provider = provider))
                }
            } catch (error: TimeoutCancellationException) {
                if (isActive) translationFailure(entry, target, error) else throw error
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                translationFailure(entry, target, error)
            } catch (error: IllegalStateException) {
                translationFailure(entry, target, error)
            }
        }
    }

    fun showTranslation(downloadModels: Boolean = false) {
        val entry = state.value.entry ?: return
        val ui = state.value.translation ?: return
        if (!sameTranslation(entry, ui.targetTag)) return
        if (downloadModels && ui.phase != DefinitionTranslationPhase.MODEL_REQUIRED) {
            Log.w("DefinitionTranslation", "Translation downloads require the model confirmation")
            return
        }
        if (ui.phase == DefinitionTranslationPhase.READY && !downloadModels) {
            mutableState.update { it.copy(translation = ui.copy(expanded = true)) }
            return
        }
        if (ui.phase == DefinitionTranslationPhase.FAILED) {
            prepareTranslation(entry, force = true)
            return
        }
        val provider = ui.provider
        if (provider == null) {
            Log.w("DefinitionTranslation", "Translation was requested before an engine was available")
            return
        }
        translationJob?.cancel()
        mutableState.update { it.copy(translation = ui.copy(phase = DefinitionTranslationPhase.TRANSLATING, expanded = true)) }
        translationJob = viewModelScope.launch {
            try {
                val values = translator.translate(entry.language.code, ui.targetTag,
                    entry.translationTexts(), provider, downloadModels)
                val translated = entry.translatedWordCard(values)
                if (sameTranslation(entry, ui.targetTag)) mutableState.update {
                    it.copy(translation = ui.copy(phase = DefinitionTranslationPhase.READY,
                        content = translated, expanded = true))
                }
            } catch (error: TranslationModelRequired) {
                if (sameTranslation(entry, ui.targetTag)) mutableState.update {
                    it.copy(translation = ui.copy(phase = DefinitionTranslationPhase.MODEL_REQUIRED, expanded = false))
                }
            } catch (error: TimeoutCancellationException) {
                if (isActive) translationFailure(entry, ui.targetTag, error) else throw error
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                translationFailure(entry, ui.targetTag, error)
            } catch (error: IllegalStateException) {
                translationFailure(entry, ui.targetTag, error)
            }
        }
    }

    fun hideTranslation() {
        translationJob?.cancel()
        mutableState.update { state ->
            state.copy(translation = state.translation?.let {
                it.copy(expanded = false, phase = if (it.content != null)
                    DefinitionTranslationPhase.READY else DefinitionTranslationPhase.COLLAPSED)
            })
        }
    }

    private fun translationFailure(entry: WordEntry, target: String, error: Exception) {
        Log.e("DefinitionTranslation", "Optional on-device definition translation failed", error)
        if (sameTranslation(entry, target)) mutableState.update {
            it.copy(translation = it.translation?.copy(phase = DefinitionTranslationPhase.FAILED, expanded = false))
        }
    }

    fun removeTranslationModels() {
        translationJob?.cancel()
        launchSafely(R.string.translation_models_remove_failed, notifyOnly = true) {
            translator.removeDownloadedModels()
            message(R.string.translation_models_removed)
            state.value.entry?.let { prepareTranslation(it, force = true) }
        }
    }

    private fun recordEntry(entry: WordEntry, snapshot: AppUiState) {
        launchSafely(R.string.error_saved, notifyOnly = true) {
            favoriteMutex.withLock {
                personal.recordViewed(entry)
                val saved = personal.words()
                mutableState.update { it.copy(saved = saved) }
            }
            lexicon.seen(entry)
            val garden = snapshot.daily
            val progress = if (garden != null && garden.grid.any { it.id == entry.id }) {
                lexicon.exploreGarden(snapshot.settings.language, garden.date,
                    snapshot.settings.levels.getValue(snapshot.settings.language), garden.round, entry.id)
            } else null
            if (progress != null) mutableState.update {
                if (it.sameGardenAs(snapshot)) it.copy(daily = progress.copy(explored = progress.explored + it.daily?.explored.orEmpty()))
                else it
            }
        }
    }

    fun refreshGarden(visible: Set<String>) {
        val snapshot = state.value
        val garden = snapshot.daily ?: return
        if (snapshot.loading || gardenJob?.isActive == true) return
        mutableState.update { it.copy(refreshingGarden = true) }
        gardenJob = launchSafely(R.string.error_garden_refresh, notifyOnly = true) {
            try {
                val next = lexicon.refreshGarden(snapshot.settings.language, garden.date,
                    snapshot.settings.levels.getValue(snapshot.settings.language), garden.round, visible)
                if (next == null) message(R.string.garden_no_fresh)
                else mutableState.update { if (it.sameGardenAs(snapshot)) it.copy(daily = next) else it }
            } finally {
                mutableState.update { it.copy(refreshingGarden = false) }
            }
        }
    }

    fun toggleFavorite(entry: WordEntry) {
        launchSafely(R.string.error_saved) {
            favoriteMutex.withLock {
                personal.setFavorite(entry, entry.id !in state.value.favoriteIds)
                val saved = personal.words()
                mutableState.update { it.copy(saved = saved) }
            }
        }
    }

    fun refreshDayIfNeeded() {
        if (state.value.loading || state.value.daily?.date == LocalDate.now() || dayJob?.isActive == true) return
        dayJob = launchSafely(R.string.error_dictionary) {
            val language = state.value.settings.language
            val daily = lexicon.daily(language, LocalDate.now(), state.value.settings.levels.getValue(language))
            mutableState.update { if (it.settings.language == language) it.copy(daily = daily) else it }
        }
    }

    fun retry() {
        launchSafely(R.string.error_dictionary) {
            lexicon.resetCache()
            observeSettings()
        }
    }

    fun retryEntry(id: String) {
        launchSafely(R.string.error_entry) {
            lexicon.resetCache()
            openEntry(id)
        }
    }

    fun searchOnline() {
        val snapshot = state.value
        if (snapshot.query.isBlank()) return
        if (snapshot.mode != SearchMode.WORDS || snapshot.query.any { it == '*' || it == '?' }) {
            message(R.string.online_exact_only)
            return
        }
        searchJob?.cancel()
        mutableState.update { it.copy(searching = true, error = null) }
        searchJob = launchSafely(R.string.error_online) {
            val (entry, cached) = lexicon.lookupOnline(snapshot.settings.language, snapshot.query)
            mutableState.update { it.copy(matches = listOfNotNull(entry), searching = false) }
            if (!cached) message(R.string.cache_full)
        }
    }

    fun download(language: Language) = DictionaryDownloadWorker.start(getApplication(), language)
    fun cancelDownload(language: Language) = DictionaryDownloadWorker.cancel(getApplication(), language)

    fun removeDownload(language: Language) {
        launchSafely(R.string.error_download) {
            DictionaryDownloadWorker.cancel(getApplication(), language)
            FullDictionaryStore.get(getApplication()).remove(language)
            mutableState.update { it.copy(packs = it.packs + (language to DownloadUi())) }
            lexicon.resetCache()
            observeSettings()
        }
    }

    private fun observeDownloads() {
        Language.entries.forEach { language ->
            launchSafely(R.string.error_download) {
                WorkManager.getInstance(getApplication()).getWorkInfosForUniqueWorkFlow(DictionaryDownloadWorker.name(language))
                    .collectLatest { works ->
                        val old = state.value.packs[language]
                        val work = works.firstOrNull { !it.state.isFinished }
                            ?: works.find { it.id.toString() == old?.workId } ?: works.lastOrNull()
                        val info = FullDictionaryStore.get(getApplication()).info(language)
                        val ui = DownloadUi(
                            info = info, active = work != null && !work.state.isFinished,
                            preparing = work?.progress?.getString("phase") == "prepare",
                            percent = work?.progress?.getInt("percent", 0) ?: 0,
                            failed = info == null && work?.state == WorkInfo.State.FAILED,
                            workId = work?.id?.toString(),
                        )
                        mutableState.update { it.copy(packs = it.packs + (language to ui)) }
                        if (info != null && old?.info == null) {
                            lexicon.resetCache()
                            observeSettings()
                        }
                    }
            }
        }
    }

    fun level(level: VocabularyLevel) {
        launchSafely(R.string.error_settings) { preferences.level(state.value.settings.language, level) }
    }

    fun startAssessment() {
        mutableState.update { it.copy(assessment = emptyList(), assessmentIndex = 0, assessmentCorrect = 0, answering = false, error = null) }
        launchSafely(R.string.error_assessment) {
            val words = lexicon.assessment(state.value.settings.language)
            if (words.size != 6) throw IOException("The vocabulary check needs six frequency-banded starter words")
            mutableState.update { it.copy(assessment = words) }
        }
    }

    fun answerAssessment(choice: String?) {
        val snapshot = state.value
        val question = snapshot.assessment.getOrNull(snapshot.assessmentIndex) ?: return
        if (snapshot.answering) return
        mutableState.update { it.copy(answering = true) }
        launchSafely(R.string.error_assessment) {
            val correct = choice == question.id
            lexicon.answer(question, correct)
            val score = snapshot.assessmentCorrect + if (correct) 1 else 0
            val next = snapshot.assessmentIndex + 1
            if (next == snapshot.assessment.size) {
                val estimated = if (score <= 1) VocabularyLevel.BEGINNER else if (score <= 4) VocabularyLevel.INTERMEDIATE else VocabularyLevel.ADVANCED
                preferences.level(question.language, estimated)
            }
            mutableState.update { it.copy(assessmentIndex = next, assessmentCorrect = score, answering = false) }
        }
    }

    fun message(resource: Int, argument: String? = null) {
        if (messageChannel.trySend(UiMessage(resource, argument)).isFailure) {
            Log.w("MaxFluency", "Unable to deliver UI message $resource")
        }
    }

    private fun launchSafely(resource: Int, notifyOnly: Boolean = false, block: suspend () -> Unit): Job = viewModelScope.launch {
        guarded(resource, notifyOnly, block)
    }

    private suspend fun guarded(resource: Int, notifyOnly: Boolean = false, block: suspend () -> Unit) {
        try {
            block()
        } catch (error: IOException) {
            fail(resource, error, notifyOnly)
        } catch (error: SQLiteException) {
            fail(resource, error, notifyOnly)
        } catch (error: SerializationException) {
            fail(resource, error, notifyOnly)
        }
    }

    private fun fail(resource: Int, error: Exception, notifyOnly: Boolean) {
        Log.e("MaxFluency", "Offline operation failed", error)
        if (!notifyOnly) mutableState.update { it.copy(loading = false, searching = false, entryLoading = false, error = resource) }
        message(resource)
    }

    override fun onCleared() {
        super.onCleared()
        translator.close()
        CoroutineScope(Dispatchers.IO).launch {
            lexicon.close()
            personal.close()
        }
        messageChannel.close()
    }
}
