package ru.lifety.app

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import ru.lifety.app.navigation.AppDestination
import ru.lifety.app.navigation.LocalRootNavigator
import ru.lifety.app.navigation.RootNavigator
import ru.lifety.app.navigation.TaskEditorRoute
import ru.lifety.core.navigation.LocalPlannerNavigation
import ru.lifety.core.navigation.PlannerNavigation
import ru.lifety.core.repository.Repositories
import ru.lifety.core.ui.LifetyMotion
import ru.lifety.core.ui.LifetyTheme
import ru.lifety.core.ui.PlanningProvider
import ru.lifety.core.ui.LocalRepositories
import ru.lifety.core.ui.LocalBottomNavigationInset
import ru.lifety.core.ui.isIos
import ru.lifety.feature.assistant.AssistantScreen
import ru.lifety.feature.dashboard.DashboardScreen
import ru.lifety.feature.finance.FinanceScreen
import ru.lifety.feature.health.HealthScreen
import ru.lifety.feature.tasks.TaskEditorScreen
import ru.lifety.core.security.*
import ru.lifety.app.navigation.*
import ru.lifety.feature.people.PeopleScreen
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import ru.lifety.core.ui.LifetyBottomSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LifetyApp(repositories: Repositories? = null) {
    SectionLockProvider { NavigationProvider(repositories?.navigation) { LifetyContent(repositories) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LifetyContent(repositories: Repositories?) {
    val controller = rememberNavController()
    val entry by controller.currentBackStackEntryAsState()
    val isEditor = entry?.destination?.hasRoute<TaskEditorRoute>() == true
    val route = entry?.destination?.route
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val keyboardVisible by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
    var settings by rememberSaveable { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val navigationItems = LocalNavigationItems.current
    val lockState = LocalSectionLockState.current
    var settingsEpoch by remember { mutableStateOf(lockState.epoch) }
    var planRoute by rememberSaveable { mutableStateOf(AppDestination.TASKS.route) }
    val navigator = remember(controller) {
        RootNavigator(onNavigate = { destination ->
            controller.navigate(destination.route) {
                popUpTo(controller.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        })
    }
    LaunchedEffect(entry) {
        AppDestination.entries.firstOrNull { it.route == route }?.let { navigator.syncDestination(it) }
        if (route == AppDestination.TASKS.route || route == AppDestination.CALENDAR.route) planRoute = route
    }
    val planner = remember(controller) {
        object : PlannerNavigation {
            override fun openTask(id: String) { controller.navigate(TaskEditorRoute(taskId = id)) { launchSingleTop = true } }
            override fun createTask(scheduledAt: Long?) { controller.navigate(TaskEditorRoute(scheduledAt = scheduledAt)) { launchSingleTop = true } }
        }
    }
    PlanningProvider(repositories) {
    LifetyTheme {
        CompositionLocalProvider(LocalRepositories provides repositories, LocalPlannerNavigation provides planner, LocalRootNavigator provides navigator) {
            ru.lifety.core.companion.PlatformPlannerIntegration(repositories)
            val section = AppDestination.fromRoute(route.orEmpty())
            val selectedNavigation = if (section in listOf(AppDestination.CALENDAR, AppDestination.PEOPLE) && section !in navigationItems && AppDestination.TASKS in navigationItems) AppDestination.TASKS else section
            val showNavigation = !isEditor && !keyboardVisible
            val bottomInset = if (isIos && showNavigation) (if (LocalNavigationState.current.presentation == "arc") 72.dp else 49.dp) + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() else 0.dp
            val selectSection: (AppDestination) -> Unit = { item ->
                val target = if (item == AppDestination.TASKS && AppDestination.CALENDAR !in navigationItems) AppDestination.fromRoute(planRoute) else item
                if (section != item) navigator.navigate(target)
            }
            CompositionLocalProvider(LocalBottomNavigationInset provides bottomInset) {
                Box(Modifier.fillMaxSize()) {
                    Scaffold(
                        contentWindowInsets = if (isIos && !isEditor) WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal) else ScaffoldDefaults.contentWindowInsets,
                        topBar = {
                            if (!isEditor) PlatformTopBar(
                                title = if (section == AppDestination.DASHBOARD) "Lifety" else section.title,
                                showMascot = section == AppDestination.DASHBOARD,
                                onSections = { settingsEpoch = lockState.epoch; more = true },
                                onSettings = { settingsEpoch = lockState.epoch; settings = true },
                            )
                        },
                        bottomBar = {
                            if (showNavigation && !isIos) PlatformNavigationBar(selectedNavigation, selectSection)
                        },
                    ) { padding ->
                        NavHost(
                            controller, startDestination = AppDestination.DASHBOARD.route,
                            modifier = Modifier.fillMaxSize().padding(padding),
                            enterTransition = { if (isIos) EnterTransition.None else fadeIn(tween(LifetyMotion.Standard)) },
                            exitTransition = { if (isIos) ExitTransition.None else fadeOut(tween(LifetyMotion.Quick)) },
                            popEnterTransition = { if (isIos) EnterTransition.None else fadeIn(tween(LifetyMotion.Standard)) },
                            popExitTransition = { if (isIos) ExitTransition.None else fadeOut(tween(LifetyMotion.Quick)) },
                        ) {
                            composable(AppDestination.DASHBOARD.route) { SectionGuard("dashboard") { DashboardScreen(Modifier.fillMaxSize()) } }
                            composable(AppDestination.TASKS.route) { SectionGuard("tasks") { PlanScreen(modifier = Modifier.fillMaxSize()) } }
                            composable(AppDestination.CALENDAR.route) { SectionGuard("calendar") { PlanScreen(initialCalendar = true, modifier = Modifier.fillMaxSize()) } }
                            composable(AppDestination.FINANCE.route) { SectionGuard("finance") { FinanceScreen(Modifier.fillMaxSize()) } }
                            composable(AppDestination.ASSISTANT.route) { SectionGuard("assistant") { AssistantScreen(Modifier.fillMaxSize()) } }
                            composable(AppDestination.HEALTH.route) { SectionGuard("health") { HealthScreen(Modifier.fillMaxSize()) } }
                            composable(AppDestination.PEOPLE.route) { SectionGuard("people") { PeopleScreen(Modifier.fillMaxSize()) } }
                            composable<TaskEditorRoute>(
                                enterTransition = { EnterTransition.None },
                                exitTransition = { ExitTransition.None },
                                popEnterTransition = { EnterTransition.None },
                                popExitTransition = { ExitTransition.None },
                            ) { backStackEntry ->
                                val editor = backStackEntry.toRoute<TaskEditorRoute>()
                                SectionGuard("tasks", onBack = { controller.popBackStack() }) { TaskEditorScreen(editor.taskId, editor.scheduledAt, onClose = { controller.popBackStack() }) }
                            }
                        }
                    }
                    if (isIos && showNavigation) Box(Modifier.align(Alignment.BottomCenter)) {
                        PlatformNavigationBar(selectedNavigation, selectSection)
                    }
                }
                if (settings && settingsEpoch == lockState.epoch) LifetySettingsSheet(onDismiss = { settings = false }, onFinance = {
                    settings = false
                    navigator.navigate(AppDestination.FINANCE)
                })
                if (more && settingsEpoch == lockState.epoch) LifetyBottomSheet(onDismissRequest = { more = false }) {
                    LazyColumn(contentPadding = PaddingValues(16.dp)) {
                        item { Text("Все разделы", style = MaterialTheme.typography.headlineSmall) }
                        items(availableNavigation) { id ->
                            val destination = AppDestination.fromRoute(id)
                            TextButton(onClick = { more = false; navigator.navigate(destination) }, modifier = Modifier.fillMaxWidth()) { Text(destination.title) }
                        }
                        item { HorizontalDivider(); TextButton(onClick = { more = false; settings = true }) { Text("Настройки · нижняя панель") } }
                    }
                }
            }
        }
    }
}
}
