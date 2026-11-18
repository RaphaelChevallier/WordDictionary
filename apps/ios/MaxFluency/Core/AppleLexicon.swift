import CryptoKit
import Foundation

struct AppleIndexRow: Codable, Sendable {
    let word: String
    let offset: Int
    let bytes: Int
    let decoded: Int
    let daily: Bool
    let level: VocabularyLevel
}

private struct AppleIndex: Decodable {
    let schemaVersion: Int
    let language: DictionaryLanguage
    let sources: [DictionarySource]
    let dailyOrdinals: [Int]
    let rows: [AppleIndexRow]
}

final class AppleLexicon {
    let language: DictionaryLanguage
    let sources: [DictionarySource]
    let dailyOrdinals: [Int]
    let rows: [AppleIndexRow]
    private let data: Data
    private let dataOffset: Int
    private let positions: [String: Int]

    convenience init(url: URL, info: ApplePackInfo) throws {
        let bytes = try Data(contentsOf: url, options: .mappedIfSafe)
        guard bytes.count == info.compressedBytes, sha256(bytes) == info.sha256 else {
            throw DictionaryError.invalidData("Offline \(info.language) dictionary integrity check failed. Reinstall the app's bundled content.")
        }
        try self.init(data: bytes)
        guard language.rawValue == info.language, rows.count == info.entries, dailyOrdinals.count == info.dailyEntries else {
            throw DictionaryError.invalidData("Offline dictionary index does not match the manifest.")
        }
    }

    init(data: Data) throws {
        guard data.count >= 12, data.prefix(8) == Data("MFAP0001".utf8) else {
            throw DictionaryError.invalidData("Invalid MFAP0001 dictionary header.")
        }
        let size = try data.u32LE(at: 8)
        guard (1...67_108_864).contains(size), size <= data.count - 12 else {
            throw DictionaryError.invalidData("Truncated or oversized Apple dictionary index.")
        }
        let indexData = data.subdata(in: 12..<(12 + size))
        guard !indexData.contains(0), String(data: indexData, encoding: .utf8) != nil else {
            throw DictionaryError.invalidData("The Apple dictionary index is not valid UTF-8.")
        }
        let index = try JSONDecoder().decode(AppleIndex.self, from: indexData)
        guard index.schemaVersion == 1, (1...500_000).contains(index.rows.count),
              (1...16).contains(index.sources.count),
              Set(index.sources.map(\.id)).count == index.sources.count,
              index.sources.allSatisfy({ !$0.id.isEmpty && !$0.title.isEmpty && !$0.license.isEmpty
                  && $0.edition.range(of: "^[a-z][a-z-]{1,23}$", options: .regularExpression) != nil }),
              !index.dailyOrdinals.isEmpty, index.dailyOrdinals.count <= index.rows.count else {
            throw DictionaryError.invalidData("Invalid Apple dictionary language, sources or entry counts.")
        }
        var end = 0
        var positions: [String: Int] = [:]
        for (ordinal, row) in index.rows.enumerated() {
            guard !row.word.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  row.word.utf16.count <= 1024,
                  !row.word.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }),
                  row.word.utf8.elementsEqual(row.word.precomposedStringWithCanonicalMapping.utf8),
                  positions[row.word] == nil, row.offset == end,
                  (1...16_777_216).contains(row.bytes), (1...16_777_216).contains(row.decoded),
                  row.bytes <= data.count - 12 - size - end else {
                throw DictionaryError.invalidData("Invalid dictionary row, duplicate word, or truncated frame.")
            }
            positions[row.word] = ordinal
            end += row.bytes
        }
        guard end == data.count - 12 - size else { throw DictionaryError.invalidData("Trailing dictionary payload bytes.") }
        var daily = Set<Int>()
        for ordinal in index.dailyOrdinals {
            guard index.rows.indices.contains(ordinal), daily.insert(ordinal).inserted, index.rows[ordinal].daily else {
                throw DictionaryError.invalidData("Invalid or duplicate daily word ordinal.")
            }
        }
        guard index.rows.enumerated().allSatisfy({ $0.element.daily == daily.contains($0.offset) }) else {
            throw DictionaryError.invalidData("Daily eligibility does not match the dictionary rows.")
        }
        language = index.language
        sources = index.sources
        rows = index.rows
        dailyOrdinals = index.dailyOrdinals
        dataOffset = 12 + size
        self.data = data
        self.positions = positions
    }

    func entry(word: String) throws -> WordEntry? {
        guard let ordinal = positions[word.precomposedStringWithCanonicalMapping] else { return nil }
        return try entry(at: ordinal)
    }

    func entry(at ordinal: Int) throws -> WordEntry {
        guard rows.indices.contains(ordinal) else { throw DictionaryError.invalidData("Invalid dictionary word ordinal.") }
        let row = rows[ordinal]
        let start = dataOffset + row.offset
        let decoded = try Deflate.decode(data.subdata(in: start..<(start + row.bytes)), expectedBytes: row.decoded)
        guard !decoded.contains(0), String(data: decoded, encoding: .utf8) != nil else {
            throw DictionaryError.invalidData("The Apple dictionary word record is not valid UTF-8.")
        }
        let result = try JSONDecoder().decode(WordEntry.self, from: decoded)
        try result.validate()
        guard result.word == row.word, result.id == "\(language.rawValue):\(row.word)", result.language == language,
              !result.sources.isEmpty, result.senses.count <= 64,
              result.sourceEdition == result.sources.first?.edition,
              result.sources.allSatisfy({ sources.contains($0) }),
              result.senses.allSatisfy({ !$0.sourceIds.isEmpty }) else {
            throw DictionaryError.invalidData("Dictionary frame identity, complete meanings, or sources do not match its index.")
        }
        return result
    }

    func search(_ plan: SearchPlan, limit: Int = 60) throws -> [WordEntry] {
        if case .empty = plan { return [] }
        var result: [WordEntry] = []
        for (ordinal, row) in rows.enumerated() {
            if ordinal % 128 == 0 { try Task.checkCancellation() }
            if plan.matches(row.word) {
                result.append(try entry(at: ordinal))
                if result.count >= min(60, max(0, limit)) { break }
            }
        }
        return result
    }

    func level(word: String) -> VocabularyLevel? { positions[word].map { rows[$0].level } }
}

func sha256(_ bytes: Data) -> String { SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined() }

extension Data {
    func u32LE(at position: Int) throws -> Int {
        guard position >= 0, position <= count - 4 else {
            throw DictionaryError.invalidData("Truncated unsigned integer.")
        }
        let offset = startIndex + position
        return Int(self[offset]) | Int(self[offset + 1]) << 8 | Int(self[offset + 2]) << 16 | Int(self[offset + 3]) << 24
    }
}
