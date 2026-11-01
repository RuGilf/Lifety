package ru.lifety.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import ru.lifety.core.di.AppContainer
import ru.lifety.core.model.Task
import ru.lifety.core.model.HealthSnapshot
import ru.lifety.core.planning.*
import ru.lifety.database.generated.LifetyDatabase
import java.nio.file.Files
import kotlin.time.Instant
import kotlin.test.*

class ChatGptExchangePersistenceTest {
    private val now = Instant.parse("2026-10-28T12:00:00Z").toEpochMilliseconds()
    private val zone get() = TimeZone.currentSystemDefault()
    private suspend fun draft(app: AppContainer, id: String = "r"): ChatGptDraft {
        val r = app.repositories
        val workspace = r.chatGptExchange!!.observe().first()
        val summary = if (workspace.preferences.includeSleep) summarizeSleep(r.health.observeAll().first(), now, zone) else null
        return prepareChatGptDraft(r.planning!!.snapshot(), workspace.preferences, summary, workspace.lastSent, workspace.lastSent == null, "Два часа", id, now, zone)
    }
    private fun plan(draft: ChatGptDraft) = ImportedPlan("lifety-plan-v2", draft.baseSnapshot, "Доклад сегодня, книга в очереди",
        listOf(PlannedTask("a", Instant.fromEpochMilliseconds(now + 3_600_000).toString(), 45, "Подготовить тезисы")), listOf(DeferredTask("b", "Сегодня не хватает времени")), draft.id)

