import SwiftUI
import WidgetKit
import UserNotifications
import LifetyShared

@MainActor
final class PlannerCompanion: NSObject, ObservableObject, @preconcurrency IosPlannerCompanion, UNUserNotificationCenterDelegate {
    static let shared = PlannerCompanion()
    @Published var preferences: ReminderPreferences {
        didSet {
            if let data = try? JSONEncoder().encode(preferences) { UserDefaults.standard.set(data, forKey: Self.preferencesKey) }
            scheduleSync()
        }
    }
    @Published private(set) var permission: UNAuthorizationStatus = .notDetermined
    @Published private(set) var pendingCount = 0
    @Published private(set) var error: String?
    @Published private(set) var widgetAvailable = true
    private static let preferencesKey = "lifety.reminders.v1"
    private var snapshot: PlannerSnapshot?
    private let center = UNUserNotificationCenter.current()
    private var syncRunning = false
    private var dirty = false
    private var lastWidget: TodoWidgetDocument?

    override init() {
        if let data = UserDefaults.standard.data(forKey: Self.preferencesKey), let saved = try? JSONDecoder().decode(ReminderPreferences.self, from: data) { preferences = saved }
        else { preferences = ReminderPreferences() }
        super.init()
        center.delegate = self
    }
    func update(snapshotJson: String) {
        guard let data = snapshotJson.data(using: .utf8), let value = try? JSONDecoder().decode(PlannerSnapshot.self, from: data), value.version == 1 else {
            error = "Не удалось обновить данные Плана."; return
        }
        snapshot = value
        exportWidget(value)
        scheduleSync()
    }
    func refresh() { scheduleSync() }

    private func exportWidget(_ snapshot: PlannerSnapshot) {
        let tasks = snapshot.protected ? [] : Array(snapshot.tasks.sorted {
            $0.orderDate == $1.orderDate ? $0.id < $1.id : $0.orderDate < $1.orderDate
        }.prefix(200))
        if let lastWidget, lastWidget.protected == snapshot.protected, lastWidget.tasks == tasks { return }
        let document = TodoWidgetDocument(updatedAt: Date(), protected: snapshot.protected, tasks: tasks)
        guard let url = TodoWidgetDocument.fileURL else { widgetAvailable = false; return }
        do {
            let data = try JSONEncoder().encode(document)
            try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            lastWidget = document; widgetAvailable = true
            WidgetCenter.shared.reloadTimelines(ofKind: TodoWidgetDocument.kind)
        } catch { self.error = "Не удалось обновить виджет. Откройте приложение ещё раз." }
    }
    private func scheduleSync() {
        dirty = true
        guard !syncRunning else { return }
        syncRunning = true
        _Concurrency.Task { @MainActor in
            while self.dirty {
                self.dirty = false
                await self.reconcile()
            }
            self.syncRunning = false
        }
    }
    private func reconcile() async {
        let settings = await center.notificationSettings()
        permission = settings.authorizationStatus
        guard let snapshot else { return }
        let prefs = preferences
        let allowed = [.authorized, .provisional, .ephemeral].contains(permission)
        let planned = allowed ? ReminderPlanner.requests(snapshot: snapshot, preferences: prefs, now: Date()) : []
        let existing = await center.pendingNotificationRequests()
        let foreign = existing.filter { !$0.identifier.hasPrefix("lifety.planner.") }
        let desired = Array(planned.prefix(max(0, ReminderPlanner.capacity - foreign.count)))
        let wanted = Set(desired.map(\.id))
        let ours = existing.filter { $0.identifier.hasPrefix("lifety.planner.") }
        center.removePendingNotificationRequests(withIdentifiers: ours.filter { !wanted.contains($0.identifier) }.map(\.identifier))
        let old = Dictionary(uniqueKeysWithValues: ours.map { ($0.identifier, $0) })
        var failed = false
        for reminder in desired {
            let content = UNMutableNotificationContent()
            content.title = reminder.title; content.body = reminder.body
            content.sound = prefs.sound ? .default : nil
            content.threadIdentifier = reminder.route == "people" ? "lifety.birthdays" : "lifety.tasks"
            content.userInfo = ["route": reminder.route, "taskId": reminder.taskId ?? "", "fireAt": reminder.date.timeIntervalSince1970, "sound": prefs.sound]
            if let previous = old[reminder.id], previous.content.title == content.title, previous.content.body == content.body,
               previous.content.userInfo["fireAt"] as? Double == reminder.date.timeIntervalSince1970,
               previous.content.userInfo["sound"] as? Bool == prefs.sound { continue }
            // Absolute dates include the current time zone offset; birthday dates are recalculated on foreground/time change.
            var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(secondsFromGMT: 0)!
            var components = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: reminder.date)
            components.timeZone = calendar.timeZone
            let request = UNNotificationRequest(identifier: reminder.id, content: content, trigger: UNCalendarNotificationTrigger(dateMatching: components, repeats: false))
            do { try await center.add(request) } catch { failed = true }
        }
        let delivered = await center.deliveredNotifications()
        let activeTaskIds = Set(snapshot.tasks.flatMap { ["lifety.planner.due." + $0.id, "lifety.planner.before." + $0.id, "lifety.planner.start." + $0.id] })
        let personPrefixes = snapshot.people.map { ["lifety.planner.birthday." + $0.id + ".", "lifety.planner.birthdayBefore." + $0.id + "."] }.flatMap { $0 }
        let obsolete = delivered.filter {
            let id = $0.request.identifier
            guard id.hasPrefix("lifety.planner.") else { return false }
            return !prefs.enabled || !allowed || snapshot.protected || (!activeTaskIds.contains(id) && !personPrefixes.contains { id.hasPrefix($0) })
        }
        center.removeDeliveredNotifications(withIdentifiers: obsolete.map { $0.request.identifier })
        pendingCount = await center.pendingNotificationRequests().filter { $0.identifier.hasPrefix("lifety.planner.") }.count
        if failed { error = "Часть напоминаний не удалось запланировать. Откройте настройки ещё раз." }
        else { error = nil }
    }
    func enable() {
        _Concurrency.Task { @MainActor in
            do {
                let allowed = try await center.requestAuthorization(options: [.alert, .sound, .badge])
                preferences.enabled = allowed
                refresh()
            } catch { self.error = "Не удалось запросить уведомления. Проверьте настройки iOS." }
        }
    }
    func showSettings(presenter: UIViewController) {
        var host = presenter
        while let next = host.presentedViewController { host = next }
        let controller = UIHostingController(rootView: ReminderSettingsView(service: self))
        controller.modalPresentationStyle = .pageSheet
        host.present(controller, animated: true)
        refresh()
    }
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let route = response.notification.request.content.userInfo["route"] as? String ?? "tasks"
        let taskId = response.notification.request.content.userInfo["taskId"] as? String
        await MainActor.run { PlannerCompanion_iosKt.openPlannerLink(route: route, taskId: taskId); self.refresh() }
    }
    func open(_ url: URL) {
        guard url.scheme == "lifety", let route = url.host, ["tasks", "people"].contains(route) else { return }
        let id = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "id" }?.value
        PlannerCompanion_iosKt.openPlannerLink(route: route, taskId: id)
    }
}

