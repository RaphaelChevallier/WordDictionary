import Foundation

struct BinaryBytes {
    let data: Data
    var offset = 0

    mutating func take(_ count: Int) throws -> Data {
        guard count >= 0, offset >= 0, count <= data.count - offset else {
            throw PlatformFailure.message("A compressed dictionary record is truncated.")
        }
        defer { offset += count }
        return data.subdata(in: offset..<(offset + count))
    }

    mutating func byte() throws -> UInt8 { try take(1)[0] }
    mutating func little16() throws -> Int {
        let bytes = try take(2)
        return Int(bytes[0]) | Int(bytes[1]) << 8
    }
    mutating func little32() throws -> Int {
        let bytes = try take(4)
        return (0..<4).reduce(0) { $0 | Int(bytes[$1]) << ($1 * 8) }
    }
    mutating func big32() throws -> Int {
        let bytes = try take(4)
        return bytes.reduce(0) { $0 << 8 | Int($1) }
    }
    mutating func word() throws -> String? {
        if offset == data.count { return nil }
        let start = offset
        while try byte() != 0 {
            guard offset - start <= 4_096 else { throw PlatformFailure.message("A dictionary headword is too long.") }
        }
        guard let word = String(data: data.subdata(in: start..<(offset - 1)), encoding: .utf8), !word.isEmpty else {
            throw PlatformFailure.message("A dictionary headword is invalid.")
        }
        return word
    }
}

enum CRC32 {
    private static let table: [UInt32] = (0..<256).map { value in
        (0..<8).reduce(UInt32(value)) { number, _ in
            number & 1 == 0 ? number >> 1 : (number >> 1) ^ 0xedb88320
        }
    }
    static func update(_ crc: UInt32, with data: Data) -> UInt32 {
        data.reduce(crc) { (value, byte) in table[Int((value ^ UInt32(byte)) & 255)] ^ (value >> 8) }
    }
    static func checksum(_ data: Data) -> UInt32 { update(0xffffffff, with: data) ^ 0xffffffff }
}

enum ZipArchive {
    struct Entry {
        let name: String
        let method: Int
        let checksum: UInt32
        let compressed: Int
        let decoded: Int
        let localOffset: Int
    }

    static func entries(in data: Data) throws -> [Entry] {
        guard data.count >= 22 else { throw PlatformFailure.message("ZIP end record is missing.") }
        let minimum = max(0, data.count - 65_557)
        var end: Int?
        for position in stride(from: data.count - 22, through: minimum, by: -1) {
            var reader = BinaryBytes(data: data, offset: position)
            if try reader.little32() != 0x06054b50 { continue }
            reader.offset = position + 20
            if try position + 22 + reader.little16() == data.count { end = position; break }
        }
        guard let end else { throw PlatformFailure.message("ZIP end record is invalid.") }
        var footer = BinaryBytes(data: data, offset: end + 4)
        guard try footer.little16() == 0, try footer.little16() == 0 else {
            throw PlatformFailure.message("Multi-disk ZIP dictionaries are not supported.")
        }
        let diskCount = try footer.little16()
        let count = try footer.little16()
        let directoryBytes = try footer.little32()
        let directoryOffset = try footer.little32()
        guard count == diskCount, count > 0, count <= 16,
              directoryOffset + directoryBytes == end else {
            throw PlatformFailure.message("The dictionary ZIP directory is invalid.")
        }
        var reader = BinaryBytes(data: data, offset: directoryOffset)
        var result: [Entry] = []
        var names = Set<String>()
        var total = 0
        for _ in 0..<count {
            guard try reader.little32() == 0x02014b50 else { throw PlatformFailure.message("Invalid ZIP directory entry.") }
            _ = try reader.take(4)
            let flags = try reader.little16()
            let method = try reader.little16()
            _ = try reader.take(4)
            let checksum = try reader.little32()
            let compressed = try reader.little32()
            let decoded = try reader.little32()
            let nameLength = try reader.little16()
            let extraLength = try reader.little16()
            let commentLength = try reader.little16()
            _ = try reader.take(8)
            let local = try reader.little32()
            guard let name = String(data: try reader.take(nameLength), encoding: .utf8),
                  !name.hasSuffix("/"), names.insert(name).inserted, flags & 1 == 0,
                  method == 0 || method == 8, compressed < 512_000_000, decoded <= 512_000_000,
                  local >= 0, local < directoryOffset else {
                throw PlatformFailure.message("Unsupported or duplicate ZIP dictionary entry.")
            }
            total += decoded
            guard total <= 512_000_000 else { throw PlatformFailure.message("Dictionary extraction exceeds its safety limit.") }
            _ = try reader.take(extraLength + commentLength)
            result.append(Entry(name: name, method: method, checksum: UInt32(checksum),
                                compressed: compressed, decoded: decoded, localOffset: local))
        }
        guard reader.offset == end else { throw PlatformFailure.message("ZIP directory length does not match.") }
        return result
    }

