import Foundation

public struct VocabularyRanks: Sendable {
    private let bands: [DictionaryLanguage: [String: VocabularyLevel]]

    public init(resourceURL: URL) throws {
        let directory = try Self.rankDirectory(resourceURL: resourceURL)
        var bands: [DictionaryLanguage: [String: VocabularyLevel]] = [:]
        for language in DictionaryLanguage.allCases {
            let data = try Data(contentsOf: directory.appendingPathComponent("\(language.rawValue).txt"))
            guard data.count <= 16_777_216, let text = String(data: data, encoding: .utf8) else {
                throw DictionaryError.invalidData("Invalid UTF-8 vocabulary ranks for \(language.rawValue).")
            }
            var lines = text.components(separatedBy: "\n")
            if lines.last == "" { lines.removeLast() }
            guard (40_000...250_000).contains(lines.count) else {
                throw DictionaryError.invalidData("The vocabulary rank file has incomplete frequency coverage.")
            }
            var values: [String: VocabularyLevel] = [:]
            for (position, line) in lines.enumerated() {
                let word = line.hasSuffix("\r") ? String(line.dropLast()) : line
                guard !word.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                    throw DictionaryError.invalidData("The vocabulary rank file contains an empty word.")
                }
                // Kotlin associate() retains the final rank when accent normalization collides.
                values[SearchKeys.normalize(word)] = position < 2000 ? .beginner : position < 10000 ? .intermediate : .advanced
            }
            bands[language] = values
        }
        self.bands = bands
    }

    static func rankDirectory(resourceURL: URL) throws -> URL {
        let locations = [
            resourceURL.appendingPathComponent("ranks", isDirectory: true),
            resourceURL.appendingPathComponent("apple").appendingPathComponent("ranks", isDirectory: true),
            resourceURL,
        ]
        guard let directory = locations.first(where: {
            FileManager.default.fileExists(atPath: $0.appendingPathComponent("en.txt").path)
        }) else { throw DictionaryError.unavailable("The bundled vocabulary frequency ranks are missing.") }
        return directory
    }

    public func level(language: DictionaryLanguage, word: String) -> VocabularyLevel {
        bands[language]?[SearchKeys.normalize(word)] ?? .advanced
    }
}
