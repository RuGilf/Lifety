package ru.lifety.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import ru.lifety.core.di.AppContainer
import ru.lifety.core.model.Task
import ru.lifety.core.model.Goal
import ru.lifety.core.model.TaskPlan
import ru.lifety.database.generated.LifetyDatabase
import ru.lifety.core.planning.*
import ru.lifety.feature.assistant.agent.*
import kotlin.time.Instant
import kotlin.test.*

class PlanningPersistenceTest {
    @Test fun manuallyEditedIntervalsAndStepsPersistTogetherAndRejectInvalidChanges() = runBlocking {
        val path = Files.createTempFile("lifety-manual-plan-", ".db")
        val start = Instant.parse("2030-10-28T09:00:00Z").toEpochMilliseconds()
        try {
            val driver = JdbcSqliteDriver("jdbc:sqlite:$path"); LifetyDatabase.Schema.create(driver)
            AppContainer(driver).use { app ->
                val repo = app.repositories.planning!!
                app.repositories.goals!!.upsert(Goal("g", "Диплом"))
                val task = Task("t", "Подготовить материалы", start, start + 90 * 60_000, false)
                repo.saveTask(task, TaskPlan("t", 60, "Найти источники\nСоставить план", "g"))
                val before = repo.snapshot()
                assertFailsWith<IllegalArgumentException> { repo.saveTask(task.copy(title = "Не должно сохраниться"), TaskPlan("t", 120)) }
                assertEquals(before, repo.snapshot())
                assertFailsWith<IllegalArgumentException> { repo.saveTask(task, TaskPlan("wrong-id", 30)) }
                assertEquals(before, repo.snapshot())
            }
            AppContainer(JdbcSqliteDriver("jdbc:sqlite:$path")).use { app ->
                val repo = app.repositories.planning!!
                val snapshot = repo.snapshot()
                assertEquals(start, snapshot.tasks.single().scheduledAt)
                assertEquals(60, snapshot.details.single().durationMinutes)
                assertEquals("g", snapshot.details.single().goalId)
                assertEquals("Найти источники\nСоставить план", snapshot.details.single().approach)
                repo.saveTask(snapshot.tasks.single().copy(scheduledAt = null), snapshot.details.single())
                assertNull(app.repositories.tasks.observeAll().first().single().scheduledAt)
                assertEquals(60, repo.observeAll().first().single().durationMinutes)
            }
        } finally { Files.deleteIfExists(path) }
    }

