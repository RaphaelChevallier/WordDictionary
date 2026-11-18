import Foundation

private struct AppleAssetIntegrity: Decodable {
    let schemaVersion: Int
    let compression: String
    let rankHashes: [String: String]
}

public actor LexiconRepository {
    private let resourceURL: URL
    private let store: PersonalStore
    private let external: (any ExternalLexicon)?
    private let loadedManifest: AppleManifest
    private let ranks: VocabularyRanks
    private let online: OnlineWordCache
    private let guides: PronunciationGuides
    private var readers: [DictionaryLanguage: AppleLexicon] = [:]
    private var prepared: [String: PreparedEntry] = [:]
    private var preparedOrder: [String] = []
    private var preparedDaily: [String: PreparedEntry] = [:]
    private var generation: UInt64 = 0

    public init(resourceURL: URL, store: PersonalStore, external: (any ExternalLexicon)? = nil) throws {
        let data = try Data(contentsOf: resourceURL.appendingPathComponent("apple").appendingPathComponent("manifest.json"))
        let manifest = try JSONDecoder().decode(AppleManifest.self, from: data)
        let integrity = try JSONDecoder().decode(AppleAssetIntegrity.self, from: data)
        let codes = Set(DictionaryLanguage.allCases.map(\.rawValue))
        guard integrity.schemaVersion == 1, integrity.compression == "zlib-frames",
              !manifest.releaseVersion.isEmpty, !manifest.attribution.isEmpty,
              manifest.languages.count == codes.count, Set(manifest.languages.map(\.language)) == codes,
              Set(manifest.discovery.excludedWords.keys) == codes,
              Set(integrity.rankHashes.keys) == codes else {
            throw DictionaryError.invalidData("The bundled Apple manifest is incompatible with this app.")
        }
        var total = 0
        for info in manifest.languages {
            guard info.file == "\(info.language).mfap", (1...499_999).contains(info.entries),
                  info.dailyEntries >= 21, info.dailyEntries <= info.entries,
                  (12...536_870_912).contains(info.compressedBytes),
                  info.sha256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else {
                throw DictionaryError.invalidData("Invalid offline language-pack metadata.")
            }
            total += info.entries
        }
        guard total < 500_000 else { throw DictionaryError.storage("The starter bank exceeds the temporary word-storage limit.") }
        let ranksDirectory = try VocabularyRanks.rankDirectory(resourceURL: resourceURL)
        for language in DictionaryLanguage.allCases {
            let data = try Data(contentsOf: ranksDirectory.appendingPathComponent("\(language.rawValue).txt"), options: .mappedIfSafe)
            guard sha256(data) == integrity.rankHashes[language.rawValue] else {
                throw DictionaryError.invalidData("Vocabulary frequency rank integrity check failed for \(language.rawValue).")
            }
        }
        self.resourceURL = resourceURL
        self.store = store
        self.external = external
        loadedManifest = manifest
        ranks = try VocabularyRanks(resourceURL: resourceURL)
        guides = PronunciationGuides(resourceURL: resourceURL)
        online = try OnlineWordCache(url: store.directory.appendingPathComponent("online-ios-v1.sqlite"), maximumWords: 500_000 - total)
    }

    public func manifest() -> AppleManifest { loadedManifest }

    public func preparedEntries() -> [String: PreparedEntry] {
        prepared.merging(preparedDaily) { _, pinned in pinned }
    }

    public func daily(language: DictionaryLanguage, date: String, level: VocabularyLevel) async throws -> DailyWords {
        let context = try DailyContext(date: date, language: language, level: level)
        if await store.daily(context.key) == nil {
            let entries = try await discoveryCandidates(context, round: 0, excluded: [], limit: 21)
            let created = try await store.createDaily(context, candidates: entries)
            if created.inserted { try online.seen(created.selection.entries.map(\.id)) }
        }
        return try await prepareCurrent(context)
    }

    public func refreshGarden(language: DictionaryLanguage, date: String, level: VocabularyLevel, round: Int,
                              visible: Set<String>) async throws -> DailyWords? {
        let context = try DailyContext(date: date, language: language, level: level)
        guard let current = await store.daily(context.key) else { throw DictionaryError.unavailable("The garden selection is missing.") }
        guard current.round == round else { return try await prepareCurrent(context) }
        let ids = Set(current.entries.dropFirst().map(\.id))
        guard !visible.isEmpty, visible.isSubset(of: ids), let featured = current.entries.first else {
            throw DictionaryError.invalidQuery("Refresh requires words from the current garden.")
        }
        guard round < Int.max else { throw DictionaryError.storage("The garden round counter is exhausted.") }
        let shown = current.shown.union(current.explored).union(visible)
        let fresh = try await discoveryCandidates(context, round: round + 1, excluded: shown.union([featured.id]), limit: 20)
        guard await store.daily(context.key) == current else { return try await prepareCurrent(context) }
        guard !fresh.isEmpty else { return nil }
        let next = try await store.replaceGarden(context, expected: current, entries: [featured] + fresh, shown: shown)
        if next.committed { try online.seen(next.selection.entries.dropFirst().map(\.id)) }
        return try await prepareCurrent(context)
    }

    public func exploreGarden(language: DictionaryLanguage, date: String, level: VocabularyLevel, round: Int,
                              id: String) async throws -> DailyWords {
        let context = try DailyContext(date: date, language: language, level: level)
        _ = try await store.exploreDaily(context, round: round, id: id)
        return try await prepareCurrent(context)
    }

    public func entry(id: String) async throws -> WordEntry? { try await readEntry(id) }

    public func search(language: DictionaryLanguage, query: String, mode: SearchMode) async throws -> [WordEntry] {
        let plan = try SearchPlan(query: query, mode: mode)
        if case .empty = plan { return [] }
        let started = generation
        let full = try await external?.search(language: language, query: query, mode: mode, limit: 60) ?? []
        var seen = Set<String>()
        let candidates = full + (try online.search(language: language, plan: plan)) + (try reader(language).search(plan))
        var result: [WordEntry] = []
        for candidate in candidates where seen.insert(candidate.id).inserted {
            try Task.checkCancellation()
            try candidate.validate()
            guard candidate.language == language, plan.matches(candidate.word) else {
                throw DictionaryError.invalidData("An external dictionary returned an unrelated search result.")
            }
            let word = try enrich(candidate)
            result.append(word)
            if started == generation { putPrepared(PreparedEntry(entry: word)) }
            if result.count == 60 { break }
        }
        return result
    }

    public func calendarMonth(month: String, language: DictionaryLanguage, level: VocabularyLevel,
                              today: String) async throws -> [ArchivedDailyWord] {
        _ = try LocalDay.date(today)
        guard month.range(of: "^[0-9]{4}-[0-9]{2}$", options: .regularExpression) != nil,
              month <= LocalDay.month(today) else {
            throw DictionaryError.invalidQuery("A daily word calendar month must not be in the future.")
        }
        var date = month + "-01"
        _ = try LocalDay.date(date)
        let existing = await store.archivedMonth(month: month, language: language, level: level)
        let dates = Set(existing.map(\.date))
        let dictionary = try reader(language)
        let positions = dictionary.dailyOrdinals.filter { dictionary.rows[$0].level == level }
        guard !positions.isEmpty else { throw DictionaryError.unavailable("No offline words are available for this calendar level.") }
        var additions: [ArchivedDailyWord] = []
        while LocalDay.month(date) == month && date <= today {
            try Task.checkCancellation()
            if !dates.contains(date) {
                let context = try DailyContext(date: date, language: language, level: level)
                let selected = await store.daily(context.key)?.entries.first
                let entry: WordEntry
                if let selected = selected { entry = selected }
                else {
                    let position = try calendarDailyIndex(date: date, language: language, total: positions.count)
                    entry = try guides.enrich(dictionary.entry(at: positions[position]))
                }
                additions.append(ArchivedDailyWord(date: date, level: level, entry: entry, backfilled: selected == nil))
            }
            // Avoid adding beyond the final representable civil date.
            if date == "9999-12-31" { break }
            date = try LocalDay.adding(1, to: date)
        }
        try await store.saveArchives(additions)
        return await store.archivedMonth(month: month, language: language, level: level).filter { $0.date <= today }
    }

    public func archivedWord(date: String, language: DictionaryLanguage, level: VocabularyLevel) async throws -> ArchivedDailyWord {
        _ = try LocalDay.date(date)
        guard let word = await store.archive(date: date, language: language, level: level) else {
            throw DictionaryError.unavailable("The archived daily word is missing. Open that calendar month to assign missing past dates.")
        }
        return word
    }

    public func planArchivedWord(date: String, language: DictionaryLanguage, level: VocabularyLevel) async throws -> ArchivedDailyWord {
        let context = try DailyContext(date: date, language: language, level: level)
        if let existing = await store.archive(date: date, language: language, level: level) { return existing }
        let dictionary = try reader(language)
        let positions = dictionary.dailyOrdinals.filter { dictionary.rows[$0].level == level }
        let index = try calendarDailyIndex(date: date, language: language, total: positions.count)
        let candidate = try guides.enrich(dictionary.entry(at: positions[index]))
        return try await store.planArchive(context, candidate: candidate, backfilled: false)
    }

    public func assessment(language: DictionaryLanguage) async throws -> [WordEntry] {
        let dictionary = try reader(language)
        var result: [WordEntry] = []
        for level in VocabularyLevel.allCases {
            let positions = dictionary.dailyOrdinals.filter { dictionary.rows[$0].level == level }.shuffled().prefix(2)
            guard positions.count == 2 else { throw DictionaryError.unavailable("The local dictionary cannot provide six vocabulary-check words. Retry after restoring the bundled content.") }
            for position in positions {
                let word = try guides.enrich(dictionary.entry(at: position))
                result.append(word)
                putPrepared(PreparedEntry(entry: word))
            }
        }
        return result
    }

    public func answer(_ entry: WordEntry, correct: Bool) async throws {
        try await store.answer(entry, correct: correct)
        if correct { try online.correct(entry.id) }
    }

    public func cacheOnline(_ entry: WordEntry) async throws {
        try entry.validate()
        let complete = try enrich(entry)
        let installed = try await external?.entry(language: entry.language, word: entry.word)
        if let installed = installed {
            try installed.validate()
            guard installed.id == entry.id, installed.language == entry.language, installed.word == entry.word else {
                throw DictionaryError.invalidData("The installed dictionary returned a mismatched word identity.")
            }
        }
        let preferred = try enrich(installed ?? complete)
        let protected = await store.protectedIDs(date: LocalDay.string())
        try online.put(complete, level: ranks.level(language: entry.language, word: entry.word), protectedIDs: protected)
        let prepared = PreparedEntry(entry: preferred)
        putPrepared(prepared)
        if preparedDaily[entry.id] != nil { preparedDaily[entry.id] = prepared }
    }

    public func resetCache() {
        generation &+= 1
        readers.removeAll()
        prepared.removeAll()
        preparedOrder.removeAll()
        preparedDaily.removeAll()
        guides.reset()
    }

    private func discoveryCandidates(_ context: DailyContext, round: Int, excluded: Set<String>, limit: Int) async throws -> [WordEntry] {
        let dictionary = try reader(context.language)
        let fullWords = try await external?.candidateWords(language: context.language, level: context.level,
                                                          day: context.date, round: round) ?? []
        var seen = Set<String>()
        let allIDs = fullWords.map { "\(context.language.rawValue):\($0)" }
            + (try online.candidateIDs(language: context.language, level: context.level))
            + dictionary.dailyOrdinals.map { "\(context.language.rawValue):\(dictionary.rows[$0].word)" }
        let candidates = allIDs.filter { !excluded.contains($0) && seen.insert($0).inserted }
        let familiarity = await store.familiarity(candidates)
        let seed = round == 0 ? context.date : "\(context.date):\(round)"
        let ranked = try candidates.enumerated().map { index, id -> (String, Int, Bool, Int, Int32, Int) in
            let (_, word) = try parseID(id)
            let difference = abs(ranks.level(language: context.language, word: word).ordinal - context.level.ordinal)
            return (id, difference, (familiarity[id]?.correct ?? 0) >= 2, familiarity[id]?.views ?? 0,
                    SearchKeys.javaHash("\(seed):\(id)") ^ Int32(0x5f3759df), index)
        }.sorted {
            if $0.1 != $1.1 { return $0.1 < $1.1 }
            if $0.2 != $1.2 { return !$0.2 }
            if $0.3 != $1.3 { return $0.3 < $1.3 }
            if $0.4 != $1.4 { return $0.4 < $1.4 }
            return $0.5 < $1.5
        }
        var result: [WordEntry] = []
        for candidate in ranked {
            try Task.checkCancellation()
            guard let word = try await readEntry(candidate.0),
                  discoveryPreview(word, policy: loadedManifest.discovery) != nil else { continue }
            result.append(word)
            if result.count == limit { break }
        }
        return result
    }

    private func prepareCurrent(_ context: DailyContext) async throws -> DailyWords {
        while true {
            try Task.checkCancellation()
            guard let selection = await store.daily(context.key) else {
                throw DictionaryError.unavailable("The garden selection is missing.")
            }
            let started = generation
            var complete: [PreparedEntry] = []
            for snapshot in selection.entries {
                let live = try await readEntry(snapshot.id)
                if let live = live {
                    complete.append(PreparedEntry(entry: live))
                } else {
                    complete.append(PreparedEntry(entry: try guides.enrich(snapshot), archived: true))
                }
            }
            let latest = await store.daily(context.key)
            guard started == generation, latest == selection else { continue }
            guard let featured = complete.first else { throw DictionaryError.invalidData("Daily discovery has no featured word.") }
            preparedDaily = Dictionary(uniqueKeysWithValues: complete.map { ($0.entry.id, $0) })
            let previews = complete.map { discoveryPreview($0.entry, policy: loadedManifest.discovery) ?? $0.entry }
            guard let preview = previews.first else { throw DictionaryError.invalidData("Daily discovery has no featured preview.") }
            return DailyWords(date: context.date, featured: preview, grid: Array(previews.dropFirst()),
                              explored: selection.explored, round: selection.round, featuredMeaningCount: featured.entry.senses.count)
        }
    }

    private func readEntry(_ id: String) async throws -> WordEntry? {
        let (language, word) = try parseID(id)
        if let cached = preparedDaily[id], !cached.archived { return cached.entry }
        if let cached = prepared[id], !cached.archived { touch(id); return cached.entry }
        let started = generation
        let installed = try await external?.entry(language: language, word: word)
        let found = try installed ?? online.entry(id: id) ?? reader(language).entry(word: word)
        guard let found = found else { return nil }
        try found.validate()
        guard found.id == id, found.language == language, found.word == word else {
            throw DictionaryError.invalidData("The installed dictionary returned a mismatched word identity.")
        }
        let result = try enrich(found)
        if generation == started { putPrepared(PreparedEntry(entry: result)) }
        return result
    }

    private func enrich(_ entry: WordEntry) throws -> WordEntry {
        let bundled = try reader(entry.language).entry(word: entry.word)
        var original = entry
        original.pronunciationGuide = ""
        if original == bundled { return try guides.enrich(entry) }
        return try guides.enrich(entry.withUsageExamples(from: bundled))
    }

    private func reader(_ language: DictionaryLanguage) throws -> AppleLexicon {
        if let reader = readers[language] { return reader }
        guard let info = loadedManifest.languages.first(where: { $0.language == language.rawValue }) else {
            throw DictionaryError.invalidData("The offline language pack is missing from the manifest.")
        }
        let reader = try AppleLexicon(url: resourceURL.appendingPathComponent("apple").appendingPathComponent(info.file), info: info)
        guard reader.rows.allSatisfy({ $0.level == ranks.level(language: language, word: $0.word) }) else {
            throw DictionaryError.invalidData("The dictionary frequency bands do not match the pinned ranks.")
        }
        readers[language] = reader
        return reader
    }

    private func parseID(_ id: String) throws -> (DictionaryLanguage, String) {
        guard let separator = id.firstIndex(of: ":"),
              let language = DictionaryLanguage(rawValue: String(id[..<separator])) else {
            throw DictionaryError.invalidData("Unsupported dictionary entry language.")
        }
        let word = String(id[id.index(after: separator)...])
        guard !word.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              word.utf16.count <= 1024, !word.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else {
            throw DictionaryError.invalidData("Invalid dictionary entry headword.")
        }
        return (language, word)
    }

    private func putPrepared(_ value: PreparedEntry) {
        prepared[value.entry.id] = value
        touch(value.entry.id)
        while preparedOrder.count > 128 { prepared.removeValue(forKey: preparedOrder.removeFirst()) }
    }

    private func touch(_ id: String) { preparedOrder.removeAll { $0 == id }; preparedOrder.append(id) }
}
