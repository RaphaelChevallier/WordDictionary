import Foundation

public enum DictionaryLanguage: String, Codable, CaseIterable, Identifiable, Sendable {
    case english = "en", french = "fr", spanish = "es"
    public var id: String { rawValue }
    public var nativeName: String {
        switch self {
        case .english: return "English"
        case .french: return "Français"
        case .spanish: return "Español"
        }
    }
}

public enum VocabularyLevel: String, Codable, CaseIterable, Sendable {
    case beginner = "BEGINNER", intermediate = "INTERMEDIATE", advanced = "ADVANCED"
    public var ordinal: Int {
        switch self {
        case .beginner: return 0
        case .intermediate: return 1
        case .advanced: return 2
        }
    }
}

public enum SearchMode: String, Codable, CaseIterable, Sendable { case words, anagrams }
public enum AppTheme: String, Codable, CaseIterable, Sendable { case system, light, dark }
public enum AppAccent: String, Codable, CaseIterable, Sendable { case monochrome, blue, green, rose }
public enum ReadingSize: String, Codable, CaseIterable, Sendable {
    case standard, large
    public var scale: Double { self == .standard ? 1 : 1.2 }
}
public enum HomeView: String, Codable, CaseIterable, Sendable { case garden, wordOfDay }

public struct AppSettings: Codable, Equatable, Sendable {
    public var language: DictionaryLanguage
    public var interfaceLanguage: String
    public var theme: AppTheme
    public var accent: AppAccent
    public var readingSize: ReadingSize
    public var levels: [String: VocabularyLevel]
    public var homeView: HomeView
    public var reminderEnabled: Bool
    public var reminderMinute: Int

    public init(language: DictionaryLanguage = .english, interfaceLanguage: String = "",
                theme: AppTheme = .system, accent: AppAccent = .monochrome,
                readingSize: ReadingSize = .standard,
                levels: [String: VocabularyLevel] = ["en": .intermediate, "fr": .intermediate, "es": .intermediate],
                homeView: HomeView = .garden, reminderEnabled: Bool = false, reminderMinute: Int = 540) {
        self.language = language
        self.interfaceLanguage = interfaceLanguage
        self.theme = theme
        self.accent = accent
        self.readingSize = readingSize
        self.levels = levels
        self.homeView = homeView
        self.reminderEnabled = reminderEnabled
        self.reminderMinute = reminderMinute
    }

    public func level(for language: DictionaryLanguage) -> VocabularyLevel { levels[language.rawValue] ?? .intermediate }
}

public struct Sense: Codable, Equatable, Hashable, Sendable {
    public var partOfSpeech: String
    public var definition: String
    public var sourceIds: [String]
    public var examples: [String]

    public init(partOfSpeech: String, definition: String, sourceIds: [String] = [], examples: [String] = []) {
        self.partOfSpeech = partOfSpeech
        self.definition = definition
        self.sourceIds = sourceIds
        self.examples = examples
    }

    private enum CodingKeys: String, CodingKey { case partOfSpeech, definition, sourceIds, examples }
    public init(from decoder: Decoder) throws {
        let value = try decoder.container(keyedBy: CodingKeys.self)
        partOfSpeech = try value.decode(String.self, forKey: .partOfSpeech)
        definition = try value.decode(String.self, forKey: .definition)
        sourceIds = try value.decodeIfPresent([String].self, forKey: .sourceIds) ?? []
        examples = try value.decodeIfPresent([String].self, forKey: .examples) ?? []
    }
}

public struct DictionarySource: Codable, Equatable, Hashable, Sendable {
    public var id: String
    public var edition: String
    public var title: String
    public var license: String
    public init(id: String, edition: String, title: String, license: String) {
        self.id = id
        self.edition = edition
        self.title = title
        self.license = license
    }
}

public struct SourceLink: Hashable, Identifiable, Sendable {
    public let title: String
    public let url: URL
    public var id: URL { url }
    public init(title: String, url: URL) { self.title = title; self.url = url }
}

