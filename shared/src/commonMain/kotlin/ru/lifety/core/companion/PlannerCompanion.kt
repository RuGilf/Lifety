package ru.lifety.core.companion

import androidx.compose.runtime.Composable
import kotlinx.serialization.Serializable
import ru.lifety.core.model.Person
import ru.lifety.core.model.Task
import ru.lifety.core.repository.Repositories

/** Small, versioned native payload. Photos and personal notes never leave the repository. */
@Serializable internal data class CompanionSnapshot(
    val version: Int = 1,
    val protected: Boolean,
    val tasks: List<CompanionTask>,
    val people: List<CompanionPerson>,
)
@Serializable internal data class CompanionTask(val id: String, val title: String, val scheduledAt: Long?, val deadlineAt: Long?)
@Serializable internal data class CompanionPerson(val id: String, val fullName: String, val birthdayMonth: Int, val birthdayDay: Int)

internal fun companionSnapshot(tasks: List<Task>, people: List<Person>, protected: Boolean) = CompanionSnapshot(
    protected = protected,
    tasks = tasks.filterNot { it.isCompleted }.map { CompanionTask(it.id, if (protected) "" else it.title, it.scheduledAt, it.deadlineAt) },
    people = people.mapNotNull { person ->
        val month = person.birthdayMonth ?: return@mapNotNull null
        val day = person.birthdayDay ?: return@mapNotNull null
        CompanionPerson(person.id, if (protected) "" else person.fullName, month, day)
    },
)

@Composable internal expect fun PlatformPlannerIntegration(repositories: Repositories?)
@Composable internal expect fun PlatformReminderSettingsLink()
