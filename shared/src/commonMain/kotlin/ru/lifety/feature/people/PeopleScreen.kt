package ru.lifety.feature.people

import ru.lifety.core.ui.LifetyTextField

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import ru.lifety.core.model.Person
import ru.lifety.core.time.formatDate
import ru.lifety.core.time.today
import ru.lifety.core.ui.*
import kotlin.uuid.Uuid

@Composable
fun PeopleScreen(modifier: Modifier = Modifier) {
    val repository = LocalRepositories.current?.people
    if (repository == null) { PlaceholderScreen("Люди · хранилище не подключено", modifier); return }
    val model = viewModel { PeopleViewModel(repository) }
    val state by model.state.collectAsState()
    val bottomInset = LocalBottomNavigationInset.current
    var search by rememberSaveable { mutableStateOf("") }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var currentDay by remember { mutableStateOf(today()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); currentDay = today() } }
    val visible = remember(state.people, search, currentDay) {
        val term = search.trim()
        state.people.filter { person -> term.isEmpty() || (listOf(person.fullName, person.interests, person.hobbies, person.giftIdeas) + person.noteSections.flatMap { listOf(it.title) + it.items }).any { it.contains(term, ignoreCase = true) } }
            .sortedWith(compareBy<Person> { nextBirthday(it, currentDay)?.toEpochDays() ?: Long.MAX_VALUE }
                .thenBy { it.fullName.lowercase() }.thenBy { it.id })
    }
    Scaffold(modifier.fillMaxSize(), contentWindowInsets = WindowInsets(0, 0, 0, 0), floatingActionButton = {
        FloatingActionButton(onClick = { adding = true; editingId = Uuid.random().toString() }, modifier = Modifier.padding(bottom = bottomInset)
            .semantics { contentDescription = "Добавить человека" }, containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary, shape = MaterialTheme.shapes.large) { LifetyIcon(LifetySymbol.Plus) }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 100.dp + bottomInset), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                LifetyTextField(search, { search = it }, label = { Text("Поиск людей и заметок") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            when {
                state.loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.error != null -> item {
                    Text(requireNotNull(state.error), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = model::retry) { Text("Повторить") }
                }
                state.people.isEmpty() -> item {
                    LifetyEmptyState("Важные люди рядом", "Сохрани интересы и идеи подарков. Дни рождения автоматически появятся в календаре.",
                        actionLabel = "Добавить человека", onAction = { adding = true; editingId = Uuid.random().toString() }, mascotSize = 88.dp)
                }
                visible.isEmpty() -> item { Text("Никого не нашли. Попробуйте другое имя или интерес.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else -> {
                    item { Text("Сначала ближайшие дни рождения · ${state.people.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(visible, key = { it.id }, contentType = { "person" }) { person ->
                        Card(onClick = { adding = false; editingId = person.id }, modifier = Modifier.fillMaxWidth()
                            .animateItem(fadeInSpec = tween(LifetyMotion.Quick), placementSpec = tween(LifetyMotion.Standard))) {
                            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                PersonAvatar(person.fullName, person.avatarBase64, size = 48.dp)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(person.fullName, style = MaterialTheme.typography.titleMedium)
                                    birthdayLabel(person)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
                                    nextBirthday(person, currentDay)?.let { date ->
                                        Text(if (date == currentDay) "Сегодня день рождения!" else "В календаре: ${formatDate(date)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    val preview = when {
                                        person.giftIdeas.isNotBlank() -> "Подарки: ${personNotePreview(person.giftIdeas)}"
                                        person.interests.isNotBlank() -> personNotePreview(person.interests)
                                        person.hobbies.isNotBlank() -> personNotePreview(person.hobbies)
                                        else -> person.noteSections.firstOrNull { it.items.any(String::isNotBlank) }?.let { "${it.title}: ${it.items.take(3).joinToString(" · ")}" }.orEmpty()
                                    }
                                    if (preview.isNotBlank()) Text(preview, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                LifetyIcon(LifetySymbol.Chevron, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
    editingId?.let { id ->
        // Keep the snapshot during save/delete so the sheet can finish its exit animation.
        val initial = remember(id, state.loading) { if (adding) Person(id, "") else state.people.find { it.id == id } }
        if (initial != null) key(id) { PersonEditorSheet(initial, adding, repository, onDismiss = { editingId = null }) }
    }
}
