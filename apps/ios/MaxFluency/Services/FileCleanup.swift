import Foundation
import os

enum FileCleanup {
    private static let logger = Logger(subsystem: "com.maxfluency.app", category: "file-cleanup")
    static func close(_ handle: FileHandle) {
        do { try handle.close() }
        catch { logger.error("A temporary file handle could not be closed: \(error.localizedDescription, privacy: .private)") }
    }
    static func remove(_ url: URL) {
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        do { try FileManager.default.removeItem(at: url) }
        catch { logger.error("A temporary file could not be removed: \(error.localizedDescription, privacy: .private)") }
    }
}
