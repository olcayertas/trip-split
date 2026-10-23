import XCTest

/// Takes one screenshot of each screen. It only runs when SHOT_DIR is set (TEST_RUNNER_SHOT_DIR for xcodebuild).
final class ScreenshotTests: XCTestCase {
    @MainActor
    func testScreens() throws {
        guard let dir = ProcessInfo.processInfo.environment["SHOT_DIR"] else { throw XCTSkip("SHOT_DIR is not set") }
        continueAfterFailure = false

        func shot(_ name: String) {
            sleep(1)
            let data = XCUIScreen.main.screenshot().pngRepresentation
            try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
            try? data.write(to: URL(fileURLWithPath: dir).appending(path: name + ".png"))
        }

        // First run.
        var app = XCUIApplication()
        app.launchArguments = ["-uitest", "-AppleLanguages", "(en)", "-AppleLocale", "en_GB"]
        app.launch()
        XCTAssertTrue(app.textFields["firstRunName"].waitForExistence(timeout: 10))
        shot("01-first-run")
        app.terminate()

        // Demo data.
        app = XCUIApplication()
        app.launchArguments = ["-uitest", "-seed-demo", "-AppleLanguages", "(en)", "-AppleLocale", "en_GB"]
        app.launch()
        let groupRow = app.staticTexts["Kapadokya 2026"]
        XCTAssertTrue(groupRow.waitForExistence(timeout: 15))
        shot("02-groups")

        app.buttons["createGroup"].tap()
        XCTAssertTrue(app.textFields["groupName"].waitForExistence(timeout: 5))
        shot("03-create-group")
        app.buttons["Cancel"].tap()

        app.buttons["joinGroup"].tap()
        XCTAssertTrue(app.buttons["scanQR"].waitForExistence(timeout: 5))
        shot("12-join")
        app.buttons["Cancel"].tap()

        groupRow.tap()
        XCTAssertTrue(app.buttons["addExpense"].waitForExistence(timeout: 10))
        shot("04-expenses")

        app.buttons["addExpense"].tap()
        let title = app.textFields["expenseTitle"]
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        title.tap()
        title.typeText("Lunch")
        app.textFields["expenseAmount"].tap()
        app.textFields["expenseAmount"].typeText("42.00")
        shot("05-add-expense")
        app.segmentedControls["splitMode"].buttons["Weights"].tap()
        shot("06-split-weights")
        app.buttons["Cancel"].tap()

        app.tabBars.buttons.element(boundBy: 1).tap()
        XCTAssertTrue(app.buttons["recordPayment"].waitForExistence(timeout: 5))
        shot("07-balances")

        app.tabBars.buttons.element(boundBy: 2).tap()
        XCTAssertTrue(app.buttons["addMember"].waitForExistence(timeout: 5))
        shot("08-members")
        app.buttons["invite"].tap()
        XCTAssertTrue(app.images["Invite QR code"].waitForExistence(timeout: 5))
        shot("11-invite")
        app.buttons["Done"].tap()

        app.buttons["History"].tap()
        sleep(1)
        shot("09-history")
        app.navigationBars.buttons.element(boundBy: 0).tap()

        app.buttons["nearby"].tap()
        sleep(1)
        shot("10-nearby")
    }
}
