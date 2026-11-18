import Foundation
import SQLite3

private let sqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

final class OnlineWordCache {
    private let database: OpaquePointer
    private let maximumWords: Int
    private var isOpen = true

    init(url: URL, maximumWords: Int) throws {
        guard maximumWords > 0 else { throw DictionaryError.storage("The starter bank exceeds the temporary word-storage limit.") }
        var connection: OpaquePointer?
        let status = sqlite3_open_v2(url.path, &connection, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nil)
        guard status == SQLITE_OK, let connection = connection else {
            if let connection = connection { sqlite3_close(connection) }
            throw DictionaryError.storage("Could not open the explicit online dictionary cache.")
        }
        database = connection
        self.maximumWords = maximumWords
        do {
            sqlite3_busy_timeout(database, 5000)
            let version = try scalar("PRAGMA user_version")
            guard version == 0 || version == 1 else {
                throw DictionaryError.storage("Unsupported online-cache schema; a non-destructive migration is required.")
            }
            if version == 0 {
                guard try scalar("SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'") == 0 else {
                    throw DictionaryError.storage("Unrecognized online-cache database; the original was retained.")
                }
                try transaction {
                    try execute("""
                        CREATE TABLE cache(id TEXT PRIMARY KEY NOT NULL,payload TEXT NOT NULL,language TEXT NOT NULL,
                          word TEXT NOT NULL,norm TEXT NOT NULL,anagram TEXT NOT NULL,level TEXT NOT NULL,
                          views INTEGER NOT NULL DEFAULT 0,correct INTEGER NOT NULL DEFAULT 0,last_access INTEGER NOT NULL);
                        CREATE INDEX idx_words ON cache(language,norm,word);
                        CREATE INDEX idx_anagram ON cache(language,anagram);
                        PRAGMA user_version=1;
                        """)
                }
            }
            try statement("PRAGMA quick_check(1)") { query in
                guard sqlite3_step(query) == SQLITE_ROW, try string(query, column: 0) == "ok" else {
                    throw DictionaryError.storage("The online word cache is corrupt; no data was reset.")
                }
            }
            guard try scalar("SELECT count(*) FROM cache") <= maximumWords else {
                throw DictionaryError.storage("The online cache exceeds the starter-plus-online word limit.")
            }
        } catch { sqlite3_close(database); isOpen = false; throw error }
    }

    deinit { if isOpen { sqlite3_close(database) } }

    func entry(id: String) throws -> WordEntry? {
        var result: WordEntry?
        try statement("SELECT payload,id FROM cache WHERE id=?", [id]) { query in
            let status = sqlite3_step(query)
            if status == SQLITE_ROW { result = try entry(query) }
            else if status != SQLITE_DONE { throw failure() }
        }
        return result
    }

    func search(language: DictionaryLanguage, plan: SearchPlan) throws -> [WordEntry] {
        guard let (filter, args) = plan.sqlFilter else { return [] }
        var results: [WordEntry] = []
        try statement("SELECT payload,id FROM cache WHERE language=? AND \(filter) ORDER BY norm,word LIMIT 60",
                      [language.rawValue] + args) { query in
            while true {
                let status = sqlite3_step(query)
                if status == SQLITE_DONE { break }
                guard status == SQLITE_ROW else { throw failure() }
                let word = try entry(query)
                guard word.language == language, plan.matches(word.word) else {
                    throw DictionaryError.storage("The online cache search index does not match its word payload.")
                }
                results.append(word)
            }
        }
        return results
    }

    func candidateIDs(language: DictionaryLanguage, level: VocabularyLevel) throws -> [String] {
        var result: [String] = []
        try statement("SELECT id FROM cache WHERE language=? AND level=? ORDER BY correct,views,last_access DESC LIMIT 200",
                      [language.rawValue, level.rawValue]) { query in
            while true {
                let status = sqlite3_step(query)
                if status == SQLITE_DONE { break }
                guard status == SQLITE_ROW else { throw failure() }
                result.append(try string(query, column: 0))
            }
        }
        return result
    }

