package ru.lifety.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import ru.lifety.core.di.AppContainer
import ru.lifety.core.model.Expense
import ru.lifety.database.generated.LifetyDatabase
import kotlin.test.*

class ExpenseNotesAndNavigationTest {
    @Test fun noteAndArcPresentationSurviveReopenAndRouteOnlySave() = runBlocking {
        val file = Files.createTempFile("lifety-notes-arc-", ".db")
        try {
            val driver = JdbcSqliteDriver("jdbc:sqlite:$file")
            LifetyDatabase.Schema.create(driver)
            AppContainer(driver).use { app ->
                app.repositories.expenses.upsert(Expense("note", 12500, "RUB", "Магазин", "food", 100, "manual", "Хлеб и молоко"))
                app.repositories.navigation!!.saveConfiguration(listOf("tasks", "people"), "arc")
            }
            AppContainer(JdbcSqliteDriver("jdbc:sqlite:$file")).use { app ->
                assertEquals("Хлеб и молоко", app.repositories.expenses.observeAll().first().single().note)
                val navigation = app.repositories.navigation!!
                assertEquals("arc", navigation.observePresentation().first())
                assertEquals(listOf("tasks", "people"), navigation.observe().first())
                navigation.save(listOf("finance"))
                assertEquals("arc", navigation.observePresentation().first())
                assertFailsWith<IllegalArgumentException> { navigation.saveConfiguration(listOf("unknown"), "tabs") }
                assertEquals(listOf("finance"), navigation.observe().first())
                assertEquals("arc", navigation.observePresentation().first())
            }
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun versionSixMigrationPreservesExpensesAndDefaultsToTabs() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            driver.execute(null, "CREATE TABLE expense(id TEXT NOT NULL PRIMARY KEY, amountMinor INTEGER NOT NULL, currency TEXT NOT NULL, merchant TEXT NOT NULL, category TEXT NOT NULL, timestamp INTEGER NOT NULL, source TEXT NOT NULL)", 0)
            driver.execute(null, "CREATE TABLE navigationPreference(id INTEGER NOT NULL PRIMARY KEY CHECK(id=1), routes TEXT NOT NULL)", 0)
            driver.execute(null, "INSERT INTO expense VALUES ('old',999,'RUB','Store','food',10,'manual')", 0)
            driver.execute(null, "INSERT INTO navigationPreference VALUES (1,'[\"health\",\"tasks\"]')", 0)
            driver.execute(null, "CREATE TABLE person(id TEXT NOT NULL PRIMARY KEY, fullName TEXT NOT NULL, birthdayMonth INTEGER, birthdayDay INTEGER, birthYear INTEGER, interests TEXT NOT NULL, hobbies TEXT NOT NULL, giftIdeas TEXT NOT NULL)", 0)
            LifetyDatabase.Schema.migrate(driver, 6, LifetyDatabase.Schema.version)
            val database = createDatabase(driver)
            val expenses = SqlDelightExpenseRepository(database)
            val navigation = SqlDelightNavigationPreferencesRepository(database)
            val old = expenses.observeAll().first().single()
            assertEquals(999L, old.amountMinor)
            assertEquals("", old.note)
            assertEquals(listOf("health", "tasks"), navigation.observe().first())
            assertEquals("tabs", navigation.observePresentation().first())
            expenses.upsert(old.copy(note = "Пояснение после обновления"))
            assertEquals("Пояснение после обновления", expenses.observeAll().first().single().note)
        } finally { driver.close() }
    }
}
