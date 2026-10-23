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

extension InviteUITests {
    /// "From now on" is the default: a member added later does not join the earlier expenses.
    @MainActor
    func testNewMemberFromNowOnLeavesEarlierExpenses() throws {
        let app = launch()
        let create = app.buttons["createGroupEmpty"]
        XCTAssertTrue(create.waitForExistence(timeout: 10))
        create.tap()
        let groupName = app.textFields["groupName"]
        XCTAssertTrue(groupName.waitForExistence(timeout: 5))
        groupName.tap()
        groupName.typeText("Trip")
        app.buttons["createGroupConfirm"].tap()

        // An expense that only the creator is in.
        let add = app.buttons["addExpense"]
        XCTAssertTrue(add.waitForExistence(timeout: 10))
        add.tap()
        let title = app.textFields["expenseTitle"]
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        title.tap()
        title.typeText("Taxi")
        let amount = app.textFields["expenseAmount"]
        amount.tap()
        amount.typeText("30")
        let save = app.buttons["saveExpense"]
        expectation(for: NSPredicate(format: "isEnabled == true"), evaluatedWith: save)
        waitForExpectations(timeout: 5)
        save.tap()

        // Add a member: the app asks about the earlier expenses.
        app.tabBars.buttons.element(boundBy: 2).tap()
        app.buttons["addMember"].tap()
        let name = app.textFields["nameField"]
        XCTAssertTrue(name.waitForExistence(timeout: 5))
        name.tap()
        name.typeText("Ayse")
        app.buttons["nameConfirm"].tap()
        let also = app.buttons["Also earlier expenses"]
        XCTAssertTrue(also.waitForExistence(timeout: 10))
        app.buttons["From now on"].tap()

        // The default changed nothing: nobody owes anything.
        app.tabBars.buttons.element(boundBy: 1).tap()
        XCTAssertTrue(app.staticTexts["Everyone is settled."].waitForExistence(timeout: 5))
    }
}