    func put(_ entry: WordEntry, level: VocabularyLevel, protectedIDs: Set<String>) throws {
        try entry.validate()
        let encoded = try JSONEncoder().encode(entry)
        guard let payload = String(data: encoded, encoding: .utf8), encoded.count <= 16_777_216 else {
            throw DictionaryError.storage("The online word is too large to cache.")
        }
        try transaction {
            let existing = try scalar("SELECT count(*) FROM cache WHERE id=?", [entry.id]) != 0
            if !existing {
                let count = try scalar("SELECT count(*) FROM cache")
                if count >= maximumWords {
                    try execute("CREATE TEMP TABLE IF NOT EXISTS protected(id TEXT PRIMARY KEY); DELETE FROM protected;")
                    for id in protectedIDs { try update("INSERT OR IGNORE INTO protected VALUES (?)", [id]) }
                    try update("""
                        DELETE FROM cache WHERE id IN (SELECT id FROM cache
                        WHERE id NOT IN (SELECT id FROM protected)
                        ORDER BY correct DESC,views DESC,last_access ASC LIMIT ?)
                        """, [String(count - maximumWords + 1)])
                    guard try scalar("SELECT count(*) FROM cache") < maximumWords else {
                        throw DictionaryError.storage("The online cache is full; saved words and today's selection were protected.")
                    }
                }
            }
            try update("""
                INSERT INTO cache(id,payload,language,word,norm,anagram,level,last_access) VALUES (?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET payload=excluded.payload,language=excluded.language,word=excluded.word,
                norm=excluded.norm,anagram=excluded.anagram,level=excluded.level,last_access=excluded.last_access
                """, [entry.id, payload, entry.language.rawValue, entry.word, SearchKeys.normalize(entry.word),
                      SearchKeys.anagram(entry.word), level.rawValue, String(Int64(Date().timeIntervalSince1970 * 1000))])
        }
    }

    func seen(_ ids: [String]) throws {
        try transaction {
            let now = String(Int64(Date().timeIntervalSince1970 * 1000))
            for id in ids { try update("UPDATE cache SET views=views+1,last_access=? WHERE id=?", [now, id]) }
        }
    }

    func correct(_ id: String) throws { try update("UPDATE cache SET correct=correct+1 WHERE id=?", [id]) }

    private func entry(_ query: OpaquePointer) throws -> WordEntry {
        let payload = try string(query, column: 0)
        guard payload.utf8.count <= 16_777_216 else { throw DictionaryError.storage("Oversized online-cache word record.") }
        let result = try JSONDecoder().decode(WordEntry.self, from: Data(payload.utf8))
        try result.validate()
        guard result.id == (try string(query, column: 1)) else { throw DictionaryError.storage("Online-cache identity mismatch.") }
        return result
    }

    private func string(_ query: OpaquePointer, column: Int32) throws -> String {
        guard sqlite3_column_type(query, column) == SQLITE_TEXT,
              let bytes = sqlite3_column_text(query, column) else { throw DictionaryError.storage("Invalid online-cache text field.") }
        let count = Int(sqlite3_column_bytes(query, column))
        guard count <= 16_777_216, let value = String(data: Data(bytes: bytes, count: count), encoding: .utf8) else {
            throw DictionaryError.storage("Invalid online-cache UTF-8 field.")
        }
        return value
    }

    private func scalar(_ sql: String, _ arguments: [String] = []) throws -> Int {
        var result = 0
        try statement(sql, arguments) { query in
            guard sqlite3_step(query) == SQLITE_ROW else { throw failure() }
            let value = sqlite3_column_int64(query, 0)
            guard value >= 0, value <= Int64(Int.max) else { throw DictionaryError.storage("Invalid online-cache count.") }
            result = Int(value)
        }
        return result
    }

    private func update(_ sql: String, _ arguments: [String] = []) throws {
        try statement(sql, arguments) { query in
            guard sqlite3_step(query) == SQLITE_DONE else { throw failure() }
        }
    }

    private func statement(_ sql: String, _ arguments: [String] = [], body: (OpaquePointer) throws -> Void) throws {
        var prepared: OpaquePointer?
        guard sqlite3_prepare_v2(database, sql, -1, &prepared, nil) == SQLITE_OK, let prepared = prepared else { throw failure() }
        defer { sqlite3_finalize(prepared) }
        guard sqlite3_bind_parameter_count(prepared) == Int32(arguments.count) else {
            throw DictionaryError.storage("Online-cache SQL parameter mismatch.")
        }
        for (index, value) in arguments.enumerated() {
            let status = value.withCString { sqlite3_bind_text(prepared, Int32(index + 1), $0, -1, sqliteTransient) }
            guard status == SQLITE_OK else { throw failure() }
        }
        try body(prepared)
    }

    private func execute(_ sql: String) throws {
        guard sqlite3_exec(database, sql, nil, nil, nil) == SQLITE_OK else { throw failure() }
    }
    private func transaction(_ mutation: () throws -> Void) throws {
        try execute("BEGIN IMMEDIATE")
        do { try mutation(); try execute("COMMIT") }
        catch { try? execute("ROLLBACK"); throw error }
    }
    private func failure() -> DictionaryError {
        let detail = sqlite3_errmsg(database).map { String(cString: $0) } ?? "Unknown SQLite error"
        return .storage("Online-cache SQLite failure: \(detail)")
    }
}
