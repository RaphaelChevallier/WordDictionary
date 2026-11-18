import Foundation

public enum SearchKeys {
    public static func normalize(_ text: String) -> String {
        let scalars = text.decomposedStringWithCanonicalMapping.unicodeScalars.filter {
            !CharacterSet.nonBaseCharacters.contains($0)
        }
        return String(String.UnicodeScalarView(scalars)).lowercased()
            .replacingOccurrences(of: "œ", with: "oe").replacingOccurrences(of: "æ", with: "ae")
            .replacingOccurrences(of: "ß", with: "ss").replacingOccurrences(of: "’", with: "'")
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
    }

    public static func anagram(_ text: String) -> String {
        let letters = normalize(text).unicodeScalars.filter { CharacterSet.letters.contains($0) }
        return String(String.UnicodeScalarView(letters.sorted { $0.value < $1.value }))
    }

    public static func javaHash(_ text: String) -> Int32 {
        text.utf16.reduce(Int32(0)) { ($0 &* 31) &+ Int32($1) }
    }
}

public enum LocalDay {
    private static var calendar: Calendar {
        var value = Calendar(identifier: .gregorian)
        value.locale = Locale(identifier: "en_US_POSIX")
        value.timeZone = .current
        return value
    }

    public static func string(_ date: Date = Date()) -> String {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    public static func date(_ value: String) throws -> Date {
        guard value.utf8.count == 10,
              value.range(of: "^[0-9]{4}-[0-9]{2}-[0-9]{2}$", options: .regularExpression) != nil else {
            throw DictionaryError.invalidData("Expected a Gregorian local date in YYYY-MM-DD form.")
        }
        let parts = value.split(separator: "-")
        guard parts.count == 3, let year = Int(parts[0]), let month = Int(parts[1]), let day = Int(parts[2]),
              (1...9999).contains(year), (1...12).contains(month), (1...31).contains(day),
              let result = calendar.date(from: DateComponents(year: year, month: month, day: day, hour: 12)),
              string(result) == value else {
            throw DictionaryError.invalidData("Invalid Gregorian local civil date: \(value).")
        }
        return result
    }

    public static func adding(_ days: Int, to value: String) throws -> String {
        let original = try date(value)
        guard let result = calendar.date(byAdding: .day, value: days, to: original) else {
            throw DictionaryError.invalidData("The local calendar date is out of range.")
        }
        let text = string(result)
        _ = try date(text)
        return text
    }

    public static func month(_ value: String) -> String { String(value.prefix(7)) }

    static func epochDay(_ value: String) throws -> Int64 {
        _ = try date(value)
        let parts = value.split(separator: "-")
        guard let year = Int(parts[0]), let month = Int(parts[1]), let day = Int(parts[2]) else {
            throw DictionaryError.invalidData("Invalid civil date.")
        }
        // Gregorian civil arithmetic, independent of DST, UTC offsets and week-numbering years.
        let adjustedYear = year - (month <= 2 ? 1 : 0)
        let era = adjustedYear / 400
        let yearOfEra = adjustedYear - era * 400
        let dayOfYear = (153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1
        let dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return Int64(era * 146097 + dayOfEra - 719468)
    }
}

public func discoveryPreview(_ entry: WordEntry, policy: DiscoveryPolicy) -> WordEntry? {
    let word = entry.word.precomposedStringWithCanonicalMapping.lowercased()
    if policy.excludedWords[entry.language.rawValue, default: []].contains(where: {
        $0.precomposedStringWithCanonicalMapping.lowercased() == word
    }) { return nil }
    let senses = entry.senses.filter {
        !policy.excludedPartsOfSpeech.contains($0.partOfSpeech) && !$0.partOfSpeech.hasPrefix("other:")
    }
    guard !senses.isEmpty else { return nil }
    var result = entry
    result.senses = senses
    return result
}

enum SearchPlan {
    case empty, prefix(String), wildcard(String), anagram(String)

    init(query: String, mode: SearchMode) throws {
        let normalized = SearchKeys.normalize(query)
        guard !normalized.isEmpty else { self = .empty; return }
        guard normalized.utf16.count <= 1024 else { throw DictionaryError.invalidQuery("The search query is too long.") }
        if mode == .anagrams {
            let key = SearchKeys.anagram(normalized)
            guard !key.isEmpty else { throw DictionaryError.invalidQuery("Anagrams require letters.") }
            self = .anagram(key)
        } else if normalized.contains(where: { $0 == "*" || $0 == "?" || $0 == "." }) {
            self = .wildcard(normalized.replacingOccurrences(of: ".", with: "?"))
        } else { self = .prefix(normalized) }
    }

    func matches(_ word: String) -> Bool {
        let normalized = SearchKeys.normalize(word)
        switch self {
        case .empty: return false
        case .prefix(let prefix): return normalized.hasPrefix(prefix)
        case .anagram(let key): return SearchKeys.anagram(normalized) == key
        case .wildcard(let pattern):
            let input = Array(normalized.unicodeScalars)
            let tokens = Array(pattern.unicodeScalars)
            var i = 0, j = 0, star: Int? = nil, mark = 0
            while i < input.count {
                if j < tokens.count && (tokens[j] == "?" || tokens[j] == input[i]) { i += 1; j += 1 }
                else if j < tokens.count && tokens[j] == "*" { star = j; j += 1; mark = i }
                else if let saved = star { j = saved + 1; mark += 1; i = mark }
                else { return false }
            }
            while j < tokens.count && tokens[j] == "*" { j += 1 }
            return j == tokens.count
        }
    }

    var sqlFilter: (String, [String])? {
        switch self {
        case .empty: return nil
        case .prefix(let prefix): return ("norm>=? AND norm<?", [prefix, prefix + "\u{ffff}"])
        case .anagram(let key): return ("anagram=?", [key])
        case .wildcard(let pattern):
            let escaped = pattern.map { char -> String in
                if char == "[" { return "[[]" }
                if char == "]" { return "[]]" }
                return String(char)
            }.joined()
            return ("norm GLOB ?", [escaped])
        }
    }
}

func utf8Less(_ first: String, _ second: String) -> Bool { first.utf8.lexicographicallyPrecedes(second.utf8) }

func calendarDailyIndex(date: String, language: DictionaryLanguage, total: Int) throws -> Int {
    guard total > 0 else { throw DictionaryError.unavailable("No offline words are available for this calendar level.") }
    var stride = min(21, total)
    func gcd(_ first: Int, _ second: Int) -> Int {
        var a = first, b = second
        while b != 0 { let remainder = a % b; a = b; b = remainder }
        return a
    }
    while gcd(stride, total) != 1 { stride += 1 }
    let raw = (try LocalDay.epochDay(date)) * Int64(stride) + Int64(SearchKeys.javaHash(language.rawValue))
    return Int((raw % Int64(total) + Int64(total)) % Int64(total))
}

extension WordEntry {
    func withUsageExamples(from bundled: WordEntry?) -> WordEntry {
        guard let bundled = bundled, id == bundled.id, language == bundled.language, word == bundled.word else { return self }
        func key(_ sense: Sense) -> String {
            let definition = UsageExampleText.definition(sense.definition)
                .replacingOccurrences(of: "^(?:\\([^()]*\\)\\s*)+", with: "", options: .regularExpression)
                .replacingOccurrences(of: "’", with: "'").lowercased()
                .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
                .trimmingCharacters(in: .whitespacesAndNewlines)
            return sense.partOfSpeech + "\u{1f}" + definition
        }
        let candidates = Dictionary(grouping: bundled.senses, by: key)
        var contributed = Set<String>()
        var result = self
        result.senses = senses.map { sense in
            let senseKey = key(sense)
            guard !senseKey.hasSuffix("\u{1f}"), let matches = candidates[senseKey], matches.count == 1,
                  let match = matches.first, !match.examples.isEmpty else {
                var cleaned = sense
                cleaned.examples = UsageExampleText.examples(sense.examples)
                return cleaned
            }
            var enriched = sense
            enriched.examples = UsageExampleText.examples(sense.examples + match.examples)
            var ids = Set<String>()
            enriched.sourceIds = ((sense.sourceIds.isEmpty ? sources.map(\.id) : sense.sourceIds) + match.sourceIds)
                .filter { ids.insert($0).inserted }
            contributed.formUnion(match.sourceIds)
            return enriched
        }
        var sourceIDs = Set(sources.map(\.id))
        result.sources += bundled.sources.filter { contributed.contains($0.id) && sourceIDs.insert($0.id).inserted }
        return result
    }
}

private enum UsageExampleText {
    private static let brokenMarkup = "\\{\\{|\\}\\}|\\[\\[|]]|\\uFFFD|</?[a-z][^>]*>"
    private static let citation = "^(?:\\d{3,4}(?:\\s+[\\p{L}-]+)?\\s*[,:\\u2014]|(?:\\d{1,2}\\s+)?(?:January|February|March|April|May|June|July|August|September|October|November|December)\\s+(?:\\d{1,2},?\\s+)?\\d{4}\\s*[,:\\u2014])"
    private static let sentenceEnd = "[.!?…][”\"')»\\]]?$"

    static func definition(_ value: String) -> String {
        let text = value.precomposedStringWithCanonicalMapping
            .replacingOccurrences(of: "\\s*\\^\\(\\[(?:\\d+(?:[\\s,;-]+\\d+)*|note\\s+\\d+)]\\)", with: "",
                                  options: [.regularExpression, .caseInsensitive])
            .replacingOccurrences(of: "\\s*\\^\\(\\[([^]]+)]\\)", with: " ($1)", options: .regularExpression)
            .replacingOccurrences(of: "\\[\\[([^\\[\\]|]+)\\|([^\\[\\]]+)]]", with: "$2", options: .regularExpression)
            .replacingOccurrences(of: "\\[\\[([^\\[\\]|]+)]]", with: "$1", options: .regularExpression)
            .replacingOccurrences(of: "\\[\\[([^\\[\\]|]+)](?!])", with: "$1", options: .regularExpression)
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .replacingOccurrences(of: "\\s+\\.(?!\\.)", with: ".", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if text.range(of: brokenMarkup, options: [.regularExpression, .caseInsensitive]) != nil
            || !text.unicodeScalars.contains(where: { CharacterSet.alphanumerics.contains($0) }) { return "" }
        return text
    }

    static func examples(_ values: [String]) -> [String] {
        var seen = Set<String>()
        let cleaned = values.map {
            $0.precomposedStringWithCanonicalMapping
                .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
                .trimmingCharacters(in: .whitespacesAndNewlines)
        }.filter {
            !$0.isEmpty && $0.utf16.count <= 600 && seen.insert($0).inserted
                && $0.range(of: brokenMarkup, options: [.regularExpression, .caseInsensitive]) == nil
                && $0.range(of: "https?://|www\\.", options: [.regularExpression, .caseInsensitive]) == nil
                && $0.range(of: citation, options: [.regularExpression, .caseInsensitive]) == nil
        }
        return cleaned.enumerated().sorted {
            let first = $0.element.range(of: sentenceEnd, options: .regularExpression) != nil && $0.element.split(separator: " ").count >= 3
            let second = $1.element.range(of: sentenceEnd, options: .regularExpression) != nil && $1.element.split(separator: " ").count >= 3
            return first == second ? $0.offset < $1.offset : first
        }.prefix(3).map(\.element)
    }
}
