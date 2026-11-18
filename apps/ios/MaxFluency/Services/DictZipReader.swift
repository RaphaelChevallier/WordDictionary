import Foundation

final class DictZipReader {
    private let data: Data
    private let chunkSize: Int
    private let offsets: [Int]
    private let lengths: [Int]
    private var cache: [Int: Data] = [:]
    private var recency: [Int] = []
    let uncompressedBytes: Int

    init(url: URL) throws {
        let data = try Data(contentsOf: url, options: .mappedIfSafe)
        var input = BinaryBytes(data: data)
        guard data.count >= 24, try input.take(3) == Data([31, 139, 8]) else {
            throw PlatformFailure.message("The installed dictionary is not a gzip dictionary.")
        }
        let flags = try input.byte()
        guard flags & 4 != 0, flags & 0xe0 == 0 else {
            throw PlatformFailure.message("The dictionary has no supported random-access table.")
        }
        _ = try input.take(6)
        let extraBytes = try input.little16()
        let extraEnd = input.offset + extraBytes
        var size = 0
        var lengths: [Int]?
        while input.offset < extraEnd {
            let first = try input.byte()
            let second = try input.byte()
            let count = try input.little16()
            let end = input.offset + count
            guard end <= extraEnd else { throw PlatformFailure.message("Invalid dictzip extra data.") }
            if first == 82, second == 65 {
                guard lengths == nil, try input.little16() == 1 else {
                    throw PlatformFailure.message("Unsupported or duplicate dictzip table.")
                }
                size = try input.little16()
                let chunks = try input.little16()
                guard size > 0, chunks > 0, count == 6 + chunks * 2 else {
                    throw PlatformFailure.message("Invalid dictzip chunk sizes.")
                }
                lengths = try (0..<chunks).map { _ in try input.little16() }
            }
            input.offset = end
        }
        guard input.offset == extraEnd, let lengths else {
            throw PlatformFailure.message("Dictzip random-access metadata is missing.")
        }
        for flag: UInt8 in [8, 16] where flags & flag != 0 {
            var terminated = false
            for _ in 0..<4_096 {
                if try input.byte() == 0 { terminated = true; break }
            }
            guard terminated else { throw PlatformFailure.message("Dictionary header text exceeds its limit.") }
        }
        if flags & 2 != 0 { _ = try input.take(2) }
        var position = input.offset
        var offsets: [Int] = []
        for length in lengths {
            guard length > 0 else { throw PlatformFailure.message("Dictionary contains an empty compressed frame.") }
            offsets.append(position)
            position += length
        }
        guard position <= data.count - 8 else { throw PlatformFailure.message("Dictionary frames are truncated.") }
        var footer = BinaryBytes(data: data, offset: data.count - 4)
        let decoded = try footer.little32()
        guard decoded > 0, (decoded + size - 1) / size == lengths.count else {
            throw PlatformFailure.message("Dictionary uncompressed size does not match its frame table.")
        }
        self.data = data
        self.chunkSize = size
        self.lengths = lengths
        self.offsets = offsets
        self.uncompressedBytes = decoded
    }

    func read(offset: Int, size: Int) throws -> Data {
        guard offset >= 0, size >= 0, size <= 2_097_152,
              offset <= uncompressedBytes, size <= uncompressedBytes - offset else {
            throw PlatformFailure.message("Dictionary article range is invalid.")
        }
        var result = Data()
        result.reserveCapacity(size)
        while result.count < size {
            let absolute = offset + result.count
            let index = absolute / chunkSize
            let chunk: Data
            if let stored = cache[index] { chunk = stored }
            else {
                let expected = min(chunkSize, uncompressedBytes - index * chunkSize)
                chunk = try Deflate.decode(data.subdata(in: offsets[index]..<(offsets[index] + lengths[index])),
                                           expectedBytes: expected, raw: true, allowIncomplete: true)
                cache[index] = chunk
            }
            recency.removeAll { $0 == index }
            recency.append(index)
            if recency.count > 4 { cache.removeValue(forKey: recency.removeFirst()) }
            let start = absolute % chunkSize
            let count = min(size - result.count, chunk.count - start)
            guard count > 0 else { throw PlatformFailure.message("Dictionary article is truncated.") }
            result.append(chunk.subdata(in: start..<(start + count)))
        }
        return result
    }
}