    @Test fun preferencesDraftAndConfirmedBaselineSurviveReopenAndDraftDoesNotPretendToBeSent() = runBlocking<Unit> {
        val path = Files.createTempFile("lifety-exchange-", ".db")
        try {
            val driver = JdbcSqliteDriver("jdbc:sqlite:$path"); LifetyDatabase.Schema.create(driver)
            AppContainer(driver).use { app ->
                app.repositories.tasks.upsert(Task("a", "Доклад", null, null, false))
                val repo = app.repositories.chatGptExchange!!
                repo.savePreferences(ChatGptPreferences("После 18:00", true, "https://chatgpt.com/c/abc"))
                repo.saveDraft(draft(app)); assertNull(repo.observe().first().lastSent)
                assertFailsWith<IllegalArgumentException> { repo.markSent("not-this-draft", now) }
                repo.markSent("r", now)
            }
            AppContainer(JdbcSqliteDriver("jdbc:sqlite:$path")).use { app ->
                val state = app.repositories.chatGptExchange!!.observe().first()
                assertEquals("После 18:00", state.preferences.instructions)
                assertEquals("r", state.draft!!.id); assertTrue(state.lastSent!!.awaitingReply)
                assertEquals("a", state.lastSent!!.baseline.tasks.single().id)
            }
        } finally { Files.deleteIfExists(path) }
    }
    @Test fun applyingV2CommitsScheduleQueueReportAndReceiptTogetherAndRejectsReplay() = runBlocking<Unit> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY); LifetyDatabase.Schema.create(driver)
        AppContainer(driver).use { app ->
            val r = app.repositories; val planning = r.planning!!; val exchange = r.chatGptExchange!!
            r.tasks.upsert(Task("a", "Доклад", null, now + 86_400_000, false))
            r.tasks.upsert(Task("b", "Книга", now + 5_000_000, now + 172_800_000, false))
            createDatabase(driver).lifetyQueries.upsertTaskPlan("b", 90, "Прежняя оценка", null)
            val prepared = draft(app); exchange.saveDraft(prepared); exchange.markSent(prepared.id, now)
            planning.apply(plan(prepared), now)
            val tasks = r.tasks.observeAll().first().associateBy { it.id }
            assertEquals(now + 3_600_000, tasks.getValue("a").scheduledAt)
            assertNull(tasks.getValue("b").scheduledAt); assertEquals(now + 172_800_000, tasks.getValue("b").deadlineAt)
            assertEquals(90, planning.observeAll().first().first { it.taskId == "b" }.durationMinutes)
            val state = exchange.observe().first()
            assertNull(state.draft); assertFalse(state.lastSent!!.awaitingReply)
            assertEquals("b", state.receipt!!.deferredTasks.single().taskId)
            assertEquals(now, planning.observeReport().first()!!.importedAt)
            val before = planning.snapshot()
            assertFailsWith<IllegalArgumentException> { planning.apply(plan(prepared), now) }
            assertEquals(before, planning.snapshot())
        }
    }
    @Test fun changedSleepRejectsPlanInsideTransactionWithoutPartialUpdates() = runBlocking<Unit> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY); LifetyDatabase.Schema.create(driver)
        AppContainer(driver).use { app ->
            val r = app.repositories; val planning = r.planning!!; val exchange = r.chatGptExchange!!
            for (id in listOf("a", "b")) r.tasks.upsert(Task(id, id, null, null, false))
            r.health.update(HealthSnapshot(now - 1000, 420, 8000))
            exchange.savePreferences(ChatGptPreferences(includeSleep = true))
            val prepared = draft(app); exchange.saveDraft(prepared)
            r.health.update(HealthSnapshot(now - 500, 480, null))
            val before = planning.snapshot()
            assertFailsWith<IllegalArgumentException> { planning.apply(plan(prepared), now) }
            assertEquals(before, planning.snapshot()); assertNull(planning.observeReport().first())
            assertNull(exchange.observe().first().receipt)
        }
    }
    @Test fun revokingSleepRemovesPreparedHealthTextAndOldResponseCannotBeApplied() = runBlocking<Unit> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY); LifetyDatabase.Schema.create(driver)
        AppContainer(driver).use { app ->
            val r = app.repositories; val exchange = r.chatGptExchange!!
            for (id in listOf("a", "b")) r.tasks.upsert(Task(id, id, null, null, false))
            r.health.update(HealthSnapshot(now - 1, 417, null))
            exchange.savePreferences(ChatGptPreferences(includeSleep = true))
            val prepared = draft(app); exchange.saveDraft(prepared); exchange.markSent(prepared.id, now)
            assertTrue(exchange.observe().first().draft!!.prompt.contains("medianMinutes"))
            exchange.savePreferences(ChatGptPreferences(includeSleep = false))
            val workspace = exchange.observe().first()
            assertNull(workspace.draft)
            assertFalse(kotlinx.serialization.json.Json.encodeToString(workspace).contains("medianMinutes"))
            assertFailsWith<IllegalArgumentException> { r.planning!!.apply(plan(prepared), now) }
        }
    }
    @Test fun migrationFourToFivePreservesBusinessDataAndStartsWithEmptyWorkspace() = runBlocking<Unit> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY); LifetyDatabase.Schema.create(driver)
        driver.execute(null, "ALTER TABLE person DROP COLUMN noteSections", 0)
        driver.execute(null, "ALTER TABLE person DROP COLUMN avatarBase64", 0)
        driver.execute(null, "DROP TABLE scheduleBlock", 0)
        driver.execute(null, "ALTER TABLE expense DROP COLUMN note", 0)
        driver.execute(null, "DROP TABLE healthEntry", 0); driver.execute(null, "DROP TABLE healthMetric", 0); driver.execute(null, "DROP TABLE navigationPreference", 0)
        driver.execute(null, "DROP TABLE chatGptExchange", 0)
        driver.execute(null, "INSERT INTO task VALUES ('a','Доклад',10,20,0)", 0)
        driver.execute(null, "INSERT INTO taskPlan VALUES ('a',30,'Шаги',NULL)", 0)
        driver.execute(null, "INSERT INTO planReport VALUES (1,'Разбор',100)", 0)
        LifetyDatabase.Schema.migrate(driver, 4, LifetyDatabase.Schema.version)
        AppContainer(driver).use { app ->
            assertEquals(9L, LifetyDatabase.Schema.version)
            assertEquals(20L, app.repositories.tasks.observeAll().first().single().deadlineAt)
            assertEquals(30, app.repositories.planning!!.observeAll().first().single().durationMinutes)
            assertEquals(ChatGptWorkspace(), app.repositories.chatGptExchange!!.observe().first())
        }
    }
}
