package ru.lifety.feature.people

import ru.lifety.core.ui.LifetyTextField

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ru.lifety.core.ui.LifetyBottomSheet
import ru.lifety.core.model.Person
import ru.lifety.core.model.PersonNoteSection
import kotlinx.serialization.json.Json
import androidx.compose.runtime.saveable.Saver
import kotlin.uuid.Uuid
import ru.lifety.core.model.personValidationError
import ru.lifety.core.repository.PersonRepository
import ru.lifety.core.time.today
import ru.lifety.core.ui.LifetyIcon
import ru.lifety.core.ui.LifetySymbol

/** Shared editor opened both from People and the calendar; writes complete before closing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PersonEditorSheet(initial: Person, isNew: Boolean, repository: PersonRepository, onDismiss: () -> Unit) {
    var name by rememberSaveable(initial.id) { mutableStateOf(initial.fullName) }
    var day by rememberSaveable(initial.id) { mutableStateOf(initial.birthdayDay?.toString().orEmpty()) }
    var month by rememberSaveable(initial.id) { mutableStateOf(initial.birthdayMonth?.toString().orEmpty()) }
    var year by rememberSaveable(initial.id) { mutableStateOf(initial.birthYear?.toString().orEmpty()) }
    val itemsSaver = listSaver<List<String>, String>(save = { it }, restore = { it })
    var interests by rememberSaveable(initial.id, stateSaver = itemsSaver) { mutableStateOf(personNoteItems(initial.interests)) }
    var hobbies by rememberSaveable(initial.id, stateSaver = itemsSaver) { mutableStateOf(personNoteItems(initial.hobbies)) }
    var gifts by rememberSaveable(initial.id, stateSaver = itemsSaver) { mutableStateOf(personNoteItems(initial.giftIdeas)) }
    val sectionsSaver = Saver<List<PersonNoteSection>, String>(save = { Json.encodeToString(it) }, restore = { Json.decodeFromString(it) })
    var sections by rememberSaveable(initial.id, stateSaver = sectionsSaver) { mutableStateOf(initial.noteSections) }
    var avatar by rememberSaveable(initial.id) { mutableStateOf(initial.avatarBase64) }
    var loadingPhoto by remember { mutableStateOf(false) }
    var expandedSection by rememberSaveable(initial.id) { mutableStateOf<String?>(null) }
    var deletingSection by remember { mutableStateOf<PersonNoteSection?>(null) }
    var attempted by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val currentDay = today()
    val draft = Person(initial.id, name.trim(), month.toIntOrNull(), day.toIntOrNull(), year.toIntOrNull(), personNoteText(interests), personNoteText(hobbies), personNoteText(gifts), sections, avatar)
    val inputError = when {
        day.isNotEmpty() && day.toIntOrNull() == null || month.isNotEmpty() && month.toIntOrNull() == null || year.isNotEmpty() && year.toIntOrNull() == null -> "Проверьте дату рождения"
        else -> personValidationError(draft, currentDay)
    }
    val pickPhoto = rememberPersonPhotoPicker(onPicked = { avatar = it; error = null }, onFinished = { loadingPhoto = false }, onError = { error = it })
    val scope = rememberCoroutineScope()
    val blocked by rememberUpdatedState(busy || closing)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !blocked || closing })
    val close: () -> Unit = { if (!busy && !closing) { closing = true; scope.launch { sheet.hide(); onDismiss() } } }
    fun write(deleting: Boolean = false) {
        if (busy || closing || loadingPhoto) return
        attempted = true
        if (!deleting && inputError != null) return
        busy = true
        error = null
        scope.launch {
            try {
                if (deleting) repository.delete(initial.id) else repository.upsert(draft)
                closing = true
                sheet.hide()
                onDismiss()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Не удалось сохранить изменения. Попробуйте ещё раз." }
            finally { busy = false }
        }
    }
    LifetyBottomSheet(onDismissRequest = { if (!blocked) onDismiss() }, sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = close, enabled = !blocked, modifier = Modifier.semantics { contentDescription = "Закрыть карточку человека" }) { LifetyIcon(LifetySymbol.Close) }
                Text(if (isNew) "Новый человек" else "Карточка человека", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (!isNew) TextButton(onClick = { confirmDelete = true }, enabled = !blocked && !loadingPhoto) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    PersonAvatar(name, avatar, size = 88.dp)
                    Column {
                        TextButton(onClick = { loadingPhoto = true; error = null; focus.clearFocus(); pickPhoto() }, enabled = !blocked && !loadingPhoto) {
                            Text(if (loadingPhoto) "Загружаем фото…" else if (avatar == null) "Добавить фото" else "Изменить фото")
                        }
                        if (avatar != null) TextButton(onClick = { avatar = null }, enabled = !blocked && !loadingPhoto) { Text("Убрать фото", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (loadingPhoto) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                LifetyTextField(name, { name = it.take(200) }, label = { Text("ФИО или имя") }, modifier = Modifier.fillMaxWidth(),
                    enabled = !blocked, isError = attempted && name.isBlank(), maxLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(onClick = { focus.clearFocus(); pickingDate = true }, enabled = !blocked,
                        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "День рождения: ${birthdayLabel(draft) ?: "не указан"}. Изменить" }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("День рождения", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(birthdayLabel(draft) ?: "Выбрать дату", style = MaterialTheme.typography.bodyLarge)
                            }
                            LifetyIcon(LifetySymbol.Calendar, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text("Год можно не указывать. День рождения появится в календаре каждый год.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (day.toIntOrNull() == 29 && month.toIntOrNull() == 2) Text("В невисокосные годы — 28 февраля.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (day.isNotEmpty() || month.isNotEmpty() || year.isNotEmpty()) TextButton(onClick = { day = ""; month = ""; year = "" }, enabled = !blocked) { Text("Убрать дату рождения") }
                }
                PersonNoteListEditor("Интересы", "Интерес", "Добавить интерес", "Например, искусство", interests, !blocked) { interests = it }
                PersonNoteListEditor("Увлечения", "Увлечение", "Добавить увлечение", "Например, плавание", hobbies, !blocked) { hobbies = it }
                PersonNoteListEditor("Идеи для подарков", "Подарок", "Добавить идею", "Например, книга или ссылка", gifts, !blocked) { gifts = it }
                sections.forEach { section -> key(section.id) {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { expandedSection = if (expandedSection == section.id) null else section.id }, modifier = Modifier.weight(1f)) {
                                    Text("${section.title.ifBlank { "Новый раздел" }} · ${section.items.count { it.isNotBlank() }} ${if (expandedSection == section.id) "⌃" else "⌄"}")
                                }
                                IconButton(onClick = { deletingSection = section }, enabled = !blocked,
                                    modifier = Modifier.semantics { contentDescription = "Удалить раздел ${section.title.ifBlank { "без названия" }}" }) { LifetyIcon(LifetySymbol.Close, Modifier.size(20.dp)) }
                            }
                            if (expandedSection == section.id) {
                                LifetyTextField(section.title, { title -> sections = sections.map { if (it.id == section.id) it.copy(title = title.take(80)) else it } },
                                    label = { Text("Название раздела") }, placeholder = { Text("Например, Любимые места") }, enabled = !blocked, singleLine = true, modifier = Modifier.fillMaxWidth())
                                PersonNoteListEditor("Заметки", "Пункт", "Добавить пункт", "Что важно помнить", section.items, !blocked) { items ->
                                    sections = sections.map { if (it.id == section.id) it.copy(items = items) else it }
                                }
                            } else if (section.items.any { it.isNotBlank() }) Text(section.items.filter(String::isNotBlank).take(3).joinToString(" · "),
                                maxLines = 2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } }
                FilledTonalButton(onClick = {
                    val section = PersonNoteSection(Uuid.random().toString(), "")
                    sections = sections + section; expandedSection = section.id
                }, enabled = !blocked && sections.size < 20, modifier = Modifier.fillMaxWidth()) { LifetyIcon(LifetySymbol.Plus, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Добавить свой раздел") }

            }
            (error ?: inputError?.takeIf { attempted })?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            Button(onClick = { write() }, enabled = !blocked && !loadingPhoto, modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp)) {
                if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Text(if (busy) "Сохраняем…" else "Сохранить")
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Удалить ${initial.fullName}?") },
        text = { Text("Карточка и день рождения в календаре будут удалены. Это действие нельзя отменить.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; write(deleting = true) }, enabled = !blocked) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } })
    deletingSection?.let { section -> AlertDialog(onDismissRequest = { deletingSection = null }, title = { Text("Убрать раздел?") },
        text = { Text("«${section.title.ifBlank { "Новый раздел" }}» и его пункты будут убраны из карточки после сохранения.") },
        confirmButton = { TextButton(onClick = { sections = sections.filterNot { it.id == section.id }; if (expandedSection == section.id) expandedSection = null; deletingSection = null }) { Text("Убрать", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deletingSection = null }) { Text("Отмена") } }) }
    if (pickingDate) BirthdayPickerDialog(
        initial = if (draft.birthdayDay != null && draft.birthdayMonth != null) BirthdaySelection(draft.birthdayDay, draft.birthdayMonth, draft.birthYear) else null,
        onConfirm = { selected ->
            day = selected.day.toString(); month = selected.month.toString(); year = selected.year?.toString().orEmpty()
            pickingDate = false
        }, onDismiss = { pickingDate = false })
}
