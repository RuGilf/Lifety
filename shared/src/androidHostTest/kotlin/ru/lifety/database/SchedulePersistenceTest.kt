package ru.lifety.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.lifety.core.di.AppContainer
import ru.lifety.core.model.ScheduleBlock as CoreScheduleBlock
import ru.lifety.database.generated.LifetyDatabase
import kotlin.test.*

class SchedulePersistenceTest {
    @Test fun timetableSurvivesReopenAndAppearsInPlanningSnapshot() = runBlocking {
        val file = Files.createTempFile("lifety-schedule-", ".db")
        val weekly = CoreScheduleBlock("weekly", "Работа", 540, 1020, setOf(1, 2, 3, 4, 5))
        val once = CoreScheduleBlock("once", "Встреча", 1080, 1140, date = "2026-10-30")
        try {
            val driver = JdbcSqliteDriver("jdbc:sqlite:$file")
            LifetyDatabase.Schema.create(driver)
            AppContainer(driver).use { app ->
                app.repositories.schedule!!.upsert(weekly)
                app.repositories.schedule.upsert(once)
                assertEquals(setOf(weekly, once), app.repositories.planning!!.snapshot().schedule.toSet())
            }
            AppContainer(JdbcSqliteDriver("jdbc:sqlite:$file")).use { app ->
                val repository = app.repositories.schedule!!
                assertEquals(setOf(weekly, once), repository.observeAll().first().toSet())
                repository.upsert(weekly.copy(title = "Лекции"))
                repository.delete(once.id)
                assertEquals(listOf(weekly.copy(title = "Лекции")), repository.observeAll().first())
                assertFailsWith<IllegalArgumentException> { repository.upsert(weekly.copy(weekdays = emptySet())) }
                assertEquals("Лекции", repository.observeAll().first().single().title)
            }
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun updatesAndDeletesEmitWithoutReopeningDatabase() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LifetyDatabase.Schema.create(driver)
            val repository = SqlDelightScheduleRepository(createDatabase(driver), Dispatchers.Unconfined)
            val emissions = mutableListOf<List<CoreScheduleBlock>>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) { repository.observeAll().take(3).toList(emissions) }
            yield()
            repository.upsert(CoreScheduleBlock("a", "Пара", 540, 600, setOf(1)))
            yield()
            repository.delete("a")
            withTimeout(3000) { job.join() }
            assertEquals(listOf(0, 1, 0), emissions.map { it.size })
        } finally { driver.close() }
    }

    @Test fun versionSevenMigrationKeepsExistingNotesAndNavigation() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LifetyDatabase.Schema.create(driver)
            driver.execute(null, "ALTER TABLE person DROP COLUMN noteSections", 0)
        driver.execute(null, "ALTER TABLE person DROP COLUMN avatarBase64", 0)
        driver.execute(null, "DROP TABLE scheduleBlock", 0)
            val old = createDatabase(driver)
            old.lifetyQueries.upsertExpense("e", 100, "RUB", "Магазин", "other", 1, "manual", "Хлеб")
            old.lifetyQueries.upsertNavigationConfiguration("[\"tasks\"]", "arc")
            LifetyDatabase.Schema.migrate(driver, 7, LifetyDatabase.Schema.version)
            AppContainer(driver).use { app ->
                assertEquals("Хлеб", app.repositories.expenses.observeAll().first().single().note)
                assertEquals("arc", app.repositories.navigation!!.observePresentation().first())
                assertTrue(app.repositories.schedule!!.observeAll().first().isEmpty())
                app.repositories.schedule.upsert(CoreScheduleBlock("a", "Пара", 540, 600, setOf(1)))
                assertEquals(1, app.repositories.schedule.observeAll().first().size)
            }
        } finally { driver.close() }
    }
}
