package ru.lifety.feature.calendar

import androidx.compose.animation.core.tween
import ru.lifety.core.ui.LifetyMotion
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import ru.lifety.feature.people.*
import ru.lifety.core.navigation.LocalPlannerNavigation
import ru.lifety.core.planner.TaskListViewModel
import ru.lifety.core.time.formatDate
import ru.lifety.core.time.localDate
import ru.lifety.core.time.monthGrid
import ru.lifety.core.time.russianMonths
import ru.lifety.core.time.tasksOnDate
import ru.lifety.core.time.today
import kotlinx.coroutines.delay
import kotlin.time.Clock
import ru.lifety.core.ui.LifetyEmptyState
import ru.lifety.core.ui.LifetyEmotion
import ru.lifety.core.ui.LifetyIcon
import ru.lifety.core.ui.LifetySymbol
import ru.lifety.core.ui.LocalRepositories
import ru.lifety.core.ui.LocalBottomNavigationInset
import ru.lifety.core.ui.PlaceholderScreen
import ru.lifety.core.ui.TaskCard

@Composable
fun CalendarScreen(modifier: Modifier = Modifier) {
    val repository = LocalRepositories.current?.tasks
    val bottomInset = LocalBottomNavigationInset.current
    if (repository == null) { PlaceholderScreen("Календарь · хранилище не подключено", modifier); return }
    val peopleRepository = LocalRepositories.current?.people
    val peopleModel = if (peopleRepository != null) viewModel { PeopleViewModel(peopleRepository) } else null
    val peopleState = peopleModel?.state?.collectAsState()?.value ?: PeopleState(loading = false)
    var editingPersonId by rememberSaveable { mutableStateOf<String?>(null) }
    val planner = LocalPlannerNavigation.current
    val model = viewModel { TaskListViewModel(repository) }
    val state by model.state.collectAsState()
    val zone = TimeZone.currentSystemDefault()
    var now by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = Clock.System.now().toEpochMilliseconds() } }
    val currentDay = localDate(now, zone)
    var selectedIso by rememberSaveable { mutableStateOf(currentDay.toString()) }
    var monthIso by rememberSaveable { mutableStateOf(LocalDate(currentDay.year, currentDay.month.number, 1).toString()) }
    val selected = LocalDate.parse(selectedIso)
    val planningState = ru.lifety.core.ui.LocalPlanningState.current
    val scheduleRepository = LocalRepositories.current?.schedule
    var editingScheduleId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedSchedule = remember(planningState.schedule, selected, zone) { ru.lifety.core.planner.scheduleOnDate(planningState.schedule, selected, zone) }
    val month = LocalDate.parse(monthIso)
    val dates = remember(month) {
        val grid = monthGrid(month)
        if (grid.takeLast(7).none { it.month == month.month }) grid.dropLast(7) else grid
    }
    val taskCounts = remember(state.tasks, zone) {
        state.tasks.flatMap { task -> listOfNotNull(task.scheduledAt, task.deadlineAt).map { localDate(it, zone) }.distinct() }.groupingBy { it }.eachCount()
    }
    val selectedTasks = remember(state.tasks, selected, zone) { tasksOnDate(state.tasks, selected, zone) }
    val birthdayCounts = remember(peopleState.people, dates) {
        dates.associateWith { birthdaysOnDate(peopleState.people, it).size }
    }
    val selectedBirthdays = remember(peopleState.people, selected) { birthdaysOnDate(peopleState.people, selected) }
    val scheduleCounts = remember(planningState.schedule, dates, zone) { dates.associateWith { ru.lifety.core.planner.scheduleOnDate(planningState.schedule, it, zone).size } }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.actionError) { state.actionError?.let { snackbar.showSnackbar(it); model.dismissError() } }
    Scaffold(modifier = modifier.fillMaxSize(), contentWindowInsets = WindowInsets(0, 0, 0, 0), snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = bottomInset)) },
        floatingActionButton = { FloatingActionButton(onClick = { planner?.createTask(selected.atTime(9, 0).toInstant(zone).toEpochMilliseconds()) },
            shape = MaterialTheme.shapes.large, containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(bottom = bottomInset).semantics { contentDescription = "Добавить задачу на выбранный день" }) { LifetyIcon(LifetySymbol.Plus) } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 16.dp, bottom = 100.dp + bottomInset)) {
            item {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { monthIso = month.plus(DatePeriod(months = -1)).toString() }, modifier = Modifier.semantics { contentDescription = "Предыдущий месяц" }) { Text("‹") }
                            Text("${russianMonths[month.month.number - 1]} ${month.year}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { monthIso = month.plus(DatePeriod(months = 1)).toString() }, modifier = Modifier.semantics { contentDescription = "Следующий месяц" }) { Text("›") }
                        }
                        Row(Modifier.fillMaxWidth()) {
                            listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach { Text(it, modifier = Modifier.weight(1f).padding(vertical = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelSmall) }
                        }
                        dates.chunked(7).forEach { week ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                week.forEach { day ->
                                    val chosen = day == selected
                                    Surface(onClick = { selectedIso = day.toString(); monthIso = LocalDate(day.year, day.month.number, 1).toString() },
                                        modifier = Modifier.weight(1f).height(48.dp).semantics { this.selected = chosen; contentDescription = "${formatDate(day)}, задач: ${taskCounts[day] ?: 0}, занятий: ${scheduleCounts[day] ?: 0}, дней рождения: ${birthdayCounts[day] ?: 0}" },
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
                                        border = if (day == currentDay && !chosen) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                            Text(day.day.toString(), style = MaterialTheme.typography.bodyMedium,
                                                color = if (chosen) MaterialTheme.colorScheme.onPrimary else if (day.month.number == month.month.number) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                                            Text(buildString {
                                                if ((taskCounts[day] ?: 0) > 0) append("•")
                                                if ((scheduleCounts[day] ?: 0) > 0) append(" ◦")
                                                if ((birthdayCounts[day] ?: 0) > 0) append(" ♥")
                                                if (isEmpty()) append(" ")
                                            }, style = MaterialTheme.typography.labelSmall, color = if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                        Text("• Дела    ◦ Расписание    ♥ Дни рождения", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatDate(selected), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { selectedIso = currentDay.toString(); monthIso = LocalDate(currentDay.year, currentDay.month.number, 1).toString() }) { Text("Сегодня") }
                }
                Text("План и дедлайны · ${selectedTasks.size} задач", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (peopleState.loading) item { Text("Загружаем дни рождения…", style = MaterialTheme.typography.bodySmall) }
            if (planningState.loading) item { Text("Загружаем расписание…", style = MaterialTheme.typography.bodySmall) }
            planningState.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            if (selectedSchedule.isNotEmpty()) item { Text("Расписание", style = MaterialTheme.typography.titleMedium) }
            items(selectedSchedule, key = { "schedule:${it.block.id}:${it.start}" }) { occurrence ->
                Card(onClick = { editingScheduleId = occurrence.block.id }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(occurrence.block.title, style = MaterialTheme.typography.titleMedium)
                        Text("${ru.lifety.core.planner.formatPlannerTime(occurrence.start, zone)} — ${ru.lifety.core.planner.formatPlannerTime(occurrence.end, zone)}", color = MaterialTheme.colorScheme.primary)
                        if (localDate(occurrence.start, zone) != selected || localDate(occurrence.end, zone) != selected)
                            Text("${formatDate(localDate(occurrence.start, zone))} — ${formatDate(localDate(occurrence.end, zone))}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            peopleState.error?.let { error -> item {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { peopleModel?.retry() }) { Text("Повторить") }
            } }
            if (selectedBirthdays.isNotEmpty()) {
                item { Text("Дни рождения", style = MaterialTheme.typography.titleMedium) }
                items(selectedBirthdays, key = { "birthday:${it.id}" }, contentType = { "birthday" }) { person ->
                    Card(onClick = { editingPersonId = person.id }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                PersonAvatar(person.fullName, person.avatarBase64, size = 40.dp)
                                Text(person.fullName, style = MaterialTheme.typography.titleMedium)
                            }
                            Text(birthdayAgeLabel(person, selected), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                            if (person.giftIdeas.isNotBlank()) Text("Идеи подарков: ${personNotePreview(person.giftIdeas)}", maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Открыть карточку", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            when {
                state.loading -> item { androidx.compose.material3.CircularProgressIndicator() }
                state.readError != null -> item {
                    Text(requireNotNull(state.readError), color = MaterialTheme.colorScheme.error)
                    Button(onClick = model::retry) { Text("Повторить") }
                }
                selectedTasks.isEmpty() && selectedBirthdays.isEmpty() && selectedSchedule.isEmpty() && !planningState.loading && planningState.error == null && !peopleState.loading && peopleState.error == null -> item { LifetyEmptyState("Свободный день", "Оставь время для себя или добавь новый план.", emotion = LifetyEmotion.Calm, mascotSize = 72.dp) }
                else -> items(selectedTasks, key = { "task:${it.id}" }, contentType = { "task" }) { task ->
                    androidx.compose.foundation.layout.Box(Modifier.animateItem(fadeInSpec = tween(LifetyMotion.Quick), placementSpec = tween(LifetyMotion.Standard), fadeOutSpec = tween(LifetyMotion.Quick))) {
                    TaskCard(task, task.id in state.busyIds, onOpen = { planner?.openTask(task.id) }, onToggle = { model.toggle(task) }, now = now)
                    }
                }
            }
        }
    }
    editingPersonId?.let { id ->
        val initial = remember(id, peopleState.loading) { peopleState.people.find { it.id == id } }
        if (initial != null && peopleRepository != null) key(id) {
            PersonEditorSheet(initial, false, peopleRepository, onDismiss = { editingPersonId = null })
        }
    }
    editingScheduleId?.let { id ->
        val initial = planningState.schedule.find { it.id == id }
        if (initial != null && scheduleRepository != null) key(id) {
            ru.lifety.feature.schedule.ScheduleEditorSheet(initial, false, scheduleRepository) { editingScheduleId = null }
        }
    }

}
