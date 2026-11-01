import XCTest
import Security
@testable import Lifety

final class IosPlatformTests: XCTestCase {
    private func date(_ value: String) -> Date { ISO8601DateFormatter().date(from: value)! }
    private var utc: Calendar { var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(secondsFromGMT: 0)!; return calendar }

    func testOverlappingSleepStagesAreNotDoubleCountedAndCrossMidnightIsSplit() {
        let first = date("2026-10-24T00:00:00Z"), second = date("2026-10-25T00:00:00Z")
        let intervals = [DateInterval(start: date("2026-10-24T22:00:00Z"), end: date("2026-10-25T06:00:00Z")),
                         DateInterval(start: date("2026-10-24T23:00:00Z"), end: date("2026-10-25T02:00:00Z"))]
        let result = SleepHistory.totals(intervals: intervals, dates: [first, second], end: date("2026-10-26T00:00:00Z"), calendar: utc)
        XCTAssertEqual(result[first], 120)
        XCTAssertEqual(result[second], 360)
    }

    func testMissingSleepStaysUnknownAndCurrentDayIsClipped() {
        let day = date("2026-10-25T00:00:00Z"), end = date("2026-10-25T03:00:00Z")
        XCTAssertTrue(SleepHistory.totals(intervals: [], dates: [day], end: end, calendar: utc).isEmpty)
        let result = SleepHistory.totals(intervals: [DateInterval(start: day, end: date("2026-10-25T06:00:00Z"))], dates: [day], end: end, calendar: utc)
        XCTAssertEqual(result[day], 180)
    }

    func testDaylightSavingDayUsesCalendarBoundaries() {
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(identifier: "Europe/Berlin")!
        let day = date("2026-10-24T22:00:00Z"), next = date("2026-10-25T23:00:00Z")
        let result = SleepHistory.totals(intervals: [DateInterval(start: day, end: next)], dates: [day], end: next, calendar: calendar)
        XCTAssertEqual(result[day], 25 * 60)
    }

    func testKeychainPersistsAcrossStoreInstancesAndUpdatesExistingEntry() {
        let service = "ru.lifety.ios.tests.\(UUID().uuidString)"
        defer { SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service] as CFDictionary) }
        let first = KeychainStore(service: service)
        XCTAssertNil(first.read().value)
        XCTAssertTrue(first.write(value: "synthetic-test-credentials"))
        let second = KeychainStore(service: service)
        XCTAssertEqual(second.read().value, "synthetic-test-credentials")
        XCTAssertNil(second.read().error)
        XCTAssertTrue(second.write(value: "synthetic-updated"))
        XCTAssertEqual(first.read().value, "synthetic-updated")
    }
}

