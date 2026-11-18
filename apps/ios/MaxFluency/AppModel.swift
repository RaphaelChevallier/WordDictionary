import Foundation
import Observation
import SwiftUI
import UIKit
import os

enum AppTab: String, CaseIterable { case today, alternate, library, settings }
enum AppRoute: Hashable { case word(String), calendar, credits, assessment }

struct AppLocalization {
    let bundle: Bundle
    let locale: Locale

    init(language: String = "") throws {
        guard language.isEmpty || ["en", "fr", "es"].contains(language) else {
            throw PlatformFailure.message("The saved interface language is not supported.")
        }
        if language.isEmpty {
            bundle = .main
            locale = .current
        } else {
            guard let url = Bundle.main.url(forResource: language, withExtension: "lproj"), let localized = Bundle(url: url) else {
                throw PlatformFailure.message("The interface translation is missing from this app.")
            }
            bundle = localized
            locale = Locale(identifier: language)
        }
    }

    func text(_ key: String) -> String { bundle.localizedString(forKey: key, value: nil, table: nil) }
}

struct CalendarCell: Identifiable {
    let id: String
    let number: Int?
    let date: Date?
    let word: ArchivedDailyWord?
    let future: Bool
}

@MainActor @Observable
final class AppModel {
    static let shared = AppModel()
    var settings = AppSettings()
    var daily: DailyWords?
    var featuredDate = Date()
    var manifest: AppleManifest?
    var saved: [SavedWord] = []
    var selectedEntry: WordEntry?
    var entryDateLabel: String?
    var entryArchived = false
    var entryBackfilled = false
    var entryLoading = false
    var loading = true
    var startupFailed = false
    var loadingDaily = false
    var refreshingGarden = false
    var savingSettings = false
    var problem: String?
    var notice: String?
    var tab = AppTab.today
    var routes: [AppRoute] = []
    var query = ""
    var mode = SearchMode.words
    var matches: [WordEntry] = []
    var searching = false
    var onlineSearching = false
    var systemLanguageTag = Locale.preferredLanguages.first
    var packs: [DictionaryLanguage: FullDictionaryInfo] = [:]
    var packFailures: [DictionaryLanguage: String] = [:]
    var downloadProgress: [DictionaryLanguage: Double] = [:]
    var preparingDownloads = Set<DictionaryLanguage>()
    var calendarDate = Date()
    var calendarLanguage = DictionaryLanguage.english
    var calendarLevel = VocabularyLevel.intermediate
    var calendarCells: [CalendarCell] = []
    var calendarSelected: String?
    var calendarLoading = false
    var assessment: [WordEntry] = []
    var assessmentIndex = 0
    var assessmentCorrect = 0
    var answering = false
    var exportEntries: [WordEntry]?
    var exporting = false
    var remindersBusy = false

    @ObservationIgnored private var localization: AppLocalization?
    @ObservationIgnored private(set) var store: PersonalStore?
    @ObservationIgnored private(set) var repository: LexiconRepository?
    @ObservationIgnored private var full: FullDictionaryStore?
    @ObservationIgnored private var prepared: [String: PreparedEntry] = [:]
    @ObservationIgnored private var startTask: Task<Void, Never>?
    @ObservationIgnored private var searchTask: Task<Void, Never>?
    @ObservationIgnored private var calendarTask: Task<Void, Never>?
    @ObservationIgnored private var reminderTask: Task<Bool, Never>?
    @ObservationIgnored private var downloadTasks: [DictionaryLanguage: Task<Void, Never>] = [:]
    @ObservationIgnored private var downloadTokens: [DictionaryLanguage: UUID] = [:]
    @ObservationIgnored private let online = PublicDictionaryService()
    @ObservationIgnored private let exporter = AnkiExporter()
    @ObservationIgnored private let reminders = DailyReminderService()
    @ObservationIgnored let speech = SpeechService()
    @ObservationIgnored private let logger = Logger(subsystem: "com.maxfluency.app", category: "application")

