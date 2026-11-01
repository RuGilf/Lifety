package ru.lifety.core.planning

import kotlinx.datetime.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import ru.lifety.core.model.*
import ru.lifety.core.time.localDate

@Serializable data class MetricDay(val date: String, val value: String?)
@Serializable data class MetricMonth(val month: String, val median: Double, val recordedDays: Int)
@Serializable data class MetricSummary(val id: String, val name: String, val unit: String, val kind: MetricKind,
    val aggregation: MetricAggregation, val last7Days: List<MetricDay>, val months: List<MetricMonth>)

fun metricDailyValues(metric: HealthMetric, entries: List<HealthEntry>, now: Long, zone: TimeZone): Map<LocalDate, String> =
    entries.filter { it.metricId == metric.id && it.timestamp <= now }.groupBy { localDate(it.timestamp, zone) }.mapValues { (_, rows) ->
        if (metric.kind != MetricKind.NUMBER || metric.aggregation == MetricAggregation.LATEST) rows.filter { it.source == "manual" }.ifEmpty { rows }.maxWith(compareBy<HealthEntry> { it.timestamp }.thenBy { it.id }).value
        else {
            // Imported rows are daily totals. A second provider is an alternative total, not another cup/workout.
            val manual = rows.filter { it.source == "manual" }.mapNotNull { it.value.toDoubleOrNull()?.takeIf(Double::isFinite) }
            val imported = rows.filter { it.source != "manual" }.maxWithOrNull(compareBy<HealthEntry> { it.timestamp }.thenBy { it.id })?.value?.toDoubleOrNull()
            val values = manual.ifEmpty { listOfNotNull(imported) }
            (if (metric.aggregation == MetricAggregation.SUM) values.sum() else values.average()).toString()
        }
    }

fun summarizeMetric(metric: HealthMetric, entries: List<HealthEntry>, now: Long, zone: TimeZone): MetricSummary {
    val today = localDate(now, zone)
    val days = metricDailyValues(metric, entries, now, zone)
    val recent = List(7) { i -> val date = today.plus(DatePeriod(days = i - 6)); MetricDay(date.toString(), days[date]) }
    val months = if (metric.kind != MetricKind.NUMBER) emptyList() else days.entries.groupBy { it.key.toString().take(7) }.entries.sortedBy { it.key }.mapNotNull { (month, rows) ->
        val values = rows.mapNotNull { it.value.toDoubleOrNull()?.takeIf(Double::isFinite) }.sorted()
        if (values.isEmpty()) null else MetricMonth(month, if (values.size % 2 == 0) (values[values.size / 2 - 1] + values[values.size / 2]) / 2 else values[values.size / 2], values.size)
    }
    return MetricSummary(metric.id, metric.name, metric.unit, metric.kind, metric.aggregation, recent, months)
}

fun selectedExtraContext(preferences: ChatGptPreferences, metrics: HealthMetricSnapshot, health: List<HealthSnapshot>,
    expenses: List<Expense>, people: List<Person>, now: Long, zone: TimeZone): JsonObject? {
    if (!preferences.includeSteps && preferences.healthMetricIds.isEmpty() && !preferences.includeFinance && !preferences.includePeople) return null
    return buildJsonObject {
        if (preferences.includeSteps) {
            val metric = HealthMetric("steps", "Шаги", "шагов")
            val rows = health.mapNotNull { h -> h.steps?.let { HealthEntry("steps:${h.timestamp}", "steps", h.timestamp, it.toString()) } }
            put("steps", Json.encodeToJsonElement(summarizeMetric(metric, rows, now, zone)))
        }
        if (preferences.healthMetricIds.isNotEmpty()) put("healthMetrics", Json.encodeToJsonElement(metrics.metrics.filter { !it.archived && it.id in preferences.healthMetricIds }.sortedBy { it.id }
            .map { summarizeMetric(it, metrics.entries, now, zone) }))
        if (preferences.includeFinance) {
            val today = localDate(now, zone)
            val rows = expenses.filter { it.timestamp <= now && localDate(it.timestamp, zone) >= today.minus(DatePeriod(days = 29)) }
            put("financeLast30Days", JsonArray(rows.groupBy { it.currency to it.category }.entries.sortedBy { it.key.toString() }.map { (key, values) -> buildJsonObject {
                put("currency", key.first); put("category", key.second); put("amountMinor", exactMinorTotal(values.map { it.amountMinor })); put("records", values.size)
            } }))
        }
        if (preferences.includePeople) put("people", Json.encodeToJsonElement(people.sortedBy { it.id }.map { it.textContext() }))
    }
}

/** Decimal addition keeps finance totals exact even when a category exceeds Long.MAX_VALUE. */
internal fun exactMinorTotal(values: List<Long>): String {
    // Expenses are nonnegative by repository contract. Keep the defensive bound explicit.
    require(values.all { it >= 0 })
    var result = "0"
    values.forEach { amount ->
        val a = result.reversed(); val b = amount.toString().reversed()
        var carry = 0
        val next = StringBuilder()
        repeat(maxOf(a.length, b.length)) { i ->
            val n = (a.getOrNull(i)?.digitToInt() ?: 0) + (b.getOrNull(i)?.digitToInt() ?: 0) + carry
            next.append(n % 10); carry = n / 10
        }
        if (carry != 0) next.append(carry)
        result = next.reverse().toString()
    }
    return result
}
