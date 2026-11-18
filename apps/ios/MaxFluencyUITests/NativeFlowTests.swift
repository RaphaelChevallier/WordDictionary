import XCTest

@MainActor
final class NativeFlowTests: XCTestCase {
    private func launch(featured: Bool = false) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        if featured { app.launchArguments.append("--ui-test-word") }
        app.launch()
        return app
    }

    func testGardenOpensCompleteCardAndSavesToTheLibrary() {
        let app = launch()
        let first = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "garden-word-")).firstMatch
        XCTAssertTrue(first.waitForExistence(timeout: 20))
        first.tap()
        XCTAssertTrue(app.descendants(matching: .any)["entry-screen"].firstMatch.waitForExistence(timeout: 5))
        XCTAssertFalse(app.textFields["search-field"].isHittable)
        app.buttons["word-favorite"].tap()
        XCTAssertTrue(app.buttons["Unsave word"].waitForExistence(timeout: 5))
        app.navigationBars.buttons.firstMatch.tap()
        app.buttons["nav-library"].tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "word-row-")).count > 0 ||
                      app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "word-row-")).count > 0)
    }

    func testAdditionalMeaningHintOpensAllMeaningsAndAnkiStaysInOverflow() {
        let app = launch(featured: true)
        let hint = app.descendants(matching: .any)["more-meanings"].firstMatch
        XCTAssertTrue(hint.waitForExistence(timeout: 20))
        hint.tap()
        XCTAssertTrue(app.descendants(matching: .any)["entry-screen"].firstMatch.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["Export to Anki"].exists)
        for _ in 0..<6 {
            if app.descendants(matching: .any)["original-sense-1"].firstMatch.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(app.descendants(matching: .any)["original-sense-1"].firstMatch.exists)
        for _ in 0..<6 {
            if app.buttons["word-more-actions"].isHittable { break }
            app.swipeDown()
        }
        app.buttons["word-more-actions"].tap()
        app.buttons["Export to Anki"].tap()
        XCTAssertTrue(app.buttons["Save deck file (.apkg)"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Send to AnkiMobile"].exists)
    }

    func testListenOnTheDailyPreviewDoesNotNavigate() {
        let app = launch(featured: true)
        let listen = app.buttons["daily-listen"]
        XCTAssertTrue(listen.waitForExistence(timeout: 20))
        listen.tap()
        XCTAssertFalse(app.descendants(matching: .any)["entry-screen"].firstMatch.exists)
        XCTAssertTrue(app.descendants(matching: .any)["word-of-day"].firstMatch.exists)
    }
}