    private val now = Instant.parse("2026-10-27T09:00:00Z").toEpochMilliseconds()
    private fun plan(s: PlanSnapshot) = ImportedPlan("lifety-plan-v1", snapshotFingerprint(s), "Работаем по шагам",
        s.tasks.filterNot { it.isCompleted }.mapIndexed { i, t -> PlannedTask(t.id, Instant.fromEpochMilliseconds(now + (i + 1) * 3_600_000L).toString(), 30, "Шаг 1: изучить материалы", "g") })
    @Test fun appliedScheduleGoalsEstimatesAndReportSurviveReopen() = runBlocking {
        val path = Files.createTempFile("lifety-plan-", ".db")
        try {
            val driver = JdbcSqliteDriver("jdbc:sqlite:$path"); LifetyDatabase.Schema.create(driver)
            AppContainer(driver).use { app ->
                app.repositories.tasks.upsert(Task("t", "Доклад", null, now + 86_400_000, false))
                app.repositories.goals!!.upsert(Goal("g", "Защита", "Подготовиться", now + 86_400_000))
                val repository = app.repositories.planning!!
                repository.apply(plan(repository.snapshot()), now)
                assertEquals(now + 3_600_000, app.repositories.tasks.observeAll().first().single().scheduledAt)
                assertEquals(now + 86_400_000, app.repositories.tasks.observeAll().first().single().deadlineAt)
            }
            AppContainer(JdbcSqliteDriver("jdbc:sqlite:$path")).use { app ->
                assertEquals("Защита", app.repositories.goals!!.observeAll().first().single().title)
                assertEquals(30, app.repositories.planning!!.observeAll().first().single().durationMinutes)
                assertEquals("Работаем по шагам", app.repositories.planning!!.observeReport().first()!!.summary)
                assertEquals(now + 3_600_000, app.repositories.tasks.observeAll().first().single().scheduledAt)
            }
        } finally { Files.deleteIfExists(path) }
    }
    @Test fun invalidOrStalePlanNeverPartiallyUpdatesTasksOrReport() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY); LifetyDatabase.Schema.create(driver)
        AppContainer(driver).use { app ->
            app.repositories.goals!!.upsert(Goal("g", "Цель"))
            for (id in listOf("a", "b")) app.repositories.tasks.upsert(Task(id, id, null, now + 86_400_000, false))
            val repo = app.repositories.planning!!; val before = repo.snapshot(); val p = plan(before)
            assertFailsWith<IllegalArgumentException> { repo.apply(p.copy(tasks = listOf(p.tasks[0], p.tasks[1].copy(durationMinutes = -1))), now) }
            assertEquals(before, repo.snapshot()); assertNull(repo.observeReport().first())
            app.repositories.tasks.upsert(before.tasks.first().copy(title = "Изменили после preview"))
            val changed = repo.snapshot()
            assertFailsWith<IllegalArgumentException> { repo.apply(p, now) }
            assertEquals(changed, repo.snapshot()); assertEquals(emptyList(), repo.observeAll().first())
        }
    }
    @Test fun flowsEmitAndDeletingTaskOrGoalCleansLinks() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY); LifetyDatabase.Schema.create(driver)
        AppContainer(driver).use { app ->
            val repo = app.repositories.planning!!
            app.repositories.tasks.upsert(Task("t", "Тест", null, null, false)); app.repositories.goals!!.upsert(Goal("g", "Цель"))
            val rows = Channel<List<TaskPlan>>(Channel.UNLIMITED)
            val job = launch(start = CoroutineStart.UNDISPATCHED) { repo.observeAll().collect { rows.send(it) } }
            try {
                assertEquals(emptyList(), withTimeout(5000) { rows.receive() })
                repo.apply(plan(repo.snapshot()), now)
                assertEquals("g", withTimeout(5000) { rows.receive() }.single().goalId)
                app.repositories.goals!!.delete("g")
                assertNull(withTimeout(5000) { rows.receive() }.single().goalId)
                app.repositories.tasks.delete("t")
                assertEquals(emptyList(), withTimeout(5000) { rows.receive() })
            } finally { job.cancelAndJoin(); rows.close() }
        }
    }
    @Test fun migrationThreeToCurrentPreservesAllExistingRecords() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY); LifetyDatabase.Schema.create(driver)
        // Reconstruct the real v3 table set, then run the additive migration chain.
        driver.execute(null, "ALTER TABLE person DROP COLUMN noteSections", 0)
        driver.execute(null, "ALTER TABLE person DROP COLUMN avatarBase64", 0)
        driver.execute(null, "DROP TABLE scheduleBlock", 0)
        driver.execute(null, "ALTER TABLE expense DROP COLUMN note", 0)
        driver.execute(null, "DROP TABLE healthEntry", 0); driver.execute(null, "DROP TABLE healthMetric", 0); driver.execute(null, "DROP TABLE navigationPreference", 0)
        driver.execute(null, "DROP TABLE chatGptExchange", 0)
        driver.execute(null, "DROP TABLE goal", 0); driver.execute(null, "DROP TABLE taskPlan", 0); driver.execute(null, "DROP TABLE planReport", 0)
        driver.execute(null, "INSERT INTO task VALUES ('t','Старая задача',NULL,100,0)", 0)
        driver.execute(null, "INSERT INTO person VALUES ('p','Друг',10,27,NULL,'Кино','Бег','Книга')", 0)
        driver.execute(null, "INSERT INTO expense VALUES ('e',9007199254740993,'RUB','Shop','taxi',1,'manual')", 0)
        LifetyDatabase.Schema.migrate(driver, 3, LifetyDatabase.Schema.version)
        AppContainer(driver).use { app ->
            assertEquals("Старая задача", app.repositories.tasks.observeAll().first().single().title)
            assertEquals("Друг", app.repositories.people!!.observeAll().first().single().fullName)
            assertEquals(9_007_199_254_740_993L, app.repositories.expenses.observeAll().first().single().amountMinor)
            assertEquals(emptyList(), app.repositories.goals!!.observeAll().first())
            assertEquals(emptyList(), app.repositories.planning!!.observeAll().first())
        }
    }
    @Test fun agentRefreshIncludesImportedPlanOnlyWithTaskAccess() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY); LifetyDatabase.Schema.create(driver)
        val root = Files.createTempDirectory("lifety-planning-agent-").toFile()
        try {
            AppContainer(driver).use { app ->
                app.repositories.tasks.upsert(Task("t", "Доклад", null, null, false)); app.repositories.goals!!.upsert(Goal("g", "Защита"))
                val workspace = AgentWorkspace(LocalAgentDisk(root.path)); val env = LifetyAgentEnvironment(app.repositories, workspace, { now })
                env.prepare(AgentScopes(tasks = true), AgentDocument())
                assertTrue(workspace.read("data/taskPlans/records.json").contains("[]"))
                app.repositories.planning!!.apply(plan(app.repositories.planning!!.snapshot()), now)
                env.prepare(AgentScopes(tasks = true), AgentDocument())
                assertTrue(workspace.read("data/taskPlans/records.json").contains("изучить материалы"))
                assertTrue(workspace.read("data/goals/records.json").contains("Защита"))
                assertTrue(workspace.read("data/planning/report.json").contains("Работаем по шагам"))
                env.prepare(AgentScopes(), AgentDocument())
                assertTrue(workspace.files("data/taskPlans").isEmpty()); assertTrue(workspace.files("data/planning").isEmpty()); assertTrue(workspace.files("data/goals").isEmpty())
            }
        } finally { root.deleteRecursively() }
    }
}
