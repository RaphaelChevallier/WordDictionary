import Foundation

public enum Deflate {
    // Optional full-pack ZIP members need this shared ceiling; readers retain their tighter bounds.
    private static let maximumBytes = 512_000_000

    public static func decode(_ data: Data, expectedBytes: Int, raw: Bool = false,
                              allowIncomplete: Bool = false) throws -> Data {
        guard (0...maximumBytes).contains(expectedBytes), data.count <= maximumBytes else {
            throw DictionaryError.invalidData("Deflate input or output exceeds the 512,000,000-byte shared decoder safety limit.")
        }
        // One spare byte detects output beyond the advertised length, including empty records.
        var output = Data(count: expectedBytes + 1)
        let capacity = output.count
        var written = 0
        let status: Int32 = output.withUnsafeMutableBytes { destination in
            data.withUnsafeBytes { source in
                mf_inflate(source.bindMemory(to: UInt8.self).baseAddress, data.count,
                           destination.bindMemory(to: UInt8.self).baseAddress, capacity,
                           &written, raw ? 1 : 0, allowIncomplete ? 1 : 0)
            }
        }
        guard status == 0, written == expectedBytes else {
            throw DictionaryError.invalidData("Corrupt/truncated deflate frame or decoded length mismatch (zlib \(status)).")
        }
        output.removeSubrange(written..<output.count)
        return output
    }
}