private struct ReminderSettingsView: View {
    @ObservedObject var service: PlannerCompanion
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    if service.permission == .notDetermined {
                        Button("Включить уведомления") { service.enable() }
                    } else if service.permission == .denied {
                        Label("Уведомления запрещены в iOS", systemImage: "bell.slash")
                        Button("Открыть настройки iOS") { if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) } }
                    } else {
                        Toggle("Напоминания", isOn: $service.preferences.enabled)
                    }
                } footer: { Text("Локальные уведомления работают без интернета и при закрытом Lifety. Режим фокусирования и настройки iOS могут скрыть или задержать их.") }
                Section("Задачи") {
                    Toggle("В момент дедлайна", isOn: $service.preferences.deadlines)
                    Picker("Предупредить заранее", selection: $service.preferences.leadMinutes) {
                        Text("Не предупреждать").tag(0)
                        Text("За 5 минут").tag(5); Text("За 15 минут").tag(15)
                        Text("За 30 минут").tag(30); Text("За час").tag(60); Text("За день").tag(1440)
                    }.disabled(!service.preferences.deadlines)
                    Toggle("В начале запланированного дела", isOn: $service.preferences.starts)
                }.disabled(!service.preferences.enabled)
                Section("Дни рождения") {
                    Toggle("В день рождения", isOn: $service.preferences.birthdays)
                    Toggle("Также за день", isOn: $service.preferences.birthdayBefore).disabled(!service.preferences.birthdays)
                    Picker("Время", selection: $service.preferences.birthdayHour) {
                        ForEach(0..<24) { hour in Text(String(format: "%02d:00", hour)).tag(hour) }
                    }.disabled(!service.preferences.birthdays)
                }.disabled(!service.preferences.enabled)
                Section {
                    Toggle("Звук", isOn: $service.preferences.sound).disabled(!service.preferences.enabled)
                    LabeledContent("Запланировано", value: "\(service.pendingCount)")
                    if let error = service.error { Text(error).foregroundStyle(.red) }
                } footer: { Text("В очередь попадают до 60 ближайших напоминаний. Она обновляется при изменении данных и открытии приложения. Завершённые и удалённые задачи отменяют будущие напоминания.") }
                Section {
                    Label("Рабочий стол и экран блокировки", systemImage: "checklist")
                    Text("Удерживайте свободное место на экране iPhone → Изменить → Добавить виджет → Lifety. Доступны маленький, средний, большой и виджеты экрана блокировки.")
                    if !service.widgetAvailable { Text("Общий контейнер недоступен. Для сборки на устройстве включите App Groups у приложения и расширения.").foregroundStyle(.orange) }
                } header: { Text("Виджеты TODO") } footer: { Text("Виджет обновляется после изменений в Lifety; точное время обновления выбирает iOS. Если План защищён кодом, виджет скрывает дела, а уведомления — названия и имена.") }
            }
            .navigationTitle("Виджеты и напоминания")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Готово") { dismiss() } } }
        }
    }
}
