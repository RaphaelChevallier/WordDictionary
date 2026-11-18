import Foundation
import CryptoKit
import UIKit

struct AnkiNote: Sendable {
    let key: String
    let front: String
    let back: String
    let tag: String
    var guid: String {
        "mf" + Data(SHA256.hash(data: Data(key.utf8)).prefix(15)).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
}

actor AnkiExporter {
    static let modelID: Int64 = 1_763_400_001_001
    static let deckID: Int64 = 1_763_400_001_002
    static let modelName = "MaxFluency Vocabulary v1"
    static let deckName = "MaxFluency"
    private var active = false
    private var nextExport = Date.distantPast

    static func html(_ value: String) -> String {
        value.unicodeScalars.map { scalar -> String in
            switch scalar.value {
            case 10: return "<br>"
            case 38: return "&amp;"
            case 60: return "&lt;"
            case 62: return "&gt;"
            case 34: return "&quot;"
            case 39: return "&#39;"
            case 0...31, 127...159: return " "
            default: return String(scalar)
            }
        }.joined()
    }

    static func notes(_ entries: [WordEntry]) throws -> [AnkiNote] {
        guard (1...5_000).contains(entries.count) else { throw PlatformFailure.message("Choose 1–5,000 words for an Anki export.") }
        var seen = Set<String>()
        return try entries.filter { seen.insert($0.id).inserted }.map { word in
            guard word.id == "\(word.language.rawValue):\(word.word)", !word.word.isEmpty, !word.senses.isEmpty else {
                throw PlatformFailure.message("Anki export requires a complete dictionary word.")
            }
            let key = "mf-\(word.language.rawValue)-" + SHA256.hash(data: Data(word.id.utf8)).prefix(16).map { String(format: "%02x", $0) }.joined()
            let front = "<div class=\"language\">\(html(word.language.nativeName))</div><div class=\"word\">\(html(word.word))</div>"
            var back = "<ol>"
            for sense in word.senses {
                back += "<li>\(html(sense.definition))"
                for example in sense.examples { back += "<div class=\"example\">\(html(example))</div>" }
                back += "</li>"
            }
            back += "</ol><div class=\"credits\">Wiktionary contributors"
            for source in word.sourceLinks { back += "<br><a href=\"\(html(source.url.absoluteString))\">\(html(source.title))</a>" }
            back += "<br><a href=\"https://creativecommons.org/licenses/by-sa/4.0/\">CC BY-SA 4.0</a></div>"
            guard front.utf8.count + back.utf8.count <= 200_000 else { throw PlatformFailure.message("Anki note exceeds the safe export size.") }
            return AnkiNote(key: key, front: front, back: back, tag: "maxfluency::\(word.language.rawValue)")
        }
    }

    func export(entries: [WordEntry]) async throws -> URL {
        guard !active, Date() >= nextExport else { throw PlatformFailure.message("Please wait for the current export or a few seconds before trying again.") }
        active = true
        defer { active = false; nextExport = Date().addingTimeInterval(5) }
        let notes = try Self.notes(entries)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("maxfluency-anki-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
        do {
            let collection = directory.appendingPathComponent("collection.anki2")
            try await createCollection(at: collection, notes: notes)
            let media = directory.appendingPathComponent("media")
            try Data("{}".utf8).write(to: media, options: .atomic)
            let result = directory.appendingPathComponent("MaxFluency.apkg")
            try ZipArchive.createStored(files: [("collection.anki2", collection), ("media", media)], at: result)
            try FileManager.default.removeItem(at: collection)
            try FileManager.default.removeItem(at: media)
            return result
        } catch { FileCleanup.remove(directory); throw error }
    }

    private func createCollection(at url: URL, notes: [AnkiNote]) async throws {
        let database = try SQLite(url: url)
        for sql in [
            "CREATE TABLE col(id INTEGER PRIMARY KEY,crt INTEGER NOT NULL,mod INTEGER NOT NULL,scm INTEGER NOT NULL,ver INTEGER NOT NULL,dty INTEGER NOT NULL,usn INTEGER NOT NULL,ls INTEGER NOT NULL,conf TEXT NOT NULL,models TEXT NOT NULL,decks TEXT NOT NULL,dconf TEXT NOT NULL,tags TEXT NOT NULL)",
            "CREATE TABLE notes(id INTEGER PRIMARY KEY,guid TEXT NOT NULL,mid INTEGER NOT NULL,mod INTEGER NOT NULL,usn INTEGER NOT NULL,tags TEXT NOT NULL,flds TEXT NOT NULL,sfld INTEGER NOT NULL,csum INTEGER NOT NULL,flags INTEGER NOT NULL,data TEXT NOT NULL)",
            "CREATE TABLE cards(id INTEGER PRIMARY KEY,nid INTEGER NOT NULL,did INTEGER NOT NULL,ord INTEGER NOT NULL,mod INTEGER NOT NULL,usn INTEGER NOT NULL,type INTEGER NOT NULL,queue INTEGER NOT NULL,due INTEGER NOT NULL,ivl INTEGER NOT NULL,factor INTEGER NOT NULL,reps INTEGER NOT NULL,lapses INTEGER NOT NULL,left INTEGER NOT NULL,odue INTEGER NOT NULL,odid INTEGER NOT NULL,flags INTEGER NOT NULL,data TEXT NOT NULL)",
            "CREATE TABLE revlog(id INTEGER PRIMARY KEY,cid INTEGER NOT NULL,usn INTEGER NOT NULL,ease INTEGER NOT NULL,ivl INTEGER NOT NULL,lastIvl INTEGER NOT NULL,factor INTEGER NOT NULL,time INTEGER NOT NULL,type INTEGER NOT NULL)",
            "CREATE TABLE graves(usn INTEGER NOT NULL,oid INTEGER NOT NULL,type INTEGER NOT NULL)",
            "CREATE INDEX ix_notes_csum ON notes(csum)", "CREATE INDEX ix_notes_usn ON notes(usn)",
            "CREATE INDEX ix_cards_nid ON cards(nid)", "CREATE INDEX ix_cards_sched ON cards(did,queue,due)",
            "CREATE INDEX ix_cards_usn ON cards(usn)", "CREATE INDEX ix_revlog_cid ON revlog(cid)", "CREATE INDEX ix_revlog_usn ON revlog(usn)"
        ] { try database.execute(sql) }
        let now = Int64(Date().timeIntervalSince1970 * 1_000)
        let seconds = now / 1_000
        let fields = ["MaxFluencyId", "Word", "Meanings"].enumerated().map { ordinal, name -> [String: Any] in
            ["name": name, "ord": ordinal, "sticky": false, "rtl": false, "font": "Arial", "size": 20, "media": [String]()]
        }
        let model: [String: Any] = [
            "id": Self.modelID, "name": Self.modelName, "type": 0, "mod": seconds, "usn": -1, "sortf": 1, "did": Self.deckID,
            "css": ".card { font-family: sans-serif; font-size: 20px; text-align: left; } .word { font-family: serif; font-size: 30px; } .language, .credits { font-size: 14px; } .example { font-style: italic; margin: 0.4em 0; } li { margin-bottom: 0.8em; }",
            "latexPre": "", "latexPost": "", "tags": [String](), "vers": [Int](), "flds": fields,
            "tmpls": [["name": "Word to meanings", "ord": 0, "qfmt": "{{Word}}", "afmt": "{{FrontSide}}<hr id=\"answer\">{{Meanings}}", "bqfmt": "", "bafmt": "", "did": NSNull()]],
            "req": [[0, "all", [1]] as [Any]]
        ]
        func deck(_ id: Int64, _ name: String) -> [String: Any] {
            ["id": id, "name": name, "mod": seconds, "usn": -1, "desc": "", "dyn": 0, "conf": 1, "collapsed": false,
             "browserCollapsed": false, "extendNew": 0, "extendRev": 0, "newToday": [0, 0], "revToday": [0, 0], "lrnToday": [0, 0], "timeToday": [0, 0]]
        }
        let deckConfig: [String: Any] = [
            "id": 1, "name": "Default", "mod": seconds, "usn": -1, "maxTaken": 60, "autoplay": true, "timer": 0, "replayq": true,
            "new": ["delays": [1, 10], "ints": [1, 4, 0], "initialFactor": 2_500, "order": 1, "perDay": 20, "bury": false],
            "rev": ["perDay": 200, "ease4": 1.3, "fuzz": 0.05, "ivlFct": 1, "maxIvl": 36_500, "minSpace": 1, "bury": false],
            "lapse": ["delays": [10], "mult": 0, "minInt": 1, "leechFails": 8, "leechAction": 0]
        ]
        let config: [String: Any] = ["curDeck": Self.deckID, "activeDecks": [Self.deckID], "curModel": String(Self.modelID),
                                     "nextPos": notes.count + 1, "newSpread": 0, "collapseTime": 1_200, "timeLim": 0,
                                     "estTimes": true, "dueCounts": true, "schedVer": 2, "addToCur": true]
        func json(_ value: Any) throws -> SQLValue {
            let data = try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])
            guard let text = String(data: data, encoding: .utf8) else { throw PlatformFailure.message("Anki metadata is not valid UTF-8.") }
            return .text(text)
        }
        try database.execute("INSERT INTO col VALUES(1,?,?,?,11,0,0,0,?,?,?,?,?)", [
            .integer(seconds), .integer(now), .integer(now), try json(config), try json([String(Self.modelID): model]),
            try json(["1": deck(1, "Default"), String(Self.deckID): deck(Self.deckID, Self.deckName)]),
            try json(["1": deckConfig]), .text("{}")
        ])
        try database.execute("BEGIN")
        var batchBytes = 0
        var batchNotes = 0
        for (index, note) in notes.enumerated() {
            try Task.checkCancellation()
            let size = note.front.utf8.count + note.back.utf8.count
            if batchNotes >= 20 || batchBytes + size > 200_000 {
                try await Task.sleep(for: .milliseconds(500))
                batchNotes = 0; batchBytes = 0
            }
            let id = now + Int64(index)
            let checksum = Insecure.SHA1.hash(data: Data(note.key.utf8)).prefix(4).reduce(Int64(0)) { ($0 << 8) | Int64($1) }
            let sort = try DictionaryHTML.plainText(note.front)
            try database.execute("INSERT INTO notes VALUES(?,?,?,?,-1,?,?,?, ?,0,'')", [
                .integer(id), .text(note.guid), .integer(Self.modelID), .integer(seconds),
                .text(" maxfluency \(note.tag) "), .text([note.key, note.front, note.back].joined(separator: "\u{001f}")),
                .text(sort), .integer(checksum)
            ])
            try database.execute("INSERT INTO cards VALUES(?,?,?,0,?,-1,0,0,?,0,2500,0,0,0,0,0,0,'')", [
                .integer(id), .integer(id), .integer(Self.deckID), .integer(seconds), .integer(Int64(index + 1))
            ])
            batchBytes += size; batchNotes += 1
        }
        try database.execute("COMMIT")
        var valid = false
        try database.rows("PRAGMA integrity_check") { valid = try $0.text(0) == "ok" }
        guard valid else { throw PlatformFailure.message("The exported Anki collection failed its integrity check.") }
    }

}

