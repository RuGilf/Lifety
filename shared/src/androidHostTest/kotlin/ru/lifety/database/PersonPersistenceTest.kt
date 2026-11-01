package ru.lifety.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import ru.lifety.core.di.AppContainer
import ru.lifety.core.model.Person
import ru.lifety.database.generated.LifetyDatabase
import ru.lifety.feature.people.birthdaysOnDate
import kotlin.test.*

class PersonPersistenceTest {
    @Test fun peopleAndAllNotesSurviveDatabaseCloseAndReopen() = runBlocking {
        val file = Files.createTempFile("lifety-people-", ".db")
        val person = Person("p", "Анна Иванова", 2, 29, 2000, "Архитектура", "Плавание", "Книга\nhttps://example.org/gift")
        try {
            val driver = JdbcSqliteDriver("jdbc:sqlite:$file")
            LifetyDatabase.Schema.create(driver)
            AppContainer(driver).use { app -> requireNotNull(app.repositories.people).upsert(person) }
            AppContainer(JdbcSqliteDriver("jdbc:sqlite:$file")).use { app ->
                val repo = requireNotNull(app.repositories.people)
                assertEquals(listOf(person), repo.observeAll().first())
                assertFailsWith<IllegalArgumentException> { repo.upsert(person.copy(birthdayMonth = 4, birthdayDay = 31)) }
                assertEquals(listOf(person), repo.observeAll().first())
            }
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun calendarReactsToCreateDateChangeClearAndDeleteWithoutCreatingTasks() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LifetyDatabase.Schema.create(driver)
        AppContainer(driver).use { app ->
            val repo = requireNotNull(app.repositories.people)
            val rows = Channel<List<Person>>(Channel.UNLIMITED)
            val job = launch(start = CoroutineStart.UNDISPATCHED) { repo.observeAll().collect { rows.send(it) } }
            suspend fun next() = withTimeout(5_000) { rows.receive() }
            val date = LocalDate(2026, 10, 27)
            val person = Person("p", "Мария", 10, 27, giftIdeas = "Цветы")
            try {
                assertEquals(emptyList(), next())
                repo.upsert(person)
                assertEquals(listOf(person), birthdaysOnDate(next(), date))
                val changed = person.copy(birthdayDay = 28, giftIdeas = "Книга")
                repo.upsert(changed)
                val afterEdit = next()
                assertEquals(emptyList(), birthdaysOnDate(afterEdit, date))
                assertEquals(listOf(changed), birthdaysOnDate(afterEdit, LocalDate(2026, 10, 28)))
                repo.upsert(changed.copy(birthdayMonth = null, birthdayDay = null))
                assertEquals(emptyList(), birthdaysOnDate(next(), LocalDate(2026, 10, 28)))
                repo.delete(person.id)
                assertEquals(emptyList(), next())
                repo.delete("absent")
                assertEquals(emptyList(), app.repositories.tasks.observeAll().first())
            } finally { job.cancel(); job.join(); rows.close() }
        }
    }

    @Test fun migrationTwoToCurrentPreservesPlannerFinanceCategoriesAndHealth() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "CREATE TABLE task(id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, scheduledAt INTEGER, deadlineAt INTEGER, isCompleted INTEGER NOT NULL)", 0)
        driver.execute(null, "CREATE TABLE expense(id TEXT NOT NULL PRIMARY KEY, amountMinor INTEGER NOT NULL, currency TEXT NOT NULL, merchant TEXT NOT NULL, category TEXT NOT NULL, timestamp INTEGER NOT NULL, source TEXT NOT NULL)", 0)
        driver.execute(null, "CREATE TABLE healthSnapshot(timestamp INTEGER NOT NULL PRIMARY KEY, sleepMinutes INTEGER, steps INTEGER)", 0)
        driver.execute(null, "CREATE TABLE expenseCategory(id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, colorHex TEXT NOT NULL, archived INTEGER NOT NULL DEFAULT 0)", 0)
        driver.execute(null, "INSERT INTO task VALUES ('t','Дедлайн',NULL,300,0)", 0)
        driver.execute(null, "INSERT INTO expense VALUES ('e',9007199254740993,'RUB','Магазин','custom:taxi',100,'manual')", 0)
        driver.execute(null, "INSERT INTO healthSnapshot VALUES (100,420,8000)", 0)
        driver.execute(null, "INSERT INTO expenseCategory VALUES ('custom:taxi','Такси','#D5A1FF',0)", 0)
        assertEquals(9L, LifetyDatabase.Schema.version)
        LifetyDatabase.Schema.migrate(driver, 2, LifetyDatabase.Schema.version)
        AppContainer(driver).use { app ->
            assertEquals(300L, app.repositories.tasks.observeAll().first().single().deadlineAt)
            assertEquals(9_007_199_254_740_993L, app.repositories.expenses.observeAll().first().single().amountMinor)
            assertEquals("Такси", requireNotNull(app.repositories.expenseCategories).observeAll().first().single().name)
            assertEquals(8000, app.repositories.health.observeLatest().first()!!.steps)
            val people = requireNotNull(app.repositories.people)
            assertEquals(emptyList(), people.observeAll().first())
            people.upsert(Person("p", "Друг", 10, 27))
            assertEquals("Друг", people.observeAll().first().single().fullName)
        }
    }
}
