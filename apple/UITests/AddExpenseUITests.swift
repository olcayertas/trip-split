import XCTest

final class AddExpenseUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    func testFirstRunCreateGroupAndAddExpense() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-uitest", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()

        // First run: enter a name.
        let name = app.textFields["firstRunName"]
        XCTAssertTrue(name.waitForExistence(timeout: 10))
        name.tap()
        name.typeText("Ali")
        app.buttons["firstRunContinue"].tap()

        // Create a group.
        let create = app.buttons["createGroupEmpty"]
        XCTAssertTrue(create.waitForExistence(timeout: 10))
        create.tap()
        let groupName = app.textFields["groupName"]
        XCTAssertTrue(groupName.waitForExistence(timeout: 5))
        groupName.tap()
        groupName.typeText("Kapadokya")
        app.buttons["createGroupConfirm"].tap()

        // The group opens. Add a member, then an expense.
        let addMemberTab = app.tabBars.buttons.element(boundBy: 2)
        XCTAssertTrue(addMemberTab.waitForExistence(timeout: 10))
        addMemberTab.tap()
        app.buttons["addMember"].tap()
        let memberName = app.textFields["nameField"]
        XCTAssertTrue(memberName.waitForExistence(timeout: 5))
        memberName.tap()
        memberName.typeText("Ayse")
        app.buttons["nameConfirm"].tap()
        XCTAssertTrue(app.staticTexts["Ayse"].waitForExistence(timeout: 5))

        app.tabBars.buttons.element(boundBy: 0).tap()
        let add = app.buttons["addExpense"]
        XCTAssertTrue(add.waitForExistence(timeout: 5))
        add.tap()

        let title = app.textFields["expenseTitle"]
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        title.tap()
        title.typeText("Dinner")
        let amount = app.textFields["expenseAmount"]
        amount.tap()
        amount.typeText("30.50")

        let save = app.buttons["saveExpense"]
        let enabled = NSPredicate(format: "isEnabled == true")
        expectation(for: enabled, evaluatedWith: save)
        waitForExpectations(timeout: 5)
        save.tap()

        // The expense is in the list with its amount, and the balances show the debt and the credit.
        XCTAssertTrue(element(app, containing: "Dinner").waitForExistence(timeout: 5))
        XCTAssertTrue(element(app, containing: "30.50 ").exists)
        app.tabBars.buttons.element(boundBy: 1).tap()
        XCTAssertTrue(element(app, containing: "+15.25 ").waitForExistence(timeout: 5))
        XCTAssertTrue(element(app, containing: "\u{2212}15.25 ").exists)
    }

    /// A list row can merge its texts into one element, so match on part of the label.
    @MainActor
    private func element(_ app: XCUIApplication, containing text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }
}
