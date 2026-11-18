import Foundation

final class PronunciationIndex {
    let entries: Int
    private let file: FileHandle
    private let length: UInt64
    private let offsets: [Int]
    private let dataOffset: Int
    private var cache: [String: String] = [:]
    private var order: [String] = []

    init(url: URL) throws {
        let file = try FileHandle(forReadingFrom: url)
        do {
            let length = try file.seekToEnd()
            func read(_ offset: UInt64, _ count: Int) throws -> Data {
                guard offset <= length, UInt64(count) <= length - offset else {
                    throw DictionaryError.invalidData("Truncated pronunciation asset.")
                }
                try file.seek(toOffset: offset)
                guard let result = try file.read(upToCount: count), result.count == count else {
                    throw DictionaryError.invalidData("Short pronunciation read.")
                }
                return result
            }
            let header = try read(0, 24)
            guard header.prefix(8) == Data("MFPR0001".utf8) else {
                throw DictionaryError.invalidData("Invalid pronunciation header.")
            }
            let entries = try header.u32LE(at: 8)
            let indexBytes = try header.u32LE(at: 12)
            let dataBytes = try header.u32LE(at: 16)
            guard (1...250_000).contains(entries), indexBytes == (entries + 1) * 4,
                  (1...16_777_216).contains(dataBytes), try header.u32LE(at: 20) == 1024,
                  length == UInt64(24 + indexBytes + dataBytes) else {
                throw DictionaryError.invalidData("Invalid pronunciation sizes.")
            }
            let data = try read(24, indexBytes)
            let offsets = try (0...entries).map { try data.u32LE(at: $0 * 4) }
            guard offsets.first == 0, offsets.last == dataBytes,
                  (0..<entries).allSatisfy({ (1...1024).contains(offsets[$0 + 1] - offsets[$0]) }) else {
                throw DictionaryError.invalidData("Invalid pronunciation offsets.")
            }
            self.file = file; self.length = length; self.entries = entries
            self.offsets = offsets; self.dataOffset = 24 + indexBytes
        } catch { try? file.close(); throw error }
    }

    deinit { try? file.close() }

    func lookup(_ word: String) throws -> String? {
        let key = word.precomposedStringWithCanonicalMapping.lowercased().replacingOccurrences(of: "’", with: "'")
        guard key.range(of: "^[a-z][a-z'.-]{0,127}$", options: .regularExpression) != nil,
              !(word.utf16.count > 1 && word.unicodeScalars.allSatisfy({ (65...90).contains($0.value) })) else { return nil }
        if let result = cache[key] { touch(key); return result }
        var low = 0, high = entries
        while low < high {
            let middle = (low + high) / 2
            let (spelling, guide) = try record(middle)
            if spelling == key {
                cache[key] = guide; touch(key)
                if order.count > 128 { cache.removeValue(forKey: order.removeFirst()) }
                return guide
            }
            if utf8Less(spelling, key) { low = middle + 1 } else { high = middle }
        }
        return nil
    }

    private func touch(_ key: String) { order.removeAll { $0 == key }; order.append(key) }

    private func record(_ ordinal: Int) throws -> (String, String) {
        let offset = UInt64(dataOffset + offsets[ordinal])
        let count = offsets[ordinal + 1] - offsets[ordinal]
        guard offset <= length, UInt64(count) <= length - offset else {
            throw DictionaryError.invalidData("Truncated pronunciation record.")
        }
        try file.seek(toOffset: offset)
        guard let bytes = try file.read(upToCount: count), bytes.count == count,
              bytes.allSatisfy({ $0 < 128 }), let text = String(data: bytes, encoding: .ascii) else {
            throw DictionaryError.invalidData("Invalid pronunciation record encoding.")
        }
        let values = text.split(separator: "\t", omittingEmptySubsequences: false)
        guard values.count == 2,
              values[0].range(of: "^[a-z][a-z'.-]{0,127}$", options: .regularExpression) != nil,
              values[1].range(of: "^[a-zA-Z -]+(?: / [a-zA-Z -]+)?$", options: .regularExpression) != nil else {
            throw DictionaryError.invalidData("Invalid pronunciation record text.")
        }
        return (String(values[0]), String(values[1]))
    }
}

final class PronunciationGuides {
    private struct Manifest: Decodable {
        let schemaVersion: Int
        let language: String
        let file: String
        let entries: Int
        let bytes: Int
        let sha256: String
    }
    private let resourceURL: URL
    private var index: PronunciationIndex?
    init(resourceURL: URL) { self.resourceURL = resourceURL }

    func enrich(_ entry: WordEntry) throws -> WordEntry {
        var result = entry
        if entry.language == .english { result.pronunciationGuide = try open().lookup(entry.word) ?? "" }
        else { result.pronunciationGuide = "" }
        return result
    }

    func reset() { index = nil }

    private func open() throws -> PronunciationIndex {
        if let index = index { return index }
        let folder = resourceURL.appendingPathComponent("pronunciations", isDirectory: true)
        let manifest = try JSONDecoder().decode(Manifest.self, from: Data(contentsOf: folder.appendingPathComponent("manifest.json")))
        guard manifest.schemaVersion == 1, manifest.language == "en",
              (1...250_000).contains(manifest.entries), (28...16_777_216).contains(manifest.bytes),
              manifest.sha256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
              manifest.file == "en-\(manifest.sha256.prefix(16)).mfpr" else {
            throw DictionaryError.invalidData("Incompatible English pronunciation metadata.")
        }
        let url = folder.appendingPathComponent(manifest.file)
        let bytes = try Data(contentsOf: url, options: .mappedIfSafe)
        guard bytes.count == manifest.bytes, sha256(bytes) == manifest.sha256 else {
            throw DictionaryError.invalidData("English pronunciation integrity check failed.")
        }
        let result = try PronunciationIndex(url: url)
        guard result.entries == manifest.entries else { throw DictionaryError.invalidData("Pronunciation entry count mismatch.") }
        index = result
        return result
    }
}
