package ru.lifety.core.model

import kotlinx.serialization.Serializable

/** Birthday is a civil month/day, with an optional known year; never a UTC instant. */
@Serializable
data class Person(
    val id: String,
    val fullName: String,
    val birthdayMonth: Int? = null,
    val birthdayDay: Int? = null,
    val birthYear: Int? = null,
    val interests: String = "",
    val hobbies: String = "",
    val giftIdeas: String = "",
    val noteSections: List<PersonNoteSection> = emptyList(),
    val avatarBase64: String? = null,
)

@Serializable
data class PersonNoteSection(val id: String, val title: String, val items: List<String> = emptyList())

/** Photos stay local, including when the user opts into text context about people. */
fun Person.textContext(): Person = if (avatarBase64 == null) this else copy(avatarBase64 = null)

const val MAX_PERSON_AVATAR_BYTES = 180_000
const val MAX_PERSON_AVATAR_BASE64 = 240_000