    var locale: Locale { localization?.locale ?? .current }
    var favoriteIDs: Set<String> { Set(saved.filter(\.favorite).map(\.entry.id)) }
    var currentLevel: VocabularyLevel { settings.level(for: settings.language) }
    var storageReady: Bool { store != nil && repository != nil }
    var alternateView: HomeView { settings.homeView == .garden ? .wordOfDay : .garden }
    var displayedHome: HomeView { tab == .alternate ? alternateView : settings.homeView }

    func text(_ key: String) -> String { localization?.text(key) ?? Bundle.main.localizedString(forKey: key, value: nil, table: nil) }
    func format(_ key: String, _ arguments: CVarArg...) -> String {
        String(format: text(key), locale: locale, arguments: arguments)
    }
    func plural(_ key: String, _ count: Int) -> String {
        String.localizedStringWithFormat(text(key), count)
    }
    func number(_ value: Int) -> String { value.formatted(.number.locale(locale)) }

    func report(_ error: Error, _ key: String) {
        logger.error("\(key, privacy: .public): \(error.localizedDescription, privacy: .private)")
        problem = text(key) + "\n\n" + error.localizedDescription
    }

    func start() async {
        if let task = startTask { await task.value; return }
        let task = Task { await self.load() }
        startTask = task
        await task.value
    }

    func retry() {
        startTask = nil
        Task { await start() }
    }

