import Foundation

struct DailyContext: Sendable {
    let date: String
    let language: DictionaryLanguage
    let level: VocabularyLevel
    var key: String { "\(date):\(language.rawValue):\(level.rawValue)" }
    init(date: String, language: DictionaryLanguage, level: VocabularyLevel) throws {
        _ = try LocalDay.date(date)
        self.date = date; self.language = language; self.level = level
    }
    init(key: String) throws {
        let parts = key.split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count == 3, let language = DictionaryLanguage(rawValue: String(parts[1])),
              let level = VocabularyLevel(rawValue: String(parts[2])) else {
            throw DictionaryError.invalidData("Invalid stored daily selection key.")
        }
        try self.init(date: String(parts[0]), language: language, level: level)
    }
}

struct DailySelection: Codable, Equatable, Sendable {
    let entries: [WordEntry]
    let round: Int
    let explored: Set<String>
    let shown: Set<String>
    init(entries: [WordEntry], round: Int = 0, explored: Set<String> = [], shown: Set<String> = []) {
        self.entries = entries; self.round = round; self.explored = explored; self.shown = shown
    }
}

struct Familiarity: Codable, Equatable, Sendable {
    var views = 0
    var correct = 0
    mutating func record(correct answer: Bool = false) throws {
        guard views < Int.max, !answer || correct < Int.max else {
            throw DictionaryError.storage("The learning counter is exhausted.")
        }
        views += 1
        if answer { correct += 1 }
    }
}

private struct PersonalState: Codable {
    var schemaVersion = 1
    var settings = AppSettings()
    var words: [String: SavedWord] = [:]
    var learning: [String: Familiarity] = [:]
    var daily: [String: DailySelection] = [:]
    var archives: [String: ArchivedDailyWord] = [:]
}

