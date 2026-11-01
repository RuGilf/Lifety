package ru.lifety.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import ru.lifety.core.di.AppContainer
import ru.lifety.core.model.Person
import ru.lifety.core.model.PersonNoteSection
import ru.lifety.database.generated.LifetyDatabase
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class PersonExtrasPersistenceTest {
    private fun photo(): String = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray().let { java.util.Base64.getEncoder().encodeToString(it) }

    @Test fun sectionsAndLocalPhotoSurviveReopenAndCanBeRenamedOrRemoved() = runBlocking {
        val file = Files.createTempFile("lifety-person-extras-", ".db")
        val person = Person("p", "Анна", 2, 29, 2000, "Кино", "Бег", "Книга", listOf(PersonNoteSection("places", "Любимые места", listOf("Парк", "Музей"))), photo())
        try {
            val driver = JdbcSqliteDriver("jdbc:sqlite:$file"); LifetyDatabase.Schema.create(driver)
            AppContainer(driver).use { requireNotNull(it.repositories.people).upsert(person) }
            AppContainer(JdbcSqliteDriver("jdbc:sqlite:$file")).use { app ->
                val repository = requireNotNull(app.repositories.people)
                assertEquals(person, repository.observeAll().first().single())
                val renamed = person.copy(noteSections = listOf(person.noteSections.single().copy(title = "Где погулять")))
                repository.upsert(renamed)
                assertEquals(renamed, repository.observeAll().first().single())
                assertFailsWith<IllegalArgumentException> { repository.upsert(renamed.copy(avatarBase64 = "/9j/!!!!")) }
                assertEquals(renamed, repository.observeAll().first().single())
                val cleared = renamed.copy(noteSections = emptyList(), avatarBase64 = null)
                repository.upsert(cleared)
                assertEquals(cleared, repository.observeAll().first().single())
                assertEquals("Книга", cleared.giftIdeas)
            }
        } finally { Files.deleteIfExists(file) }
    }
    @Test fun migrationEightPreservesExistingPeopleAndNotes() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LifetyDatabase.Schema.create(driver)
        driver.execute(null, "ALTER TABLE person DROP COLUMN noteSections", 0)
        driver.execute(null, "ALTER TABLE person DROP COLUMN avatarBase64", 0)
        driver.execute(null, "INSERT INTO person VALUES('p','Анна',2,29,2000,'Кино','Бег','Книга')", 0)
        LifetyDatabase.Schema.migrate(driver, 8, LifetyDatabase.Schema.version)
        AppContainer(driver).use { app ->
            val repository = requireNotNull(app.repositories.people)
            val old = repository.observeAll().first().single()
            assertEquals(Person("p", "Анна", 2, 29, 2000, "Кино", "Бег", "Книга"), old)
            assertEquals(9L, LifetyDatabase.Schema.version)
            val updated = old.copy(avatarBase64 = photo(), noteSections = listOf(PersonNoteSection("sizes", "Размеры", listOf("M"))))
            repository.upsert(updated)
            assertEquals("M", repository.observeAll().first().single().noteSections.single().items.single())
        }
    }
    @Test fun changesRemainReactiveWithoutReopening() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY); LifetyDatabase.Schema.create(driver)
        AppContainer(driver).use { app ->
            val repository = requireNotNull(app.repositories.people)
            val updates = Channel<List<Person>>(Channel.UNLIMITED)
            val job = launch(start = CoroutineStart.UNDISPATCHED) { repository.observeAll().collect { updates.send(it) } }
            suspend fun next() = withTimeout(5000) { updates.receive() }
            try {
                assertTrue(next().isEmpty())
                val person = Person("p", "Анна", noteSections = listOf(PersonNoteSection("a", "Размеры", listOf("M"))))
                repository.upsert(person); assertEquals(person, next().single())
                val changed = person.copy(avatarBase64 = photo(), noteSections = emptyList())
                repository.upsert(changed); assertEquals(changed, next().single())
                repository.delete(person.id); assertTrue(next().isEmpty())
            } finally { job.cancelAndJoin(); updates.close() }
        }
    }
}