    static func decode(_ entry: Entry, from data: Data) throws -> Data {
        var reader = BinaryBytes(data: data, offset: entry.localOffset)
        guard try reader.little32() == 0x04034b50 else { throw PlatformFailure.message("ZIP local header is invalid.") }
        _ = try reader.take(4)
        guard try reader.little16() == entry.method else { throw PlatformFailure.message("ZIP compression metadata does not match.") }
        _ = try reader.take(16)
        let name = try reader.little16()
        let extra = try reader.little16()
        _ = try reader.take(name + extra)
        let compressed = try reader.take(entry.compressed)
        let decoded = entry.method == 0 ? compressed : try Deflate.decode(compressed, expectedBytes: entry.decoded, raw: true)
        guard decoded.count == entry.decoded, CRC32.checksum(decoded) == entry.checksum else {
            throw PlatformFailure.message("Dictionary archive checksum does not match.")
        }
        return decoded
    }

    static func createStored(files: [(name: String, url: URL)], at destination: URL) throws {
        guard !files.isEmpty, files.count <= 16,
              FileManager.default.createFile(atPath: destination.path, contents: nil) else {
            throw PlatformFailure.message("Anki package could not be created.")
        }
        let output = try FileHandle(forWritingTo: destination)
        defer { FileCleanup.close(output) }
        var directory = Data()
        var position = 0
        for file in files {
            try Task.checkCancellation()
            let name = Data(file.name.utf8)
            let length = try FileManager.default.attributesOfItem(atPath: file.url.path)[.size] as? NSNumber
            guard let length, length.int64Value >= 0, length.int64Value < Int64(UInt32.max),
                  name.count <= 65_535, position < Int(UInt32.max) else {
                throw PlatformFailure.message("Anki package exceeds ZIP size limits.")
            }
            let input = try FileHandle(forReadingFrom: file.url)
            defer { FileCleanup.close(input) }
            var crc = UInt32.max
            while let chunk = try input.read(upToCount: 65_536), !chunk.isEmpty {
                try Task.checkCancellation()
                crc = CRC32.update(crc, with: chunk)
            }
            crc ^= UInt32.max
            let bytes = length.intValue
            var header = Data()
            header.zip32(0x04034b50); header.zip16(20); header.zip16(0x0800); header.zip16(0)
            header.zip16(0); header.zip16(0); header.zip32(Int(crc)); header.zip32(bytes); header.zip32(bytes)
            header.zip16(name.count); header.zip16(0); header.append(name)
            try output.write(contentsOf: header)
            try input.seek(toOffset: 0)
            while let chunk = try input.read(upToCount: 65_536), !chunk.isEmpty {
                try Task.checkCancellation()
                try output.write(contentsOf: chunk)
            }
            directory.zip32(0x02014b50); directory.zip16(20); directory.zip16(20)
            directory.zip16(0x0800); directory.zip16(0); directory.zip16(0); directory.zip16(0)
            directory.zip32(Int(crc)); directory.zip32(bytes); directory.zip32(bytes)
            directory.zip16(name.count); directory.zip16(0); directory.zip16(0); directory.zip16(0)
            directory.zip16(0); directory.zip32(0); directory.zip32(position); directory.append(name)
            position += header.count + bytes
        }
        try output.write(contentsOf: directory)
        var footer = Data()
        footer.zip32(0x06054b50); footer.zip16(0); footer.zip16(0)
        footer.zip16(files.count); footer.zip16(files.count)
        footer.zip32(directory.count); footer.zip32(position); footer.zip16(0)
        try output.write(contentsOf: footer)
        try output.synchronize()
    }
}

private extension Data {
    mutating func zip16(_ value: Int) { for shift in [0, 8] { append(UInt8(truncatingIfNeeded: value >> shift)) } }
    mutating func zip32(_ value: Int) { for shift in [0, 8, 16, 24] { append(UInt8(truncatingIfNeeded: value >> shift)) } }
}