public actor PersonalStore {
    nonisolated let directory: URL
    private let fileURL: URL
    private var state: PersonalState

    public init(directory: URL) throws {
        self.directory = directory
        fileURL = directory.appendingPathComponent("personal-ios-v1.json")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        if FileManager.default.fileExists(atPath: fileURL.path) {
            let size = try FileManager.default.attributesOfItem(atPath: fileURL.path)[.size] as? NSNumber
            guard let size = size, size.int64Value <= 134_217_728 else {
                throw DictionaryError.storage("The personal data file exceeds its safety limit. Keep the file for recovery.")
            }
            do {
                state = try JSONDecoder().decode(PersonalState.self, from: Data(contentsOf: fileURL))
                try Self.validate(state)
            } catch {
                throw DictionaryError.storage("Could not read personal-ios-v1.json; the original file was retained. \(error.localizedDescription)")
            }
        } else {
            let initial = PersonalState()
            try Self.persist(initial, to: fileURL)
            state = initial
        }
    }

    public func settings() -> AppSettings { state.settings }

    public func updateSettings(_ value: AppSettings) throws {
        try Self.validateSettings(value)
        try transaction { $0.settings = value }
    }

    public func words() -> [SavedWord] { Self.sortedWords(state.words) }

    public func recordViewed(_ entry: WordEntry, now: Date = Date()) throws {
        try entry.validate()
        guard now.timeIntervalSince1970.isFinite else { throw DictionaryError.storage("Invalid history timestamp.") }
        try transaction { next in
            next.words[entry.id] = SavedWord(entry: entry, favorite: next.words[entry.id]?.favorite ?? false, lastViewed: now)
            var familiarity = next.learning[entry.id] ?? Familiarity()
            try familiarity.record()
            next.learning[entry.id] = familiarity
            let recent = Set(Self.sortedWords(next.words).prefix(100).map(\.id))
            next.words = next.words.filter { $0.value.favorite || recent.contains($0.key) }
        }
    }

    public func toggleFavorite(_ entry: WordEntry) throws {
        try entry.validate()
        try transaction { next in
            let previous = next.words[entry.id]
            next.words[entry.id] = SavedWord(entry: entry, favorite: !(previous?.favorite ?? false),
                                             lastViewed: previous?.lastViewed ?? Date())
        }
    }

    public func archive(date: String, language: DictionaryLanguage, level: VocabularyLevel) -> ArchivedDailyWord? {
        state.archives["\(date):\(language.rawValue):\(level.rawValue)"]
    }

    public func saveArchives(_ words: [ArchivedDailyWord]) throws {
        try transaction { next in
            for word in words { try Self.writeArchive(word, to: &next) }
        }
    }

    func archivedMonth(month: String, language: DictionaryLanguage, level: VocabularyLevel) -> [ArchivedDailyWord] {
        state.archives.values.filter {
            LocalDay.month($0.date) == month && $0.entry.language == language && $0.level == level
        }.sorted { $0.date < $1.date }
    }

    func snapshot(_ id: String) -> WordEntry? { state.words[id]?.entry }
    func daily(_ key: String) -> DailySelection? { state.daily[key] }

    func familiarity(_ ids: [String]) -> [String: Familiarity] {
        var result: [String: Familiarity] = [:]
        for id in ids { if let value = state.learning[id] { result[id] = value } }
        return result
    }

    func answer(_ entry: WordEntry, correct: Bool) throws {
        try entry.validate()
        try transaction { next in
            var familiarity = next.learning[entry.id] ?? Familiarity()
            try familiarity.record(correct: correct)
            next.learning[entry.id] = familiarity
        }
    }

    func protectedIDs(date: String) -> Set<String> {
        var result = Set(state.words.values.filter(\.favorite).map(\.id))
        for (key, selection) in state.daily where key.hasPrefix(date + ":") {
            result.formUnion(selection.entries.map(\.id))
        }
        return result
    }

    func createDaily(_ context: DailyContext, candidates: [WordEntry]) throws -> (selection: DailySelection, inserted: Bool) {
        if let existing = state.daily[context.key] { return (existing, false) }
        var committed: DailySelection?
        try transaction { next in
            guard let fallback = candidates.first else { throw DictionaryError.unavailable("No local daily word is available.") }
            let archived = next.archives[context.key]
            let featured = archived?.entry ?? fallback
            let entries = [featured] + Array(candidates.filter { $0.id != featured.id }.prefix(20))
            let selection = DailySelection(entries: entries)
            try Self.validateSelection(selection, context: context)
            guard entries.count == 21 else {
                throw DictionaryError.unavailable("Not enough local words for daily discovery.")
            }
            next.daily[context.key] = selection
            try Self.writeArchive(ArchivedDailyWord(date: context.date, level: context.level, entry: featured), to: &next)
            for entry in entries {
                var familiarity = next.learning[entry.id] ?? Familiarity()
                try familiarity.record()
                next.learning[entry.id] = familiarity
            }
            let oldest = try LocalDay.adding(-7, to: context.date)
            next.daily = next.daily.filter { String($0.key.prefix(10)) >= oldest }
            committed = selection
        }
        guard let committed = committed else { throw DictionaryError.storage("Daily words were not saved.") }
        return (committed, true)
    }

    func exploreDaily(_ context: DailyContext, round: Int, id: String) throws -> DailySelection {
        guard let current = state.daily[context.key] else { throw DictionaryError.unavailable("The garden selection is missing.") }
        guard current.round == round, current.entries.dropFirst().contains(where: { $0.id == id }) else { return current }
        let next = DailySelection(entries: current.entries, round: round, explored: current.explored.union([id]), shown: current.shown)
        try transaction { $0.daily[context.key] = next }
        return next
    }

    func replaceGarden(_ context: DailyContext, expected: DailySelection, entries: [WordEntry], shown: Set<String>) throws -> (selection: DailySelection, committed: Bool) {
        guard let current = state.daily[context.key] else { throw DictionaryError.unavailable("The garden selection is missing.") }
        // A suspended external read may race another refresh or exploration. Never overwrite that progress.
        guard current == expected else { return (current, false) }
        guard current.round < Int.max else { throw DictionaryError.storage("The garden round counter is exhausted.") }
        let selection = DailySelection(entries: entries, round: current.round + 1, shown: shown)
        try Self.validateSelection(selection, context: context)
        guard selection.entries.first?.id == current.entries.first?.id else {
            throw DictionaryError.storage("Refreshing the garden must preserve its featured word.")
        }
        try transaction { next in
            next.daily[context.key] = selection
            for entry in entries.dropFirst() {
                var familiarity = next.learning[entry.id] ?? Familiarity()
                try familiarity.record()
                next.learning[entry.id] = familiarity
            }
        }
        return (selection, true)
    }

    func planArchive(_ context: DailyContext, candidate: WordEntry, backfilled: Bool) throws -> ArchivedDailyWord {
        if let existing = state.archives[context.key] { return existing }
        let chosen = state.daily[context.key]?.entries.first ?? candidate
        let value = ArchivedDailyWord(date: context.date, level: context.level, entry: chosen,
                                      backfilled: state.daily[context.key] == nil && backfilled)
        try transaction { next in try Self.writeArchive(value, to: &next) }
        guard let saved = state.archives[context.key] else { throw DictionaryError.storage("Daily word archive write failed.") }
        return saved
    }

    private func transaction(_ mutation: (inout PersonalState) throws -> Void) throws {
        var next = state
        try mutation(&next)
        try Self.persist(next, to: fileURL)
        state = next
    }

    private static func sortedWords(_ words: [String: SavedWord]) -> [SavedWord] {
        words.values.sorted {
            $0.lastViewed == $1.lastViewed ? utf8Less($0.id, $1.id) : $0.lastViewed > $1.lastViewed
        }
    }

    private static func persist(_ value: PersonalState, to url: URL) throws {
        do {
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            let data = try encoder.encode(value)
            guard data.count <= 134_217_728 else { throw DictionaryError.storage("The personal data file exceeds its safety limit.") }
            try data.write(to: url, options: [.atomic])
        } catch { throw DictionaryError.storage("Atomic personal-data write failed; the prior snapshot was retained. \(error.localizedDescription)") }
    }

    private static func validateSettings(_ settings: AppSettings) throws {
        guard (0...1439).contains(settings.reminderMinute),
              settings.levels.keys.allSatisfy({ DictionaryLanguage(rawValue: $0) != nil }),
              settings.interfaceLanguage.utf8.count <= 64,
              !settings.interfaceLanguage.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else {
            throw DictionaryError.storage("Invalid saved reminder time, interface language or vocabulary level key.")
        }
    }

    private static func validateSelection(_ selection: DailySelection, context: DailyContext) throws {
        guard (2...21).contains(selection.entries.count), selection.round >= 0,
              Set(selection.entries.map(\.id)).count == selection.entries.count,
              selection.entries.allSatisfy({ $0.language == context.language }),
              selection.explored.isSubset(of: Set(selection.entries.dropFirst().map(\.id))),
              selection.shown.allSatisfy({ $0.hasPrefix(context.language.rawValue + ":") }) else {
            throw DictionaryError.storage("Stored daily discovery is invalid.")
        }
        for entry in selection.entries { try entry.validate() }
    }

    private static func writeArchive(_ word: ArchivedDailyWord, to next: inout PersonalState) throws {
        _ = try LocalDay.date(word.date)
        try word.entry.validate()
        if let existing = next.archives[word.id] {
            guard word.backfilled || existing.entry.id == word.entry.id else {
                throw DictionaryError.storage("A saved daily word cannot be replaced.")
            }
            if !word.backfilled && existing.backfilled {
                next.archives[word.id] = ArchivedDailyWord(date: existing.date, level: existing.level,
                                                           entry: existing.entry, backfilled: false)
            }
        } else { next.archives[word.id] = word }
    }

    private static func validate(_ value: PersonalState) throws {
        guard value.schemaVersion == 1 else {
            throw DictionaryError.storage("Unsupported iOS personal schema; a non-destructive migration is required.")
        }
        try validateSettings(value.settings)
        for (id, word) in value.words {
            try word.entry.validate()
            guard id == word.id, word.lastViewed.timeIntervalSince1970.isFinite else {
                throw DictionaryError.storage("Invalid saved-word identity or timestamp.")
            }
        }
        for (id, familiarity) in value.learning {
            guard let colon = id.firstIndex(of: ":"), DictionaryLanguage(rawValue: String(id[..<colon])) != nil,
                  id.index(after: colon) < id.endIndex, familiarity.views >= 0,
                  familiarity.correct >= 0, familiarity.correct <= familiarity.views else {
                throw DictionaryError.storage("Invalid saved learning record.")
            }
        }
        for (key, selection) in value.daily {
            let context = try DailyContext(key: key)
            try validateSelection(selection, context: context)
            guard value.archives[key]?.entry.id == selection.entries.first?.id else {
                throw DictionaryError.storage("Daily selection does not match its immutable featured archive.")
            }
        }
        for (key, archive) in value.archives {
            _ = try LocalDay.date(archive.date)
            try archive.entry.validate()
            guard key == archive.id else { throw DictionaryError.storage("Invalid daily-word archive identity.") }
        }
    }
}
