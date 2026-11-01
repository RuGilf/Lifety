import Foundation

// Shared only between the native app and WidgetKit. The extension never opens the KMP database.
struct PlannerSnapshot: Codable, Equatable {
    var version: Int = 1
    var protected: Bool
    var tasks: [PlannerTask]
    var people: [PlannerPerson]
}
struct PlannerTask: Codable, Equatable, Identifiable {
    var id: String
    var title: String
    var scheduledAt: Double?
    var deadlineAt: Double?
    var scheduled: Date? { scheduledAt.map { Date(timeIntervalSince1970: $0 / 1000) } }
    var deadline: Date? { deadlineAt.map { Date(timeIntervalSince1970: $0 / 1000) } }
    var orderDate: Date { min(scheduled ?? .distantFuture, deadline ?? .distantFuture) }
    var url: URL { plannerURL(route: "tasks", id: id) }
}
struct PlannerPerson: Codable, Equatable {
    var id: String
    var fullName: String
    var birthdayMonth: Int
    var birthdayDay: Int
}
func plannerURL(route: String, id: String? = nil) -> URL {
    var components = URLComponents()
    components.scheme = "lifety"; components.host = route
    if let id { components.queryItems = [URLQueryItem(name: "id", value: id)] }
    return components.url!
}

struct TodoWidgetDocument: Codable, Equatable {
    var version = 1
    var updatedAt: Date
    var protected: Bool
    var tasks: [PlannerTask]
    static let group = "group.ru.lifety.ios"
    static let filename = "todo-widget-v1.json"
    static let kind = "LifetyTodo"
    static var fileURL: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)?.appendingPathComponent(filename)
    }
    static func read() -> Self? {
        guard let url = fileURL, let data = try? Data(contentsOf: url),
              let value = try? JSONDecoder().decode(Self.self, from: data), value.version == 1 else { return nil }
        return value
    }
    func visibleTasks(at date: Date, calendar: Calendar = .current) -> [PlannerTask] {
        guard !protected else { return [] }
        let end = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: date)) ?? date
        return tasks.filter { $0.orderDate < end || $0.orderDate == .distantFuture }.sorted {
            if $0.orderDate != $1.orderDate { return $0.orderDate < $1.orderDate }
            return $0.id < $1.id
        }
    }
}

struct ReminderPreferences: Codable, Equatable {
    var enabled = false
    var deadlines = true
    var leadMinutes = 15
    var starts = false
    var birthdays = true
    var birthdayBefore = true
    var birthdayHour = 9
    var sound = true
}
struct PlannedReminder: Equatable {
    var id: String
    var title: String
    var body: String
    var date: Date
    var route: String
    var taskId: String?
}

enum ReminderPlanner {
    // Leave room for other app notification features and the OS's pending-request limit.
    static let capacity = 60
    static func requests(snapshot: PlannerSnapshot, preferences: ReminderPreferences, now: Date, calendar source: Calendar = .current) -> [PlannedReminder] {
        guard preferences.enabled else { return [] }
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = source.timeZone
        var result: [PlannedReminder] = []
        func add(_ id: String, _ title: String, _ body: String, _ date: Date, _ route: String = "tasks", _ taskId: String? = nil) {
            guard date > now.addingTimeInterval(1) else { return }
            result.append(PlannedReminder(id: "lifety.planner." + id, title: title, body: body, date: date, route: route, taskId: taskId))
        }
        for task in snapshot.tasks {
            let body = snapshot.protected ? "Откройте защищённый План, чтобы посмотреть дело." : task.title
            if preferences.deadlines, let deadline = task.deadline {
                add("due." + task.id, "Срок задачи наступил", body, deadline, "tasks", task.id)
                let lead = [0, 5, 15, 30, 60, 1440].contains(preferences.leadMinutes) ? preferences.leadMinutes : 15
                if lead > 0 {
                    let text = lead == 1440 ? "До дедлайна — 1 день" : "До дедлайна — \(lead) мин"
                    add("before." + task.id, text, body, deadline.addingTimeInterval(-Double(lead) * 60), "tasks", task.id)
                }
            }
            if preferences.starts, let start = task.scheduled, start != task.deadline {
                add("start." + task.id, "Пора начать задачу", body, start, "tasks", task.id)
            }
        }
        if preferences.birthdays {
            let year = calendar.component(.year, from: now)
            for person in snapshot.people {
                guard (1...12).contains(person.birthdayMonth), (1...31).contains(person.birthdayDay) else { continue }
                // Up to three annual occurrences, bounded by the nearest-reminder queue.
                for y in year...(year + 2) {
                    let leap = y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)
                    let day = person.birthdayMonth == 2 && person.birthdayDay == 29 && !leap ? 28 : person.birthdayDay
                    let hour = (0...23).contains(preferences.birthdayHour) ? preferences.birthdayHour : 9
                    guard let birthday = calendar.date(from: DateComponents(year: y, month: person.birthdayMonth, day: day, hour: hour)),
                          calendar.component(.month, from: birthday) == person.birthdayMonth,
                          calendar.component(.day, from: birthday) == day else { continue }
                    let name = snapshot.protected ? "День рождения в вашем календаре" : person.fullName
                    add("birthday.\(person.id).\(y)", "Сегодня день рождения", name, birthday, "people")
                    if preferences.birthdayBefore, let before = calendar.date(byAdding: .day, value: -1, to: birthday) {
                        add("birthdayBefore.\(person.id).\(y)", "Завтра день рождения", name, before, "people")
                    }
                }
            }
        }
        return Array(result.sorted { $0.date == $1.date ? $0.id < $1.id : $0.date < $1.date }.prefix(capacity))
    }
}