public struct WordEntry: Codable, Equatable, Hashable, Identifiable, Sendable {
    public var id: String
    public var language: DictionaryLanguage
    public var word: String
    public var pronunciation: String
    public var senses: [Sense]
    public var related: [String]
    public var sourceEdition: String
    public var sources: [DictionarySource]
    public var pronunciationGuide: String

    public init(id: String, language: DictionaryLanguage, word: String, pronunciation: String = "",
                senses: [Sense], related: [String] = [], sourceEdition: String? = nil,
                sources: [DictionarySource] = [], pronunciationGuide: String = "") {
        self.id = id
        self.language = language
        self.word = word
        self.pronunciation = pronunciation
        self.senses = senses
        self.related = related
        self.sourceEdition = sourceEdition ?? sources.first?.edition ?? language.rawValue
        self.sources = sources
        self.pronunciationGuide = pronunciationGuide
    }

    private enum CodingKeys: String, CodingKey {
        case id, language, word, pronunciation, senses, related, sourceEdition, sources, pronunciationGuide
    }
    public init(from decoder: Decoder) throws {
        let value = try decoder.container(keyedBy: CodingKeys.self)
        id = try value.decode(String.self, forKey: .id)
        language = try value.decode(DictionaryLanguage.self, forKey: .language)
        word = try value.decode(String.self, forKey: .word)
        pronunciation = try value.decode(String.self, forKey: .pronunciation)
        senses = try value.decode([Sense].self, forKey: .senses)
        related = try value.decode([String].self, forKey: .related)
        sourceEdition = try value.decode(String.self, forKey: .sourceEdition)
        sources = try value.decodeIfPresent([DictionarySource].self, forKey: .sources) ?? []
        pronunciationGuide = try value.decodeIfPresent(String.self, forKey: .pronunciationGuide) ?? ""
    }

    public var sourceLinks: [SourceLink] {
        let attribution = sources.isEmpty
            ? [DictionarySource(id: sourceEdition, edition: sourceEdition, title: "Wiktionary", license: "CC-BY-SA-4.0")]
            : sources
        var seen = Set<URL>()
        var result: [SourceLink] = []
        let safe = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-._~"))
        guard let encoded = word.addingPercentEncoding(withAllowedCharacters: safe) else { return [] }
        for source in attribution {
            if source.edition.range(of: "^[a-z][a-z-]{1,23}$", options: .regularExpression) != nil,
               let url = URL(string: "https://\(source.edition).wiktionary.org/wiki/\(encoded)"),
               seen.insert(url).inserted {
                result.append(SourceLink(title: source.title, url: url))
            }
            if source.id == "reader-dict",
               let url = URL(string: "https://www.reader-dict.com/download/\(language.rawValue)"),
               seen.insert(url).inserted {
                result.append(SourceLink(title: "reader.dict", url: url))
            }
        }
        return result
    }
}

public struct PreparedEntry: Sendable {
    public let entry: WordEntry
    public let archived: Bool
    public init(entry: WordEntry, archived: Bool = false) { self.entry = entry; self.archived = archived }
}

public struct DailyWords: Equatable, Sendable {
    public let date: String
    public let featured: WordEntry
    public let grid: [WordEntry]
    public let explored: Set<String>
    public let round: Int
    public let featuredMeaningCount: Int
    public init(date: String, featured: WordEntry, grid: [WordEntry], explored: Set<String> = [], round: Int = 0,
                featuredMeaningCount: Int? = nil) {
        self.date = date
        self.featured = featured
        self.grid = grid
        self.explored = explored
        self.round = round
        self.featuredMeaningCount = featuredMeaningCount ?? featured.senses.count
    }
}

public struct SavedWord: Codable, Identifiable, Sendable, Equatable {
    public let entry: WordEntry
    public let favorite: Bool
    public let lastViewed: Date
    public var id: String { entry.id }
    public init(entry: WordEntry, favorite: Bool, lastViewed: Date) {
        self.entry = entry; self.favorite = favorite; self.lastViewed = lastViewed
    }
}