final class PlannerCompanionTests: XCTestCase {
    private var utc: Calendar { var value = Calendar(identifier: .gregorian); value.timeZone = TimeZone(secondsFromGMT: 0)!; return value }
    private func date(_ value: String) -> Date { ISO8601DateFormatter().date(from: value)! }
    private func task(_ id: String, deadline: Date?, start: Date? = nil) -> PlannerTask {
        PlannerTask(id: id, title: "Task " + id, scheduledAt: start.map { $0.timeIntervalSince1970 * 1000 }, deadlineAt: deadline.map { $0.timeIntervalSince1970 * 1000 })
    }
    func testDeadlineAndLeadAreScheduledAndPastRemindersExcluded() {
        let now = date("2026-11-01T10:00:00Z")
        var preferences = ReminderPreferences(); preferences.enabled = true
        let snapshot = PlannerSnapshot(protected: false, tasks: [task("future", deadline: now.addingTimeInterval(3600)), task("past", deadline: now.addingTimeInterval(-1))], people: [])
        let requests = ReminderPlanner.requests(snapshot: snapshot, preferences: preferences, now: now, calendar: utc)
        XCTAssertEqual(requests.map(\.id), ["lifety.planner.before.future", "lifety.planner.due.future"])
        XCTAssertEqual(requests[0].date, now.addingTimeInterval(2700))
        XCTAssertEqual(requests[1].date, now.addingTimeInterval(3600))
        XCTAssertEqual(requests[1].taskId, "future")
        preferences.enabled = false
        XCTAssertTrue(ReminderPlanner.requests(snapshot: snapshot, preferences: preferences, now: now).isEmpty)
    }
    func testOptionalStartsAndNoDuplicateWhenStartEqualsDeadline() {
        let now = date("2026-11-01T10:00:00Z"), future = now.addingTimeInterval(600)
        var preferences = ReminderPreferences(); preferences.enabled = true; preferences.starts = true; preferences.leadMinutes = 0
        let snapshot = PlannerSnapshot(protected: false, tasks: [task("a", deadline: future, start: future), task("b", deadline: nil, start: future)], people: [])
        XCTAssertEqual(Set(ReminderPlanner.requests(snapshot: snapshot, preferences: preferences, now: now).map(\.id)), ["lifety.planner.due.a", "lifety.planner.start.b"])
    }
    func testBirthdayReminderAtLocalTimeAndDayBeforeAcrossYearBoundary() {
        var preferences = ReminderPreferences(); preferences.enabled = true
        var calendar = utc; calendar.timeZone = TimeZone(identifier: "Europe/Moscow")!
        let now = date("2026-12-30T10:00:00Z")
        let snapshot = PlannerSnapshot(protected: false, tasks: [], people: [PlannerPerson(id: "p", fullName: "Анна", birthdayMonth: 1, birthdayDay: 1)])
        let requests = ReminderPlanner.requests(snapshot: snapshot, preferences: preferences, now: now, calendar: calendar)
        XCTAssertEqual(requests.first?.date, date("2026-12-31T06:00:00Z"))
        XCTAssertEqual(requests[1].date, date("2027-01-01T06:00:00Z"))
        XCTAssertEqual(requests[1].route, "people")
    }
    func testLeapBirthdaysMatchInAppCalendarAndInvalidDatesDoNotRoll() {
        var preferences = ReminderPreferences(); preferences.enabled = true; preferences.birthdayBefore = false
        let snapshot = PlannerSnapshot(protected: false, tasks: [], people: [PlannerPerson(id: "leap", fullName: "Анна", birthdayMonth: 2, birthdayDay: 29), PlannerPerson(id: "bad", fullName: "Bad", birthdayMonth: 4, birthdayDay: 31)])
        let requests = ReminderPlanner.requests(snapshot: snapshot, preferences: preferences, now: date("2027-01-01T00:00:00Z"), calendar: utc)
        XCTAssertEqual(requests[0].date, date("2027-02-28T09:00:00Z"))
        XCTAssertEqual(requests[1].date, date("2028-02-29T09:00:00Z"))
        XCTAssertFalse(requests.contains { $0.id.contains("bad") })
    }
    func testProtectedPlanHasGenericNotificationsAndNoWidgetTasks() {
        let now = date("2026-11-01T10:00:00Z")
        var preferences = ReminderPreferences(); preferences.enabled = true
        let snapshot = PlannerSnapshot(protected: true, tasks: [task("a", deadline: now.addingTimeInterval(3600))], people: [PlannerPerson(id: "p", fullName: "PRIVATE_NAME", birthdayMonth: 11, birthdayDay: 2)])
        let requests = ReminderPlanner.requests(snapshot: snapshot, preferences: preferences, now: now, calendar: utc)
        XCTAssertFalse(requests.contains { $0.body.contains("PRIVATE_NAME") || $0.body.contains("Task a") })
        XCTAssertTrue(TodoWidgetDocument(updatedAt: now, protected: true, tasks: snapshot.tasks).visibleTasks(at: now).isEmpty)
    }
    func testQueueIsBoundedAndStableAndDeletionChangesDesiredSet() {
        let now = date("2026-11-01T10:00:00Z")
        var preferences = ReminderPreferences(); preferences.enabled = true; preferences.leadMinutes = 0
        let snapshot = PlannerSnapshot(protected: false, tasks: (0..<100).map { task(String($0), deadline: now.addingTimeInterval(Double(100 + $0))) }, people: [])
        let requests = ReminderPlanner.requests(snapshot: snapshot, preferences: preferences, now: now)
        XCTAssertEqual(requests.count, 60)
        XCTAssertEqual(requests.first?.id, "lifety.planner.due.0")
        let removed = PlannerSnapshot(protected: false, tasks: Array(snapshot.tasks.dropFirst()), people: [])
        XCTAssertFalse(ReminderPlanner.requests(snapshot: removed, preferences: preferences, now: now).contains { $0.id == requests.first?.id })
    }
    func testWidgetTodayOverdueAndUndatedAndStableOrdering() {
        let now = date("2026-11-01T10:00:00Z")
        let document = TodoWidgetDocument(updatedAt: now, protected: false, tasks: [task("tomorrow", deadline: now.addingTimeInterval(86400)), task("no-date", deadline: nil), task("today", deadline: now.addingTimeInterval(3600)), task("overdue", deadline: now.addingTimeInterval(-86400))])
        XCTAssertEqual(document.visibleTasks(at: now, calendar: utc).map(\.id), ["overdue", "today", "no-date"])
        XCTAssertEqual(document.visibleTasks(at: now.addingTimeInterval(86400), calendar: utc).map(\.id), ["overdue", "today", "tomorrow", "no-date"])
    }
    func testURLsRoundTripOpaqueTaskIdentifiers() {
        let id = "a/b ?&юникод"
        let url = plannerURL(route: "tasks", id: id)
        XCTAssertEqual(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first?.value, id)
        XCTAssertEqual(url.host, "tasks")
    }
    func testModelDecodesActualKotlinShapeWithNullDates() throws {
        let value = try JSONDecoder().decode(PlannerSnapshot.self, from: Data(#"{"version":1,"protected":false,"tasks":[{"id":"t","title":"Дело","scheduledAt":null,"deadlineAt":1234}],"people":[]}"#.utf8))
        XCTAssertNil(value.tasks.first?.scheduled)
        XCTAssertEqual(try XCTUnwrap(value.tasks.first?.deadline?.timeIntervalSince1970), 1.234, accuracy: 0.000001)
    }
}
