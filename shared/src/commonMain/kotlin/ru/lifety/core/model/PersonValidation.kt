package ru.lifety.core.model

import kotlinx.datetime.LocalDate
import ru.lifety.core.time.today

fun personValidationError(person: Person, currentDate: LocalDate = today()): String? = when {
    person.id.isBlank() || person.id.length > 100 -> "Некорректный идентификатор"
    person.fullName.isBlank() -> "Укажите имя человека"
    person.fullName.length > 200 -> "Имя должно быть не длиннее 200 символов"
    listOf(person.interests, person.hobbies, person.giftIdeas).any { it.length > 10_000 } -> "Заметка должна быть не длиннее 10 000 символов"
    person.noteSections.size > 20 -> "В карточке может быть до 20 своих разделов"
    person.noteSections.any { it.id.isBlank() || it.id.length > 100 } || person.noteSections.map { it.id }.distinct().size != person.noteSections.size -> "Проверьте разделы заметок"
    person.noteSections.any { it.title.isBlank() || it.title.length > 80 } -> "Укажите название раздела до 80 символов"
    (listOf("Интересы", "Увлечения", "Идеи для подарков") + person.noteSections.map { it.title.trim() }).map { it.lowercase() }.let { it.distinct().size != it.size } -> "Такой раздел уже есть. Выберите другое название"
    person.noteSections.sumOf { section -> section.items.sumOf { it.length.toLong() } } > 40_000 -> "В своих разделах можно сохранить до 40 000 символов"
    person.noteSections.any { it.items.size > 100 || it.items.sumOf { item -> item.length.toLong() } > 10_000 } -> "В разделе может быть до 100 пунктов и 10 000 символов"
    person.avatarBase64 != null && (person.avatarBase64.length > MAX_PERSON_AVATAR_BASE64 || !person.avatarBase64.startsWith("/9j/") || person.avatarBase64.length % 4 != 0) -> "Не удалось прочитать фото. Выберите его заново"
    (person.birthdayMonth == null) != (person.birthdayDay == null) -> "Укажите и день, и месяц рождения"
    person.birthYear != null && person.birthdayMonth == null -> "Укажите день и месяц рождения"
    person.birthYear != null && person.birthYear !in 1..currentDate.year -> "Проверьте год рождения"
    person.birthdayMonth != null && runCatching {
        LocalDate(person.birthYear ?: 2000, person.birthdayMonth, requireNotNull(person.birthdayDay))
    }.isFailure -> "Такой даты рождения нет"
    person.birthYear != null && LocalDate(person.birthYear, person.birthdayMonth!!, person.birthdayDay!!) > currentDate -> "Дата рождения ещё не наступила"
    else -> null
}

fun normalizedPerson(person: Person): Person = person.copy(
    fullName = person.fullName.trim(), interests = person.interests.trim(), hobbies = person.hobbies.trim(), giftIdeas = person.giftIdeas.trim(),
    noteSections = person.noteSections.map { section -> section.copy(title = section.title.trim(),
        items = section.items.flatMap { it.lineSequence().map(String::trim).filter(String::isNotEmpty).toList() }) },
)
