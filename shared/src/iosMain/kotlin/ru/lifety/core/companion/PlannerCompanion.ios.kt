@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package ru.lifety.core.companion

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import platform.UIKit.UIViewController
import ru.lifety.app.navigation.AppDestination
import ru.lifety.app.navigation.LocalRootNavigator
import ru.lifety.core.navigation.LocalPlannerNavigation
import ru.lifety.core.repository.Repositories
import ru.lifety.core.security.LocalSectionLockState

/** Implemented by the WidgetKit/UserNotifications host. No database access from extensions. */
interface IosPlannerCompanion {
    fun update(snapshotJson: String)
    fun showSettings(presenter: UIViewController)
}
object IosPlannerServices { var companion: IosPlannerCompanion? = null }

private data class OpenRequest(val route: String, val taskId: String?, val serial: Long)
private val opening = MutableStateFlow<OpenRequest?>(null)
private var serial = 0L
/** Main-thread entry from app URLs and notifications; guards still apply at the destination. */
fun openPlannerLink(route: String, taskId: String?) {
    if (route !in setOf("tasks", "people")) return
    opening.value = OpenRequest(route, taskId?.takeIf { it.length <= 200 && it.isNotBlank() }, ++serial)
}

@Composable internal actual fun PlatformPlannerIntegration(repositories: Repositories?) {
    val locks = LocalSectionLockState.current
    val hidden = !locks.ready || locks.protects("tasks")
    val navigator = LocalRootNavigator.current
    val planner = LocalPlannerNavigation.current
    LaunchedEffect(repositories, hidden) {
        repositories ?: return@LaunchedEffect
        combine(repositories.tasks.observeAll(), repositories.people?.observeAll() ?: flowOf(emptyList())) { tasks, people ->
            withContext(Dispatchers.Default) {
                Json { encodeDefaults = true }.encodeToString(companionSnapshot(tasks, people, hidden))
            }
        }.distinctUntilChanged().collect { IosPlannerServices.companion?.update(it) }
    }
    LaunchedEffect(navigator, planner) {
        if (navigator == null || planner == null) return@LaunchedEffect
        opening.filterNotNull().collect { request ->
            navigator.navigate(if (request.route == "people") AppDestination.PEOPLE else AppDestination.TASKS)
            if (request.route == "tasks") request.taskId?.let { planner.openTask(it) }
            opening.compareAndSet(request, null)
        }
    }
}

@Composable internal actual fun PlatformReminderSettingsLink() {
    val presenter = LocalUIViewController.current
    OutlinedCard(onClick = { IosPlannerServices.companion?.showSettings(presenter) }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Виджеты и напоминания", style = MaterialTheme.typography.titleMedium)
            Text("Дела на экране iPhone, дедлайны и дни рождения", style = MaterialTheme.typography.bodySmall)
        }
    }
}
