import SwiftUI
import WidgetKit

private struct TodoEntry: TimelineEntry {
    var date: Date
    var document: TodoWidgetDocument?
    var preview = false
    var tasks: [PlannerTask] { document?.visibleTasks(at: date) ?? [] }
}
private struct TodoProvider: TimelineProvider {
    func placeholder(in context: Context) -> TodoEntry { sample() }
    func getSnapshot(in context: Context, completion: @escaping (TodoEntry) -> Void) {
        completion(context.isPreview ? sample() : TodoEntry(date: Date(), document: TodoWidgetDocument.read()))
    }
    func getTimeline(in context: Context, completion: @escaping (Timeline<TodoEntry>) -> Void) {
        let now = Date(), calendar = Calendar.current
        let document = TodoWidgetDocument.read()
        // Future entries update overdue labels without waking the app at each task deadline.
        let nextMidnight = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: now)) ?? now.addingTimeInterval(86400)
        let dates = Set([now, nextMidnight] + (document?.tasks.flatMap { [$0.deadline, $0.scheduled].compactMap { $0 } }.filter { $0 > now && $0 < nextMidnight } ?? []))
        let entries = dates.sorted().prefix(40).map { TodoEntry(date: $0, document: document) }
        completion(Timeline(entries: Array(entries), policy: .after(nextMidnight)))
    }
    private func sample() -> TodoEntry {
        TodoEntry(date: Date(), document: TodoWidgetDocument(updatedAt: Date(), protected: false, tasks: [
            PlannerTask(id: "preview1", title: "Подготовить презентацию", scheduledAt: nil, deadlineAt: Date().addingTimeInterval(3600).timeIntervalSince1970 * 1000),
            PlannerTask(id: "preview2", title: "Прогуляться", scheduledAt: nil, deadlineAt: nil)
        ]), preview: true)
    }
}

private struct TodoWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: TodoEntry
    private var protected: Bool { entry.document?.protected == true }
    private var limit: Int { family == .systemSmall ? 2 : family == .systemMedium ? 3 : 7 }
    var body: some View {
        Group {
            switch family {
            case .accessoryInline:
                Text(protected ? "Lifety · План защищён" : entry.tasks.first.map { "□ " + $0.title } ?? "Lifety · Нет дел")
            case .accessoryCircular:
                VStack(spacing: 1) {
                    Image(systemName: protected ? "lock.fill" : "checklist")
                    if !protected { Text("\(entry.tasks.count)").font(.headline) }
                }.widgetAccentable()
            case .accessoryRectangular:
                VStack(alignment: .leading, spacing: 3) {
                    Label("Lifety · Дела", systemImage: protected ? "lock.fill" : "checklist").font(.caption.bold()).widgetAccentable()
                    if protected { Text("Откройте План").font(.caption) }
                    else { ForEach(Array(entry.tasks.prefix(2))) { task in Text("□ " + task.title).font(.caption).lineLimit(1) } }
                    if !protected && entry.tasks.isEmpty { Text("Нет дел на сегодня").font(.caption) }
                }
            default:
                homeWidget
            }
        }
        .privacySensitive()
        .widgetURL(plannerURL(route: "tasks"))
        .containerBackground(.background, for: .widget)
    }
    private var homeWidget: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Label("Дела", systemImage: "checklist").font(.headline).lineLimit(1).layoutPriority(1).widgetAccentable()
                Spacer()
                if !protected && family != .systemSmall { Text(entry.date, format: .dateTime.day().month(.abbreviated)).font(.caption).foregroundStyle(.secondary) }
            }
            if protected {
                Spacer(minLength: 0)
                Label("План защищён", systemImage: "lock.fill").font(.subheadline)
                Text("Откройте Lifety").font(.caption).foregroundStyle(.secondary)
                Spacer(minLength: 0)
            } else if entry.document == nil {
                Text("Откройте Lifety, чтобы загрузить дела").font(.subheadline).foregroundStyle(.secondary)
                Spacer(minLength: 0)
            } else if entry.tasks.isEmpty {
                Spacer(minLength: 0)
                Label("Всё свободно", systemImage: "checkmark.circle").font(.subheadline)
                Text("Добавьте дело в План").font(.caption).foregroundStyle(.secondary)
                Spacer(minLength: 0)
            } else {
                ForEach(Array(entry.tasks.prefix(limit))) { task in
                    Link(destination: task.url) {
                        HStack(alignment: .top, spacing: 8) {
                            Image(systemName: "circle").font(.subheadline).foregroundStyle(.secondary).padding(.top, 2)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(task.title).font(.subheadline.weight(.medium)).lineLimit(family == .systemSmall ? 2 : 1)
                                if let deadline = task.deadline {
                                    HStack(spacing: 3) {
                                        Image(systemName: "flag")
                                        Text(deadline, format: .dateTime.day().month(.abbreviated).hour().minute())
                                    }.font(.caption2).foregroundStyle(deadline < entry.date ? Color.red : Color.secondary)
                                } else if let start = task.scheduled {
                                    Text(start, format: .dateTime.day().month(.abbreviated).hour().minute()).font(.caption2).foregroundStyle(.secondary)
                                }
                            }
                            Spacer(minLength: 0)
                        }
                    }.buttonStyle(.plain)
                }
                Spacer(minLength: 0)
                if entry.tasks.count > limit { Text("Ещё \(entry.tasks.count - limit) в Плане").font(.caption2).foregroundStyle(.secondary) }
            }
        }
    }
}

@main
struct LifetyTodoWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: TodoWidgetDocument.kind, provider: TodoProvider()) { entry in TodoWidgetView(entry: entry) }
            .configurationDisplayName("Дела Lifety")
            .description("Дела на сегодня, просроченные задачи и список без даты. Нажмите, чтобы открыть План.")
            .supportedFamilies([.systemSmall, .systemMedium, .systemLarge, .accessoryInline, .accessoryCircular, .accessoryRectangular])
    }
}
