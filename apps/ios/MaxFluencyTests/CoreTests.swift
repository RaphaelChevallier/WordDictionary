import Foundation
import XCTest
@testable import MaxFluency

// Supplied for Xcode on the user's Mac. These Apple SDK tests are not executable on Windows.
final class CoreTests: XCTestCase {
    private func resources() throws -> URL {
        let locations = [Bundle.main.resourceURL, Bundle(for: CoreTests.self).resourceURL].compactMap { $0 }
        return try XCTUnwrap(locations.first {
            FileManager.default.fileExists(atPath: $0.appendingPathComponent("apple").appendingPathComponent("manifest.json").path)
        }, "The app/test host must bundle apple, pronunciations and licenses folder references.")
    }

    private func directory() throws -> URL {
        let support = try XCTUnwrap(FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first)
        let url = support.appendingPathComponent("MaxFluencyCoreTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        addTeardownBlock { try FileManager.default.removeItem(at: url) }
        return url
    }

    private func pack(_ language: DictionaryLanguage, at resourceURL: URL) throws -> AppleLexicon {
        let manifest = try JSONDecoder().decode(AppleManifest.self, from: Data(contentsOf:
            resourceURL.appendingPathComponent("apple").appendingPathComponent("manifest.json")))
        let info = try XCTUnwrap(manifest.languages.first { $0.language == language.rawValue })
        return try AppleLexicon(url: resourceURL.appendingPathComponent("apple").appendingPathComponent(info.file), info: info)
    }

    private func sample(_ word: String, language: DictionaryLanguage = .english) -> WordEntry {
        WordEntry(id: "\(language.rawValue):\(word)", language: language, word: word,
                  senses: [Sense(partOfSpeech: "noun", definition: "A complete fixture meaning.", sourceIds: ["fixture"],
                                 examples: ["This sentence belongs to this meaning."])],
                  sources: [DictionarySource(id: "fixture", edition: language.rawValue, title: "Fixture", license: "CC-BY-SA-4.0")])
    }

    func testNativePackReadsCompleteShippedRecordsAndRejectsDamage() throws {
        let resourceURL = try resources()
        var total = 0
        let ranks = try VocabularyRanks(resourceURL: resourceURL)
        for language in DictionaryLanguage.allCases {
            let dictionary = try pack(language, at: resourceURL)
            total += dictionary.rows.count
            for position in [0, dictionary.rows.count / 2, dictionary.rows.count - 1] {
                let entry = try dictionary.entry(at: position)
                XCTAssertEqual(entry, try dictionary.entry(word: entry.word))
                XCTAssertEqual(entry.id, "\(language.rawValue):\(dictionary.rows[position].word)")
                XCTAssertFalse(entry.sources.isEmpty)
                XCTAssertTrue(entry.senses.allSatisfy { !$0.definition.isEmpty && !$0.sourceIds.isEmpty })
                XCTAssertEqual(dictionary.rows[position].level, ranks.level(language: language, word: entry.word))
            }
            XCTAssertEqual(Set(dictionary.dailyOrdinals).count, dictionary.rows.filter(\.daily).count)
        }
        XCTAssertEqual(total, 8235)
        let url = resourceURL.appendingPathComponent("apple").appendingPathComponent("fr.mfap")
        let original = try Data(contentsOf: url)
        XCTAssertThrowsError(try AppleLexicon(data: original.prefix(11)))
        XCTAssertThrowsError(try AppleLexicon(data: original.dropLast()))
        var damaged = original
        damaged[damaged.count - 1] ^= 0xff
        let dictionary = try AppleLexicon(data: damaged)
        XCTAssertThrowsError(try dictionary.entry(at: dictionary.rows.count - 1))
    }

    func testDeflateChecksLengthChecksumAndRawStreams() throws {
        let raw = try XCTUnwrap(Data(base64Encoded: "y0jNyclXyEssySxLVcj0DwYA"))
        let expected = Data("hello native iOS".utf8)
        XCTAssertEqual(try Deflate.decode(raw, expectedBytes: expected.count, raw: true), expected)
        XCTAssertThrowsError(try Deflate.decode(raw, expectedBytes: expected.count - 1, raw: true))
        XCTAssertThrowsError(try Deflate.decode(raw, expectedBytes: expected.count + 1, raw: true))
        XCTAssertThrowsError(try Deflate.decode(raw.dropLast(), expectedBytes: expected.count, raw: true))
        XCTAssertEqual(try Deflate.decode(raw.dropLast(), expectedBytes: expected.count, raw: true, allowIncomplete: true), expected)
        XCTAssertThrowsError(try Deflate.decode(raw + Data([0]), expectedBytes: expected.count, raw: true))
    }

    func testPronunciationBinaryLookupAndEnglishOnlyEnrichment() async throws {
        let resourceURL = try resources()
        struct Manifest: Decodable { let file: String; let entries: Int }
        let folder = resourceURL.appendingPathComponent("pronunciations")
        let manifest = try JSONDecoder().decode(Manifest.self, from: Data(contentsOf: folder.appendingPathComponent("manifest.json")))
        let index = try PronunciationIndex(url: folder.appendingPathComponent(manifest.file))
        XCTAssertEqual(index.entries, manifest.entries)
        XCTAssertEqual(try index.lookup("disaster"), "dih-ZAS-ter")
        XCTAssertNotNil(try index.lookup("colonel"))
        XCTAssertNil(try index.lookup("NASA"))
        XCTAssertNil(try index.lookup("école"))
        XCTAssertNil(try index.lookup("not-a-real-english-headword"))
        let guides = PronunciationGuides(resourceURL: resourceURL)
        var french = sample("disaster", language: .french)
        french.pronunciation = "/raw-ipa-metadata/"
        french.pronunciationGuide = "must not leak an English respelling"
        let enriched = try guides.enrich(french)
        XCTAssertTrue(enriched.pronunciationGuide.isEmpty)
        XCTAssertEqual(enriched.pronunciation, french.pronunciation)
        let damaged = try directory().appendingPathComponent("damaged.mfpr")
        let bytes = try Data(contentsOf: folder.appendingPathComponent(manifest.file))
        try bytes.dropLast().write(to: damaged)
        XCTAssertThrowsError(try PronunciationIndex(url: damaged))
    }

    func testCivilDatesAndKotlinSearchKeys() throws {
        XCTAssertEqual(SearchKeys.normalize("  CŒUR  ÉTÉ\tStraße ’ "), "coeur ete strasse '")
        XCTAssertEqual(SearchKeys.anagram("Côté!"), "ceot")
        XCTAssertEqual(SearchKeys.javaHash("😀"), 1_772_899, "Java hashes signed UTF-16 code units, not Swift Characters.")
        XCTAssertEqual(SearchKeys.javaHash("polygenelubricants"), Int32.min)
        XCTAssertEqual(try LocalDay.adding(1, to: "2024-02-28"), "2024-02-29")
        XCTAssertEqual(try LocalDay.adding(1, to: "2024-12-31"), "2025-01-01")
        XCTAssertEqual(try LocalDay.adding(1, to: "2026-03-08"), "2026-03-09")
        XCTAssertEqual(LocalDay.string(try LocalDay.date("2021-01-01")), "2021-01-01")
        for invalid in ["2023-02-29", "2024-02-30", "0000-01-01", "2024-2-01", "2024-01-01extra", "2024-13-01"] {
            XCTAssertThrowsError(try LocalDay.date(invalid))
        }
    }

    func testSearchNormalizesAccentsSupportsWildcardAndAnagramsAndCapsResults() async throws {
        let store = try PersonalStore(directory: directory())
        let repository = try LexiconRepository(resourceURL: resources(), store: store)
        let prefix = try await repository.search(language: .french, query: "COTE", mode: .words)
        XCTAssertTrue(prefix.contains { $0.word == "côté" })
        let wildcard = try await repository.search(language: .french, query: "c.t.", mode: .words)
        XCTAssertTrue(wildcard.contains { $0.word == "côté" })
        let star = try await repository.search(language: .french, query: "c*té", mode: .words)
        XCTAssertTrue(star.contains { $0.word == "côté" })
        let anagrams = try await repository.search(language: .french, query: "etoc", mode: .anagrams)
        XCTAssertTrue(anagrams.contains { $0.word == "côté" })
        let all = try await repository.search(language: .english, query: "*", mode: .words)
        XCTAssertEqual(all.count, 60)
        let blank = try await repository.search(language: .english, query: " \n ", mode: .anagrams)
        XCTAssertTrue(blank.isEmpty)
        do {
            _ = try await repository.search(language: .english, query: "123?!", mode: .anagrams)
            XCTFail("Anagrams with no letters must report an actionable query error.")
        } catch { XCTAssertTrue(error is DictionaryError) }
    }

    func testFavoritesHistoryAndImmutableArchivesSurviveReopeningWithoutResettingCorruption() async throws {
        let directory = try directory()
        let store = try PersonalStore(directory: directory)
        let complete = try XCTUnwrap(pack(.english, at: resources()).entry(word: "disaster"))
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        try await store.recordViewed(complete, now: now)
        try await store.toggleFavorite(complete)
        let archive = ArchivedDailyWord(date: "2024-02-29", level: .intermediate, entry: complete, backfilled: true)
        try await store.saveArchives([archive])
        var changed = complete
        changed.senses = [Sense(partOfSpeech: "noun", definition: "Later source text must not replace the original.")]
        try await store.saveArchives([ArchivedDailyWord(date: archive.date, level: archive.level, entry: changed)])
        let reopened = try PersonalStore(directory: directory)
        let saved = await reopened.words()
        XCTAssertEqual(saved.first?.entry, complete)
        XCTAssertEqual(saved.first?.lastViewed, now)
        XCTAssertEqual(saved.first?.favorite, true)
        let immutable = await reopened.archive(date: archive.date, language: .english, level: archive.level)
        XCTAssertEqual(immutable?.entry, complete)
        XCTAssertEqual(immutable?.backfilled, false)
        let addition = ArchivedDailyWord(date: "2024-03-01", level: .intermediate, entry: complete)
        do {
            try await reopened.saveArchives([addition,
                ArchivedDailyWord(date: archive.date, level: archive.level, entry: sample("different"))])
            XCTFail("Conflicting immutable assignments must fail the entire archive transaction.")
        } catch { XCTAssertTrue(error is DictionaryError) }
        let rolledBack = await reopened.archive(date: addition.date, language: .english, level: addition.level)
        XCTAssertNil(rolledBack)
        let file = directory.appendingPathComponent("personal-ios-v1.json")
        let corrupt = Data("{not valid personal data".utf8)
        try corrupt.write(to: file)
        XCTAssertThrowsError(try PersonalStore(directory: directory))
        XCTAssertEqual(try Data(contentsOf: file), corrupt)
    }

    func testHistoryRetainsLatestHundredAndOlderFavorites() async throws {
        let store = try PersonalStore(directory: directory())
        let favorite = sample("old-favorite")
        try await store.recordViewed(favorite, now: Date(timeIntervalSince1970: 0))
        try await store.toggleFavorite(favorite)
        for i in 1...105 { try await store.recordViewed(sample("word-\(i)"), now: Date(timeIntervalSince1970: Double(i))) }
        let saved = await store.words()
        XCTAssertEqual(saved.count, 101)
        XCTAssertTrue(saved.contains { $0.id == favorite.id && $0.favorite })
        XCTAssertFalse(saved.contains { $0.id == "en:word-1" })
        XCTAssertEqual(saved.first?.id, "en:word-105")
    }

    func testInvalidSettingsDoNotMutateSavedLanguageLevels() async throws {
        let directory = try directory()
        let store = try PersonalStore(directory: directory)
        var settings = await store.settings()
        settings.levels["fr"] = .advanced
        try await store.updateSettings(settings)
        var invalid = settings
        invalid.reminderMinute = 1440
        do {
            try await store.updateSettings(invalid)
            XCTFail("An invalid reminder time must not be persisted.")
        } catch { XCTAssertTrue(error is DictionaryError) }
        let reopened = try PersonalStore(directory: directory)
        let retained = await reopened.settings()
        XCTAssertEqual(retained, settings)
        XCTAssertEqual(retained.level(for: .english), .intermediate)
        XCTAssertEqual(retained.level(for: .french), .advanced)
    }

    func testOnlineCacheCapProtectsSavedWordsAndRollsBackFailedInsert() throws {
        let cache = try OnlineWordCache(url: directory().appendingPathComponent("bounded.sqlite"), maximumWords: 2)
        let first = sample("first"), second = sample("second"), third = sample("third")
        try cache.put(first, level: .beginner, protectedIDs: [])
        try cache.put(second, level: .beginner, protectedIDs: [])
        XCTAssertThrowsError(try cache.put(third, level: .beginner, protectedIDs: [first.id, second.id]))
        XCTAssertNotNil(try cache.entry(id: first.id))
        XCTAssertNotNil(try cache.entry(id: second.id))
        XCTAssertNil(try cache.entry(id: third.id))
        try cache.put(third, level: .beginner, protectedIDs: [first.id])
        XCTAssertNotNil(try cache.entry(id: first.id))
        XCTAssertNil(try cache.entry(id: second.id))
        XCTAssertEqual(try cache.entry(id: third.id)?.senses, third.senses)
    }

    func testDailyPinsFullMeaningsWhilePreviewFiltersGrammarAndOtherEntriesStayBounded() async throws {
        let resourceURL = try resources()
        let store = try PersonalStore(directory: directory())
        let dictionary = try pack(.english, at: resourceURL)
        let full = try XCTUnwrap(dictionary.entry(word: "say"))
        try await store.saveArchives([ArchivedDailyWord(date: "2026-03-08", level: .beginner, entry: full)])
        let repository = try LexiconRepository(resourceURL: resourceURL, store: store)
        let daily = try await repository.daily(language: .english, date: "2026-03-08", level: .beginner)
        XCTAssertEqual(daily.featured.id, full.id)
        XCTAssertLessThan(daily.featured.senses.count, full.senses.count)
        XCTAssertEqual(daily.featuredMeaningCount, full.senses.count)
        let ids = Set(([daily.featured] + daily.grid).map(\.id))
        XCTAssertEqual(ids.count, 21)
        for row in dictionary.rows.prefix(180) { _ = try await repository.entry(id: "en:\(row.word)") }
        let prepared = await repository.preparedEntries()
        XCTAssertLessThanOrEqual(prepared.count, 149)
        for id in ids { XCTAssertNotNil(prepared[id], "Complete daily entries cannot be evicted by normal word browsing.") }
        XCTAssertEqual(prepared[full.id]?.entry.senses, full.senses)
    }

    func testDeterministicSelectionRoundsExplorationAndExhaustionPersist() async throws {
        let resourceURL = try resources()
        let directory = try directory()
        let store = try PersonalStore(directory: directory)
        let repository = try LexiconRepository(resourceURL: resourceURL, store: store)
        let context = try DailyContext(date: "2026-03-08", language: .english, level: .beginner)
        let first = try await repository.daily(language: .english, date: context.date, level: .beginner)
        XCTAssertEqual(([first.featured] + first.grid).prefix(5).map(\.id),
                       ["en:orders", "en:opened", "en:others", "en:needed", "en:nights"])
        let exploredID = try XCTUnwrap(first.grid.first?.id)
        let explored = try await repository.exploreGarden(language: .english, date: context.date, level: .beginner, round: 0, id: exploredID)
        XCTAssertTrue(explored.explored.contains(exploredID))
        let visible = Set(first.grid.map(\.id))
        let refreshed = try await repository.refreshGarden(language: .english, date: context.date, level: .beginner, round: 0, visible: visible)
        let next = try XCTUnwrap(refreshed)
        XCTAssertEqual(next.featured.id, first.featured.id)
        XCTAssertEqual(next.round, 1)
        XCTAssertTrue(next.explored.isEmpty)
        XCTAssertTrue(Set(next.grid.map(\.id)).isDisjoint(with: visible))
        let stale = try await repository.exploreGarden(language: .english, date: context.date, level: .beginner, round: 0, id: exploredID)
        XCTAssertEqual(stale, next)
        let currentValue = await store.daily(context.key)
        let current = try XCTUnwrap(currentValue)
        let dictionary = try pack(.english, at: resourceURL)
        _ = try await store.replaceGarden(context, expected: current, entries: current.entries,
                                          shown: Set(dictionary.rows.map { "en:\($0.word)" }))
        let exhausted = try await repository.refreshGarden(language: .english, date: context.date, level: .beginner,
                                                           round: 2, visible: Set(next.grid.map(\.id)))
        XCTAssertNil(exhausted)
        let reopenedStore = try PersonalStore(directory: directory)
        let reopened = try LexiconRepository(resourceURL: resourceURL, store: reopenedStore)
        let restored = try await reopened.daily(language: .english, date: context.date, level: .beginner)
        XCTAssertEqual(restored.round, 2)
        XCTAssertEqual(restored.grid.map(\.id), next.grid.map(\.id))
        let immutable = try await reopened.archivedWord(date: context.date, language: .english, level: .beginner)
        XCTAssertEqual(immutable.entry.id, first.featured.id)
    }

    func testFutureArchivePlanningDoesNotAlterTodaysGardenProgressOrLearning() async throws {
        let store = try PersonalStore(directory: directory())
        let repository = try LexiconRepository(resourceURL: resources(), store: store)
        let today = "2026-03-08"
        let initial = try await repository.daily(language: .english, date: today, level: .intermediate)
        let reviewed = try XCTUnwrap(initial.grid.first?.id)
        let current = try await repository.exploreGarden(language: .english, date: today, level: .intermediate, round: 0, id: reviewed)
        let future = try LocalDay.adding(3, to: today)
        let dictionary = try pack(.english, at: resources())
        let ids = dictionary.rows.map { "en:\($0.word)" }
        let before = await store.familiarity(ids)
        let settings = await store.settings()
        let planned = try await repository.planArchivedWord(date: future, language: .english, level: .intermediate)
        let repeated = try await repository.planArchivedWord(date: future, language: .english, level: .intermediate)
        XCTAssertEqual(planned, repeated)
        XCTAssertFalse(planned.entry.senses.isEmpty)
        let futureContext = try DailyContext(date: future, language: .english, level: .intermediate)
        let futureGarden = await store.daily(futureContext.key)
        XCTAssertNil(futureGarden)
        let after = await store.familiarity(ids)
        let afterSettings = await store.settings()
        XCTAssertEqual(before, after, "Planning an unseen future word is not a view or answer.")
        XCTAssertEqual(settings, afterSettings)
        let unchanged = try await repository.daily(language: .english, date: today, level: .intermediate)
        XCTAssertEqual(current, unchanged)
        let arrives = try await repository.daily(language: .english, date: future, level: .intermediate)
        XCTAssertEqual(arrives.featured.id, planned.entry.id)
    }

    func testCalendarBackfillsOnceNeverShowsFutureOrChangesToday() async throws {
        let store = try PersonalStore(directory: directory())
        let repository = try LexiconRepository(resourceURL: resources(), store: store)
        let today = try await repository.daily(language: .french, date: "2024-02-13", level: .intermediate)
        let settings = await store.settings()
        let month = try await repository.calendarMonth(month: "2024-02", language: .french, level: .intermediate, today: today.date)
        XCTAssertEqual(month.count, 13)
        XCTAssertTrue(month.allSatisfy { $0.date <= today.date && !$0.entry.senses.isEmpty })
        XCTAssertTrue(month.filter { $0.date != today.date }.allSatisfy(\.backfilled))
        let repeated = try await repository.calendarMonth(month: "2024-02", language: .french, level: .intermediate, today: today.date)
        XCTAssertEqual(month, repeated)
        let unchanged = try await repository.daily(language: .french, date: today.date, level: .intermediate)
        let afterSettings = await store.settings()
        XCTAssertEqual(today, unchanged)
        XCTAssertEqual(settings, afterSettings)
        do {
            _ = try await repository.calendarMonth(month: "2024-03", language: .french, level: .intermediate, today: today.date)
            XCTFail("Future calendar months are not browsable.")
        } catch { XCTAssertTrue(error is DictionaryError) }
    }

    func testAssessmentUsesTwoSourceWordsPerFrequencyBandAndPersistsAnswers() async throws {
        let directory = try directory()
        let store = try PersonalStore(directory: directory)
        let resourceURL = try resources()
        let repository = try LexiconRepository(resourceURL: resourceURL, store: store)
        let ranks = try VocabularyRanks(resourceURL: resourceURL)
        let assessment = try await repository.assessment(language: .spanish)
        XCTAssertEqual(assessment.count, 6)
        for level in VocabularyLevel.allCases {
            XCTAssertEqual(assessment.filter { ranks.level(language: .spanish, word: $0.word) == level }.count, 2)
        }
        for (index, word) in assessment.enumerated() { try await repository.answer(word, correct: index < 3) }
        let reopened = try PersonalStore(directory: directory)
        let familiarity = await reopened.familiarity(assessment.map(\.id))
        XCTAssertEqual(familiarity.values.reduce(0) { $0 + $1.views }, 6)
        XCTAssertEqual(familiarity.values.reduce(0) { $0 + $1.correct }, 3)
    }

    func testExternalDefinitionsTakePriorityAndOnlyUnambiguousBundledExamplesAttach() async throws {
        let resourceURL = try resources()
        let bundled = try XCTUnwrap(pack(.french, at: resourceURL).entry(word: "qui"))
        let sense = try XCTUnwrap(bundled.senses.first { !$0.examples.isEmpty })
        let source = DictionarySource(id: "reader-dict", edition: "fr", title: "French Wiktionary", license: "CC-BY-SA-4.0")
        let complete = WordEntry(id: bundled.id, language: .french, word: bundled.word,
                                 senses: [
                                    Sense(partOfSpeech: sense.partOfSpeech, definition: sense.definition, sourceIds: [source.id]),
                                    Sense(partOfSpeech: "noun", definition: "A complete external-only sense.", sourceIds: [source.id]),
                                 ], sources: [source])
        let store = try PersonalStore(directory: directory())
        let repository = try LexiconRepository(resourceURL: resourceURL, store: store, external: FixedExternal(words: [complete]))
        let value = try await repository.entry(id: complete.id)
        let live = try XCTUnwrap(value)
        XCTAssertEqual(live.senses.count, complete.senses.count)
        XCTAssertEqual(live.senses.first?.examples, sense.examples)
        XCTAssertTrue(live.sources.contains(source))
        XCTAssertTrue(live.sourceLinks.contains { $0.title == "reader.dict" })
        var onlineReplacement = complete
        onlineReplacement.senses = [Sense(partOfSpeech: "noun", definition: "A less complete online response.")]
        try await repository.cacheOnline(onlineReplacement)
        let stillComplete = try await repository.entry(id: complete.id)
        XCTAssertEqual(stillComplete?.senses, live.senses, "An explicit online response must not demote complete installed definitions.")
        var ambiguous = bundled
        ambiguous.senses = [sense, sense]
        XCTAssertTrue(complete.withUsageExamples(from: ambiguous).senses[0].examples.isEmpty)
        let onlineOnly = sample("online-only-fixture")
        try await repository.cacheOnline(onlineOnly)
        await repository.resetCache()
        let retained = try await repository.entry(id: onlineOnly.id)
        XCTAssertEqual(retained?.senses, onlineOnly.senses)
    }

    func testSuspendedRefreshCannotOverwriteConcurrentExploration() async throws {
        let gate = GatedExternal()
        let store = try PersonalStore(directory: directory())
        let repository = try LexiconRepository(resourceURL: resources(), store: store, external: gate)
        let day = "2026-03-08"
        let first = try await repository.daily(language: .english, date: day, level: .beginner)
        let id = try XCTUnwrap(first.grid.last?.id)
        let refresh = Task {
            try await repository.refreshGarden(language: .english, date: day, level: .beginner,
                                                round: 0, visible: Set(first.grid.prefix(5).map(\.id)))
        }
        await gate.waitUntilBlocked()
        _ = try await repository.exploreGarden(language: .english, date: day, level: .beginner, round: 0, id: id)
        await gate.unblock()
        let result = try await refresh.value
        XCTAssertEqual(result?.round, 0)
        XCTAssertEqual(result?.explored, Set([id]))
    }
}

private struct FixedExternal: ExternalLexicon {
    let words: [WordEntry]
    func entry(language: DictionaryLanguage, word: String) async throws -> WordEntry? {
        words.first { $0.language == language && $0.word == word }
    }
    func search(language: DictionaryLanguage, query: String, mode: SearchMode, limit: Int) async throws -> [WordEntry] {
        let plan = try SearchPlan(query: query, mode: mode)
        return Array(words.filter { $0.language == language && plan.matches($0.word) }.prefix(limit))
    }
    func candidateWords(language: DictionaryLanguage, level: VocabularyLevel, day: String, round: Int) async throws -> [String] {
        words.filter { $0.language == language }.map(\.word)
    }
}

private actor GatedExternal: ExternalLexicon {
    private var blocked = false
    private var blockedWaiters: [CheckedContinuation<Void, Never>] = []
    private var release: CheckedContinuation<Void, Never>?
    func entry(language: DictionaryLanguage, word: String) async throws -> WordEntry? { nil }
    func search(language: DictionaryLanguage, query: String, mode: SearchMode, limit: Int) async throws -> [WordEntry] { [] }
    func candidateWords(language: DictionaryLanguage, level: VocabularyLevel, day: String, round: Int) async throws -> [String] {
        if round == 1 {
            blocked = true
            for waiter in blockedWaiters { waiter.resume() }
            blockedWaiters.removeAll()
            await withCheckedContinuation { release = $0 }
        }
        return []
    }
    func waitUntilBlocked() async {
        if blocked { return }
        await withCheckedContinuation { blockedWaiters.append($0) }
    }
    func unblock() { release?.resume(); release = nil }
}
