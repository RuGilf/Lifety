package ru.lifety.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.lifety.core.appearance.*
import ru.lifety.core.ui.*
import ru.lifety.core.security.*

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("UNUSED_PARAMETER") // Kept for source compatibility; finance has its own tab.
@Composable
fun LifetySettingsSheet(onDismiss: () -> Unit, onFinance: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val controller = LocalAppearance.current ?: return
    val state by controller.state.collectAsState()
    var security by remember { mutableStateOf(false) }
    var navigation by remember { mutableStateOf(false) }
    val lockState = LocalSectionLockState.current
    LaunchedEffect(controller) { controller.refreshIcon() }
    LifetyBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, contentColor = MaterialTheme.colorScheme.onSurface,
        containerColor = if (isIos) MaterialTheme.colorScheme.background else BottomSheetDefaults.ContainerColor) {
        if (security) SectionLockSettingsContent(onBack = { security = false }) else if (navigation) NavigationSettingsContent(onBack = { navigation = false }) else {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Настройки", style = MaterialTheme.typography.headlineSmall)
                    Text("Ваш Lifety", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { scope.launch { sheet.hide(); onDismiss() } }, modifier = Modifier.semantics { contentDescription = "Закрыть настройки" }) { LifetyIcon(LifetySymbol.Close) }
            }
            if (isIos) Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
                    SettingsLink("Код-пароль на разделы", if (lockState.configuration.enabled) "Защищено разделов: ${lockState.configuration.sections.size}" else "Выберите, какие разделы защищать") { security = true }
                    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
                    SettingsLink("Нижняя панель", "Разделы и их порядок") { navigation = true }
                }
            } else {
            OutlinedCard(onClick = { security = true }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Код-пароль на разделы", style = MaterialTheme.typography.titleMedium)
                    Text(if (lockState.configuration.enabled) "Защищено разделов: ${lockState.configuration.sections.size}" else "Выберите, какие разделы защищать",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedCard(onClick = { navigation = true }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Нижняя панель", style = MaterialTheme.typography.titleMedium)
                    Text("Пункты и порядок в овале", style = MaterialTheme.typography.bodySmall)
                }
            }
            }
            ru.lifety.core.companion.PlatformReminderSettingsLink()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Тема", style = MaterialTheme.typography.titleMedium)
                Text("Палитры из мира Neovim. Применяются сразу.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(false to "Светлые", true to "Тёмные").forEach { (dark, title) ->
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    LifetyPalette.entries.filter { it.palette.isDark == dark }.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { theme -> ThemeTile(theme, state.theme == theme, !state.savingTheme, Modifier.weight(1f)) { controller.selectTheme(theme) } }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Иконка приложения", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AppIconPreview(state.theme, Modifier.size(64.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("В цвет темы ${state.theme.title}", style = MaterialTheme.typography.bodyLarge)
                        Text("Сейчас: ${state.icon.title}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (state.supportsIcons) {
                    Button(onClick = { controller.changeIcon(state.theme) }, enabled = !state.changingIcon && state.icon != state.theme, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.changingIcon) "Меняем иконку…" else if (state.icon == state.theme) "Иконка соответствует теме" else "Применить иконку ${state.theme.title}")
                    }
                    if (state.icon != LifetyPalette.Lifety) TextButton(onClick = { controller.changeIcon(LifetyPalette.Lifety) }, enabled = !state.changingIcon) { Text("Вернуть стандартную иконку") }
                    Text(if (isIos) "iOS может показать системное подтверждение смены иконки." else "Лаунчер может обновить значок с небольшой задержкой.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else Text("Смена иконки недоступна на этом устройстве.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("На вашем устройстве", style = MaterialTheme.typography.titleSmall)
                    Text("Задачи, цели, люди, расходы и здоровье хранятся локально. Доступ агента к данным настраивается в ассистенте.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        }
    }
}

@Composable
private fun ThemeTile(theme: LifetyPalette, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = theme.palette
    Surface(modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(18.dp), color = p.surface, contentColor = p.text,
        border = if (selected) BorderStroke(2.dp, p.accent) else if (isIos) null else BorderStroke(1.dp, p.outline.copy(alpha = .5f))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIconPreview(theme, Modifier.size(36.dp))
                Spacer(Modifier.weight(1f))
                if (selected) LifetyIcon(LifetySymbol.Check, color = p.accent)
            }
            Column {
                Text(theme.title, style = MaterialTheme.typography.titleSmall)
                Text(theme.subtitle, style = MaterialTheme.typography.labelSmall, color = p.muted)
            }
            Canvas(Modifier.fillMaxWidth().height(22.dp).clearAndSetSemantics {}) {
                drawRoundRect(p.background, cornerRadius = CornerRadius(5.dp.toPx()))
                drawRoundRect(p.accent, Offset(8.dp.toPx(), 7.dp.toPx()), Size(size.width * .35f, 3.dp.toPx()), CornerRadius(2.dp.toPx()))
                drawRoundRect(p.muted.copy(alpha = .5f), Offset(8.dp.toPx(), 13.dp.toPx()), Size(size.width * .60f, 2.dp.toPx()), CornerRadius(2.dp.toPx()))
            }
        }
    }
}

/** The same geometric mark as the bundled launcher icons. */
@Composable
private fun AppIconPreview(theme: LifetyPalette, modifier: Modifier = Modifier) {
    val p = theme.palette
    Canvas(modifier.clip(RoundedCornerShape(14.dp)).background(p.background).clearAndSetSemantics {}) {
        val unit = size.minDimension
        val center = Offset(size.width / 2, size.height / 2)
        drawCircle(p.accent, unit * 30f / 108f, center, style = Stroke(unit * 4f / 108f))
        val smile = Path().apply {
            moveTo(center.x - unit * 12f / 108f, center.y + unit * 3f / 108f)
            cubicTo(center.x - unit * 8f / 108f, center.y + unit * 15f / 108f, center.x + unit * 8f / 108f, center.y + unit * 15f / 108f, center.x + unit * 12f / 108f, center.y + unit * 3f / 108f)
        }
        drawPath(smile, p.accent, style = Stroke(unit * 4f / 108f, cap = StrokeCap.Round))
    }
}

@Composable
private fun SettingsLink(title: String, detail: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainerLow, contentColor = MaterialTheme.colorScheme.onSurface) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LifetyIcon(LifetySymbol.Chevron, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