    private func load() async {
        loading = true
        startupFailed = false
        do {
            guard let resources = Bundle.main.resourceURL else { throw PlatformFailure.message("The app resource folder is missing.") }
            let directory: URL
            if ProcessInfo.processInfo.arguments.contains("--ui-testing") {
                directory = FileManager.default.temporaryDirectory.appendingPathComponent("maxfluency-ui-\(UUID().uuidString)", isDirectory: true)
            } else {
                directory = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                         appropriateFor: nil, create: true).appendingPathComponent("MaxFluency", isDirectory: true)
            }
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let personal = try PersonalStore(directory: directory)
            let full = try FullDictionaryStore(directory: directory, resourceURL: resources)
            let repository = try LexiconRepository(resourceURL: resources, store: personal, external: full)
            self.store = personal; self.full = full; self.repository = repository
            settings = await personal.settings()
            localization = try AppLocalization(language: settings.interfaceLanguage)
            manifest = await repository.manifest()
            saved = await personal.words()
            try await loadPackInfo()
            try await loadDaily()
            loading = false
            if ProcessInfo.processInfo.arguments.contains("--ui-test-word") {
                guard let word = try await repository.entry(id: "fr:abjurer"), let current = daily else {
                    throw PlatformFailure.message("The native UI test word is missing.")
                }
                daily = DailyWords(date: current.date, featured: word, grid: current.grid, explored: current.explored,
                                   round: current.round, featuredMeaningCount: word.senses.count)
                prepared[word.id] = PreparedEntry(entry: word, archived: false)
                settings.homeView = .wordOfDay
            }
            queueReminderRefresh()
            await consumeNotification()
        } catch {
            loading = false; startupFailed = true
            report(error, "error_dictionary")
        }
    }

    private func loadDaily() async throws {
        guard let repository else { throw PlatformFailure.message("The offline dictionary has not been initialized.") }
        let language = settings.language, level = currentLevel
        let day = LocalDay.string()
        let words = try await repository.daily(language: language, date: day, level: level)
        let entries = await repository.preparedEntries()
        guard settings.language == language, currentLevel == level else { return }
        prepared = entries
        featuredDate = try LocalDay.date(words.date)
        daily = words
    }

    func refreshDay() {
        systemLanguageTag = Locale.preferredLanguages.first
        guard !loading, !loadingDaily, daily?.date != LocalDay.string() else { return }
        loadingDaily = true
        Task {
            defer { loadingDaily = false }
            do { try await loadDaily(); queueReminderRefresh() }
            catch { report(error, "error_dictionary") }
        }
    }

    func updateSettings(_ change: (inout AppSettings) -> Void) {
        guard !savingSettings, let store else { return }
        var proposed = settings
        change(&proposed)
        let old = settings
        savingSettings = true
        Task {
            defer { savingSettings = false }
            do {
                let localized = try AppLocalization(language: proposed.interfaceLanguage)
                try await store.updateSettings(proposed)
                settings = proposed
                localization = localized
                if old.language != proposed.language || old.level(for: proposed.language) != proposed.level(for: proposed.language) {
                    loadingDaily = true
                    if old.language != proposed.language { daily = nil }
                    defer { loadingDaily = false }
                    try await loadDaily()
                    searchChanged()
                }
                if old.reminderEnabled != proposed.reminderEnabled || old.reminderMinute != proposed.reminderMinute ||
                    old.language != proposed.language || old.level(for: proposed.language) != proposed.level(for: proposed.language) {
                    queueReminderRefresh()
                }
            } catch { report(error, "error_settings") }
        }
    }

    func selectTab(_ value: AppTab) {
        if value != tab, value == .today || value == .alternate {
            query = ""; matches = []; mode = .words; searchTask?.cancel()
        }
        tab = value
        routes.removeAll()
    }

    func searchChanged() {
        searchTask?.cancel()
        let query = query, language = settings.language, mode = mode
        guard !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            searching = false; matches = []; return
        }
        searching = true
        searchTask = Task {
            do {
                try await Task.sleep(for: .milliseconds(200))
                guard let repository else { throw PlatformFailure.message("The offline dictionary is not ready.") }
                let results = try await repository.search(language: language, query: query, mode: mode)
                let cache = await repository.preparedEntries()
                try Task.checkCancellation()
                guard self.query == query, settings.language == language, self.mode == mode else { return }
                matches = results; prepared = cache; searching = false
            } catch is CancellationError {}
            catch { searching = false; report(error, "error_search") }
        }
    }

    func lookupOnline() {
        guard !onlineSearching else { return }
        guard mode == .words, !query.contains("*"), !query.contains("?"), !query.contains(".") else {
            problem = text("online_exact_only"); return
        }
        let language = settings.language, requested = query
        onlineSearching = true
        Task {
            defer { onlineSearching = false }
            do {
                guard let repository else { throw PlatformFailure.message("The offline dictionary is not ready.") }
                let id = "\(language.rawValue):\(requested.trimmingCharacters(in: .whitespacesAndNewlines))"
                let local = try await repository.entry(id: id)
                guard settings.language == language, query == requested else { return }
                if let local { show(local); return }
                let response = try await online.lookup(language: language, query: requested)
                guard settings.language == language, query == requested else { return }
                guard let word = response else {
                    notice = text("no_results"); return
                }
                show(word)
                do {
                    try await repository.cacheOnline(word)
                    prepared = await repository.preparedEntries()
                } catch { report(error, "error_online_cache") }
            } catch is CancellationError {}
            catch { report(error, "error_online") }
        }
    }

    func refreshGarden(visible: Set<String>) {
        guard !refreshingGarden, !loadingDaily, let daily, let repository else { return }
        let language = settings.language, level = currentLevel
        refreshingGarden = true
        Task {
            defer { refreshingGarden = false }
            do {
                let fresh = try await repository.refreshGarden(language: language, date: daily.date, level: level,
                                                               round: daily.round, visible: visible)
                guard settings.language == language, currentLevel == level, self.daily?.date == daily.date,
                      self.daily?.round == daily.round else { return }
                if let fresh { prepared = await repository.preparedEntries(); self.daily = fresh }
                else { notice = text("garden_no_fresh") }
            } catch { report(error, "error_garden_refresh") }
        }
    }

    func openWord(id: String) {
        if let word = prepared[id] { show(word.entry, archived: word.archived); return }
        if let word = saved.first(where: { $0.entry.id == id }) { show(word.entry, archived: true); return }
        selectedEntry = nil; entryLoading = true; entryDateLabel = nil
        push(.word(id))
        Task {
            defer { entryLoading = false }
            do {
                guard let repository, let word = try await repository.entry(id: id) else {
                    throw PlatformFailure.message("This word is not available in the current dictionaries or saved snapshots.")
                }
                show(word, navigate: false)
            } catch { report(error, "error_entry") }
        }
    }

    private func show(_ word: WordEntry, archived: Bool = false, navigate: Bool = true) {
        selectedEntry = word; entryLoading = false; entryDateLabel = nil
        entryArchived = archived; entryBackfilled = false
        if navigate { push(.word(word.id)) }
        let snapshot = daily, language = settings.language, level = currentLevel
        Task {
            do {
                guard let store else { throw PlatformFailure.message("Personal storage is not ready.") }
                try await store.recordViewed(word)
                saved = await store.words()
                if let snapshot, snapshot.grid.contains(where: { $0.id == word.id }), let repository {
                    let progress = try await repository.exploreGarden(language: language, date: snapshot.date, level: level,
                                                                     round: snapshot.round, id: word.id)
                    if settings.language == language, currentLevel == level, daily?.date == snapshot.date, daily?.round == snapshot.round {
                        daily = progress
                    }
                }
            } catch { report(error, "error_saved") }
        }
    }

    func openArchive(_ word: ArchivedDailyWord) {
        do {
            let formatter = DateFormatter(); formatter.locale = locale; formatter.dateStyle = .long
            entryDateLabel = format("word_of_day_date", formatter.string(from: try LocalDay.date(word.date)))
            calendarSelected = word.date
            selectedEntry = word.entry; entryLoading = false
            entryArchived = false; entryBackfilled = word.backfilled
            push(.word(word.id))
            Task {
                do {
                    guard let store else { throw PlatformFailure.message("Personal storage is not ready.") }
                    try await store.recordViewed(word.entry)
                    saved = await store.words()
                } catch { report(error, "error_saved") }
            }
        } catch { report(error, "error_entry") }
    }

    func toggleFavorite(_ word: WordEntry) {
        Task {
            do {
                guard let store else { throw PlatformFailure.message("Personal storage is not ready.") }
                try await store.toggleFavorite(word)
                saved = await store.words()
            } catch { report(error, "error_saved") }
        }
    }

    func speak(_ word: WordEntry) {
        do { try speech.speak(word) } catch { report(error, "tts_error") }
    }

    func copy(_ word: WordEntry) {
        let senses = word.senses.map { ([$0.definition] + $0.examples).joined(separator: "\n") }.joined(separator: "\n\n")
        UIPasteboard.general.string = word.word + "\n\n" + senses + "\n\n" +
            word.sourceLinks.map(\.url.absoluteString).joined(separator: "\n") + "\nhttps://creativecommons.org/licenses/by-sa/4.0/"
        notice = text("copied")
    }

    func related(_ word: String, language: DictionaryLanguage) {
        query = word; mode = .words; routes.removeAll(); tab = .today
        if settings.language == language { searchChanged() }
        else { updateSettings { $0.language = language } }
    }

    func push(_ route: AppRoute) {
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) { routes.append(route) }
    }

    func openCalendar() {
        calendarDate = Date(); calendarSelected = LocalDay.string()
        calendarLanguage = settings.language; calendarLevel = currentLevel
        loadCalendar()
        push(.calendar)
    }

    func moveCalendar(_ months: Int) {
        let calendar = Calendar(identifier: .gregorian)
        guard let date = calendar.date(byAdding: .month, value: months, to: calendarDate),
              (1...9999).contains(calendar.component(.year, from: date)),
              LocalDay.month(LocalDay.string(date)) <= LocalDay.month(LocalDay.string()) else { return }
        calendarDate = date; calendarSelected = nil; loadCalendar()
    }

    func loadCalendar() {
        calendarTask?.cancel()
        calendarLoading = true
        calendarCells = []
        let month = LocalDay.month(LocalDay.string(calendarDate)), language = calendarLanguage, level = calendarLevel
        calendarTask = Task {
            do {
                guard let repository else { throw PlatformFailure.message("The offline dictionary is not ready.") }
                let records = try await repository.calendarMonth(month: month, language: language, level: level, today: LocalDay.string())
                let calendar = Calendar(identifier: .gregorian)
                guard let first = calendar.dateInterval(of: .month, for: calendarDate)?.start,
                      let days = calendar.range(of: .day, in: .month, for: first) else {
                    throw PlatformFailure.message("The calendar month is invalid.")
                }
                var localized = calendar; localized.locale = locale
                let offset = (calendar.component(.weekday, from: first) - localized.firstWeekday + 7) % 7
                var cells = (0..<offset).map { CalendarCell(id: "padding-\($0)", number: nil, date: nil, word: nil, future: false) }
                let words = Dictionary(uniqueKeysWithValues: records.map { ($0.date, $0) })
                for day in days {
                    guard let date = calendar.date(byAdding: .day, value: day - 1, to: first) else {
                        throw PlatformFailure.message("A calendar day is invalid.")
                    }
                    let id = LocalDay.string(date)
                    cells.append(CalendarCell(id: id, number: day, date: date, word: words[id], future: id > LocalDay.string()))
                }
                try Task.checkCancellation()
                guard month == LocalDay.month(LocalDay.string(calendarDate)), calendarLanguage == language, calendarLevel == level else { return }
                calendarCells = cells; calendarLoading = false
            } catch is CancellationError {}
            catch { calendarLoading = false; report(error, "error_calendar") }
        }
    }

    func startAssessment() {
        assessment = []; assessmentIndex = 0; assessmentCorrect = 0
        push(.assessment)
        Task {
            do {
                guard let repository else { throw PlatformFailure.message("The offline dictionary is not ready.") }
                let bank = try await repository.assessment(language: settings.language)
                guard bank.count == 6 else { throw PlatformFailure.message("The vocabulary check needs six source words.") }
                assessment = bank
            } catch { report(error, "error_assessment") }
        }
    }

    func answer(_ id: String?) {
        guard !answering, assessment.indices.contains(assessmentIndex), let repository else { return }
        answering = true
        let word = assessment[assessmentIndex], correct = id == assessment[assessmentIndex].id
        Task {
            defer { answering = false }
            do {
                try await repository.answer(word, correct: correct)
                let score = assessmentCorrect + (correct ? 1 : 0)
                let next = assessmentIndex + 1
                if next == assessment.count {
                    guard let store else { throw PlatformFailure.message("Personal storage is not ready.") }
                    let level: VocabularyLevel = score <= 1 ? .beginner : score <= 4 ? .intermediate : .advanced
                    var proposed = settings
                    proposed.levels[word.language.rawValue] = level
                    try await store.updateSettings(proposed)
                    settings = proposed
                    loadingDaily = true
                    defer { loadingDaily = false }
                    try await loadDaily()
                    queueReminderRefresh()
                }
                assessmentCorrect = score; assessmentIndex = next
            } catch { report(error, "error_assessment") }
        }
    }

    private func loadPackInfo() async throws {
        guard let full else { throw PlatformFailure.message("Dictionary storage is not ready.") }
        var result: [DictionaryLanguage: FullDictionaryInfo] = [:]
        var failures: [DictionaryLanguage: String] = [:]
        for language in DictionaryLanguage.allCases {
            do { result[language] = try await full.info(language: language) }
            catch {
                logger.error("An installed dictionary is incomplete: \(error.localizedDescription, privacy: .private)")
                failures[language] = error.localizedDescription
            }
        }
        packs = result; packFailures = failures
    }

    func download(_ language: DictionaryLanguage) {
        guard downloadTasks[language] == nil else { return }
        let token = UUID()
        downloadTokens[language] = token
        downloadProgress[language] = 0
        downloadTasks[language] = Task {
            let archive = FileManager.default.temporaryDirectory.appendingPathComponent("maxfluency-dictionary-\(UUID().uuidString).zip")
            defer {
                FileCleanup.remove(archive)
                downloadTasks[language] = nil; downloadProgress[language] = nil; preparingDownloads.remove(language)
                downloadTokens[language] = nil
            }
            do {
                guard let full, let manifest, let repository else { throw PlatformFailure.message("Dictionary storage is not ready.") }
                let operation = DictionaryDownload(destination: archive) { progress in
                    Task { @MainActor in
                        if self.downloadTokens[language] == token { self.downloadProgress[language] = progress }
                    }
                }
                _ = try await operation.run(url: PublicDictionaries.url(language))
                preparingDownloads.insert(language); downloadProgress[language] = 0
                _ = try await full.install(language: language, archive: archive, policy: manifest.discovery) { progress in
                    Task { @MainActor in
                        if self.downloadTokens[language] == token { self.downloadProgress[language] = progress }
                    }
                }
                await repository.resetCache()
                try await loadPackInfo(); try await loadDaily()
                startupFailed = false
            } catch is CancellationError {}
            catch { report(error, "error_download") }
        }
    }

    func cancelDownload(_ language: DictionaryLanguage) { downloadTasks[language]?.cancel() }

    func removeDownload(_ language: DictionaryLanguage) {
        Task {
            do {
                guard let full, let repository else { throw PlatformFailure.message("Dictionary storage is not ready.") }
                try await full.remove(language: language)
                await repository.resetCache()
                try await loadPackInfo(); try await loadDaily()
                startupFailed = false
            } catch { report(error, "error_download") }
        }
    }

    func exportDeck(_ entries: [WordEntry]) async -> URL? {
        guard !exporting else { return nil }
        exporting = true
        defer { exporting = false }
        do { return try await exporter.export(entries: entries) }
        catch { report(error, "anki_export_failed"); return nil }
    }

    func enableReminders(_ enabled: Bool) {
        guard !savingSettings, !remindersBusy else { return }
        Task {
            do {
                if enabled { try await reminders.authorize() }
                updateSettings { $0.reminderEnabled = enabled }
            } catch { report(error, "reminder_permission_needed") }
        }
    }

    func queueReminderRefresh() {
        let previous = reminderTask
        previous?.cancel()
        let settings = settings
        AppDelegate.scheduleRefresh(enabled: settings.reminderEnabled)
        reminderTask = Task {
            _ = await previous?.value
            guard !Task.isCancelled, let repository else { return false }
            remindersBusy = true
            defer { remindersBusy = false }
            do { try await reminders.reschedule(settings: settings, repository: repository); return true }
            catch is CancellationError { return false }
            catch { report(error, "error_reminder"); return false }
        }
    }

    func backgroundRefresh() async -> Bool {
        await start()
        guard !startupFailed, !Task.isCancelled else { return false }
        queueReminderRefresh()
        guard let task = reminderTask else { return false }
        let success = await withTaskCancellationHandler(operation: { await task.value }, onCancel: { task.cancel() })
        return success && !Task.isCancelled
    }

    func consumeNotification() async {
        guard !loading, let target = NotificationInbox.shared.pending, let repository else { return }
        NotificationInbox.shared.pending = nil
        do {
            guard target.date <= LocalDay.string() else { throw PlatformFailure.message("A future daily word cannot be opened from this reminder.") }
            guard let code = target.id.split(separator: ":", maxSplits: 1).first.map(String.init),
                  let language = DictionaryLanguage(rawValue: code) else { throw PlatformFailure.message("Notification language is invalid.") }
            let word = try await repository.archivedWord(date: target.date, language: language, level: target.level)
            guard word.entry.id == target.id else { throw PlatformFailure.message("Notification snapshot does not match the requested word.") }
            openArchive(word)
        } catch { report(error, "error_entry") }
    }

    func openExternal(_ address: String) {
        guard let url = URL(string: address), url.scheme?.lowercased() == "https" else {
            report(PlatformFailure.message("The source address is invalid."), "error_source"); return
        }
        Task { if !(await UIApplication.shared.open(url)) { problem = text("error_source") } }
    }

    func openSystemSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { problem = text("error_settings"); return }
        Task { if !(await UIApplication.shared.open(url)) { problem = text("error_settings") } }
    }
}
