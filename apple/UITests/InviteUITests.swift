import XCTest

final class InviteUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    @MainActor
    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-uitest", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        let name = app.textFields["firstRunName"]
        XCTAssertTrue(name.waitForExistence(timeout: 10))
        name.tap()
        name.typeText("Bo")
        app.buttons["firstRunContinue"].tap()
        return app
    }

    @MainActor
    func testJoinWithPastedLinkOpensNearby() throws {
        let app = launch()
        let join = app.buttons["joinGroup"]
        XCTAssertTrue(join.waitForExistence(timeout: 10))
        join.tap()
        let field = app.descendants(matching: .any)["inviteLink"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText("tripsplit://join?g=00000000-0000-4000-8000-0000000000aa#k=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
        app.navigationBars["Join group"].buttons["Join"].tap()
        // The group opens on the Nearby screen and says that it waits for the first sync.
        XCTAssertTrue(app.navigationBars["Nearby"].waitForExistence(timeout: 10))
    }

    @MainActor
    func testInviteShowsQRCodeAndShareWarning() throws {
        let app = launch()
        let create = app.buttons["createGroupEmpty"]
        XCTAssertTrue(create.waitForExistence(timeout: 10))
        create.tap()
        let groupName = app.textFields["groupName"]
        XCTAssertTrue(groupName.waitForExistence(timeout: 5))
        groupName.tap()
        groupName.typeText("Kapadokya")
        app.buttons["createGroupConfirm"].tap()

        let members = app.tabBars.buttons.element(boundBy: 2)
        XCTAssertTrue(members.waitForExistence(timeout: 10))
        members.tap()
        app.buttons["invite"].tap()
        XCTAssertTrue(app.images["Invite QR code"].waitForExistence(timeout: 5))
        let share = app.buttons["shareLink"]
        XCTAssertTrue(share.isEnabled)
        share.tap()
        // The share sheet is behind a warning.
        XCTAssertTrue(app.alerts["Share the link?"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.alerts.staticTexts.matching(NSPredicate(format: "label CONTAINS 'read and change the group'")).firstMatch.exists)
        app.alerts.buttons["Cancel"].tap()
    }
}
