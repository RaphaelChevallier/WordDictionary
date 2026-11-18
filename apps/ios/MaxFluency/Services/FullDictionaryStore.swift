import Foundation
import CryptoKit
import os

struct FullDictionaryInfo: Codable, Sendable {
    let language: String
    let entries: Int
    let headwords: Int
    let variants: Int
    let version: String
    let archiveSha256: String
    let installedAt: Date
}

enum PublicDictionaries {
    static func url(_ language: DictionaryLanguage) throws -> URL {
        guard let url = URL(string: "https://www.reader-dict.com/file/\(language.rawValue)/dict-\(language.rawValue)-\(language.rawValue)-noetym.zip") else {
            throw PlatformFailure.message("Dictionary download address is invalid.")
        }
        return url
    }
    static func estimatedBytes(_ language: DictionaryLanguage) -> Int {
        switch language {
        case .english: return 49_837_964
        case .french: return 32_142_939
        case .spanish: return 9_545_437
        }
    }
}

actor FullDictionaryStore: ExternalLexicon {
    private struct OpenPack {
        let database: SQLite
        let dictionary: DictZipReader
    }
    private let base: URL
    private let ranks: VocabularyRanks
    private var opened: [DictionaryLanguage: OpenPack] = [:]
    private let logger = Logger(subsystem: "com.maxfluency.app", category: "full-dictionaries")

    init(directory: URL, resourceURL: URL) throws {
        base = directory.appendingPathComponent("full-dictionaries", isDirectory: true)
        ranks = try VocabularyRanks(resourceURL: resourceURL)
        try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        var excluded = base
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try excluded.setResourceValues(values)
    }

    func info(language: DictionaryLanguage) throws -> FullDictionaryInfo? {
        let directory = base.appendingPathComponent(language.rawValue, isDirectory: true)
        guard FileManager.default.fileExists(atPath: directory.path) else { return nil }
        let info = try JSONDecoder().decode(FullDictionaryInfo.self, from: Data(contentsOf: directory.appendingPathComponent("info.json")))
        guard info.language == language.rawValue, info.entries > 0,
              FileManager.default.fileExists(atPath: directory.appendingPathComponent("index.sqlite").path),
              FileManager.default.fileExists(atPath: directory.appendingPathComponent("data.dict.dz").path) else {
            throw PlatformFailure.message("The installed dictionary is incomplete. Remove it and download it again.")
        }
        return info
    }

    func install(language: DictionaryLanguage, archive: URL, policy: DiscoveryPolicy,
                 progress: @Sendable (Double) -> Void) async throws -> FullDictionaryInfo {
        let archiveData = try Data(contentsOf: archive, options: .mappedIfSafe)
        guard archiveData.count <= 100_000_000 else { throw PlatformFailure.message("Dictionary download exceeds its safety limit.") }
        let free = try base.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage
        guard let free, free >= Int64(archiveData.count * 5 + 80_000_000) else {
            throw PlatformFailure.message("More free storage is needed to prepare the offline dictionary.")
        }
        let staging = base.appendingPathComponent(".\(language.rawValue)-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: false)
        defer { FileCleanup.remove(staging) }
        var found = Set<String>()
        for entry in try ZipArchive.entries(in: archiveData) {
            try Task.checkCancellation()
            let target: String
            if entry.name.hasSuffix(".dict.dz") { target = "data.dict.dz" }
            else if entry.name.hasSuffix(".idx") { target = "index.raw" }
            else if entry.name.hasSuffix(".syn") { target = "variants.raw" }
            else if entry.name.hasSuffix(".ifo") { target = "book.ifo" }
            else { throw PlatformFailure.message("Unexpected file in the dictionary archive.") }
            guard found.insert(target).inserted else { throw PlatformFailure.message("Duplicate dictionary archive file.") }
            try ZipArchive.decode(entry, from: archiveData).write(to: staging.appendingPathComponent(target), options: .atomic)
        }
        guard found.isSuperset(of: ["data.dict.dz", "index.raw", "book.ifo"]) else {
            throw PlatformFailure.message("Required dictionary archive files are missing.")
        }
        let digest = SHA256.hash(data: archiveData).map { String(format: "%02x", $0) }.joined()
        let info = try await buildIndex(language: language, staging: staging, digest: digest, policy: policy, progress: progress)
        try JSONEncoder().encode(info).write(to: staging.appendingPathComponent("info.json"), options: .atomic)
        try FileManager.default.removeItem(at: staging.appendingPathComponent("index.raw"))
        if found.contains("variants.raw") { try FileManager.default.removeItem(at: staging.appendingPathComponent("variants.raw")) }
        try Task.checkCancellation()
        opened.removeValue(forKey: language)
        let destination = base.appendingPathComponent(language.rawValue, isDirectory: true)
        if FileManager.default.fileExists(atPath: destination.path) {
            let backup = base.appendingPathComponent(".\(language.rawValue)-previous-\(UUID().uuidString)", isDirectory: true)
            try FileManager.default.moveItem(at: destination, to: backup)
            do { try FileManager.default.moveItem(at: staging, to: destination) }
            catch {
                let installationError = error
                do { try FileManager.default.moveItem(at: backup, to: destination) }
                catch { throw PlatformFailure.message("Dictionary replacement and rollback failed: \(installationError.localizedDescription); \(error.localizedDescription)") }
                throw installationError
            }
            FileCleanup.remove(backup)
        } else { try FileManager.default.moveItem(at: staging, to: destination) }
        progress(1)
        return info
    }

    private func buildIndex(language: DictionaryLanguage, staging: URL, digest: String, policy: DiscoveryPolicy,
                            progress: @Sendable (Double) -> Void) async throws -> FullDictionaryInfo {
        let description = try String(contentsOf: staging.appendingPathComponent("book.ifo"), encoding: .utf8)
        var properties: [String: String] = [:]
        for line in description.components(separatedBy: .newlines).dropFirst() {
            guard let separator = line.firstIndex(of: "=") else { continue }
            let key = String(line[..<separator])
            guard properties[key] == nil else { throw PlatformFailure.message("Duplicate dictionary metadata.") }
            properties[key] = String(line[line.index(after: separator)...]).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        guard properties["version"] == "3.0.0", properties["sametypesequence"] == "h",
              properties["bookname"]?.uppercased().hasSuffix(language.rawValue.uppercased()) == true,
              properties["idxoffsetbits"] == nil || properties["idxoffsetbits"] == "32",
              let mainCount = properties["wordcount"].flatMap(Int.init), (1...10_000_000).contains(mainCount),
              let indexSize = properties["idxfilesize"].flatMap(Int.init) else {
            throw PlatformFailure.message("Unsupported or wrong-language StarDict metadata.")
        }
        let variants: Int
        if let value = properties["synwordcount"] {
            guard let count = Int(value), (0...10_000_000).contains(count) else {
                throw PlatformFailure.message("Invalid dictionary alias count.")
            }
            variants = count
        } else { variants = 0 }
        let index = try Data(contentsOf: staging.appendingPathComponent("index.raw"), options: .mappedIfSafe)
        guard index.count == indexSize else { throw PlatformFailure.message("Dictionary index size does not match its metadata.") }
        let variantBytes: Int
        if variants > 0 {
            guard let size = try staging.appendingPathComponent("variants.raw").resourceValues(forKeys: [.fileSizeKey]).fileSize else {
                throw PlatformFailure.message("Dictionary alias file size is unavailable.")
            }
            variantBytes = size
        } else { variantBytes = 0 }
        let budget = Int64(index.count + variantBytes) * 6 + Int64(mainCount + variants) * 64 + 32_000_000
        guard let free = try staging.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage,
              free >= budget else { throw PlatformFailure.message("More free storage is needed for the full offline search index.") }
        let dictionary = try DictZipReader(url: staging.appendingPathComponent("data.dict.dz"))
        let database = try SQLite(url: staging.appendingPathComponent("index.sqlite"))
        try database.execute("PRAGMA journal_mode=OFF")
        try database.execute("PRAGMA synchronous=OFF")
        try database.execute("CREATE TABLE articles(id INTEGER PRIMARY KEY,off INTEGER NOT NULL,size INTEGER NOT NULL,spelling TEXT NOT NULL)")
        try database.execute("CREATE TABLE words(norm TEXT NOT NULL,spelling TEXT NOT NULL,anagram TEXT NOT NULL,target INTEGER NOT NULL,level INTEGER NOT NULL,eligible INTEGER NOT NULL,PRIMARY KEY(norm,spelling,target)) WITHOUT ROWID")
        let article = try database.prepare("INSERT INTO articles VALUES(?,?,?,?)")
        let insert = try database.prepare("INSERT OR IGNORE INTO words VALUES(?,?,?,?,?,?)")
        let pattern = try NSRegularExpression(pattern: "^\\p{Ll}[\\p{L}'-]{2,19}$")
        let excluded = Set(policy.excludedWords[language.rawValue] ?? [])
        func addWord(_ spelling: String, target: Int) throws {
            let spelling = spelling.precomposedStringWithCanonicalMapping
            let eligible = pattern.firstMatch(in: spelling, range: NSRange(spelling.startIndex..., in: spelling)) != nil && !excluded.contains(spelling)
            try insert.run([.text(SearchKeys.normalize(spelling)), .text(spelling), .text(SearchKeys.anagram(spelling)),
                            .integer(Int64(target)), .integer(Int64(ranks.level(language: language, word: spelling).ordinal)),
                            .integer(eligible ? 1 : 0)])
        }
        try database.execute("BEGIN")
        var input = BinaryBytes(data: index)
        var ordinal = 0
        while let spelling = try input.word() {
            let offset = try input.big32()
            let size = try input.big32()
            guard size <= 2_097_152, offset <= dictionary.uncompressedBytes, size <= dictionary.uncompressedBytes - offset else {
                throw PlatformFailure.message("Dictionary article offset is invalid.")
            }
            try article.run([.integer(Int64(ordinal)), .integer(Int64(offset)), .integer(Int64(size)),
                             .text(spelling.precomposedStringWithCanonicalMapping)])
            try addWord(spelling, target: ordinal)
            ordinal += 1
            if ordinal % 10_000 == 0 {
                try Task.checkCancellation()
                progress(Double(ordinal) * 0.9 / Double(mainCount + variants))
                await Task.yield()
            }
        }
        guard ordinal == mainCount else { throw PlatformFailure.message("Dictionary article count does not match.") }
        if variants > 0 {
            var aliases = BinaryBytes(data: try Data(contentsOf: staging.appendingPathComponent("variants.raw"), options: .mappedIfSafe))
            var count = 0
            while let spelling = try aliases.word() {
                let target = try aliases.big32()
                guard target < mainCount else { throw PlatformFailure.message("Dictionary alias points to an invalid article.") }
                try addWord(spelling, target: target)
                count += 1
                if count % 10_000 == 0 {
                    try Task.checkCancellation()
                    progress(Double(mainCount + count) * 0.9 / Double(mainCount + variants))
                    await Task.yield()
                }
            }
            guard count == variants else { throw PlatformFailure.message("Dictionary alias count does not match.") }
        }
        try database.execute("COMMIT")
        progress(0.92)
        try database.execute("CREATE INDEX idx_anagram ON words(anagram)")
        try database.execute("CREATE INDEX idx_daily ON words(eligible,level)")
        var checked = false
        try database.rows("PRAGMA quick_check") { checked = try $0.text(0) == "ok" }
        guard checked else { throw PlatformFailure.message("Dictionary search index integrity check failed.") }
        let count = try database.integer("SELECT count(*) FROM (SELECT norm,spelling FROM words GROUP BY norm,spelling)")
        return FullDictionaryInfo(language: language.rawValue, entries: Int(count), headwords: mainCount, variants: variants,
                                  version: properties["date"] ?? "", archiveSha256: digest, installedAt: Date())
    }

    func remove(language: DictionaryLanguage) throws {
        opened.removeValue(forKey: language)
        let directory = base.appendingPathComponent(language.rawValue, isDirectory: true)
        if FileManager.default.fileExists(atPath: directory.path) { try FileManager.default.removeItem(at: directory) }
    }

    private func open(_ language: DictionaryLanguage) throws -> OpenPack? {
        if let pack = opened[language] { return pack }
        guard try info(language: language) != nil else { return nil }
        let directory = base.appendingPathComponent(language.rawValue, isDirectory: true)
        let pack = OpenPack(database: try SQLite(url: directory.appendingPathComponent("index.sqlite"), readOnly: true),
                            dictionary: try DictZipReader(url: directory.appendingPathComponent("data.dict.dz")))
        opened[language] = pack
        return pack
    }

    func entry(language: DictionaryLanguage, word: String) async throws -> WordEntry? {
        guard let pack = try open(language) else { return nil }
        return try readEntry(pack: pack, language: language, spelling: word)
    }

    private func readEntry(pack: OpenPack, language: DictionaryLanguage, spelling: String) throws -> WordEntry? {
        var parsed: [WordEntry] = []
        try pack.database.rows("SELECT DISTINCT a.off,a.size FROM words w JOIN articles a ON a.id=w.target WHERE w.norm=? AND w.spelling=?",
                               [.text(SearchKeys.normalize(spelling)), .text(spelling)]) { row in
            let bytes = try pack.dictionary.read(offset: Int(row.integer(0)), size: Int(row.integer(1)))
            guard let html = String(data: bytes, encoding: .utf8) else { throw PlatformFailure.message("Dictionary article is not valid UTF-8.") }
            if let entry = try DictionaryHTML.starDict(language: language, word: spelling, html: html) { parsed.append(entry) }
            else { logger.warning("A dictionary article has no usable lexical meanings.") }
        }
        return DictionaryHTML.merge(parsed)
    }

    func search(language: DictionaryLanguage, query: String, mode: SearchMode, limit: Int) async throws -> [WordEntry] {
        guard let pack = try open(language) else { return [] }
        let normalized = SearchKeys.normalize(query).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else { return [] }
        let predicate: String
        let values: [SQLValue]
        if mode == .anagrams {
            let key = SearchKeys.anagram(normalized)
            guard !key.isEmpty else { return [] }
            predicate = "anagram=?"
            values = [.text(key)]
        } else if normalized.contains("*") || normalized.contains("?") || normalized.contains(".") {
            let glob = normalized.map { char -> String in
                switch char { case "[": return "[[]"; case "]": return "[]]"; case ".": return "?"; default: return String(char) }
            }.joined()
            predicate = "norm GLOB ?"
            values = [.text(glob)]
        } else {
            predicate = "norm>=? AND norm<?"
            values = [.text(normalized), .text(normalized + "\u{ffff}")]
        }
        var names: [String] = []
        try pack.database.rows("SELECT spelling FROM words WHERE \(predicate) GROUP BY norm,spelling ORDER BY norm,spelling LIMIT ?",
                               values + [.integer(Int64(min(60, max(1, limit))))]) { names.append(try $0.text(0)) }
        return try names.compactMap { try readEntry(pack: pack, language: language, spelling: $0) }
    }

    func candidateWords(language: DictionaryLanguage, level: VocabularyLevel, day: String, round: Int) async throws -> [String] {
        guard let pack = try open(language) else { return [] }
        let count = try pack.database.integer("SELECT count(*) FROM words WHERE eligible=1 AND level=?", [.integer(Int64(level.ordinal))])
        guard count > 0 else { return [] }
        let calendar = Calendar(identifier: .gregorian)
        guard let days = calendar.dateComponents([.day], from: try LocalDay.date("1970-01-01"), to: try LocalDay.date(day)).day else {
            throw PlatformFailure.message("Daily dictionary date is invalid.")
        }
        let languageIndex: Int64
        switch language { case .english: languageIndex = 0; case .french: languageIndex = 1; case .spanish: languageIndex = 2 }
        let seed = Int64(days) * 97 + languageIndex * 17 + Int64(round) * 160
        let offset = (seed % count + count) % count
        var words: [String] = []
        try pack.database.rows("SELECT spelling FROM words WHERE eligible=1 AND level=? LIMIT 160 OFFSET ?",
                               [.integer(Int64(level.ordinal)), .integer(offset)]) { words.append(try $0.text(0)) }
        return words
    }
}
