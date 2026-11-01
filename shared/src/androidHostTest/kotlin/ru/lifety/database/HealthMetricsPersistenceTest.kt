package ru.lifety.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import ru.lifety.core.di.AppContainer
import ru.lifety.core.model.HealthSnapshot
import ru.lifety.core.model.Task
import ru.lifety.core.model.HealthMetric
import ru.lifety.core.model.*
import ru.lifety.database.generated.LifetyDatabase
import kotlin.test.*

class HealthMetricsPersistenceTest {
    @Test fun metricsEntriesAndNavigationSurviveReopenAndNotifyObservers() = runBlocking {
        val file = Files.createTempFile("lifety-metrics", ".db")
        try {
            val driver = JdbcSqliteDriver("jdbc:sqlite:$file")
            LifetyDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            AppContainer(driver).use { app ->
                val repo = app.repositories.healthMetrics!!
                val update = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5000) { repo.observe().first { it.entries.size == 1 } } }
                repo.saveMetric(HealthMetric("m", "Настроение", "баллов"))
                repo.saveEntry(HealthEntry("entry", "m", 100, "4,5"))
                assertEquals("4.5", update.await().entries.single().value)
                repo.saveMetric(HealthMetric("m", "Энергия", "баллов")) // Foreign keys preserve children during rename.
                assertEquals(1, repo.snapshot().entries.size)
                assertFailsWith<IllegalArgumentException> { repo.saveMetric(HealthMetric("m", "Энергия", kind = MetricKind.TEXT)) }
                app.repositories.navigation!!.save(listOf("people", "calendar", "health"))
            }
            AppContainer(JdbcSqliteDriver("jdbc:sqlite:$file")).use { app ->
                val repo = app.repositories.healthMetrics!!
                assertEquals("Энергия", repo.snapshot().metrics.single().name)
                assertEquals("4.5", repo.snapshot().entries.single().value)
                assertEquals(listOf("people", "calendar", "health"), app.repositories.navigation!!.observe().first())
                assertFailsWith<IllegalArgumentException> { app.repositories.navigation!!.save(emptyList()) }
                assertFailsWith<IllegalArgumentException> { app.repositories.navigation!!.save(listOf("health", "health")) }
                repo.deleteEntry("entry")
                assertTrue(repo.snapshot().entries.isEmpty())
            }
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun repeatedImportsReplaceDailyTotalsAndCannotBeEditedAsManual() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY); LifetyDatabase.Schema.create(driver)
        AppContainer(driver).use { app ->
            val repo = app.repositories.healthMetrics!!
            repo.importSamples(listOf(HealthApiSample("water", 100, 500.0)), "healthkit")
            repo.importSamples(listOf(HealthApiSample("water", 100, 600.0)), "healthkit")
            var state = repo.snapshot()
            assertEquals(1, state.metrics.size); assertEquals("600.0", state.entries.single().value)
            assertFailsWith<IllegalArgumentException> { repo.saveEntry(state.entries.single().copy(value = "999", source = "manual")) }
            repo.saveMetric(state.metrics.single().copy(name = "Моя вода", archived = true))
            assertFailsWith<IllegalArgumentException> { repo.saveEntry(HealthEntry("manual", state.metrics.single().id, 100, "100")) }
            repo.importSamples(listOf(HealthApiSample("water", 100, 700.0)), "healthkit")
            state = repo.snapshot()
            assertEquals("Моя вода", state.metrics.single().name); assertFalse(state.metrics.single().archived)
            repo.importSamples(listOf(HealthApiSample("water", 100, 800.0)), "health-connect")
            assertEquals(2, repo.snapshot().entries.size)
            assertFailsWith<IllegalArgumentException> { repo.importSamples(listOf(HealthApiSample("weight", 200, Double.NaN)), "healthkit") }
            assertEquals(1, repo.snapshot().metrics.size) // Invalid batch rolled back.
        }
    }

    @Test fun migrationFiveToSixPreservesExistingHealthAndStartsWithDefaultNavigation() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LifetyDatabase.Schema.create(driver)
        driver.execute(null, "ALTER TABLE person DROP COLUMN noteSections", 0)
        driver.execute(null, "ALTER TABLE person DROP COLUMN avatarBase64", 0)
        driver.execute(null, "DROP TABLE scheduleBlock", 0)
        driver.execute(null, "ALTER TABLE expense DROP COLUMN note", 0)
        driver.execute(null, "DROP TABLE healthEntry", 0)
        driver.execute(null, "DROP TABLE healthMetric", 0)
        driver.execute(null, "DROP TABLE navigationPreference", 0)
        driver.execute(null, "INSERT INTO healthSnapshot VALUES (100, 420, 8000)", 0)
        LifetyDatabase.Schema.migrate(driver, 5, LifetyDatabase.Schema.version)
        AppContainer(driver).use { app ->
            assertEquals(420, app.repositories.health.observeLatest().first()!!.sleepMinutes)
            assertTrue(app.repositories.healthMetrics!!.snapshot().metrics.isEmpty())
            assertEquals(ru.lifety.app.navigation.defaultNavigation, app.repositories.navigation!!.observe().first())
        }
    }
    @Test fun selectedMetricEditsRejectAStalePlanBeforeAnyScheduleChanges() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY); LifetyDatabase.Schema.create(driver)
        AppContainer(driver).use { app ->
            val now = kotlin.time.Instant.parse("2026-10-30T12:00:00Z").toEpochMilliseconds()
            val zone = kotlinx.datetime.TimeZone.currentSystemDefault()
            val metrics = app.repositories.healthMetrics!!
            val exchange = app.repositories.chatGptExchange!!
            val planning = app.repositories.planning!!
            app.repositories.tasks.upsert(Task("t", "Задача", null, null, false))
            metrics.saveMetric(HealthMetric("m", "Вода", "мл"))
            metrics.saveEntry(HealthEntry("e", "m", now, "500"))
            val preferences = ru.lifety.core.planning.ChatGptPreferences(healthMetricIds = setOf("m"))
            exchange.savePreferences(preferences)
            val snapshot = planning.snapshot()
            val extra = ru.lifety.core.planning.selectedExtraContext(preferences, metrics.snapshot(), emptyList(), emptyList(), emptyList(), now, zone)
            val draft = ru.lifety.core.planning.prepareChatGptDraft(snapshot, preferences, null, null, true, "", "request", now, zone, extra)
            exchange.saveDraft(draft)
            metrics.saveEntry(HealthEntry("e", "m", now, "750"))
            val plan = ru.lifety.core.planning.ImportedPlan("lifety-plan-v2", draft.baseSnapshot, "Позже", emptyList(),
                listOf(ru.lifety.core.planning.DeferredTask("t", "Нужен отдых")), draft.id)
            assertFailsWith<IllegalArgumentException> { planning.apply(plan, now) }
            assertNull(planning.observeReport().first())
            assertEquals("request", exchange.observe().first().draft!!.id)
            assertEquals(snapshot, planning.snapshot())
        }
    }

    @Test fun unifiedDeviceImportIsAtomicIdempotentAndChecksPreviewFreshness() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY); LifetyDatabase.Schema.create(driver)
        AppContainer(driver).use { app ->
            val metrics = app.repositories.healthMetrics!!
            val health = app.repositories.health
            health.update(HealthSnapshot(100, 420, 8000))
            val baseline = health.observeAll().first()
            val invalid = listOf(HealthApiSample("sleep", 100, 480.0), HealthApiSample("unknown", 100, 5.0))
            assertFailsWith<IllegalArgumentException> { metrics.importDeviceSamples(invalid, "healthkit", baseline) }
            assertEquals(baseline, health.observeAll().first())
            assertTrue(metrics.snapshot().metrics.isEmpty())
            val rows = listOf(HealthApiSample("sleep", 100, 480.0), HealthApiSample("weight", 100, 75.0))
            metrics.importDeviceSamples(rows, "healthkit", baseline)
            val after = health.observeAll().first()
            assertEquals(HealthSnapshot(100, 480, 8000), after.single()) // Unselected steps remain known.
            metrics.importDeviceSamples(rows, "healthkit", after)
            assertEquals(1, metrics.snapshot().entries.size)
            assertEquals(1, health.observeAll().first().size)
            assertFailsWith<IllegalArgumentException> { metrics.importDeviceSamples(rows, "healthkit", baseline) }
            assertEquals(after, health.observeAll().first())
            assertFailsWith<IllegalArgumentException> { metrics.importDeviceSamples(listOf(HealthApiSample("steps", 200, 1.5)), "healthkit", after) }
            assertEquals(after, health.observeAll().first())
        }
    }

}
