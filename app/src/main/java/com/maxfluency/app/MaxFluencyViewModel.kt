package com.maxfluency.app

import android.app.Application
import android.database.sqlite.SQLiteException
import android.util.Log
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

class MaxFluencyViewModel(application: Application) : AndroidViewModel(application) {
    private val lexicon = LexiconRepository(application)
    private val personal = PersonalStore(application)
    private val preferences = SettingsStore(application)
    private val mutableState = MutableStateFlow(AppUiState())
    val state = mutableState.asStateFlow()
    private val messageChannel = Channel<UiMessage>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()
    private var settingsJob: Job? = null
    private var searchJob: Job? = null
    private var entryJob: Job? = null
    private var dayJob: Job? = null
    private var gardenJob: Job? = null
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

    fun relatedWord(word: String, language: Language) {
        mutableState.update { it.copy(query = word, mode = SearchMode.WORDS, matches = emptyList()) }
        launchSafely(R.string.error_settings) {
            if (state.value.settings.language != language) preferences.language(language)
            else startSearch()
        }
    }

    fun openEntry(id: String) {
        entryJob?.cancel()
        entryJob = null
        val snapshot = state.value
        val prepared = lexicon.preparedEntry(id)
        val available = prepared?.entry ?: snapshot.matches.find { it.id == id }
            ?: snapshot.saved.find { it.entry.id == id }?.entry
        mutableState.update {
            it.copy(entryId = id, entry = available, entryLoading = available == null,
                archivedEntry = prepared?.archived ?: false, error = null)
        }
        if (prepared != null) {
            recordEntry(prepared.entry, snapshot)
            return
        }
        entryJob = launchSafely(R.string.error_entry, notifyOnly = available != null) {
            val current = lexicon.entry(id)
            val entry = current ?: available
                ?: personal.snapshot(id)
                ?: snapshot.daily?.let { (it.grid + it.featured).find { word -> word.id == id } }
                ?: throw IOException("No current or saved definition for $id")
            mutableState.update { it.copy(entry = entry, entryLoading = false, archivedEntry = current == null) }
            recordEntry(entry, snapshot)
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
        CoroutineScope(Dispatchers.IO).launch {
            lexicon.close()
            personal.close()
        }
        messageChannel.close()
    }
}
