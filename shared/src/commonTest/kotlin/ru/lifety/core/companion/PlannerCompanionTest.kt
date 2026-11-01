package ru.lifety.core.companion

import kotlin.test.*
import kotlinx.serialization.json.Json
import ru.lifety.core.model.Person
import ru.lifety.core.model.Task

class PlannerCompanionTest {
    @Test fun completedTasksAndPeopleWithoutBirthdaysAreExcluded() {
        val data = companionSnapshot(listOf(Task("1", "Дело", 20, 30, false), Task("2", "Готово", null, null, true)),
            listOf(Person("p", "Анна", 2, 29), Person("q", "Без даты")), false)
        assertEquals(listOf("1"), data.tasks.map { it.id })
        assertEquals(listOf("p"), data.people.map { it.id })
        assertEquals(30L, data.tasks.single().deadlineAt)
    }
    @Test fun protectedPayloadNeverContainsTitlesNamesNotesOrPhotos() {
        val person = Person("p", "PRIVATE_NAME", 1, 2, interests = "PRIVATE_NOTE", avatarBase64 = "PRIVATE_PHOTO")
        val json = Json.encodeToString(companionSnapshot(listOf(Task("t", "PRIVATE_TITLE", null, 20, false)), listOf(person), true))
        assertFalse(json.contains("PRIVATE"))
        assertTrue(Json.decodeFromString<CompanionSnapshot>(json).protected)
    }
}
