import XCTest
@testable import MaxFluency

final class ServiceTests: XCTestCase {
    private func word() -> WordEntry {
        WordEntry(id: "fr:abjurer", language: .french, word: "abjurer", pronunciation: "",
                  senses: [Sense(partOfSpeech: "verb", definition: "Renoncer à une croyance.", sourceIds: [],
                                 examples: ["Elle refuse d’abjurer sa foi."]),
                           Sense(partOfSpeech: "verb", definition: "Abandonner une opinion.", sourceIds: [], examples: [])],
                  related: [], sourceEdition: "fr", sources: [], pronunciationGuide: "")
    }

    func testTranslationRetainsTheWordAndEveryMeaningExamplePosition() throws {
        let entry = word()
        let segments = TranslationPayload.segments(entry)
        XCTAssertEqual(segments.map(\.id), ["word", "definition:0", "example:0:0", "definition:1"])
        let result = try TranslationPayload.assemble(entry: entry, results: [
            "word": "renounce", "definition:0": "Give up a belief.", "example:0:0": "She refuses to renounce her faith.",
            "definition:1": "Abandon an opinion."
        ])
        XCTAssertEqual(result.senses[0].examples, ["She refuses to renounce her faith."])
        XCTAssertEqual(result.senses[1].examples, [])
        XCTAssertThrowsError(try TranslationPayload.assemble(entry: entry, results: ["word": "renounce"]))
    }

    func testHTMLKeepsLeafExamplesAndRejectsNestedAttributedQuotes() throws {
        let html = """
        <h2>Français</h2><h3>Verbe</h3><ol>
        <li>Un sens général.<ol><li>Un sens précis.
          <ul><li class="example">Un exemple précis.</li></ul></li></ol></li>
        <li>Un autre sens.<ul><li class="example">Un exemple utile.</li>
          <li class="h-quotation"><span class="example">Une citation publiée.</span>
          <span class="sources">Un ouvrage.</span></li></ul></li></ol>
        """
        let entry = try XCTUnwrap(try DictionaryHTML.wiki(language: .french, word: "essai", html: html))
        XCTAssertEqual(entry.senses.count, 3)
        XCTAssertTrue(entry.senses[0].examples.isEmpty)
        XCTAssertEqual(entry.senses[1].examples, ["Un exemple précis."])
        XCTAssertEqual(entry.senses[2].examples, ["Un exemple utile."])
        XCTAssertEqual(DictionaryHTML.cleanDefinition("..."), "")
        XCTAssertEqual(DictionaryHTML.cleanDefinition("Un [[mot|terme]] ^([12])."), "Un terme.")
    }

    @MainActor
    func testMeasuredGardenPackingKeepsEveryTargetSeparate() throws {
        let sizes = (0..<20).map { index in CGSize(width: index % 5 == 3 ? 48 : CGFloat(70 + index * 3),
                                                  height: index % 5 == 3 ? 120 : 48) }
        let positions = try XCTUnwrap(GardenLayout.pack(sizes: sizes, width: 390, height: 900))
        XCTAssertEqual(positions.count, sizes.count)
        let rectangles = zip(positions, sizes).map { CGRect(origin: $0.0, size: $0.1) }
        for index in rectangles.indices {
            XCTAssertTrue(CGRect(x: 0, y: 0, width: 390, height: 900).contains(rectangles[index]))
            for other in rectangles.indices where other > index {
                XCTAssertFalse(rectangles[index].intersects(rectangles[other]))
            }
        }
    }

    func testAnkiPackageContainsStableOriginalNotesWithoutTranslationsOrPronunciation() async throws {
        let entry = word()
        let notes = try AnkiExporter.notes([entry, entry])
        XCTAssertEqual(notes.count, 1)
        XCTAssertTrue(notes[0].back.contains("Elle refuse d’abjurer sa foi."))
        XCTAssertTrue(notes[0].back.contains("CC BY-SA 4.0"))
        var changed = entry
        changed.senses = [Sense(partOfSpeech: "verb", definition: "A revised definition.", sourceIds: [], examples: [])]
        XCTAssertEqual(notes[0].guid, try AnkiExporter.notes([changed])[0].guid)
        let url = try await AnkiExporter().export(entries: [entry])
        defer { FileCleanup.remove(url.deletingLastPathComponent()) }
        let bytes = try Data(contentsOf: url)
        let files = try ZipArchive.entries(in: bytes)
        XCTAssertEqual(Set(files.map(\.name)), Set(["collection.anki2", "media"]))
        let collection = try XCTUnwrap(files.first { $0.name == "collection.anki2" })
        let databaseURL = url.deletingLastPathComponent().appendingPathComponent("test-collection.sqlite")
        try ZipArchive.decode(collection, from: bytes).write(to: databaseURL)
        let database = try SQLite(url: databaseURL, readOnly: true)
        XCTAssertEqual(try database.integer("SELECT count(*) FROM notes"), 1)
        XCTAssertEqual(try database.integer("SELECT count(*) FROM cards"), 1)
        var fields = ""
        try database.rows("SELECT flds FROM notes") { fields = try $0.text(0) }
        XCTAssertEqual(fields.components(separatedBy: "\u{001f}").count, 3)
        XCTAssertTrue(fields.contains(entry.senses[1].definition))
    }

    func testZIPRejectsCorruptPayloadAndTruncatedDirectories() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
        defer { FileCleanup.remove(directory) }
        let source = directory.appendingPathComponent("source")
        try Data("123456789".utf8).write(to: source)
        let archive = directory.appendingPathComponent("fixture.zip")
        try ZipArchive.createStored(files: [("sample", source)], at: archive)
        var bytes = try Data(contentsOf: archive)
        let entry = try XCTUnwrap(try ZipArchive.entries(in: bytes).first)
        XCTAssertEqual(CRC32.checksum(try ZipArchive.decode(entry, from: bytes)), 0xcbf43926)
        bytes[30 + "sample".utf8.count] ^= 1
        XCTAssertThrowsError(try ZipArchive.decode(entry, from: bytes))
        XCTAssertThrowsError(try ZipArchive.entries(in: Data(bytes.prefix(12))))
    }

    func testNotificationTargetPreservesDateLanguageAndLevel() throws {
        let target = try ReminderTarget(userInfo: ["word": "fr:abjurer", "date": "2026-11-17", "level": "ADVANCED"])
        XCTAssertEqual(target.id, "fr:abjurer")
        XCTAssertEqual(target.date, "2026-11-17")
        XCTAssertEqual(target.level, .advanced)
        XCTAssertThrowsError(try ReminderTarget(userInfo: ["word": "fr:abjurer", "date": "not-a-date", "level": "ADVANCED"]))
    }
}