@MainActor
enum AnkiMobile {
    private static var nextSend = Date.distantPast
    static func send(_ word: WordEntry, profile: String) async throws {
        guard Date() >= nextSend else { throw PlatformFailure.message("Please wait a few seconds before sending another note.") }
        defer { nextSend = Date().addingTimeInterval(5) }
        let note = try AnkiExporter.notes([word])[0]
        var url = URLComponents()
        url.scheme = "anki"; url.host = "x-callback-url"; url.path = "/addnote"
        url.queryItems = [
            URLQueryItem(name: "type", value: AnkiExporter.modelName), URLQueryItem(name: "deck", value: AnkiExporter.deckName),
            URLQueryItem(name: "fldMaxFluencyId", value: note.key), URLQueryItem(name: "fldWord", value: note.front),
            URLQueryItem(name: "fldMeanings", value: note.back), URLQueryItem(name: "tags", value: "maxfluency \(note.tag)"),
            URLQueryItem(name: "x-success", value: "maxfluency://anki-return")
        ]
        if !profile.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            url.queryItems?.append(URLQueryItem(name: "profile", value: profile))
        }
        url.percentEncodedQuery = url.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        guard let target = url.url, target.absoluteString.utf8.count <= 32_000 else {
            throw PlatformFailure.message("This note is too large for AnkiMobile's URL handoff. Save an .apkg file instead.")
        }
        guard UIApplication.shared.canOpenURL(target) else {
            throw PlatformFailure.message("AnkiMobile is not installed. Offline .apkg export works without it.")
        }
        guard await UIApplication.shared.open(target) else { throw PlatformFailure.message("AnkiMobile could not be opened.") }
    }
}
