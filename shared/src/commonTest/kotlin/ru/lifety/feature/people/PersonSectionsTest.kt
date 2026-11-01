package ru.lifety.feature.people

import kotlin.test.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import ru.lifety.core.model.Person
import ru.lifety.core.model.PersonNoteSection
import ru.lifety.core.model.normalizedPerson
import ru.lifety.core.model.personValidationError
import ru.lifety.core.planning.*

class PersonSectionsTest {
    private val today = LocalDate(2026, 11, 1)
    @Test fun legacyCardsDecodeAndKeepAllExistingNotes() {
        val person = Json.decodeFromString<Person>("""{"id":"p","fullName":"Анна","interests":"Кино","hobbies":"Бег","giftIdeas":"Книга"}""")
        assertEquals("Книга", person.giftIdeas)
        assertTrue(person.noteSections.isEmpty())
        assertNull(person.avatarBase64)
    }
    @Test fun customSectionsKeepTheirIdsAndOrderAndNormalizePastedLists() {
        val person = Person("p", " Анна ", noteSections = listOf(
            PersonNoteSection("first", " Любимые места ", listOf("  Парк\nМузей  ", " ")),
            PersonNoteSection("second", "Размеры", listOf("M", "38"))))
        val clean = normalizedPerson(person)
        assertEquals("Анна", clean.fullName)
        assertEquals(listOf("first", "second"), clean.noteSections.map { it.id })
        assertEquals("Любимые места", clean.noteSections.first().title)
        assertEquals(listOf("Парк", "Музей"), clean.noteSections.first().items)
        assertNull(personValidationError(clean, today))
    }
    @Test fun emptyDuplicateAndOversizedSectionsAreRejected() {
        val valid = PersonNoteSection("a", "Любимые места", listOf("Парк"))
        val person = Person("p", "Анна", noteSections = listOf(valid))
        assertNull(personValidationError(person, today))
        assertNotNull(personValidationError(person.copy(noteSections = listOf(valid.copy(title = " "))), today))
        assertNotNull(personValidationError(person.copy(noteSections = listOf(valid.copy(title = "интересы"))), today))
        assertNotNull(personValidationError(person.copy(noteSections = listOf(valid, valid.copy(id = "b", title = " ЛЮБИМЫЕ МЕСТА "))), today))
        assertNotNull(personValidationError(person.copy(noteSections = listOf(valid, valid.copy(title = "Размеры"))), today))
        assertNotNull(personValidationError(person.copy(noteSections = listOf(valid.copy(items = List(101) { "x" }))), today))
        assertNotNull(personValidationError(person.copy(noteSections = listOf(valid.copy(items = listOf("x".repeat(10_001))))), today))
        assertNotNull(personValidationError(person.copy(avatarBase64 = "invalid"), today))
    }
    @Test fun aiContextIncludesSelectedTextSectionsButNeverPhotoData() {
        val person = Person("p", "Анна", noteSections = listOf(PersonNoteSection("a", "Любимые места", listOf("Парк"))), avatarBase64 = "LOCAL_PHOTO_SECRET")
        val prefs = ChatGptPreferences(includePeople = true)
        val context = selectedExtraContext(prefs, ru.lifety.core.model.HealthMetricSnapshot(), emptyList(), emptyList(), listOf(person), 1L, TimeZone.UTC).toString()
        assertTrue(context.contains("Любимые места")); assertTrue(context.contains("Парк"))
        assertFalse(context.contains("LOCAL_PHOTO_SECRET")); assertFalse(context.contains("avatarBase64"))
        val changedPhoto = selectedExtraContext(prefs, ru.lifety.core.model.HealthMetricSnapshot(), emptyList(), emptyList(), listOf(person.copy(avatarBase64 = "OTHER_PHOTO")), 1L, TimeZone.UTC).toString()
        assertEquals(context, changedPhoto)
        val excluded = selectedExtraContext(prefs.copy(includePeople = false), ru.lifety.core.model.HealthMetricSnapshot(), emptyList(), emptyList(), listOf(person), 1L, TimeZone.UTC)
        assertNull(excluded)
    }
}