public struct ArchivedDailyWord: Codable, Identifiable, Sendable, Equatable {
    public let date: String
    public let level: VocabularyLevel
    public let entry: WordEntry
    public let backfilled: Bool
    public var id: String { "\(date):\(entry.language.rawValue):\(level.rawValue)" }
    public init(date: String, level: VocabularyLevel, entry: WordEntry, backfilled: Bool = false) {
        self.date = date; self.level = level; self.entry = entry; self.backfilled = backfilled
    }
}

public struct AppleManifest: Codable, Sendable {
    public let releaseVersion: String
    public let attribution: String
    public let modifications: String
    public let languages: [ApplePackInfo]
    public let discovery: DiscoveryPolicy
    public init(releaseVersion: String, attribution: String, modifications: String,
                languages: [ApplePackInfo], discovery: DiscoveryPolicy = DiscoveryPolicy()) {
        self.releaseVersion = releaseVersion; self.attribution = attribution; self.modifications = modifications
        self.languages = languages; self.discovery = discovery
    }
}

public struct ApplePackInfo: Codable, Sendable {
    public let language: String
    public let file: String
    public let entries: Int
    public let dailyEntries: Int
    public let compressedBytes: Int
    public let sha256: String
    public init(language: String, file: String, entries: Int, dailyEntries: Int, compressedBytes: Int, sha256: String) {
        self.language = language; self.file = file; self.entries = entries; self.dailyEntries = dailyEntries
        self.compressedBytes = compressedBytes; self.sha256 = sha256
    }
}

public struct DiscoveryPolicy: Codable, Sendable {
    public let excludedPartsOfSpeech: Set<String>
    public let excludedWords: [String: [String]]
    public init(excludedPartsOfSpeech: Set<String> = [], excludedWords: [String: [String]] = [:]) {
        self.excludedPartsOfSpeech = excludedPartsOfSpeech; self.excludedWords = excludedWords
    }
}

public protocol ExternalLexicon: Sendable {
    func entry(language: DictionaryLanguage, word: String) async throws -> WordEntry?
    func search(language: DictionaryLanguage, query: String, mode: SearchMode, limit: Int) async throws -> [WordEntry]
    func candidateWords(language: DictionaryLanguage, level: VocabularyLevel, day: String, round: Int) async throws -> [String]
}

public enum DictionaryError: LocalizedError, Sendable {
    case invalidData(String)
    case storage(String)
    case unavailable(String)
    case invalidQuery(String)
    public var errorDescription: String? {
        switch self {
        case .invalidData(let detail): return "Dictionary data is invalid: \(detail)"
        case .storage(let detail): return "Personal data could not be saved or read: \(detail)"
        case .unavailable(let detail): return detail
        case .invalidQuery(let detail): return detail
        }
    }
}

extension WordEntry {
    func validate() throws {
        guard id == "\(language.rawValue):\(word)", !word.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              word.utf16.count <= 1024, !word.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }),
              !senses.isEmpty, senses.count <= 4096,
              sourceEdition.range(of: "^[a-z][a-z-]{1,23}$", options: .regularExpression) != nil,
              Set(sources.map(\.id)).count == sources.count,
              sources.allSatisfy({ !$0.id.isEmpty && !$0.title.isEmpty && !$0.license.isEmpty
                  && $0.edition.range(of: "^[a-z][a-z-]{1,23}$", options: .regularExpression) != nil }) else {
            throw DictionaryError.invalidData("A word needs a matching identity, complete meanings and valid sources (\(id)).")
        }
        let sourceIDs = Set(sources.map(\.id))
        for sense in senses {
            guard !sense.partOfSpeech.isEmpty, !sense.definition.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  sense.sourceIds.allSatisfy({ sourceIDs.contains($0) }),
                  sense.examples.count <= 3,
                  sense.examples.allSatisfy({ !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && $0.utf16.count <= 600 }) else {
                throw DictionaryError.invalidData("A meaning has invalid sources, definition or examples (\(id)).")
            }
        }
    }
}
