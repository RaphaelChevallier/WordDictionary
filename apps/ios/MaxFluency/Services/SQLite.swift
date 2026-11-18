import Foundation
import SQLite3

enum PlatformFailure: LocalizedError {
    case message(String)
    var errorDescription: String? {
        switch self { case .message(let value): return value }
    }
}

enum SQLValue {
    case text(String), integer(Int64), real(Double), null
}

final class SQLite {
    private var handle: OpaquePointer?

    init(url: URL, readOnly: Bool = false) throws {
        let flags = readOnly ? SQLITE_OPEN_READONLY : SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE
        let result = sqlite3_open_v2(url.path, &handle, flags | SQLITE_OPEN_FULLMUTEX, nil)
        guard result == SQLITE_OK else {
            let detail = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "No database handle"
            if let handle { sqlite3_close_v2(handle) }
            handle = nil
            throw PlatformFailure.message("Database could not be opened: \(detail)")
        }
        sqlite3_busy_timeout(handle, 5_000)
    }

    deinit { if let handle { sqlite3_close_v2(handle) } }

    func prepare(_ sql: String) throws -> SQLStatement {
        guard let handle else { throw PlatformFailure.message("Database is closed.") }
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &statement, nil) == SQLITE_OK, let statement else {
            throw PlatformFailure.message("Database statement failed: \(String(cString: sqlite3_errmsg(handle)))")
        }
        return SQLStatement(handle: statement, database: self)
    }

    func execute(_ sql: String, _ values: [SQLValue] = []) throws {
        try prepare(sql).run(values)
    }

    func rows(_ sql: String, _ values: [SQLValue] = [], body: (SQLStatement) throws -> Void) throws {
        let statement = try prepare(sql)
        try statement.bind(values)
        while try statement.step() { try body(statement) }
    }

    func integer(_ sql: String, _ values: [SQLValue] = []) throws -> Int64 {
        let statement = try prepare(sql)
        try statement.bind(values)
        guard try statement.step() else { throw PlatformFailure.message("Database returned no count.") }
        return statement.integer(0)
    }
}

final class SQLStatement {
    private let handle: OpaquePointer
    private let database: SQLite
    // SQLite copies bound Swift strings before their temporary C buffers disappear.
    private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    init(handle: OpaquePointer, database: SQLite) {
        self.handle = handle
        self.database = database
    }

    deinit { sqlite3_finalize(handle) }

    func bind(_ values: [SQLValue]) throws {
        sqlite3_reset(handle)
        sqlite3_clear_bindings(handle)
        guard values.count == Int(sqlite3_bind_parameter_count(handle)) else {
            throw PlatformFailure.message("Database parameter count does not match.")
        }
        for (index, value) in values.enumerated() {
            let position = Int32(index + 1)
            let result: Int32
            switch value {
            case .text(let text):
                guard !text.contains("\0") else { throw PlatformFailure.message("Database text contains a null character.") }
                result = sqlite3_bind_text(handle, position, text, -1, Self.transient)
            case .integer(let number): result = sqlite3_bind_int64(handle, position, number)
            case .real(let number): result = sqlite3_bind_double(handle, position, number)
            case .null: result = sqlite3_bind_null(handle, position)
            }
            guard result == SQLITE_OK else { throw failure() }
        }
    }

    func run(_ values: [SQLValue]) throws {
        try bind(values)
        while try step() {}
    }

    func step() throws -> Bool {
        switch sqlite3_step(handle) {
        case SQLITE_ROW: return true
        case SQLITE_DONE: return false
        default: throw failure()
        }
    }

    func integer(_ column: Int32) -> Int64 { sqlite3_column_int64(handle, column) }

    func text(_ column: Int32) throws -> String {
        guard let bytes = sqlite3_column_text(handle, column),
              let value = String(data: Data(bytes: bytes, count: Int(sqlite3_column_bytes(handle, column))), encoding: .utf8) else {
            throw PlatformFailure.message("Database text is missing or not valid UTF-8.")
        }
        return value
    }

    private func failure() -> PlatformFailure {
        .message("Database operation failed: \(String(cString: sqlite3_errmsg(sqlite3_db_handle(handle))))")
    }
}
